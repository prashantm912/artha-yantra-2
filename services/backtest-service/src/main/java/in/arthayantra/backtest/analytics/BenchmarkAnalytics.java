package in.arthayantra.backtest.analytics;

import in.arthayantra.backtest.replay.EquityPoint;
import java.math.BigDecimal;
import java.util.List;

/**
 * The §D.16 benchmark block resolved for one run: the persisted relative metrics
 * ({@code alpha}/{@code beta}/{@code informationRatio}/{@code excessCagr}), the up/down capture
 * ratios (payload-only, never a column), and the benchmark buy-and-hold curve aligned to the
 * strategy {@code equity_curve}. When {@code present} is {@code false} the benchmark series was not
 * covered for the window — every metric column persists NULL (flagged, never silently zero), per
 * the §D.16 contract.
 */
public record BenchmarkAnalytics(
    boolean present,
    BigDecimal alpha,
    BigDecimal beta,
    BigDecimal informationRatio,
    BigDecimal excessCagr,
    BigDecimal upCapture,
    BigDecimal downCapture,
    List<EquityPoint> benchmarkCurve) {

  /** The coverage-absent sentinel: every field NULL/empty, {@code present=false}. */
  public static BenchmarkAnalytics absent() {
    return new BenchmarkAnalytics(false, null, null, null, null, null, null, List.of());
  }
}
