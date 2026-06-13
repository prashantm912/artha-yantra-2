package in.arthayantra.backtest.replay.folds;

import java.math.BigDecimal;
import java.util.List;

/**
 * The across-fold roll-up that lands in {@code backtest_runs} for a walk-forward run (guards 2, 3, 6
 * and 7, §D.4). {@code headlineObjective} is the chosen {@code fold_aggregation} over the valid
 * folds' OOS objective ({@code mean} | {@code min} | {@code mean_minus_std}, default {@code mean};
 * the config {@code objective.metric}, default {@code sharpe}); {@code oosFoldMean}/{@code
 * oosFoldStd} are the queryable dispersion of that same OOS objective across valid folds regardless
 * of the aggregation mode; {@code sharpeDegradation} is {@code mean(train sharpe) − mean(oos sharpe)}
 * (a difference, never a ratio) or {@code null} when suppressed.
 *
 * <p><b>Exclusion accounting (never silent).</b> {@code excludedFoldCount} is folds dropped for
 * under-trading ({@code min_trades}); {@code prunedFoldCount} is folds dropped because the trial was
 * pruned (the pruning seam is the optimizer — see {@link ObjectiveAggregator}). Both are surfaced so
 * a consumer can tell "3 valid, 2 excluded, 0 pruned" from a clean "3 valid". {@code aggregation} is
 * the mode that produced {@code headlineObjective} (echoed so the leaderboard renders the right
 * "min over n folds (k excluded)" label).
 *
 * @param validFolds the folds that passed {@code min_trades}, in chronological order
 * @param excludedFoldCount folds excluded for OOS trade count below {@code min_trades}
 * @param prunedFoldCount folds excluded as pruned (partial-coverage); {@code 0} for a complete trial
 * @param aggregation the {@code fold_aggregation} mode applied ({@code mean|min|mean_minus_std})
 * @param headlineObjective the aggregated OOS objective over valid folds, or {@code null} when none
 * @param oosFoldMean mean of the OOS objective across valid folds, or {@code null}
 * @param oosFoldStd sample (Bessel-corrected, n−1) std-dev of the OOS objective across valid folds;
 *     {@code 0} for a single valid fold, {@code null} for none
 * @param sharpeDegradation {@code mean(train sharpe) − mean(oos sharpe)} across valid folds, or
 *     {@code null} when there are no valid folds or mean train sharpe {@code < 0.5}
 */
public record FoldAggregate(
    List<FoldResult> validFolds,
    int excludedFoldCount,
    int prunedFoldCount,
    String aggregation,
    BigDecimal headlineObjective,
    BigDecimal oosFoldMean,
    BigDecimal oosFoldStd,
    BigDecimal sharpeDegradation) {}
