package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * The per-strategy scalper knobs (master plan §12.2/§12.4). The underlying + exchange are read from
 * the strategy YAML {@code universe.underlying}; the delta/premium/threshold/rate values are the
 * verified §0B constants keyed by underlying — held HERE, not in YAML (the same single-source rule as
 * {@link ScalperGates}), so a scalper strategy YAML declares only {@code mode: options_of_underlying}
 * + the underlying + the scalper indicator aliases. Tuning later rides DB rows, never the YAML.
 *
 * <p>Per-strategy behaviours the chart-only YAML grammar cannot express are declared via tags (the
 * same hook the {@code scalper} detection uses): {@code two-candle-pattern} runs the {@link
 * TwoCandleGate} as a hard entry gate (and anchors the stop on the 1st candle); {@code
 * entry-candle-stop} anchors the stop on the entry (crossover) candle's extreme; {@code gap-theory}
 * runs the {@link GapTheoryGate} as a hard gap-fill pre-gate (and anchors the stop on the pre-gap
 * candle's extreme); {@code trend-change} runs the {@link TrendChangeGate} as a hard reversal pre-gate
 * (structure break + &gt;=50% OI shift + 2-candle confirm) and anchors the stop on the broken swing;
 * {@code open-high-low} runs the {@link OpenHighLowGate} as a hard FNO-structure pre-gate (front-future
 * OH/OL + the HIGH probability tier + the &lt;=50% spurt reject rules) and anchors the stop on the VWAP;
 * {@code opening-tick} (#9 Morning Trade) swaps the default time window for the opening-tick window
 * ({@link #OPENING_FROM}-{@link #OPENING_TO}), degrades the VWAP HARD gate before 10:30 IST, and
 * anchors the stop on the FIRST session candle's low (CE) / high (PE); {@code hero-zero} (#7) runs the
 * {@link HeroZeroGate} as a hard expiry-day end-of-day pre-gate (after 14:30, &gt;50% OI+price break +
 * short-covering) and anchors the stop on the OPPOSITE session extreme.
 */
public record ScalperConfig(
    String underlyingExchange,
    String underlying,
    int rollDays,
    StrikePicker.Params strikeParams,
    BigDecimal confluenceThreshold,
    boolean requireTwoCandle,
    StructuralStop structuralStop,
    boolean requireCallPutDeltaFilter,
    boolean requireGapFill,
    boolean requireTrendChange,
    boolean requireOpenHighLow,
    boolean openingTick,
    boolean requireHeroZero) {

  /** Where the entry-time structural stop-loss is anchored (none = size off structure/VWAP only). */
  public enum StructuralStop {
    NONE,
    TWO_CANDLE_FIRST,
    ENTRY_CANDLE,
    GAP_TREND,
    SWING_BREAK,
    VWAP,
    FIRST_CANDLE,
    OPPOSITE_EXTREME
  }

  // #9 (section 3.9) Morning Trade opening-tick window. Held as a constant (not read from the YAML
  // risk.session.window) because ScalperConfig.from receives only the `universe` node, not the `risk`
  // block — threading the window through would touch the shared from(...) signature + both call sites
  // for no behavioural gain. The YAML's risk.session.window (from "09:16") still drives the engine's
  // session gate; this constant 09:15-09:30 is the confluence-seam opening-tick bound (covers the
  // first 09:16-09:30 candles). Below 10:30 IST also degrades the VWAP hard gate (see the seam).
  static final LocalTime OPENING_FROM = LocalTime.of(9, 15);
  static final LocalTime OPENING_TO = LocalTime.of(9, 30);
  // #9: VWAP "not actionable before 10:30" — before this IST the opening-tick path drops VWAP from the
  // HARD validity gate (it stays a soft dot); at/after 10:30 the normal hard-VWAP behaviour resumes.
  static final LocalTime VWAP_ACTIONABLE_FROM = LocalTime.of(10, 30);

  // §0B delta band — uniform across indices (the slightly-ITM 0.6–0.7 Siva favours). The §4.14.7 /
  // §4.15.4 expiry-phase refinements (0.7–0.8 near a weekly expiry's end, ~0.5 on its first day;
  // buyer 0.9 / seller 0.4) are DEFERRED: §4.14.7 states the 0.6–0.7 baseline "remains the general
  // case", so this fixed band is a doc-sanctioned v1 simplification, not an oversight.
  private static final double DELTA_LO = 0.6;
  private static final double DELTA_HI = 0.7;
  // Black-76 risk-free rate; delta is near rate-insensitive for short-dated options, so a fixed
  // value is immaterial to strike selection.
  private static final double RATE = 0.065;
  // v1 confluence aggregate a valid signal must reach (≥ ~60% of the weighted dots).
  private static final BigDecimal THRESHOLD = new BigDecimal("0.6");
  // §0B premium bands (VERIFIED: NIFTY 100–250, BANKNIFTY 250–400). Unknown indices fall to NIFTY's
  // band conservatively (a narrower band rejects more strikes — never falsely admits one).
  private static final BigDecimal[] NIFTY_PREMIUM = {new BigDecimal("100"), new BigDecimal("250")};
  private static final Map<String, BigDecimal[]> PREMIUM =
      Map.of(
          "NIFTY 50", NIFTY_PREMIUM,
          "NIFTY BANK", new BigDecimal[] {new BigDecimal("250"), new BigDecimal("400")});

  /** Builds the config from a strategy's {@code universe} block + its tags (options_of_underlying). */
  public static ScalperConfig from(JsonNode universe, List<String> tags) {
    JsonNode u = universe.path("underlying");
    String underlying = u.path("tradingsymbol").asText();
    String exchange = u.path("exchange").asText("NSE");
    int rollDays = universe.path("futures").path("roll_days_before_expiry").asInt(2);
    BigDecimal[] premium = PREMIUM.getOrDefault(underlying, NIFTY_PREMIUM);
    StrikePicker.Params params =
        new StrikePicker.Params(DELTA_LO, DELTA_HI, premium[0], premium[1], RATE);
    boolean twoCandle = tags.contains("two-candle-pattern");
    // #4 (section 3.4): the gap-theory tag arms the gap-fill pre-gate (GapTheoryGate) + a pre-gap SL anchor.
    boolean gapFill = tags.contains("gap-theory");
    // #12 (section 3.12): the trend-change tag arms the TrendChangeGate + a broken-swing-pivot SL anchor.
    boolean trendChange = tags.contains("trend-change");
    // #2 (section 3.2): the open-high-low tag arms the OpenHighLowGate + a VWAP SL anchor.
    boolean openHighLow = tags.contains("open-high-low");
    // #9 (section 3.9): the opening-tick tag arms the Morning Trade path (opening-tick window + VWAP
    // degrade before 10:30) + a FIRST-CANDLE SL anchor (the 1st session candle low/high).
    boolean openingTick = tags.contains("opening-tick");
    // #7 (section 7): the hero-zero tag arms the HeroZeroGate (expiry-day end-of-day buy) + an
    // OPPOSITE-EXTREME SL anchor (the session extreme opposite the fire direction).
    boolean heroZero = tags.contains("hero-zero");
    StructuralStop stop =
        twoCandle
            ? StructuralStop.TWO_CANDLE_FIRST
            : gapFill
                ? StructuralStop.GAP_TREND
                : trendChange
                    ? StructuralStop.SWING_BREAK
                    : openHighLow
                        ? StructuralStop.VWAP
                        : openingTick
                            ? StructuralStop.FIRST_CANDLE
                            : heroZero
                                ? StructuralStop.OPPOSITE_EXTREME
                                : tags.contains("entry-candle-stop")
                                    ? StructuralStop.ENTRY_CANDLE
                                    : StructuralStop.NONE;
    // #5 (T2.1): the oi-cross-filter tag makes the >=50% call-put dOI imbalance a HARD pre-gate.
    boolean callPutDeltaFilter = tags.contains("oi-cross-filter");
    return new ScalperConfig(
        exchange, underlying, rollDays, params, THRESHOLD, twoCandle, stop, callPutDeltaFilter,
        gapFill, trendChange, openHighLow, openingTick, heroZero);
  }
}
