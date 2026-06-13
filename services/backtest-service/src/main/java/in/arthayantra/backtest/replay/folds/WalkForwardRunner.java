package in.arthayantra.backtest.replay.folds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.replay.CostConfig;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Orchestrates fold-mode evaluation for a TRIAL-context / stress run (Phase 31, guards 1–3 & 7 of
 * §D.4): build folds, evaluate train+OOS per fold, aggregate the OOS objective, and serialize the
 * fold-specific columns. <b>Fold-mode is opt-in</b> — only {@link in.arthayantra.backtest.replay
 * .BacktestRunner} triggers it (on {@code request.foldContext == true}); a plain {@code
 * /backtests/run} job never enters here and its fold columns stay NULL.
 *
 * <p><b>Trigger split.</b> When the config carries {@code backtest.optimize.walk_forward}, rolling
 * or anchored folds run (guard 2). With no {@code walk_forward}, the IMPLICIT 70/30 chronological
 * split applies (guard 1): the first 70% of the window's trading days train, the last 30% are the
 * single OOS test fold whose metrics are the reported headline objective.
 *
 * <p>Deterministic: trading-day math via {@link MarketCalendar}, BigDecimal aggregation, folds in
 * chronological order.
 */
@Component
public class WalkForwardRunner {

  private static final int DEFAULT_MIN_TRADES = 30;
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private final WalkForwardExpander expander;
  private final FoldEvaluator evaluator;
  private final ObjectiveAggregator aggregator;
  private final ObjectMapper objectMapper;
  private final MarketCalendar calendar = MarketCalendar.nse();

  /** Wires the fold collaborators. */
  public WalkForwardRunner(
      WalkForwardExpander expander,
      FoldEvaluator evaluator,
      ObjectiveAggregator aggregator,
      ObjectMapper objectMapper) {
    this.expander = expander;
    this.evaluator = evaluator;
    this.aggregator = aggregator;
    this.objectMapper = objectMapper;
  }

  /** True when {@code config.backtest.optimize.walk_forward} is present. */
  public static boolean hasWalkForward(JsonNode config) {
    return config != null && config.path("backtest").path("optimize").has("walk_forward");
  }

  /**
   * Builds + evaluates the folds and serializes the fold columns.
   *
   * @param config the raw resolved config JSONB (carries {@code walk_forward}/{@code constraints}/
   *     {@code objective} — the compiled definition does not)
   * @return the fold persistence bundle (never null; its {@code foldMetrics} array may be empty when
   *     every fold failed {@code min_trades})
   */
  public FoldPersistence run(
      JsonNode config,
      StrategyDefinition definition,
      String exchange,
      String tradingsymbol,
      List<EngineCandle> primary1m,
      Map<SeriesKey, List<EngineCandle>> contexts,
      BigDecimal initialEquity,
      CostConfig costs,
      boolean oneMinuteCovered,
      OffsetDateTime from,
      OffsetDateTime to) {
    JsonNode optimize = config.path("backtest").path("optimize");
    int minTrades = optimize.path("constraints").path("min_trades").asInt(DEFAULT_MIN_TRADES);
    String objectiveMetric = optimize.path("objective").path("metric").asText("sharpe");

    List<Fold> folds =
        hasWalkForward(config)
            ? walkForwardFolds(optimize.get("walk_forward"), from, to)
            : List.of(seventyThirtyFold(from, to));

    List<FoldResult> results =
        evaluator.evaluate(
            folds, definition, exchange, tradingsymbol, primary1m, contexts, initialEquity, costs,
            oneMinuteCovered);

    FoldAggregate aggregate = aggregator.aggregate(results, objectiveMetric, minTrades);
    ArrayNode foldMetrics = serialize(aggregate.validFolds());
    return new FoldPersistence(
        foldMetrics,
        aggregate.oosFoldMean(),
        aggregate.oosFoldStd(),
        aggregate.sharpeDegradation(),
        aggregate.excludedFoldCount());
  }

  private List<Fold> walkForwardFolds(JsonNode wf, OffsetDateTime from, OffsetDateTime to) {
    int trainDays = wf.path("train_days").asInt();
    int testDays = wf.path("test_days").asInt();
    int stepDays = wf.path("step_days").asInt(testDays); // default step == test (non-overlapping OOS)
    boolean anchored = wf.path("anchored").asBoolean(false);
    return expander.expand(from, to, trainDays, testDays, stepDays, anchored, calendar);
  }

  /**
   * The implicit 70/30 chronological split as a single fold: train on the first 70% of the window's
   * trading days, test on the remaining 30% (guard 1). At least one trading day lands in each side.
   *
   * <p>The OOS test window's exclusive end is the run's own requested {@code to} (date-aligned),
   * matching the {@link WalkForwardExpander} convention — NOT {@code nextTradingDay(lastDay)}, which
   * would push the persisted {@code test.to} past the user-requested window end. The candle slice is
   * unchanged either way (no bars exist past the last in-window trading day), but the persisted /
   * served boundary now never overshoots {@code to}.
   */
  private Fold seventyThirtyFold(OffsetDateTime from, OffsetDateTime to) {
    List<LocalDate> days = tradingDays(from.toLocalDate(), to.toLocalDate());
    if (days.size() < 2) {
      throw new IllegalArgumentException(
          "70/30 split needs at least 2 trading days in the window");
    }
    int trainCount = Math.max(1, Math.min(days.size() - 1, (int) Math.floor(days.size() * 0.7)));
    LocalDate splitDay = days.get(trainCount); // first OOS day
    LocalDate endExclusive = to.toLocalDate(); // [from, to) — the run's own requested window end
    return new Fold(
        0,
        startOfDay(days.get(0)),
        startOfDay(splitDay),
        startOfDay(splitDay),
        startOfDay(endExclusive));
  }

  /** Trading days with start-of-day in {@code [from, to)} — the day of {@code to} is excluded. */
  private List<LocalDate> tradingDays(LocalDate from, LocalDate toExclusive) {
    List<LocalDate> out = new ArrayList<>();
    LocalDate d = from;
    while (d.isBefore(toExclusive)) {
      if (calendar.isTradingDay(d)) {
        out.add(d);
      }
      d = d.plusDays(1);
    }
    return out;
  }

  private ArrayNode serialize(List<FoldResult> validFolds) {
    ArrayNode array = objectMapper.createArrayNode();
    for (FoldResult fr : validFolds) {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("fold", fr.fold().index());
      node.set("train", window(fr.fold().trainFrom(), fr.fold().trainTo()));
      node.set("test", window(fr.fold().testFrom(), fr.fold().testTo()));
      node.set("trainMetrics", fr.trainMetrics().full());
      node.set("oosMetrics", fr.oosMetrics().full());
      node.putNull("regimeMix"); // populated in Phase 32 (guard 6)
      array.add(node);
    }
    return array;
  }

  private ObjectNode window(OffsetDateTime fromTs, OffsetDateTime toTs) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("from", fromTs.toString());
    node.put("to", toTs.toString());
    return node;
  }

  private static OffsetDateTime startOfDay(LocalDate day) {
    return day.atStartOfDay().atOffset(IST);
  }

  // CD-12 (Phase 31 deliverable bullet 9, §D.4 / §D.7): the TRIAL-context per-fold OOS streaming
  // onto the optimizations.results stream and cancel-at-fold-boundary belong to the OPTIMIZER's
  // consumer side. No optimizer exists yet, so there is nothing to stream to — wiring it here would
  // be a fake emit. Deferred to Phase 33/34, which will consume aggregate.headlineObjective() (the
  // mean OOS objective) and each FoldResult's OOS value as the fold-fed pruner's intermediate
  // signal. Intentionally NOT stubbed.
}
