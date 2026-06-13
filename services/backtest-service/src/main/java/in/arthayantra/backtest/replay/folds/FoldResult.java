package in.arthayantra.backtest.replay.folds;

import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;

/**
 * One evaluated fold: the {@link Fold} bounds plus the in-sample (train) and out-of-sample (test)
 * metric sets and the OOS closed-trade count used for the {@code min_trades} validity check (guard
 * 3, §D.4). {@code oosTradeCount} is surfaced separately from {@code oosMetrics.tradeCount()} so the
 * validity predicate is unambiguous at the call site.
 *
 * @param fold the train/test window bounds
 * @param trainMetrics the in-sample §D.9 catalog
 * @param oosMetrics the out-of-sample §D.9 catalog (the headline objective source)
 * @param oosTradeCount closed OOS trades — {@code < min_trades} marks the fold invalid
 */
public record FoldResult(Fold fold, Metrics trainMetrics, Metrics oosMetrics, int oosTradeCount) {}
