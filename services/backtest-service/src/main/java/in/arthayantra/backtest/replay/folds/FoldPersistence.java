package in.arthayantra.backtest.replay.folds;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;

/**
 * The fold-specific columns a walk-forward (or implicit 70/30) run writes to {@code backtest_runs}
 * (Phase 31, §D.4). A plain full-window run passes {@code null} for this whole bundle and the four
 * columns stay NULL — the explicit signal that "this run had no train/test structure".
 *
 * @param foldMetrics the {@code fold_metrics} JSONB array — one {@code {fold, train, test,
 *     trainMetrics, oosMetrics, regimeMix:null}} object per VALID fold, in fold order
 * @param oosFoldMean across-fold OOS objective mean, or {@code null} when no valid folds
 * @param oosFoldStd across-fold OOS objective sample std, or {@code null} when no valid folds
 * @param sharpeDegradation {@code mean(train sharpe) − mean(oos sharpe)}, or {@code null} when
 *     suppressed (weak train signal / no valid folds)
 * @param excludedFoldCount folds dropped for OOS trade count below {@code min_trades} (guard 3/6) —
 *     surfaced on the run's {@code metrics} JSONB as {@code foldsExcluded} so a consumer can tell
 *     "3 valid, 0 excluded" from "3 valid, 2 excluded", never a silent drop; {@code 0} when none
 */
public record FoldPersistence(
    ArrayNode foldMetrics,
    BigDecimal oosFoldMean,
    BigDecimal oosFoldStd,
    BigDecimal sharpeDegradation,
    int excludedFoldCount) {}
