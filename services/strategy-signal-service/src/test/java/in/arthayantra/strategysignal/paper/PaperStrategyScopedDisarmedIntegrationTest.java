package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperService.OrderRequest;
import in.arthayantra.strategysignal.paper.ScopedKeyTwinFixture.OpenLot;
import in.arthayantra.strategysignal.paper.ScopedKeyTwinFixture.Twin;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V058 SHIPS DISARMED, and this is the proof: the DEFAULT configuration — no {@code
 * artha.paper.strategy-scoped-books} property at all — still merges two strategies into one averaged
 * lot, exactly as {@code main} does today.
 *
 * <p>Deliberately the SAME fixture as {@link
 * PaperStrategyScopedOpenKeyIntegrationTest#twinsEnteringOneKeyOnTheSameBarHoldSeparateLotsWithTheirOwnBrackets}
 * with the OPPOSITE expected outcome, so the pair isolates the flag as the only difference. A
 * migration and a set of widened joins that changed behaviour on merge would be a live-money change
 * arriving as a deploy side-effect; arming a book is an owner decision on realised P&amp;L, taken
 * separately.
 *
 * <p>UNDER A NON-CONFIGURABLE SPLIT — the shape where scoping is simply always on — every assertion
 * in this class fails: two lots instead of one, qty 10 instead of 20, a non-null strategy_id.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperStrategyScopedDisarmedIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          // Lot 5, not 50: openOrder now REFUSES a non-lot-multiple fill (the alignment rule
          // moved to the writer), and this fixture's quantities (10/20/25/30/40/65) were
          // chosen years before that rule existed. 5 divides every one of them, so the
          // QUANTITIES and every asserted figure derived from them are untouched — only the
          // stub's declared lot moves. Live is unaffected: all 40 F&O paper positions are
          // lot-aligned today (computed 2026-08-25), which is why the rule is safe to enforce.
          new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 5);
    }
  }

  private static final String EX = "NFO";
  private static final BigDecimal PX = new BigDecimal("100.00");

  @Autowired private PaperService paper;
  @Autowired private PaperPositionRepository positions;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void twoStrategiesOnOneKeyStillAverageIntoOneUnattributedLotByDefault() {
    String sym = "UNSCOPED-" + UUID.randomUUID().toString().substring(0, 8);
    Twin gc = ScopedKeyTwinFixture.seedTwin(jdbc, "unscoped-gc", EX, sym, "BUY");
    Twin ctd = ScopedKeyTwinFixture.seedTwin(jdbc, "unscoped-ctd", EX, sym, "BUY");

    paper.openOrder(entry(gc.signalId(), sym, new BigDecimal("40.00")));
    paper.openOrder(entry(ctd.signalId(), sym, new BigDecimal("55.00")));

    List<OpenLot> lots = ScopedKeyTwinFixture.openLots(jdbc, Books.OTHER, EX, sym, "BUY");
    assertThat(lots).singleElement().satisfies(lot -> {
      assertThat(lot.qty()).isEqualTo(20);              // averaged, not split
      assertThat(lot.strategyId()).isNull();            // nothing is stamped when disarmed
      assertThat(lot.stopLoss()).isEqualByComparingTo("40.00"); // the SECOND twin's stop is lost
    });

    // And the merged lot is still reachable — hence closeable — from EITHER twin's anchor, which is
    // the live behaviour this feature exists to change and must keep until a book is armed.
    assertThat(positions.openForSignal(gc.signalId())).hasSize(1);
    assertThat(positions.openForSignal(ctd.signalId())).hasSize(1);
  }

  private OrderRequest entry(long signalId, String sym, BigDecimal stopLoss) {
    return new OrderRequest(
        signalId, EX, sym, "BUY", 10, PX, stopLoss, new BigDecimal("200.00"), null, Books.OTHER);
  }
}
