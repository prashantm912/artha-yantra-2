package in.arthayantra.backtest.replay.folds;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Aggregates per-fold {@link FoldResult}s into the headline objective + dispersion + degradation
 * roll-up (guards 2, 3 and 7, §D.4).
 *
 * <p><b>Validity (guard 3).</b> A fold is invalid when its OOS closed-trade count is below {@code
 * min_trades} (default 30). Invalid folds are <b>excluded from every aggregation</b> and the count
 * of exclusions is carried explicitly on {@link FoldAggregate} — never a silent drop (a 3-trade
 * "Sharpe 4.0" fold must never inflate a headline number).
 *
 * <p><b>Headline objective (guard 2).</b> The mean of the valid folds' OOS objective — the config
 * {@code objective.metric} (default {@code sharpe}). The {@code fold_aggregation} knob ({@code mean
 * | min | mean_minus_std}) is a Phase-32 consumer; the {@link #SEAM seam} is left clean and the
 * default {@code mean} is wired here. {@code oosFoldStd} is the <b>sample (Bessel-corrected, n−1)
 * standard deviation</b> of the OOS objective across valid folds — documented choice; it is {@code
 * 0} for a single valid fold and {@code null} for none.
 *
 * <p><b>Degradation (guard 7).</b> {@code sharpe_degradation = mean(train sharpe) − mean(oos
 * sharpe)} across valid folds — a <b>difference, never a ratio</b> (the rejected S1B {@code (train −
 * oos)/train} sign-flips for negatives and divides by zero at train Sharpe 0). It is suppressed
 * (returned {@code null}, the "n/a — weak train signal" state) when there are no valid folds or the
 * mean train Sharpe is below {@code 0.5}.
 */
@Component
public class ObjectiveAggregator {

  /** Phase-32 seam: the only {@code fold_aggregation} mode wired in Phase 31. */
  public static final String SEAM = "mean";

  private static final MathContext MC = new MathContext(32, RoundingMode.HALF_UP);
  private static final int SCALE = 6;
  private static final BigDecimal WEAK_TRAIN_SHARPE = new BigDecimal("0.5");

  /**
   * Aggregates the fold results.
   *
   * @param foldResults all evaluated folds (valid + invalid), in fold order
   * @param objectiveMetric the §D.9 metric key to aggregate (e.g. {@code sharpe})
   * @param minTrades the {@code constraints.min_trades} threshold (default 30)
   * @return the across-fold roll-up; never null
   */
  public FoldAggregate aggregate(
      List<FoldResult> foldResults, String objectiveMetric, int minTrades) {
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
      return new FoldAggregate(valid, excluded, null, null, null, null);
    }

    List<BigDecimal> oosObjectives = new ArrayList<>(valid.size());
    for (FoldResult fr : valid) {
      oosObjectives.add(metricValue(fr.oosMetrics().full(), objectiveMetric, fr.oosMetrics()));
    }
    BigDecimal mean = mean(oosObjectives);
    BigDecimal std = sampleStd(oosObjectives, mean);

    BigDecimal degradation = degradation(valid);

    return new FoldAggregate(valid, excluded, mean, mean, std, degradation);
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
