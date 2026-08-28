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
 * H44 closability gate, ARMED. Sibling of {@code PaperForensicsIntegrationTest}, which exercises the
 * same paths with the flag at its shipped default (off) — the two together are what pin "armed
 * refuses" AND "disarmed is byte-identical to before".
 *
 * <p><b>Why a whole separate class.</b> The flag is read once at construction via {@code @Value}, so
 * arming it means a different Spring context, not a different method. Splitting also keeps the
 * default-off assertions in a context where nothing about this feature is set — a test that armed and
 * disarmed within one context could never prove the SHIPPED default is off.
 *
 * <p><b>Every case here supplies an explicit caller price on purpose.</b> The two positions stranded
 * live on 2026-08-28 were both {@code refSource=CALLER}, and with a caller price the pricing block
 * never consults the tick at all. A guard written inside that block would have missed the real
 * incident entirely; these tests fail if it is ever moved there.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.paper.refuse-no-tick-entries=true"
    })
class PaperNoTickGuardIntegrationTest extends StrategySignalIntegrationTestBase {

  /** NSE answers EQUITY, every derivative segment answers OPTION — the discrimination under test. */
  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          "NSE".equals(exchange)
              ? new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1)
              : new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }
  }

  @Autowired private PaperService paper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;

  @Autowired private io.micrometer.core.instrument.MeterRegistry meters;

  /**
   * The measured incident, in a test: an option contract that has never ticked is refused rather than
   * opened. Live on 2026-08-28 two such legs sat through their TIME_STOPs, their signal-exit and the
   * 15:44 square-off, accrued 1,973 starved-bracket WARNs and held two sub-accounts allocation-dead.
   */
  @Test
  void anOptionThatHasNeverTickedIsRefusedWhenArmed() {
    String sym = "H44GUARD-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "BFO", sym, "BUY", 50, new BigDecimal("779.55"), null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("no tick has ever been seen")
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo("DATA_GAP");

    assertThat(openPositionsFor(sym))
        .as("a refused entry must open NOTHING — the whole point is not to create the stuck row")
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM strategy.paper_order_rejections"
                    + " WHERE tradingsymbol=? AND reason='DATA_GAP_NEVER_TICKED'",
                Integer.class,
                sym))
        .as("the refusal is durably attributed to its OWN cause, not folded into a stale-tick row")
        .isEqualTo(1);
  }

  /**
   * ⚠️ THE BLAST-RADIUS TEST, and the reason this gate is OPTION-scoped rather than class-blind.
   *
   * <p>EQUITIES DO NOT TICK. {@code PaperService.countMtmBlindPositions} records that all 18
   * cash-equity swing positions once counted as mark-blind for exactly that structural reason. A
   * class-blind version of this guard would therefore refuse EVERY swing equity entry the moment the
   * flag was armed — turning a fix for two stranded option legs into an outage of the entire swing
   * book. If this test ever goes red, the gate has stopped discriminating and MUST NOT be armed.
   */
  @Test
  void anEquityThatHasNeverTickedStillFillsWhenArmed() {
    String sym = "H44EQ-" + UUID.randomUUID();

    PaperService.PositionDto pos =
        paper.openOrder(
            new PaperService.OrderRequest(
                null, "NSE", sym, "BUY", 10, new BigDecimal("101.25"), null, null));

    assertThat(pos).isNotNull();
    assertThat(openPositionsFor(sym)).isEqualTo(1);
    assertThat(noTickFills())
        .as("the COUNTER must be OPTION-scoped too. Round 1 filtered the gate and forgot the"
            + " listener, so equities -- which structurally never tick, and whose automatic exits"
            + " settle at an explicit session price -- would have dominated the very rate the"
            + " arming decision rests on (cross-vendor review round 2).")
        .isZero();
  }

  private double noTickFills() {
    return meters.find("ay_paper_fill_no_tick_total").counters().stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  /** The control: an option that HAS ticked is unaffected, so the gate is not simply refusing all. */
  @Test
  void anOptionThatHasTickedIsUnaffectedWhenArmed() {
    String sym = "H44OK-" + UUID.randomUUID();
    seedTick(sym, "112.40", OffsetDateTime.now(ZoneOffset.UTC));

    PaperService.PositionDto pos =
        paper.openOrder(
            new PaperService.OrderRequest(
                null, "NFO", sym, "BUY", 50, new BigDecimal("112.40"), null, null));

    assertThat(pos).isNotNull();
    assertThat(openPositionsFor(sym)).isEqualTo(1);
  }

  private int openPositionsFor(String sym) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM strategy.paper_positions WHERE tradingsymbol=? AND status='OPEN'",
        Integer.class,
        sym);
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
