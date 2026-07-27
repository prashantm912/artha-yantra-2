package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D4 paper forensics IT (mock profile, engine-disabled): the P1-5 fill-reference provenance
 * (ref_source / ref_tick_age_ms) stamped on each fill, the P1-4 order-reject ledger written when a fill is
 * refused for a stale tick, and the P1-7 flag snapshot captured at order open. Instrument-meta is stubbed
 * (no market-data in this service's IT) and the last tick is seeded into Redis directly.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperForensicsIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }
  }

  @Autowired private PaperService paper;
  @Autowired private PaperOrderRejectionRecorder rejections;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void anExplicitPriceFillStampsRefSourceCallerAndCapturesTheFlagSnapshot() {
    String sym = "TESTOPT-" + UUID.randomUUID();
    PaperService.PositionDto pos =
        paper.openOrder(
            new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("100.00"), null, null));

    // P1-5: an explicit caller price → CALLER, no tick age.
    Map<String, Object> order =
        jdbc.queryForMap(
            "SELECT ref_source, ref_tick_age_ms FROM paper_orders WHERE tradingsymbol=? ORDER BY id DESC LIMIT 1",
            sym);
    assertThat(order.get("ref_source")).isEqualTo("CALLER");
    assertThat(order.get("ref_tick_age_ms")).isNull();

    // P1-7: the open captured the paper flag regime keyed to the position id.
    Map<String, Object> snap =
        jdbc.queryForMap(
            "SELECT book, flags::text AS flags, flags_hash FROM flag_snapshots"
                + " WHERE context_kind='PAPER_ORDER' AND context_ref=?",
            String.valueOf(pos.id()));
    assertThat(snap.get("book")).isEqualTo("manual");
    assertThat(snap.get("flags").toString()).contains("paper.risk.enabled");
    assertThat(snap.get("flags_hash").toString()).isNotBlank();
  }

  @Test
  void liveTickFillStampsRefSourceLiveTickWithTheTickAge() {
    String sym = "TESTOPT-" + UUID.randomUUID();
    seedTick(sym, "100.00", OffsetDateTime.now(ZoneOffset.UTC)); // fresh (<15s)

    // No explicit price → the fill uses the live last tick.
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, null, null, null));

    Map<String, Object> order =
        jdbc.queryForMap(
            "SELECT ref_source, ref_tick_age_ms FROM paper_orders WHERE tradingsymbol=? ORDER BY id DESC LIMIT 1",
            sym);
    assertThat(order.get("ref_source")).isEqualTo("LIVE_TICK");
    assertThat(order.get("ref_tick_age_ms")).isNotNull();
  }

  @Test
  void staleTickEntryIsRefusedAndTheRejectionIsRecorded() {
    String sym = "TESTOPT-" + UUID.randomUUID();
    seedTick(sym, "100.00", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(60)); // stale (> 15s)

    // No explicit price → the fill would use the stale tick → DATA_STALE.
    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, null, null, null)))
        .isInstanceOf(ApiException.class);

    // P1-4: the refused attempt is durable even though the fill rolled back (REQUIRES_NEW recorder).
    Map<String, Object> reject =
        jdbc.queryForMap(
            "SELECT reason, tick_age_ms, book, side FROM paper_order_rejections"
                + " WHERE tradingsymbol=? ORDER BY id DESC LIMIT 1",
            sym);
    assertThat(reject.get("reason")).isEqualTo("DATA_STALE_TICK");
    assertThat(reject.get("tick_age_ms")).isNotNull();
    assertThat(reject.get("book")).isEqualTo("manual");
    assertThat(reject.get("side")).isEqualTo("BUY");
  }

  @Test
  void zeroSizedEntryRejectionIsDurableInTheExistingForensicsLedger() {
    String sym = "TESTZERO-" + UUID.randomUUID();
    rejections.recordZeroSize(
        89L, "scalper", "BFO", sym, "BUY",
        "strategy=scalp-connect-the-dots-sensex; premium=776; lot=20; budget_inr=15000; computed_lots=0");

    Map<String, Object> reject =
        jdbc.queryForMap(
            "SELECT reason, qty, book, exchange, detail FROM paper_order_rejections"
                + " WHERE tradingsymbol=? ORDER BY id DESC LIMIT 1",
            sym);
    assertThat(reject.get("reason")).isEqualTo("ZERO_SIZE");
    assertThat(reject.get("qty")).isEqualTo(0L);
    assertThat(reject.get("book")).isEqualTo("scalper");
    assertThat(reject.get("exchange")).isEqualTo("BFO");
    assertThat(reject.get("detail").toString()).contains("computed_lots=0");
  }

  private void seedTick(String sym, String price, OffsetDateTime at) {
    try {
      redis
          .opsForHash()
          .put(
              "ticks:last",
              "NFO:" + sym,
              objectMapper.writeValueAsString(
                  Map.of(
                      "exchange", "NFO", "tradingsymbol", sym, "lastPrice", price,
                      "timestamp", at.toString())));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
