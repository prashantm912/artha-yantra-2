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
 * short-covering) and anchors the stop on the OPPOSITE session extreme; {@code straddle} (#11) routes the
 * direction-NEUTRAL §3.11 LONG-straddle path — the gate skips the CE/PE directional split and the
 * directional confluence and picks BOTH ATM legs ({@link StraddleLegPicker}) on the side-agnostic §0B
 * time + volume rails (combined-premium-vs-VWAP entry timing / low-IV gate are LIVE market-data series
 * the deterministic seam cannot recompute → deferred to live management; no structural stop on the
 * future — the SL is the combined-premium %).
 */
public record ScalperConfig(
    String underlyingExchange,
    String underlying,
    String signalIndex,
    String oiIndex,
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
    boolean requireHeroZero,
    boolean requireStraddle,
    boolean requireRsiS24Bands) {

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
  // §0B premium bands (VERIFIED: NIFTY 100–250, BANKNIFTY 250–400; SENSEX 300–800, the 2b grill-locked
  // band for the higher-priced SENSEX premium — live StrikePicker only, the backtest selector ignores
  // the band and picks nearest-strike-to-spot). Unknown indices fall to NIFTY's band conservatively (a
  // narrower band rejects more strikes — never falsely admits one).
  private static final BigDecimal[] NIFTY_PREMIUM = {new BigDecimal("100"), new BigDecimal("250")};
  private static final Map<String, BigDecimal[]> PREMIUM =
      Map.of(
          "NIFTY 50", NIFTY_PREMIUM,
          "NIFTY BANK", new BigDecimal[] {new BigDecimal("250"), new BigDecimal("400")},
          "SENSEX", new BigDecimal[] {new BigDecimal("300"), new BigDecimal("800")});

  /** Builds the config from a strategy's full config + its tags (options_of_underlying). */
  public static ScalperConfig from(JsonNode config, List<String> tags) {
    JsonNode universe = config.path("universe");
    JsonNode u = universe.path("underlying");
    String underlying = u.path("tradingsymbol").asText();
    String exchange = u.path("exchange").asText("NSE");
    // 2c three-way decoupling: the option-execution root (underlying) drives the strike chain; the
    // SIGNAL future's index (the volume floor + future-pattern gates) is the signal_underlying's index
    // (a *-FUT-CONT maps to its index — the live front future is resolved there); the OI-confluence
    // index honours backtest.oi_confluence_gate.index (the niftyoi/sensexoi A/B). All default to the
    // option-root, so a plain single-index scalper is unchanged (signalIndex == oiIndex == underlying).
    String signalIndex = signalIndex(universe).tradingsymbol();
    String oiIndexRaw =
        config.path("backtest").path("oi_confluence_gate").path("index").asText(underlying);
    String oiIndex = oiIndexRaw.isBlank() ? underlying : oiIndexRaw; // blank index ⇒ the option-root
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
    // #11 (section 3.11): the straddle tag routes the direction-NEUTRAL LONG-straddle path (both ATM
    // legs). It carries NO structural stop on the index future (the SL is the combined-premium %,
    // managed on the option legs) so it never sets a StructuralStop anchor below.
    boolean straddle = tags.contains("straddle");
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
    // W3 (S24 ratification U1/U2/U3): the rsi-s24-bands tag swaps the hard RSI rail to the 50-75 /
    // 40-50 / 40-25 band (default-OFF; absent => legacy 60-80 / 20-40 band, byte-identical).
    boolean rsiS24Bands = tags.contains("rsi-s24-bands");
    return new ScalperConfig(
        exchange, underlying, signalIndex, oiIndex, rollDays, params, THRESHOLD, twoCandle, stop,
        callPutDeltaFilter, gapFill, trendChange, openHighLow, openingTick, heroZero, straddle,
        rsiS24Bands);
  }

  /** A {@code (exchange, tradingsymbol)} index reference for live front-future resolution. */
  public record IndexRef(String exchange, String tradingsymbol) {}

  // A continuous-future signal symbol -> the INDEX whose live FRONT FUTURE the signal charts on (the
  // synthetic *-FUT-CONT series is roller-refreshed daily, not tick-streamed, so live resolves the
  // front future from the index). Extend when a new continuous future joins the roster.
  private static final Map<String, IndexRef> FUT_CONT_INDEX =
      Map.of(
          "NIFTY-FUT-CONT", new IndexRef("NSE", "NIFTY 50"),
          "SENSEX-FUT-CONT", new IndexRef("BSE", "SENSEX"));

  /**
   * The index whose live FRONT FUTURE the signal charts on: {@code universe.signal_underlying} mapped
   * to its index ({@link #FUT_CONT_INDEX}) when set, else the option-root {@code universe.underlying}.
   * The live {@code SignalEngine} subscribes the signal series here and {@link #from} keys the volume
   * floor off it — so a decoupled SENSEX scalper signals on the NIFTY future, not on SENSEX.
   */
  public static IndexRef signalIndex(JsonNode universe) {
    JsonNode sig = universe.path("signal_underlying");
    String sym = sig.path("tradingsymbol").asText("");
    if (sym.isEmpty()) {
      JsonNode u = universe.path("underlying");
      return new IndexRef(u.path("exchange").asText("NSE"), u.path("tradingsymbol").asText());
    }
    return FUT_CONT_INDEX.getOrDefault(sym, new IndexRef(sig.path("exchange").asText("NSE"), sym));
  }
}
