package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ⚠️ The round-2 CRITICAL, and it REVERSED a round-1 decision — which is why it gets its own context.
 *
 * <p>The first cut ALLOWED the fill when the tick store could not answer. That was correct while the
 * probe was a DIAGNOSTIC (a diagnostic must never break the thing it observes, and an earlier inline
 * version was rejected in review for exactly that). It became wrong the moment the probe turned into
 * a SAFETY GATE: a fill we could not verify is precisely the fill that stranded funded capital on
 * 2026-08-28, so failing open recreates H44 while the flag advertises protection — worse than not
 * arming it at all, because it is believed.
 *
 * <p>The direction is settled by repo doctrine, not preference. #694: entries need fresh truth (you
 * can always NOT enter), exits need the best available truth (you cannot refuse to leave forever).
 * This is an entry.
 *
 * <p><b>A separate class because the stub is destructive.</b> A {@code @Primary} reader that throws
 * on every call cannot share a context with the tests that need a working one.
 *
 * <p><b>Blast radius, stated so it is never a surprise:</b> while the tick store is unreachable AND
 * this flag is ARMED, every option entry is refused. That is the intended trade.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.paper.refuse-no-tick-entries=true"
    })
class PaperNoTickProbeFailureIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubUnreachableTicks {

    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          "NSE".equals(exchange)
              ? new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1)
              : new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }

    /** Stands in for Redis being unreachable — throws rather than answering "no tick". */
    @Bean
    @Primary
    LastTickReader unreachableTickReader() {
      return new LastTickReader(null, null, Clock.systemUTC()) {
        @Override
        public Optional<TickView> lastTick(String exchange, String tradingsymbol) {
          throw new IllegalStateException("redis down");
        }
      };
    }
  }

  @Autowired private PaperService paper;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void anOptionEntryIsRefusedWhenTheTickStoreCannotAnswer() {
    String sym = "H44PROBE-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                paper.openOrder(
                    new PaperService.OrderRequest(
                        null, "BFO", sym, "BUY", 50, new BigDecimal("779.55"), null, null)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("cannot verify")
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo("DATA_GAP");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM strategy.paper_positions"
                    + " WHERE tradingsymbol=? AND status='OPEN'",
                Integer.class,
                sym))
        .as("failing closed means nothing opens — otherwise the flag protects nothing")
        .isZero();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM strategy.paper_order_rejections"
                    + " WHERE tradingsymbol=? AND reason='DATA_GAP_CLOSABILITY_UNKNOWN' AND qty=50",
                Integer.class,
                sym))
        .as(
            "its OWN reason code and the ATTEMPTED qty: 'we could not ask' is a different operational"
                + " fact from 'we asked and it has never ticked', and a qty of 0 would make the"
                + " forensic row lie about the size of what was refused")
        .isEqualTo(1);
  }

  // ⚠️ A SECOND TEST WAS WRITTEN HERE AND DELETED, because it asserted something FALSE and the
  // reason is worth more than the test was.
  //
  // It claimed an EQUITY entry is unaffected by an unreachable tick store, on the reasoning that the
  // H44 gate is OPTION-scoped and never probes for one. The gate part is true. The claim is not:
  // measured 2026-08-28, the equity open still failed with "redis down", from
  // PaperService:1149 -> PaperAccountService.buyingPowerWarning -> freeCash -> equity ->
  // unrealizedTotal -> mark -> markFor -> LastTickReader.lastPrice. That path is PRE-EXISTING and
  // has nothing to do with H44: valuing a book marks its open positions, and marking reads ticks.
  //
  // Two things follow, and both matter more than a green test:
  //
  // 1. In a TOTAL tick-store outage the H44 gate is not the binding constraint -- paper opens were
  //    already failing upstream of it, armed or not. The gate binds on a PARTIAL failure, which is
  //    also the realistic one (a single key read failing, a timeout on one hash).
  // 2. The surviving test above therefore passes for the RIGHT reason and not by luck: the H44 gate
  //    sits at PaperService ~964 and buyingPowerWarning at 1149, so for an option the gate is
  //    reached FIRST. That ordering is what its "cannot verify" message assertion pins -- if the
  //    gate were ever moved below the buying-power mark, the message would change and it goes red.
}
