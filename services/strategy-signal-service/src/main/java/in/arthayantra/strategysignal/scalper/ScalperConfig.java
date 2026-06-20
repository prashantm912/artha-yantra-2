package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The per-strategy scalper knobs (master plan §12.2/§12.4). The underlying + exchange are read from
 * the strategy YAML {@code universe.underlying}; the delta/premium/threshold/rate values are the
 * verified §0B constants keyed by underlying — held HERE, not in YAML (the same single-source rule as
 * {@link ScalperGates}), so a scalper strategy YAML declares only {@code mode: options_of_underlying}
 * + the underlying + the scalper indicator aliases. Tuning later rides DB rows, never the YAML.
 *
 * <p>Two per-strategy behaviours the chart-only YAML grammar cannot express are declared via tags
 * (the same hook the {@code scalper} detection uses): {@code two-candle-pattern} runs the {@link
 * TwoCandleGate} as a hard entry gate (and anchors the stop on the 1st candle); {@code
 * entry-candle-stop} anchors the stop on the entry (crossover) candle's extreme.
 */
public record ScalperConfig(
    String underlyingExchange,
    String underlying,
    int rollDays,
    StrikePicker.Params strikeParams,
    BigDecimal confluenceThreshold,
    boolean requireTwoCandle,
    StructuralStop structuralStop,
    boolean requireCallPutDeltaFilter) {

  /** Where the entry-time structural stop-loss is anchored (none = size off structure/VWAP only). */
  public enum StructuralStop {
    NONE,
    TWO_CANDLE_FIRST,
    ENTRY_CANDLE
  }

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
    StructuralStop stop =
        twoCandle
            ? StructuralStop.TWO_CANDLE_FIRST
            : tags.contains("entry-candle-stop") ? StructuralStop.ENTRY_CANDLE : StructuralStop.NONE;
    // #5 (T2.1): the oi-cross-filter tag makes the >=50% call-put dOI imbalance a HARD pre-gate.
    boolean callPutDeltaFilter = tags.contains("oi-cross-filter");
    return new ScalperConfig(
        exchange, underlying, rollDays, params, THRESHOLD, twoCandle, stop, callPutDeltaFilter);
  }
}
