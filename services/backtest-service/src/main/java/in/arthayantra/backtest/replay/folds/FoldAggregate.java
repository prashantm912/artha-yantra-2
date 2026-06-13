package in.arthayantra.backtest.replay.folds;

import java.math.BigDecimal;
import java.util.List;

/**
 * The across-fold roll-up that lands in {@code backtest_runs} for a walk-forward run (guards 2, 3
 * and 7, §D.4). {@code headlineObjective} is the <b>mean of the valid folds' OOS objective</b> (the
 * config {@code objective.metric}, default {@code sharpe}); {@code oosFoldMean}/{@code oosFoldStd}
 * are the queryable dispersion of that same OOS objective across valid folds; {@code
 * sharpeDegradation} is {@code mean(train sharpe) − mean(oos sharpe)} (a difference, never a ratio)
 * or {@code null} when suppressed. {@code excludedFoldCount} is the explicit count of folds dropped
 * for under-trading ({@code min_trades}) — surfaced, never silently swallowed.
 *
 * @param validFolds the folds that passed {@code min_trades}, in chronological order
 * @param excludedFoldCount folds excluded for OOS trade count below {@code min_trades}
 * @param headlineObjective mean of valid folds' OOS objective, or {@code null} when no valid folds
 * @param oosFoldMean mean of the OOS objective across valid folds (== headline for the {@code mean}
 *     aggregation), or {@code null}
 * @param oosFoldStd sample (Bessel-corrected, n−1) std-dev of the OOS objective across valid folds;
 *     {@code 0} for a single valid fold, {@code null} for none
 * @param sharpeDegradation {@code mean(train sharpe) − mean(oos sharpe)} across valid folds, or
 *     {@code null} when there are no valid folds or mean train sharpe {@code < 0.5}
 */
public record FoldAggregate(
    List<FoldResult> validFolds,
    int excludedFoldCount,
    BigDecimal headlineObjective,
    BigDecimal oosFoldMean,
    BigDecimal oosFoldStd,
    BigDecimal sharpeDegradation) {}
