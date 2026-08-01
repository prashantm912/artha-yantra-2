package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.strategysignal.scalper.RailMarginSign.NEGATIVE_WHEN_BLOCKED;
import static in.arthayantra.strategysignal.scalper.RailMarginSign.POSITIVE_WHEN_BLOCKED;
import static in.arthayantra.strategysignal.scalper.RailMarginSign.UNSIGNED;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The expected blocked-margin sign of EVERY confluence-gate rail (G17 / T14). Each declared sign is
 * a restatement of the rail's OWN operator direction (the deciding comparison is cited per entry),
 * NOT an independent judgment — {@code RailMarginSignTableTest} re-derives every non-UNSIGNED entry
 * by probing the cited {@link ScalperGates} function on both sides of its threshold, and
 * source-scans {@code ScalperConfluenceGate} for completeness, so an entry that drifts from the
 * operator (or a rail added without one) fails the build. That test is what makes this a DERIVED
 * map rather than a hand-maintained list: the operator is the oracle, this table is its cache.
 *
 * <p>Only rails that record a NON-NULL threshold at the {@code Diag.fails} seam can carry a margin
 * at all; every other rail is {@link RailMarginSign#UNSIGNED} (nothing to check). The consumer is
 * {@link #contradicts} at the {@code RejectionWriter} persist seam — diagnostic only, never a gate
 * input. The check is deliberately STRICT-sign: a margin of exactly zero is never flagged, because
 * strict operators (breadth's {@code > 32}) legitimately block AT the threshold — the failure
 * direction is a false pass of the invariant, never a false contradiction (same doctrine as the
 * git-prune false-KEEP rule).
 */
public final class RailMarginSigns {

  private RailMarginSigns() {}

  private static final Map<String, RailMarginSign> SIGNS =
      Map.ofEntries(
          // -- floors: pass = operand at/above the recorded threshold; blocked margin < 0 --------
          Map.entry("volume-floor", NEGATIVE_WHEN_BLOCKED), // ScalperGates.volume: vol >= floor
          Map.entry("psar-durability", NEGATIVE_WHEN_BLOCKED), // psarDurable: distPct >= min
          Map.entry("rsi-recovery", NEGATIVE_WHEN_BLOCKED), // rsiRecovery: rsi >= recoveryLevel
          Map.entry("call-put-delta-filter", NEGATIVE_WHEN_BLOCKED), // |imbalance| >= floorPct
          Map.entry("breadth-gate", NEGATIVE_WHEN_BLOCKED), // breadth: count > 32 (strict)
          // confluence-composite: aggregate >= threshold; a decisive-leg block records margin NULL
          // (compositeMargin, the B5/#985 fix) so a non-null blocked margin is the scalar shortfall
          Map.entry("confluence-composite", NEGATIVE_WHEN_BLOCKED),
          // -- ceilings/inverted: pass = operand at/below the threshold; blocked margin > 0 ------
          // vwapDistance: frac <= maxFrac. The min clause (VWAP-pin) is OFF by default
          // (VWAP_DISTANCE_MIN_FRAC = 0) and armed by NO shipped YAML — the table test pins both,
          // and arming vwap_distance_min_frac anywhere must flip this entry to UNSIGNED (a
          // too-close block would then carry a negative margin against the recorded max).
          Map.entry("vwap-distance", POSITIVE_WHEN_BLOCKED),
          Map.entry("indicator-distance-veto", POSITIVE_WHEN_BLOCKED), // nearest <= maxPct
          Map.entry("iv-buyer-cap", POSITIVE_WHEN_BLOCKED), // ivBuyerCap: iv <= cap
          Map.entry("gap-size-side-gate", POSITIVE_WHEN_BLOCKED), // suppress when gap >= pts
          // -- side-dependent / conjunction operators: no single blocked sign exists -------------
          Map.entry("pct-price-move", UNSIGNED), // CE: move >= +floor, PE: move <= -floor
          Map.entry("fii-bias", UNSIGNED), // CE: longPct >= 50, PE: longPct <= 50
          Map.entry("oi-divergence-magnitude", UNSIGNED), // div AND price-impulse conjunction
          // -- rails recording NO scalar threshold at the seam: margin is always null ------------
          Map.entry("chain-unavailable", UNSIGNED),
          Map.entry("context-unavailable", UNSIGNED),
          Map.entry("option-side-constraint", UNSIGNED),
          Map.entry("strike-pick", UNSIGNED),
          Map.entry("time-window", UNSIGNED),
          Map.entry("time-of-day-preference", UNSIGNED),
          Map.entry("rising-volume", UNSIGNED),
          Map.entry("rsi-band", UNSIGNED),
          Map.entry("rsi-cooloff", UNSIGNED),
          Map.entry("rsi-5m-cap", UNSIGNED),
          Map.entry("rsi-daily-cap", UNSIGNED),
          Map.entry("morning-opening-formation", UNSIGNED),
          Map.entry("morning-eod-precondition", UNSIGNED),
          Map.entry("indicator-alignment-gate", UNSIGNED),
          Map.entry("futures-oi-gate", UNSIGNED),
          Map.entry("oi-cross-required", UNSIGNED),
          Map.entry("oi-slope-agree", UNSIGNED),
          Map.entry("flat-oi-stand-aside", UNSIGNED),
          Map.entry("divergence-vol-gate", UNSIGNED),
          Map.entry("directional-change-gate", UNSIGNED),
          Map.entry("gap-fill", UNSIGNED),
          Map.entry("trendline-break", UNSIGNED),
          Map.entry("two-candle", UNSIGNED),
          Map.entry("trend-change", UNSIGNED),
          Map.entry("open-high-low", UNSIGNED),
          Map.entry("hero-zero", UNSIGNED),
          Map.entry("supertrend-15m", UNSIGNED),
          Map.entry("directional-vix-gate", UNSIGNED),
          Map.entry("oi-interval-and-60m-trend", UNSIGNED),
          Map.entry("max-oi-sr-gate", UNSIGNED),
          Map.entry("volume-pump", UNSIGNED),
          Map.entry("fii-dii-gate", UNSIGNED),
          Map.entry("constituent-gate", UNSIGNED),
          Map.entry("basis-gate", UNSIGNED),
          Map.entry("overbought-defer", UNSIGNED),
          Map.entry("low-iv-straddle", UNSIGNED));

  /**
   * The declared sign; {@link RailMarginSign#UNSIGNED} for a rail this registry does not know.
   * Deliberately NEVER throws (unlike {@link RailPolicies#of}): the sole consumer sits on the
   * rejection persist path, and a diagnostic must never kill a diagnostic — the table test, not a
   * runtime throw, is what forces a new rail to declare its sign.
   */
  public static RailMarginSign of(String rail) {
    return SIGNS.getOrDefault(rail, UNSIGNED);
  }

  /**
   * True when a persisted first-block would contradict its own rail's operator: the blocked margin
   * sits STRICTLY on the passing side (floor rail with margin &gt; 0, ceiling rail with margin
   * &lt; 0). A null margin or an UNSIGNED rail is never a contradiction; zero is never flagged
   * (strict operators block at the threshold).
   */
  public static boolean contradicts(String rail, BigDecimal blockedMargin) {
    if (blockedMargin == null) {
      return false;
    }
    return switch (of(rail)) {
      case NEGATIVE_WHEN_BLOCKED -> blockedMargin.signum() > 0;
      case POSITIVE_WHEN_BLOCKED -> blockedMargin.signum() < 0;
      case UNSIGNED -> false;
    };
  }

  /** The full registry (the table test pins it to the rails found in the gate source). */
  static Map<String, RailMarginSign> all() {
    return SIGNS;
  }
}
