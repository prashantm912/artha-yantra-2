package in.arthayantra.strategyengine.indicators;

import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The engine registry (Phase 19 / Q2): registry id → factory + param vocabulary. Whether a
 * config's {@code name} exists HERE is the server-side check at save/publish (the JSON Schema's
 * enum stays advisory). {@code RS_VS_INDEX}/{@code VIX_LEVEL} require the A7 indicator-level
 * {@code instrument} context override; creating them without a context series is an error,
 * never a silent null-score.
 */
public final class IndicatorRegistry {

  /** One registry entry. */
  public record Definition(
      String id, String description, Set<String> params, boolean requiresContext) {}

  @FunctionalInterface
  private interface Factory {
    EngineIndicator create(EngineSeries series, EngineSeries context, Params params);
  }

  private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();
  private static final Map<String, Factory> FACTORIES = new LinkedHashMap<>();

  static {
    register(
        new Definition("EMA", "Exponential moving average of close", Set.of("period"), false),
        (s, c, p) -> Ta4jIndicators.ema(s, requirePositive(p, "period", 0)));
    register(
        new Definition("SMA", "Simple moving average of close", Set.of("period"), false),
        (s, c, p) -> Ta4jIndicators.sma(s, requirePositive(p, "period", 0)));
    register(
        new Definition("RSI", "Wilder relative strength index", Set.of("period"), false),
        (s, c, p) -> Ta4jIndicators.rsi(s, requirePositive(p, "period", 14)));
    register(
        new Definition("VWAP", "Cumulative session VWAP (IST session)", Set.of(), false),
        (s, c, p) -> SessionIndicators.sessionVwap(s));
    register(
        new Definition("ADX", "Average directional index", Set.of("period"), false),
        (s, c, p) -> Ta4jIndicators.adx(s, requirePositive(p, "period", 14)));
    register(
        new Definition(
            "MACD_HIST", "MACD histogram (macd - signal)", Set.of("fast", "slow", "signal"),
            false),
        (s, c, p) ->
            Ta4jIndicators.macdHistogram(
                s,
                requirePositive(p, "fast", 12),
                requirePositive(p, "slow", 26),
                requirePositive(p, "signal", 9)));
    register(
        new Definition(
            "SUPERTREND", "Supertrend direction: +1 up / -1 down",
            Set.of("period", "multiplier"), false),
        (s, c, p) ->
            Ta4jIndicators.supertrendDirection(
                s,
                requirePositive(p, "period", 10),
                p.decimalValue("multiplier", BigDecimal.valueOf(3))));
    register(
        new Definition(
            "VOLUME_RATIO", "Volume vs mean of prior lookback bars", Set.of("lookback"), false),
        (s, c, p) -> SessionIndicators.volumeRatio(s, requirePositive(p, "lookback", 20)));
    register(
        new Definition(
            "OI_CHANGE_PCT", "Open-interest change percent over lookback", Set.of("lookback"),
            false),
        (s, c, p) -> SessionIndicators.oiChangePct(s, requirePositive(p, "lookback", 1)));
    register(
        new Definition("ATR", "Wilder average true range", Set.of("period"), false),
        (s, c, p) -> Ta4jIndicators.atr(s, requirePositive(p, "period", 14)));

    // session-level family (A7 [FP-18]); warm-up: PREV_DAY_*/GAP_PCT need one prior session
    register(
        new Definition(
            "ORB_HIGH", "Opening-range high (first window_minutes of the IST session)",
            Set.of("window_minutes"), false),
        (s, c, p) -> SessionIndicators.openingRange(s, requirePositive(p, "window_minutes", 15), true));
    register(
        new Definition(
            "ORB_LOW", "Opening-range low (first window_minutes of the IST session)",
            Set.of("window_minutes"), false),
        (s, c, p) -> SessionIndicators.openingRange(s, requirePositive(p, "window_minutes", 15), false));
    register(
        new Definition("PREV_DAY_HIGH", "Previous IST session high", Set.of(), false),
        (s, c, p) -> SessionIndicators.previousDay(s, SessionIndicators.PrevDayField.HIGH));
    register(
        new Definition("PREV_DAY_LOW", "Previous IST session low", Set.of(), false),
        (s, c, p) -> SessionIndicators.previousDay(s, SessionIndicators.PrevDayField.LOW));
    register(
        new Definition("PREV_DAY_CLOSE", "Previous IST session close", Set.of(), false),
        (s, c, p) -> SessionIndicators.previousDay(s, SessionIndicators.PrevDayField.CLOSE));
    register(
        new Definition("DAY_HIGH", "Running session high", Set.of(), false),
        (s, c, p) -> SessionIndicators.dayExtreme(s, true));
    register(
        new Definition("DAY_LOW", "Running session low", Set.of(), false),
        (s, c, p) -> SessionIndicators.dayExtreme(s, false));
    register(
        new Definition(
            "GAP_PCT", "Session open vs previous close, percent", Set.of(), false),
        (s, c, p) -> SessionIndicators.gapPct(s));

    // context-mechanism family (A7 [FP-19]/[FP-20]/[FP-14]) — instrument override REQUIRED
    register(
        new Definition(
            "RS_VS_INDEX",
            "Own lookback return minus the context index's, percentage points",
            Set.of("lookback"), true),
        (s, c, p) -> SessionIndicators.rsVsIndex(s, c, requirePositive(p, "lookback", 63)));
    register(
        new Definition(
            "VIX_LEVEL", "Context-series close (the INDIA VIX level)", Set.of(), true),
        (s, c, p) -> SessionIndicators.contextLevel(s, c));
  }

  private IndicatorRegistry() {}

  private static void register(Definition definition, Factory factory) {
    DEFINITIONS.put(definition.id(), definition);
    FACTORIES.put(definition.id(), factory);
  }

  /** True when the registry knows the id (the Q2 save/publish existence check). */
  public static boolean exists(String name) {
    return DEFINITIONS.containsKey(name);
  }

  /** All known ids in registration order (advisory enum source, editor autocomplete). */
  public static Set<String> knownNames() {
    return DEFINITIONS.keySet();
  }

  /** Definition lookup. */
  public static Definition definition(String name) {
    Definition definition = DEFINITIONS.get(name);
    if (definition == null) {
      throw new IllegalArgumentException("unknown indicator '" + name + "'");
    }
    return definition;
  }

  /**
   * Creates an instance. {@code context} is the A7 override series — required for
   * context-mechanism indicators, ignored by the rest.
   */
  public static EngineIndicator create(
      String name, EngineSeries series, EngineSeries context, Map<String, Object> params) {
    Definition definition = definition(name);
    Params typed = new Params(name, params);
    typed.allowOnly(definition.params().toArray(new String[0]));
    if (definition.requiresContext() && context == null) {
      throw new IllegalArgumentException(
          name + " requires the indicator-level instrument context override (A7)");
    }
    return FACTORIES.get(name).create(series, context, typed);
  }

  private static int requirePositive(Params params, String name, int defaultValue) {
    int value = params.intValue(name, defaultValue);
    if (value <= 0) {
      throw new IllegalArgumentException("param '" + name + "' must be positive");
    }
    return value;
  }
}
