package in.arthayantra.backtest.replay.folds;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.backtest.replay.CostConfig;
import in.arthayantra.backtest.replay.MetricsCalculator;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.ReplayEngine;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay;
import in.arthayantra.backtest.replay.options.PremiumProvenance;
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
 * <p><b>Warmup (AY-SL-03).</b> A fold's own window is far too short to warm a long-lookback gate (a
 * 125-bar OOS fold can never warm Minervini's 252-bar {@code WEEK52_HIGH}), so each window is replayed
 * from the run's DATA START: every bar before the window ({@code warmupPrefix}) plus contexts to the
 * same depth are fed to the signal runner with emissions SUPPRESSED, and only the in-window bars drive
 * trades/equity/metrics. Indicators reach the window warm; the fold's trade list, equity curve and
 * metrics reflect ONLY in-window activity, and no warmup position leaks across the boundary.
 *
 * <p>Single-threaded and deterministic: folds are evaluated in chronological order and the context
 * map is iterated in insertion order (no wall-clock, no randomness).
 */
@Component
public class FoldEvaluator {

  private final ReplayEngine replayEngine;
  private final OptionsPremiumReplay optionsPremiumReplay;
  private final PremiumProvenance premiumProvenance;
  private final MetricsCalculator metrics;

  /** Wires the candle + premium replay engines, the options routing predicate, and metrics. */
  public FoldEvaluator(
      ReplayEngine replayEngine,
      OptionsPremiumReplay optionsPremiumReplay,
      PremiumProvenance premiumProvenance,
      MetricsCalculator metrics) {
    this.replayEngine = replayEngine;
    this.optionsPremiumReplay = optionsPremiumReplay;
    this.premiumProvenance = premiumProvenance;
    this.metrics = metrics;
  }

  /**
   * Evaluates every fold; returns per-fold train/OOS metric sets in fold order. An options strategy
   * routes through {@link OptionsPremiumReplay} (premium-as-primary) exactly like the full-window
   * {@code BacktestRunner} path — without this an OOS fold replays the underlying candles, produces
   * ~0 option trades, and is excluded under {@code min_trades}, so no OOS objective is ever computed.
   */
  public List<FoldResult> evaluate(
      JsonNode config,
      List<Fold> folds,
      StrategyDefinition definition,
      String exchange,
      String tradingsymbol,
      List<EngineCandle> primary1m,
      List<EngineCandle> strikeRef1m,
      Map<SeriesKey, List<EngineCandle>> contexts,
      BigDecimal initialEquity,
      CostConfig costs,
      boolean oneMinuteCovered) {
    boolean options = premiumProvenance.isOptionsStrategy(config);
    List<FoldResult> results = new ArrayList<>(folds.size());
    for (Fold fold : folds) {
      // AY-SL-03: each window replays from the run's data start up to the window with emissions
      // suppressed (the `warmupPrefix` = every bar BEFORE the window start; contexts carry the same
      // warmup depth), so a long-lookback gate warms yet contributes zero pre-window signals/trades.
      ReplayResult trainResult =
          replay(
              options, config, definition, exchange, tradingsymbol,
              slice(primary1m, fold.trainFrom(), fold.trainTo()),
              slice(strikeRef1m, fold.trainFrom(), fold.trainTo()),
              warmupContexts(contexts, fold.trainTo()),
              before(primary1m, fold.trainFrom()),
              initialEquity, costs, oneMinuteCovered);
      Metrics train = metricsFor(trainResult, definition);
      ReplayResult oosResult =
          replay(
              options, config, definition, exchange, tradingsymbol,
              slice(primary1m, fold.testFrom(), fold.testTo()),
              slice(strikeRef1m, fold.testFrom(), fold.testTo()),
              warmupContexts(contexts, fold.testTo()),
              before(primary1m, fold.testFrom()),
              initialEquity, costs, oneMinuteCovered);
      Metrics oos = metricsFor(oosResult, definition);
      int oosTradeCount = closedTrades(oosResult);
      results.add(
          new FoldResult(
              fold, train, oos, oosTradeCount, oosResult.trades(), oosResult.initialEquity()));
    }
    return results;
  }

  /** Routes one slice replay to the premium-as-primary engine for options, else the candle engine. */
  private ReplayResult replay(
      boolean options,
      JsonNode config,
      StrategyDefinition definition,
      String exchange,
      String tradingsymbol,
      List<EngineCandle> primary,
      List<EngineCandle> strikeRef,
      Map<SeriesKey, List<EngineCandle>> contexts,
      List<EngineCandle> warmupPrefix,
      BigDecimal initialEquity,
      CostConfig costs,
      boolean oneMinuteCovered) {
    return options
        // The cost-stress slippageMultiplier rides on `costs` (candle path threads it directly); the
        // options path builds its own per-leg CostConfig, so pass the multiplier through explicitly so
        // a stressed trial's OOS option folds degrade in lockstep with its full-window run (EVO §3.2.5).
        ? optionsPremiumReplay.replay(
            definition, config, exchange, tradingsymbol, primary, strikeRef, contexts, initialEquity,
            new OptionsPremiumReplay.GateCoverage(), costs.slippageMultiplier(), warmupPrefix)
        : replayEngine.replay(
            definition, exchange, tradingsymbol, primary, contexts, initialEquity, costs,
            oneMinuteCovered, null, null, warmupPrefix);
  }

  private Metrics metricsFor(ReplayResult result, StrategyDefinition definition) {
    return metrics.compute(
        result.trades(),
        result.equityCurve(),
        result.initialEquity(),
        result.finalEquity(),
        definition.primaryTimeframe(),
        // AY-SL-01 cadence: fold slices replay through the same engines, whose equity curves are
        // 1m-spaced regardless of primary timeframe — annualize at the curve cadence (see
        // BacktestRunner's matching call).
        "1m",
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

  /**
   * The PAST-ONLY warmup prefix (AY-SL-03): every bar strictly BEFORE {@code boundary} by {@code
   * bucketStart} — i.e. the run's data start up to the window start. Fed to the signal runner ahead of
   * the window with emissions suppressed, it warms indicator state without contributing any in-window
   * signal. Empty when the window starts at the data start (an anchored/fold-0 train window).
   */
  static List<EngineCandle> before(List<EngineCandle> candles, OffsetDateTime boundary) {
    List<EngineCandle> out = new ArrayList<>();
    for (EngineCandle c : candles) {
      if (c.bucketStart().isBefore(boundary)) {
        out.add(c);
      }
    }
    return out;
  }

  /**
   * Every context series carried to warmup depth: all bars before {@code to} (warmup prefix + the
   * window). The runner only reveals a context bar once its bucket end passes the current primary
   * bar's close, so bars at/after {@code to} are never advanced (harmless), and pre-window bars warm
   * a long-lookback context indicator exactly as a full-window run would.
   */
  private static Map<SeriesKey, List<EngineCandle>> warmupContexts(
      Map<SeriesKey, List<EngineCandle>> contexts, OffsetDateTime to) {
    Map<SeriesKey, List<EngineCandle>> warmed = new LinkedHashMap<>();
    contexts.forEach((key, series) -> warmed.put(key, before(series, to)));
    return warmed;
  }
}
