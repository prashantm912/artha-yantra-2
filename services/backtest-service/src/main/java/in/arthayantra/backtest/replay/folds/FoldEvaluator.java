package in.arthayantra.backtest.replay.folds;

import in.arthayantra.backtest.replay.CostConfig;
import in.arthayantra.backtest.replay.MetricsCalculator;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.ReplayEngine;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Evaluates each {@link Fold} by slicing the full primary 1m candle list (and every context series)
 * to the fold's train and test windows, replaying each slice through the shared {@link ReplayEngine}
 * and computing the §D.9 {@link Metrics} for both (guards 1–2, §D.4). Slicing is by {@code
 * bucketStart} in the half-open window {@code [from, to)} — identical to the candle reader's window
 * semantics — so a fold sees byte-identical bars to a standalone backtest over the same dates. Each
 * fold replay starts from the same {@code initialEquity} (folds are independent evaluations, not a
 * continuous account), which keeps per-fold returns comparable across folds.
 *
 * <p>Single-threaded and deterministic: folds are evaluated in chronological order and the context
 * map is iterated in insertion order (no wall-clock, no randomness).
 */
@Component
public class FoldEvaluator {

  private final ReplayEngine replayEngine;
  private final MetricsCalculator metrics;

  /** Wires the replay engine + metrics calculator. */
  public FoldEvaluator(ReplayEngine replayEngine, MetricsCalculator metrics) {
    this.replayEngine = replayEngine;
    this.metrics = metrics;
  }

  /** Evaluates every fold; returns per-fold train/OOS metric sets in fold order. */
  public List<FoldResult> evaluate(
      List<Fold> folds,
      StrategyDefinition definition,
      String exchange,
      String tradingsymbol,
      List<EngineCandle> primary1m,
      Map<SeriesKey, List<EngineCandle>> contexts,
      BigDecimal initialEquity,
      CostConfig costs,
      boolean oneMinuteCovered) {
    List<FoldResult> results = new ArrayList<>(folds.size());
    for (Fold fold : folds) {
      Metrics train =
          metricsFor(
              definition,
              exchange,
              tradingsymbol,
              slice(primary1m, fold.trainFrom(), fold.trainTo()),
              sliceContexts(contexts, fold.trainFrom(), fold.trainTo()),
              initialEquity,
              costs,
              oneMinuteCovered);
      ReplayResult oosResult =
          replayEngine.replay(
              definition,
              exchange,
              tradingsymbol,
              slice(primary1m, fold.testFrom(), fold.testTo()),
              sliceContexts(contexts, fold.testFrom(), fold.testTo()),
              initialEquity,
              costs,
              oneMinuteCovered);
      Metrics oos =
          metrics.compute(
              oosResult.trades(),
              oosResult.equityCurve(),
              oosResult.initialEquity(),
              oosResult.finalEquity(),
              definition.primaryTimeframe(),
              oosResult.totalBars(),
              oosResult.barsInPosition());
      int oosTradeCount = closedTrades(oosResult);
      results.add(new FoldResult(fold, train, oos, oosTradeCount));
    }
    return results;
  }

  private Metrics metricsFor(
      StrategyDefinition definition,
      String exchange,
      String tradingsymbol,
      List<EngineCandle> primary,
      Map<SeriesKey, List<EngineCandle>> contexts,
      BigDecimal initialEquity,
      CostConfig costs,
      boolean oneMinuteCovered) {
    ReplayResult result =
        replayEngine.replay(
            definition, exchange, tradingsymbol, primary, contexts, initialEquity, costs,
            oneMinuteCovered);
    return metrics.compute(
        result.trades(),
        result.equityCurve(),
        result.initialEquity(),
        result.finalEquity(),
        definition.primaryTimeframe(),
        result.totalBars(),
        result.barsInPosition());
  }

  private static int closedTrades(ReplayResult result) {
    int n = 0;
    for (var t : result.trades()) {
      if (t.exitTs() != null) {
        n++;
      }
    }
    return n;
  }

  /** Slices a bucket-ordered candle list to {@code [from, to)} by {@code bucketStart}. */
  static List<EngineCandle> slice(List<EngineCandle> candles, OffsetDateTime from, OffsetDateTime to) {
    List<EngineCandle> out = new ArrayList<>();
    for (EngineCandle c : candles) {
      OffsetDateTime ts = c.bucketStart();
      if (!ts.isBefore(from) && ts.isBefore(to)) {
        out.add(c);
      }
    }
    return out;
  }

  private static Map<SeriesKey, List<EngineCandle>> sliceContexts(
      Map<SeriesKey, List<EngineCandle>> contexts, OffsetDateTime from, OffsetDateTime to) {
    Map<SeriesKey, List<EngineCandle>> sliced = new LinkedHashMap<>();
    contexts.forEach((key, series) -> sliced.put(key, slice(series, from, to)));
    return sliced;
  }
}
