package in.arthayantra.backtest.replay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * MV-10.1/10.2 — the Minervini swing report card + self-measurement over a set of CLOSED trades
 * (§2.9/§2.11 + the §0.5 #12 reliability bar). Pure, deterministic, single-strategy (a run is one
 * strategy, so stats are never blended). Complements {@link MetricsCalculator} (which already yields
 * winRate/profitFactor/expectancy for the run) with the Minervini-specific pieces: average win /
 * average loss, the payoff ratio (avg win ÷ |avg loss|), average hold-time, and the pass/fail against
 * the reliability bar (positive expectancy AND payoff ≥ 2 AND batting ≥ ~45%), plus a letter grade.
 *
 * <p>All percentages use each trade's {@code pnlPct} (position-size-independent). This is a read-only
 * analytics — it never touches the replay/parity path, so it cannot move a golden vector.
 */
public record SwingReportCard(
    int trades,
    int wins,
    int losses,
    BigDecimal battingAvgPct,
    BigDecimal avgWinPct,
    BigDecimal avgLossPct,
    BigDecimal payoffRatio,
    BigDecimal expectancyPct,
    BigDecimal avgBarsHeld,
    boolean meetsReliabilityBar,
    String grade) {

  private static final BigDecimal TWO = new BigDecimal("2");
  private static final BigDecimal ONE_POINT_FIVE = new BigDecimal("1.5");
  private static final BigDecimal MIN_BATTING = new BigDecimal("45");

  /** Grades a closed-trade set. An empty set is graded {@code "N/A"} and fails the bar. */
  public static SwingReportCard of(List<Trade> trades) {
    int n = trades == null ? 0 : trades.size();
    if (n == 0) {
      return new SwingReportCard(
          0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
          BigDecimal.ZERO, BigDecimal.ZERO, false, "N/A");
    }
    BigDecimal sumPct = BigDecimal.ZERO;
    BigDecimal sumWin = BigDecimal.ZERO;
    BigDecimal sumLoss = BigDecimal.ZERO;
    long sumBars = 0;
    int w = 0;
    int l = 0;
    for (Trade t : trades) {
      BigDecimal p = t.pnlPct() == null ? BigDecimal.ZERO : t.pnlPct();
      sumPct = sumPct.add(p);
      sumBars += t.barsHeld();
      if (p.signum() > 0) {
        w++;
        sumWin = sumWin.add(p);
      } else if (p.signum() < 0) {
        l++;
        sumLoss = sumLoss.add(p);
      }
    }
    BigDecimal avgWin = w == 0 ? BigDecimal.ZERO : divide(sumWin, w);
    BigDecimal avgLoss = l == 0 ? BigDecimal.ZERO : divide(sumLoss, l); // negative
    BigDecimal payoff =
        avgLoss.signum() == 0 ? BigDecimal.ZERO : divide(avgWin, avgLoss.abs());
    BigDecimal batting = divide(BigDecimal.valueOf(w).multiply(BigDecimal.valueOf(100)), n);
    BigDecimal expectancy = divide(sumPct, n);
    BigDecimal avgBars = divide(BigDecimal.valueOf(sumBars), n);

    boolean meets =
        expectancy.signum() > 0
            && payoff.compareTo(TWO) >= 0
            && batting.compareTo(MIN_BATTING) >= 0;
    String grade =
        meets
            ? "A"
            : expectancy.signum() > 0 && payoff.compareTo(ONE_POINT_FIVE) >= 0
                ? "B"
                : expectancy.signum() > 0 ? "C" : "D";

    return new SwingReportCard(
        n, w, l, batting, avgWin, avgLoss, payoff, expectancy, avgBars, meets, grade);
  }

  private static BigDecimal divide(BigDecimal num, long den) {
    return num.divide(BigDecimal.valueOf(den), 4, RoundingMode.HALF_UP);
  }

  private static BigDecimal divide(BigDecimal num, BigDecimal den) {
    return num.divide(den, 4, RoundingMode.HALF_UP);
  }
}
