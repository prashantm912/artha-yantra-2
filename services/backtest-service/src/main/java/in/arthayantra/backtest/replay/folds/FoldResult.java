package in.arthayantra.backtest.replay.folds;

import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.Trade;
import java.math.BigDecimal;
import java.util.List;

/**
 * One evaluated fold: the {@link Fold} bounds plus the in-sample (train) and out-of-sample (test)
 * metric sets and the OOS closed-trade count used for the {@code min_trades} validity check (guard
 * 3, §D.4). {@code oosTradeCount} is surfaced separately from {@code oosMetrics.tradeCount()} so the
 * validity predicate is unambiguous at the call site. The OOS trade list + the fold's initial equity
 * are carried so Phase-32 regime attribution (guard 6) can tag each trade by its entry-day regime
 * and compute per-regime OOS aggregates without re-replaying the fold.
 *
 * @param fold the train/test window bounds
 * @param trainMetrics the in-sample §D.9 catalog
 * @param oosMetrics the out-of-sample §D.9 catalog (the headline objective source)
 * @param oosTradeCount closed OOS trades — {@code < min_trades} marks the fold invalid
 * @param oosTrades the OOS replay trades (closed + open-at-end), in seq order — the regime-tag source
 * @param initialEquity the fold's starting equity (the per-trade return denominator for regime Sharpe)
 */
public record FoldResult(
    Fold fold,
    Metrics trainMetrics,
    Metrics oosMetrics,
    int oosTradeCount,
    List<Trade> oosTrades,
    BigDecimal initialEquity) {}
