package in.arthayantra.marketdata.screener.manas;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure Manas Arora selection-filter gate math (§4.1 six-criteria + §1.2 universe + §4.3 liquidity +
 * §4.4 float). No Spring, no IO; the caller supplies the (config-tunable) thresholds. Manas is a
 * simplified, India-adapted Minervini — long-only cash equities — so this mirrors the sibling
 * {@link in.arthayantra.marketdata.screener.minervini.MinerviniGates} but encodes Manas's own rules.
 *
 * <p>The 6 §4.1 gates: (1) price ≥ ₹30; (2) 200-day SMA rising ≥ 3 months (~63 sessions); (3) 50-SMA
 * &gt; 200-SMA; (4) price &gt; 200-SMA; (5) price ≥ 100% above the 52-week low; (6) a new 52-week
 * high made at least once in the last ~4–6 months (~126 sessions). The §1.2 universe filter (within
 * 25% of the 52-week high) and the §4.3 liquidity veto (~20-day average volume &gt; ~5,000 shares)
 * are separate hard gates the caller applies alongside the six. Gate 4's soft "preferably &gt; 50-SMA"
 * is tracked via {@link #aboveSma50} but never blocks a pass.
 */
final class ManasGates {

  private ManasGates() {}

  /**
   * Weighted trailing relative strength (§4.10, IBD-style): {@code 0.4·r63 + 0.2·r126 + 0.2·r189 +
   * 0.2·r252} over the 3/6/9/12-month lookbacks. A null/zero trailing close contributes 0 (see
   * {@link #ret}). Same math as the sibling {@code MinerviniGates.weightedRs}; the caller ranks these
   * cross-sectionally into the 0..100 RS-rank percentile.
   */
  static BigDecimal weightedRs(
      BigDecimal close, BigDecimal c63, BigDecimal c126, BigDecimal c189, BigDecimal c252) {
    return ret(close, c63)
        .multiply(new BigDecimal("0.4"))
        .add(ret(close, c126).multiply(new BigDecimal("0.2")))
        .add(ret(close, c189).multiply(new BigDecimal("0.2")))
        .add(ret(close, c252).multiply(new BigDecimal("0.2")));
  }

  /** Simple return {@code (now−past)/past}; 0 when {@code past} is null/zero (insufficient history). */
  static BigDecimal ret(BigDecimal now, BigDecimal past) {
    if (now == null || past == null || past.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return now.subtract(past).divide(past, 6, RoundingMode.HALF_UP);
  }

  /**
   * The 6 §4.1 selection gates as a {@code boolean[6]}: (0) close ≥ minPrice; (1) 200-SMA rising
   * (sma200 &gt; sma200Ago); (2) sma50 &gt; sma200; (3) close &gt; sma200; (4) close ≥ 52w-low·(1 +
   * pctAboveLow/100); (5) a new 52-week high within the last {@code newHighLookback} sessions, i.e.
   * the running max-high over that window equals the full 52-week high ({@code recentHigh ≥ high52w}).
   */
  static boolean[] gates(
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma200,
      BigDecimal sma200Ago,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal recentHigh,
      BigDecimal minPrice,
      BigDecimal pctAbove52wLow) {
    BigDecimal lowGate = low52w == null ? null : low52w.multiply(onePlus(pctAbove52wLow));
    boolean[] g = new boolean[6];
    g[0] = close != null && close.compareTo(minPrice) >= 0;
    g[1] = gt(sma200, sma200Ago);
    g[2] = gt(sma50, sma200);
    g[3] = gt(close, sma200);
    g[4] = lowGate != null && close != null && close.compareTo(lowGate) >= 0;
    g[5] = recentHigh != null && high52w != null && recentHigh.compareTo(high52w) >= 0;
    return g;
  }

  /** How many of the 6 gates passed. */
  static int passed(boolean[] g) {
    int p = 0;
    for (boolean b : g) {
      if (b) {
        p++;
      }
    }
    return p;
  }

  /**
   * §1.2 universe gate: the name is within {@code within52wHigh}% of its 52-week high, i.e. {@code
   * close ≥ high52w·(1 − within52wHigh/100)}. Kept separate from the six because it defines the
   * universe rather than the selection filter.
   */
  static boolean withinHigh(BigDecimal close, BigDecimal high52w, BigDecimal within52wHigh) {
    BigDecimal gate = high52w == null ? null : high52w.multiply(oneMinus(within52wHigh));
    return gate != null && close != null && close.compareTo(gate) >= 0;
  }

  /**
   * §4.3 absolute low-volume veto: reject any name whose ~20-day average traded volume is ≤ {@code
   * minAvgVolume} shares (no fair entry/exit price). True = liquid enough to keep.
   */
  static boolean liquidVolume(BigDecimal avgVolume20, BigDecimal minAvgVolume) {
    return avgVolume20 != null && avgVolume20.compareTo(minAvgVolume) > 0;
  }

  /**
   * §4.3 depth check: a 10-week (~50-session) average volume ≥ {@code liquidityMultiple × intendedQty}.
   * The caller supplies the pre-multiplied {@code liquidityFloor} (intendedQty × multiple, in shares).
   * True = the 10-week average volume clears the floor.
   */
  static boolean liquidDepth(BigDecimal avgVolume50, BigDecimal liquidityFloor) {
    return avgVolume50 != null && avgVolume50.compareTo(liquidityFloor) >= 0;
  }

  /**
   * §4.1 gate-4 soft flag: price preferably above the 50-SMA too. Tracked but never blocks a pass —
   * being above the 200-SMA (gate 3) is sufficient.
   */
  static boolean aboveSma50(BigDecimal close, BigDecimal sma50) {
    return gt(close, sma50);
  }

  /**
   * §4.4 low-cap / float gate (DEFAULT-OFF, data-gated): true = the name PASSES the gate, i.e. it is
   * a low-cap (free-float market cap &lt; {@code maxFreeFloatMcapCr} cr AND, when known, free-float %
   * &lt; {@code maxFreeFloatPct}). Degrades gracefully: an UNKNOWN free-float cap passes (never a
   * silent drop when the fundamentals feed is absent) — the caller only applies this gate when it is
   * enabled.
   */
  static boolean lowCap(
      BigDecimal freeFloatMcapCr,
      BigDecimal freeFloatPct,
      BigDecimal maxFreeFloatMcapCr,
      BigDecimal maxFreeFloatPct) {
    if (freeFloatMcapCr == null) {
      return true; // unknown → keep (owner manual-check), never a silent drop
    }
    return freeFloatMcapCr.compareTo(maxFreeFloatMcapCr) < 0
        && (freeFloatPct == null || freeFloatPct.compareTo(maxFreeFloatPct) < 0);
  }

  static boolean gt(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) > 0;
  }

  private static BigDecimal onePlus(BigDecimal pct) {
    return BigDecimal.ONE.add(pct.movePointLeft(2));
  }

  private static BigDecimal oneMinus(BigDecimal pct) {
    return BigDecimal.ONE.subtract(pct.movePointLeft(2));
  }
}
