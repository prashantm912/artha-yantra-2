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

  @Autowired private io.micrometer.core.instrument.MeterRegistry meters;

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

  /**
   * V059: the settle leg carries the exact id of the position it closes, and the ENTRY leg does not.
   *
   * <p>The asymmetry is the design, not an oversight. {@code doSettle} CAS-closes the position and
   * only THEN inserts the exit order, so the id is already in hand and the link rides the INSERT that
   * has to happen anyway — no second statement, so nothing new can fail on the money-path close.
   * {@code openOrder} runs the other way round (the order id is minted before {@code upsertPosition}
   * exists), which is exactly why V057 put entry attribution in {@code paper_position_lots} rather
   * than in a column, and why this column must stay NULL there instead of being back-filled by an
   * UPDATE.
   *
   * <p>Asserting the entry leg's NULL is the half that discriminates: a change that stamped every
   * fill would satisfy the exit assertion alone while reintroducing precisely the INSERT-then-UPDATE
   * shape V057 rejected.
   */
  @Test
  void theSettleLegCarriesItsPositionIdAndTheEntryLegDoesNot() {
    String sym = "TESTOPT-" + UUID.randomUUID();
    PaperService.PositionDto pos =
        paper.openOrder(
            new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("100.00"), null, null));
    paper.closePosition(pos.id(), new BigDecimal("101.00"));

    Map<String, Object> exit =
        jdbc.queryForMap(
            "SELECT side, leg_kind, settles_position_id FROM paper_orders WHERE tradingsymbol=?"
                + " ORDER BY id DESC LIMIT 1",
            sym);
    assertThat(exit.get("side")).isEqualTo("SELL");
    assertThat(exit.get("leg_kind")).isEqualTo("EXIT");
    assertThat(exit.get("settles_position_id")).isEqualTo(pos.id());

    Map<String, Object> entry =
        jdbc.queryForMap(
            "SELECT side, leg_kind, settles_position_id FROM paper_orders WHERE tradingsymbol=?"
                + " ORDER BY id ASC LIMIT 1",
            sym);
    assertThat(entry.get("side")).isEqualTo("BUY");
    // ⚠️ THE ASSERTION THAT KEEPS THE SELL-ENTRY CRITICAL CLOSED, and it must read a row the WRITER
    // produced. The reconciliation test that proves that Critical closed hand-seeds 'ENTRY' via raw
    // JDBC, so it builds its own sample and cannot observe the writer at all. If a future overload of
    // insertFilled delegates null instead of ENTRY_LEG — the copy-paste this repository's javadoc
    // warns about — every manual and engine entry would land (leg_kind NULL, settles_position_id
    // NULL), byte-identical to a legacy row. The reconciler's `leg_kind IS NULL` fallback would then
    // re-admit it and the masking route would reopen silently, with every other test still green.
    assertThat(entry.get("leg_kind")).isEqualTo("ENTRY");
    assertThat(entry.get("settles_position_id")).isNull();
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

  /**
   * H44: a fill struck with NO tick at all is the precursor to a position the exit path can never
   * settle, and until 2026-08-28 it was silent at the moment it happened.
   *
   * <p><b>Measured that day:</b> two SENSEX PE legs filled this way at 11:37, and it was only
   * NOTICED at the 15:44 square-off — hours later, via 1,973 starved-bracket WARNs, ~Rs 8,750
   * unbooked and two sub-accounts allocation-dead. The information existed at fill time; nothing
   * surfaced it.
   *
   * <p>⚠️ This asserts a LEADING indicator, not a verdict. A tick may still arrive and settle the
   * position normally — the counter says "currently unsettleable", never "doomed". Overstating it
   * would make the signal one an operator learns to ignore.
   *
   * <p>⚠️ The count is emitted AFTER_COMMIT by {@link NoTickFillListener}, not inline in the fill.
   * Review caught the inline version counting REJECTED and ROLLED-BACK attempts as fills, and
   * reading Redis on the money path where a blip could abort a trade. This test still asserts it
   * synchronously because the caller is not inside a transaction, so the commit — and therefore the
   * listener — completes before {@code openOrder} returns.
   */
  @Test
  void aFillWithNoTickAtAllIsCountedOnceTheFillIsDurable() {
    String sym = "H44OPT-" + UUID.randomUUID();
    double before = noTickFills();

    // No seedTick(...) on purpose: "no tick was EVER seen", not a stale one.
    //
    // ⚠️ An EXPLICIT price, i.e. the CALLER branch — which is what production actually does. The
    // scalper supplies the gate-captured chain premium, so both positions stranded on 2026-08-28
    // filled CALLER. paper_orders all-time: 119 CALLER, 42 LIVE_TICK, 0 SIGNAL_ENTRY. An earlier
    // cut of this test drove the SIGNAL_ENTRY fallback and passed while watching a branch that
    // has never executed.
    paper.openOrder(
        new PaperService.OrderRequest(
            null, "BFO", sym, "BUY", 50, new BigDecimal("779.55"), null, null));
    Map<String, Object> order =
        jdbc.queryForMap(
            "SELECT ref_source FROM paper_orders WHERE tradingsymbol=? ORDER BY id DESC LIMIT 1",
            sym);
    assertThat(order.get("ref_source"))
        .as("production prices these from the chain, so the fill is CALLER -- the tick is irrelevant"
            + " to HOW it filled, which is exactly why the indicator must not key on the branch")
        .isEqualTo("CALLER");
    assertThat(noTickFills() - before)
        .as("the unsettleable state must be visible AT FILL TIME, not hours later at square-off")
        .isEqualTo(1.0);
  }

  /**
   * The control, and the reason this counter is worth having: a normal tick-priced fill must NOT
   * move it. A counter that fires on healthy fills is noise, and noise is how the real signal gets
   * ignored.
   */
  @Test
  void anOrdinaryTickPricedFillDoesNotMoveTheNoTickCounter() {
    String sym = "H44OK-" + UUID.randomUUID();
    seedTick(sym, "101.00", OffsetDateTime.now(ZoneOffset.UTC));
    double before = noTickFills();

    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, null, null, null));

    assertThat(noTickFills()).isEqualTo(before);
  }

  private double noTickFills() {
    return meters.find(NoTickFillListener.NO_TICK_FILL_TOTAL).counters().stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  /** A published signal carrying an entry price, so the no-tick fallback has something to use. */
  private long seedSignalWithEntry(String tradingsymbol, BigDecimal entry) {
    String suffix = UUID.randomUUID().toString();
    UUID strategyId =
        jdbc.queryForObject(
            "INSERT INTO strategies (slug, name, tags) VALUES (?, ?, ?::text[]) RETURNING id",
            UUID.class,
            "h44-" + suffix,
            "H44 " + suffix,
            "{scalper}");
    UUID versionId =
        jdbc.queryForObject(
            """
            INSERT INTO strategy_versions
              (strategy_id, version, config_yaml, config, schema_version, checksum, status)
            VALUES (?, '1', '', '{}'::jsonb, '1', ?, 'published') RETURNING id
            """,
            UUID.class,
            strategyId,
            "chk-" + suffix);
    return jdbc.queryForObject(
        """
        INSERT INTO signals
          (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
           composite_score, score_breakdown, entry_price)
        VALUES (?, 'BFO', ?, '3m', 'ENTRY', 'BUY', 0.7, '{}'::jsonb, ?) RETURNING id
        """,
        Long.class,
        versionId,
        tradingsymbol,
        entry);
  }
}
