package in.arthayantra.backtest.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The §D.16 benchmark-relative metric set, computed as a pure function over two aligned periodic
 * return series (strategy vs benchmark, one return per shared period). All statistics are
 * <b>population</b> moments (divide by {@code n}) so the regression slope/intercept are
 * well-defined for any {@code n ≥ 2}; ratio metrics are annualized with the same √(periods/yr)
 * scaling family as Sharpe (§D.9). Returned values are rounded to a fixed scale so the persisted
 * decimal strings are stable — {@code null} fields mean "undefined for this window" (degenerate
 * regression or an empty up/down partition), never silently zero.
 *
 * <p>Coverage absence (no benchmark data at all) is handled upstream by {@code BenchmarkAnalyzer};
 * this class assumes the caller already has aligned, finite return pairs.
 */
public final class BenchmarkMetrics {

  private static final int SCALE = 6;
  /** Persisted columns are {@code NUMERIC(18,6)} → 12 integer digits. A value beyond this is a
   * degenerate-window artifact (e.g. annualizing a few days of return), so it is reported as
   * undefined (NULL) rather than overflowing the column. */
  private static final double MAX_MAGNITUDE = 1e11;

  private BenchmarkMetrics() {}

  /**
   * The relative metrics. {@code alpha}/{@code beta}/{@code informationRatio} are {@code null} when
   * the regression is degenerate (benchmark variance 0 or fewer than two paired returns);
   * {@code upCapture}/{@code downCapture} are {@code null} when the benchmark had no up / no down
   * period in the window (the all-up / all-down edge).
   */
  public record Result(
      BigDecimal alpha,
      BigDecimal beta,
      BigDecimal informationRatio,
      BigDecimal excessCagr,
      BigDecimal upCapture,
      BigDecimal downCapture) {}

  /**
   * Computes the relative set from aligned return arrays.
   *
   * @param strat strategy periodic returns
   * @param bench benchmark periodic returns, index-aligned to {@code strat} (same length)
   * @param periodsPerYear annualization factor (252 daily, etc.)
   * @param strategyCagr the strategy's own CAGR over the window (fraction, e.g. 0.25 = 25%)
   * @param benchmarkCagr the benchmark buy-and-hold CAGR over the identical window (fraction)
   * @return the relative metrics; never {@code null}, though individual fields may be {@code null}
   */
  public static Result compute(
      double[] strat,
      double[] bench,
      double periodsPerYear,
      BigDecimal strategyCagr,
      BigDecimal benchmarkCagr) {
    if (strat.length != bench.length) {
      throw new IllegalArgumentException(
          "aligned return series must match: " + strat.length + " vs " + bench.length);
    }
    BigDecimal excessCagr =
        strategyCagr == null || benchmarkCagr == null
            ? null
            : bounded(strategyCagr.subtract(benchmarkCagr));

    if (strat.length < 2) {
      return new Result(null, null, null, excessCagr, null, null);
    }

    double meanS = mean(strat);
    double meanB = mean(bench);
    double varB = 0;
    double cov = 0;
    for (int i = 0; i < strat.length; i++) {
      double db = bench[i] - meanB;
      varB += db * db;
      cov += (strat[i] - meanS) * db;
    }
    varB /= strat.length;
    cov /= strat.length;

    BigDecimal beta = null;
    BigDecimal alpha = null;
    if (varB > 0) {
      double b = cov / varB;
      beta = scaled(b);
      alpha = scaled((meanS - b * meanB) * periodsPerYear); // annualized intercept
    }

    BigDecimal ir = informationRatio(strat, bench, periodsPerYear);
    BigDecimal up = capture(strat, bench, true);
    BigDecimal down = capture(strat, bench, false);
    return new Result(alpha, beta, ir, excessCagr, up, down);
  }

  private static BigDecimal informationRatio(double[] strat, double[] bench, double periodsPerYear) {
    double[] active = new double[strat.length];
    for (int i = 0; i < strat.length; i++) {
      active[i] = strat[i] - bench[i];
    }
    double meanA = mean(active);
    double sq = 0;
    for (double a : active) {
      sq += (a - meanA) * (a - meanA);
    }
    double std = Math.sqrt(sq / active.length);
    if (std == 0) {
      return null;
    }
    return scaled(meanA / std * Math.sqrt(periodsPerYear));
  }

  /**
   * Mean strategy return over benchmark-up (or -down) periods divided by the benchmark's own mean
   * over those periods — {@code null} when that partition is empty (all-up / all-down window).
   */
  private static BigDecimal capture(double[] strat, double[] bench, boolean up) {
    double sumS = 0;
    double sumB = 0;
    int n = 0;
    for (int i = 0; i < bench.length; i++) {
      boolean inPartition = up ? bench[i] > 0 : bench[i] < 0;
      if (inPartition) {
        sumS += strat[i];
        sumB += bench[i];
        n++;
      }
    }
    if (n == 0 || sumB == 0) {
      return null;
    }
    return scaled((sumS / n) / (sumB / n));
  }

  private static double mean(double[] xs) {
    double sum = 0;
    for (double x : xs) {
      sum += x;
    }
    return sum / xs.length;
  }

  private static BigDecimal scaled(double v) {
    if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) >= MAX_MAGNITUDE) {
      return null;
    }
    return BigDecimal.valueOf(v).setScale(SCALE, RoundingMode.HALF_UP);
  }

  /** Rounds to scale, or {@code null} when the magnitude would overflow the persisted column. */
  private static BigDecimal bounded(BigDecimal v) {
    if (v.abs().compareTo(BigDecimal.valueOf(MAX_MAGNITUDE)) >= 0) {
      return null;
    }
    return v.setScale(SCALE, RoundingMode.HALF_UP);
  }
}
