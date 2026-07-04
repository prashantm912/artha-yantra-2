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

  /**
   * One registry entry. {@code requiresContext} = the indicator reads a second (context) series via
   * the A7 {@code instrument} override. {@code seeded} = that context is injected at RUNTIME by a
   * producer (Phase-9 Minervini geometry: {@code VCP_PIVOT}/{@code CHEAT_PIVOT}/{@code THRUST}) rather
   * than resolved from the instruments master — so the publish-time "context instrument must exist"
   * gate does NOT apply (its {@code instrument} is a sentinel key, not a tradeable instrument).
   */
  public record Definition(
      String id, String description, Set<String> params, boolean requiresContext, boolean seeded) {

    /** A non-seeded entry: any context override names a REAL market instrument (e.g. INDIA VIX). */
    public Definition(String id, String description, Set<String> params, boolean requiresContext) {
      this(id, description, params, requiresContext, false);
    }
  }

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
            "SUPERTREND_LINE", "Supertrend trailing-stop price level (pairs with SUPERTREND direction)",
            Set.of("period", "multiplier"), false),
        (s, c, p) ->
            Ta4jIndicators.supertrendLine(
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
    register(
        new Definition(
            "VWMA", "Volume-weighted moving average of close", Set.of("period"), false),
        (s, c, p) -> SessionIndicators.vwma(s, requirePositive(p, "period", 20)));
    register(
        new Definition(
            "PSAR", "Parabolic SAR stop-and-reverse level", Set.of("step", "max"), false),
        (s, c, p) ->
            Ta4jIndicators.psar(
                s,
                p.decimalValue("step", new BigDecimal("0.02")),
                p.decimalValue("max", new BigDecimal("0.2"))));

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
    register(
        new Definition(
            "BASIS_PCT",
            "Spot-minus-futures basis, percent of futures (context: front-month futures)",
            Set.of(), true),
        (s, c, p) -> SessionIndicators.basisPct(s, c));
    register(
        new Definition(
            "ADVANCE_DECLINE_RATIO",
            "Intraday advance/decline breadth (the context-series close)",
            Set.of(), true),
        (s, c, p) -> SessionIndicators.contextLevel(s, c));
    // Minervini Track-B (MV-6.1): the VCP pivot level (Phase-5 geometry) seeded into the engine as a
    // context series — the buy trigger a swing setup breaks out above. Same context-close mechanism
    // as VIX_LEVEL; NEUTRAL/absent in replay unless a pivot series is seeded (the gate then fails safe).
    register(
        new Definition(
            "VCP_PIVOT", "VCP pivot / line of least resistance (context-seeded)", Set.of(), true, true),
        (s, c, p) -> SessionIndicators.contextLevel(s, c));
    // Minervini MV-6.6 (primary-base): trailing high/low over `period` PRIOR bars (excludes current)
    // — the resistance/support a new-high/low breakout must clear. period=252 ≈ 52-week on a 1d primary.
    register(
        new Definition("WEEK52_HIGH", "Highest high over the prior `period` bars", Set.of("period"), false),
        (s, c, p) -> SessionIndicators.week52High(s, requirePositive(p, "period", 252)));
    register(
        new Definition("WEEK52_LOW", "Lowest low over the prior `period` bars", Set.of("period"), false),
        (s, c, p) -> SessionIndicators.week52Low(s, requirePositive(p, "period", 252)));
    // Minervini MV-6.4/6.5 — context-seeded levels/flags from Phase-5 geometry (like VCP_PIVOT):
    // CHEAT_PIVOT = the cheat-area pause high (§6.3, an earlier/lower entry than the final pivot);
    // THRUST = 1.0 when the base sits atop a prior +100%/<8wk thrust (§6.5 power-play precondition),
    // else 0.0. Both NEUTRAL/absent in replay unless seeded → the gate fails safe.
    register(
        new Definition("CHEAT_PIVOT", "Cheat-area pause high (context-seeded)", Set.of(), true, true),
        (s, c, p) -> SessionIndicators.contextLevel(s, c));
    register(
        new Definition(
            "THRUST", "Prior-thrust flag: 1.0 when a power-play thrust precedes the base",
            Set.of(), true, true),
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

  /**
   * True when the indicator's context series is injected at runtime by a producer (Minervini
   * geometry seeding) rather than resolved from the instruments master — so its {@code instrument}
   * override is a sentinel key and the publish-time "context instrument must exist" gate is skipped.
   */
  public static boolean isSeeded(String name) {
    Definition d = DEFINITIONS.get(name);
    return d != null && d.seeded();
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
