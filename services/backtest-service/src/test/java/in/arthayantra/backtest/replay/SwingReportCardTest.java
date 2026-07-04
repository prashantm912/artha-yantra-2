package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.Side;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MV-10.1/10.2: the Minervini swing report card grades a closed-trade set against the reliability bar
 * (positive expectancy AND payoff ≥ 2 AND batting ≥ ~45%). A winning book with a 2.5:1 payoff and a
 * 60% batting average passes (grade A); a losing book fails (grade D); an empty book is N/A.
 */
class SwingReportCardTest {

  @Test
  void aWinningBookWithA2To1PayoffPassesTheReliabilityBar() {
    // 3 wins @ +10%, 2 losses @ -4% → batting 60%, payoff 10/4 = 2.5, expectancy (30−8)/5 = +4.4%.
    List<Trade> trades =
        List.of(t(10, 8), t(10, 12), t(10, 20), t(-4, 3), t(-4, 5));
    SwingReportCard card = SwingReportCard.of(trades);

    assertThat(card.trades()).isEqualTo(5);
    assertThat(card.wins()).isEqualTo(3);
    assertThat(card.losses()).isEqualTo(2);
    assertThat(card.battingAvgPct()).isEqualByComparingTo("60.0000");
    assertThat(card.avgWinPct()).isEqualByComparingTo("10.0000");
    assertThat(card.avgLossPct()).isEqualByComparingTo("-4.0000");
    assertThat(card.payoffRatio()).isEqualByComparingTo("2.5000");
    assertThat(card.expectancyPct()).isEqualByComparingTo("4.4000");
    assertThat(card.avgBarsHeld()).isEqualByComparingTo("9.6000");
    assertThat(card.meetsReliabilityBar()).isTrue();
    assertThat(card.grade()).isEqualTo("A");
  }

  @Test
  void aLosingBookFailsAndGradesD() {
    SwingReportCard card = SwingReportCard.of(List.of(t(3, 4), t(-8, 6), t(-8, 5)));
    assertThat(card.expectancyPct()).isNegative();
    assertThat(card.meetsReliabilityBar()).isFalse();
    assertThat(card.grade()).isEqualTo("D");
  }

  @Test
  void anEmptyBookIsNotApplicable() {
    SwingReportCard card = SwingReportCard.of(List.of());
    assertThat(card.trades()).isZero();
    assertThat(card.meetsReliabilityBar()).isFalse();
    assertThat(card.grade()).isEqualTo("N/A");
  }

  private static Trade t(double pnlPct, int barsHeld) {
    BigDecimal pct = BigDecimal.valueOf(pnlPct);
    return new Trade(
        1, Side.BUY, 1L, null, BigDecimal.valueOf(100), null, BigDecimal.valueOf(100 + pnlPct),
        pct, pct, "signal_exit", barsHeld, null, null, "NSE", "TEST", null, null);
  }
}
