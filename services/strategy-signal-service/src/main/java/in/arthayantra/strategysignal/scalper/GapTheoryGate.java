package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;

/**
 * Siva #4 Gap-Theory pre-gate (master plan section 12, doc section 3.4). On the 3-min Bank Nifty future a
 * significant gap fills ~90% of the time same-day; the automatable edge is to <b>wait for the gap to
 * fill, then trade WITH the prevailing trend</b> (section 3.4 L584/L605, Session-24 L622). So this gate is a
 * HARD pre-entry filter consulted in {@link ScalperConfluenceGate}: it BLOCKS while a significant gap
 * is still open and PASSES (with-trend) once it has filled, handing back the structural stop. When no
 * significant gap exists it is INERT - it passes with no stop, leaving the normal confluence to
 * decide (a gap is only present on a fraction of bars).
 *
 * <p><b>Structural stop:</b> the doc's preferred in-trend SL is the SuperTrend level (section 3.4 L619), but
 * the engine exposes SuperTrend only as a direction (+1/-1), not a price level, so v1 uses the
 * matrix-sanctioned alternative from the same line - "SL = the low of the candle BEFORE the gap
 * candle for longs / its high for shorts". That anchor is pure and bar-derivable (mirrors {@link
 * TwoCandleGate}); the SuperTrend-level SL is a deferred refinement.
 *
 * <p><b>Deferred:</b> the counter-trend "trade toward the gap" gap-fill scalp (SL = day-high/day-low,
 * section 3.4 L614/L639) is explicitly risky / scalping-only and is NOT automated in v1 - see the strategy
 * YAML description.
 *
 * <p>Pure + deterministic over fixed closed bars, so a replay recomputes it byte-identically (section 12.9).
 */
public final class GapTheoryGate {

  private GapTheoryGate() {}

  /** Whether the entry may proceed and, when it may on a filled gap, the pre-gap structural stop. */
  public record Verdict(boolean pass, BigDecimal stopLevel) {}

  private static final Verdict BLOCK = new Verdict(false, null);
  private static final Verdict INERT = new Verdict(true, null);

  /**
   * Evaluate the gap pre-gate at the just-closed bar.
   *
   * @param future the index-future 3-min series the scalper evaluates on
   * @param index the just-closed deploy bar index
   * @param side CE (the prevailing trend is up) or PE (down) - anchors the stop side
   * @param underlying the underlying name (carried for symmetry with the other gates)
   */
  public static Verdict evaluate(
      EngineSeries future, int index, OptionType side, String underlying) {
    if (future == null || index < 1 || index >= future.size()) {
      return BLOCK; // cannot establish a gap baseline -> no entry (degrade safely)
    }
    GapState.Gap gap = GapState.detect(future, index);
    if (!gap.present()) {
      return INERT; // no significant gap on this bar -> the gate never fires
    }
    if (!gap.filled()) {
      return BLOCK; // a significant gap is still open -> wait for the fill before entering
    }
    // filled: with-trend entry allowed; anchor the SL on the candle before the gap candle.
    EngineCandle preGap = preGapCandle(future, index, gap.origin());
    if (preGap == null) {
      return new Verdict(true, null);
    }
    BigDecimal stop = side == OptionType.CE ? preGap.low() : preGap.high();
    return new Verdict(true, stop);
  }

  /**
   * The candle BEFORE the gap candle - the gap candle is the one whose prior-close equals the gap
   * origin, so its predecessor (the prior candle itself) is the SL anchor. Scans the session for the
   * gap candle that {@link GapState} matched.
   */
  private static EngineCandle preGapCandle(EngineSeries future, int index, BigDecimal origin) {
    int sessionStart = future.sessionStart(index);
    for (int gapBar = index; gapBar > sessionStart; gapBar--) {
      if (future.candle(gapBar - 1).close().compareTo(origin) == 0) {
        return future.candle(gapBar - 1);
      }
    }
    return null;
  }
}
