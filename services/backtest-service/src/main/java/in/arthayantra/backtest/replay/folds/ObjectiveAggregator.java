package in.arthayantra.backtest.replay.folds;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Aggregates per-fold {@link FoldResult}s into the headline objective + dispersion + degradation
 * roll-up (guards 2, 3, 6 and 7, §D.4).
 *
 * <p><b>Validity (guard 3).</b> A fold is invalid when its OOS closed-trade count is below {@code
 * min_trades} (default 30). Invalid folds are <b>excluded from every aggregation</b> and the count
 * of exclusions is carried explicitly on {@link FoldAggregate} — never a silent drop (a 3-trade
 * "Sharpe 4.0" fold must never inflate a headline number).
 *
 * <p><b>Fold aggregation (guard 6).</b> {@code objective.fold_aggregation} ({@code mean | min |
 * mean_minus_std}, default {@code mean}) chooses how the valid folds' OOS objective collapses to the
 * headline:
 *
 * <ul>
 *   <li><b>mean</b> — the arithmetic mean (the §D.4 default);
 *   <li><b>min</b> — the worst valid fold (maximin / conservative choice); folds failing {@code
 *       min_trades} are excluded BEFORE the min (the minimum of small-sample Sharpes is noise) and
 *       any such exclusion surfaces as an explicit count, never a silent drop;
 *   <li><b>mean_minus_std</b> — {@code mean − sample_std} (a dispersion-penalized mean).
 * </ul>
 *
 * <p><b>Pruned-trial seam.</b> Pruned trials' truncated folds are likewise excluded from {@code
 * min}/{@code mean_minus_std} (and from cross-trial comparison) and flagged <b>partial-coverage</b>
 * via {@link FoldAggregate#prunedFoldCount()} rather than compared on a truncated fold set. The
 * pruner itself is the optimizer (Phase 33/34); backtest-service only carries the count it is told,
 * because pruning is a sweep-level decision. {@code oosFoldMean}/{@code oosFoldStd} are reported for
 * every mode (the queryable dispersion is mode-independent).
 *
 * <p><b>Degradation (guard 7).</b> {@code sharpe_degradation = mean(train sharpe) − mean(oos
 * sharpe)} across valid folds — a <b>difference, never a ratio</b> (the rejected S1B {@code (train −
 * oos)/train} sign-flips for negatives and divides by zero at train Sharpe 0). It is suppressed
 * (returned {@code null}, the "n/a — weak train signal" state) when there are no valid folds or the
 * mean train Sharpe is below {@code 0.5}.
 */
@Component
public class ObjectiveAggregator {

  /** The §D.4 default {@code fold_aggregation} mode. */
  public static final String DEFAULT_AGGREGATION = "mean";

  /**
   * The only {@code fold_aggregation} mode wired in Phase 31; kept for back-compat with callers that
   * reference the seam. Phase 32 wires {@code min} and {@code mean_minus_std} too.
   */
  public static final String SEAM = DEFAULT_AGGREGATION;

  private static final MathContext MC = new MathContext(32, RoundingMode.HALF_UP);
  private static final int SCALE = 6;
  private static final BigDecimal WEAK_TRAIN_SHARPE = new BigDecimal("0.5");

  /**
   * Aggregates the fold results with the default {@code mean} aggregation and no pruning (the
   * Phase-31 entry point; retained so existing callers/tests compile unchanged).
   *
   * @param foldResults all evaluated folds (valid + invalid), in fold order
   * @param objectiveMetric the §D.9 metric key to aggregate (e.g. {@code sharpe})
   * @param minTrades the {@code constraints.min_trades} threshold (default 30)
   * @return the across-fold roll-up; never null
   */
  public FoldAggregate aggregate(
      List<FoldResult> foldResults, String objectiveMetric, int minTrades) {
    return aggregate(foldResults, objectiveMetric, minTrades, DEFAULT_AGGREGATION, 0);
  }

  /**
   * Aggregates the fold results under the chosen {@code fold_aggregation} mode (guard 6).
   *
   * @param foldResults all evaluated folds (valid + invalid), in fold order
   * @param objectiveMetric the §D.9 metric key to aggregate (e.g. {@code sharpe})
   * @param minTrades the {@code constraints.min_trades} threshold (default 30)
   * @param aggregation the mode ({@code mean | min | mean_minus_std}); unknown values fall back to
   *     {@code mean} (the schema validates the enum, so this is a defensive default only)
   * @param prunedFoldCount folds the optimizer pruned away (partial-coverage); {@code 0} normally
   * @return the across-fold roll-up; never null
   */
  public FoldAggregate aggregate(
      List<FoldResult> foldResults,
      String objectiveMetric,
      int minTrades,
      String aggregation,
      int prunedFoldCount) {
    String mode = normalize(aggregation);
    List<FoldResult> valid = new ArrayList<>();
    int excluded = 0;
    for (FoldResult fr : foldResults) {
      if (fr.oosTradeCount() >= minTrades) {
        valid.add(fr);
      } else {
        excluded++;
      }
    }

    if (valid.isEmpty()) {
      return new FoldAggregate(valid, excluded, prunedFoldCount, mode, null, null, null, null);
    }

    List<BigDecimal> oosObjectives = new ArrayList<>(valid.size());
    for (FoldResult fr : valid) {
      oosObjectives.add(metricValue(fr.oosMetrics().full(), objectiveMetric, fr.oosMetrics()));
    }
    BigDecimal mean = mean(oosObjectives);
    BigDecimal std = sampleStd(oosObjectives, mean);
    BigDecimal headline = headline(mode, oosObjectives, mean, std);
    BigDecimal degradation = degradation(valid);

    return new FoldAggregate(
        valid, excluded, prunedFoldCount, mode, headline, mean, std, degradation);
  }

  /** The §D.4 truth table: mean | min (maximin) | mean − std. */
  private static BigDecimal headline(
      String mode, List<BigDecimal> oosObjectives, BigDecimal mean, BigDecimal std) {
    return switch (mode) {
      case "min" -> min(oosObjectives);
      case "mean_minus_std" -> mean.subtract(std).setScale(SCALE, RoundingMode.HALF_UP);
      default -> mean;
    };
  }

  private static BigDecimal min(List<BigDecimal> xs) {
    BigDecimal worst = xs.get(0);
    for (BigDecimal x : xs) {
      if (x.compareTo(worst) < 0) {
        worst = x;
      }
    }
    return worst;
  }

  private static String normalize(String aggregation) {
    if (aggregation == null || aggregation.isBlank()) {
      return DEFAULT_AGGREGATION;
    }
    String m = aggregation.toLowerCase(Locale.ROOT);
    return switch (m) {
      case "min", "mean_minus_std", "mean" -> m;
      default -> DEFAULT_AGGREGATION;
    };
  }

  /** {@code mean(train sharpe) − mean(oos sharpe)} across valid folds, or null when suppressed. */
  private BigDecimal degradation(List<FoldResult> valid) {
    List<BigDecimal> trainSharpes = new ArrayList<>(valid.size());
    List<BigDecimal> oosSharpes = new ArrayList<>(valid.size());
    for (FoldResult fr : valid) {
      trainSharpes.add(fr.trainMetrics().sharpe());
      oosSharpes.add(fr.oosMetrics().sharpe());
    }
    BigDecimal meanTrain = mean(trainSharpes);
    if (meanTrain.compareTo(WEAK_TRAIN_SHARPE) < 0) {
      return null; // n/a — weak train signal (guard 7)
    }
    return meanTrain.subtract(mean(oosSharpes)).setScale(SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Reads the objective metric. {@code sharpe}/{@code sortino} have headline BigDecimal columns;
   * any other key is read out of the §D.9 catalog JSONB. Falls back to the headline Sharpe when the
   * key is absent so a typo never silently aggregates zeros.
   */
  private static BigDecimal metricValue(
      JsonNode full, String metric, in.arthayantra.backtest.replay.MetricsCalculator.Metrics m) {
    return switch (metric) {
      case "sharpe" -> m.sharpe();
      case "sortino" -> m.sortino();
      case "totalReturn" -> m.totalReturn();
      case "maxDrawdown" -> m.maxDrawdown();
      case "winRate" -> m.winRate();
      case "profitFactor" -> m.profitFactor();
      default -> {
        JsonNode node = full.get(metric);
        yield node == null || !node.isValueNode()
            ? m.sharpe()
            : new BigDecimal(node.asText());
      }
    };
  }

  private static BigDecimal mean(List<BigDecimal> xs) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal x : xs) {
      sum = sum.add(x);
    }
    return sum.divide(BigDecimal.valueOf(xs.size()), SCALE, RoundingMode.HALF_UP);
  }

  /** Sample (n−1) standard deviation; 0 for a single observation. */
  private static BigDecimal sampleStd(List<BigDecimal> xs, BigDecimal mean) {
    if (xs.size() < 2) {
      return BigDecimal.ZERO.setScale(SCALE);
    }
    BigDecimal sumSq = BigDecimal.ZERO;
    for (BigDecimal x : xs) {
      BigDecimal d = x.subtract(mean);
      sumSq = sumSq.add(d.multiply(d, MC));
    }
    BigDecimal variance = sumSq.divide(BigDecimal.valueOf(xs.size() - 1L), MC);
    return variance.sqrt(MC).setScale(SCALE, RoundingMode.HALF_UP);
  }
}
