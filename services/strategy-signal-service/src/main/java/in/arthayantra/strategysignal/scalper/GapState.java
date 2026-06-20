package in.arthayantra.strategysignal.scalper;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;

/**
 * Siva #4 Gap-Theory primitive (master plan section 12, doc section 3.4). A pure detector of the most recent
 * significant 3-min gap in the current IST session plus a {@code filled} flag - the geometry the
 * chart-only YAML gate grammar cannot express. Consumed by {@link GapTheoryGate}.
 *
 * <p>A gap is the void between the prior 3-min candle's close (the standard definition; the doc's
 * high/low "high-probability variant" is a stricter superset and is not used in v1) and the current
 * candle's open. It is <b>significant</b> when its size is above {@code 3 points} (= 60 ticks on
 * Bank Nifty; section 3.4 L596). A gap that the creating candle's own wick already retraced into is treated
 * as filled-on-creation and therefore not an actionable open gap (section 3.4 L597).
 *
 * <p>The <b>origin</b> is the prior close (the level price gapped away from). For a bullish gap-up
 * the gap fills when a later bar's low prints at/below the origin; for a bearish gap-down when a
 * later bar's high prints at/above it (the section 3.4 worked example: 4.30 pt gap, filled when the low
 * reached the prior high/close level). Pure + deterministic over fixed closed bars, so a replay
 * recomputes it byte-identically (section 12.9).
 */
public final class GapState {

  private GapState() {}

  /** The most recent significant gap in the session, its direction/size/origin, and the fill flag. */
  public record Gap(
      boolean present, boolean bullish, BigDecimal sizePoints, BigDecimal origin, boolean filled) {}

  private static final Gap ABSENT = new Gap(false, false, null, null, false);
  /** section 3.4: significant gap floor - above 3 points (= 60 ticks on Bank Nifty). */
  private static final BigDecimal MIN_POINTS = new BigDecimal("3");

  /**
   * Detect the most recent significant, still-actionable gap at or before {@code index}, scanning
   * back to the start of {@code index}'s IST session.
   *
   * @param future the index-future 3-min series
   * @param index the just-closed bar index
   */
  public static Gap detect(EngineSeries future, int index) {
    if (future == null || index < 1 || index >= future.size()) {
      return ABSENT;
    }
    int sessionStart = future.sessionStart(index);
    // walk back from the latest gap candle to the session's first gap candle; take the most recent.
    for (int gapBar = index; gapBar > sessionStart; gapBar--) {
      EngineCandle prior = future.candle(gapBar - 1);
      EngineCandle gapCandle = future.candle(gapBar);
      BigDecimal origin = prior.close();
      BigDecimal jump = gapCandle.open().subtract(origin);
      BigDecimal size = jump.abs();
      if (size.compareTo(MIN_POINTS) <= 0) {
        continue; // insignificant - not a gap
      }
      boolean bullish = jump.signum() > 0;
      // section 3.4 L597: a gap the creating candle's own wick already retraced into is filled-on-creation.
      if (filledBy(gapCandle, origin, bullish)) {
        continue;
      }
      boolean filled = filledSince(future, gapBar, index, origin, bullish);
      return new Gap(true, bullish, size, origin, filled);
    }
    return ABSENT;
  }

  /**
   * True once any bar AFTER the gap candle (through {@code index}) has retraced to the origin. The
   * gap candle itself is excluded - {@code detect} has already rejected gaps it fills on creation.
   */
  private static boolean filledSince(
      EngineSeries future, int gapBar, int index, BigDecimal origin, boolean bullish) {
    for (int i = gapBar + 1; i <= index; i++) {
      if (filledBy(future.candle(i), origin, bullish)) {
        return true;
      }
    }
    return false;
  }

  /** Bullish gap fills when a bar low reaches at/below the origin; bearish when a high reaches it. */
  private static boolean filledBy(EngineCandle c, BigDecimal origin, boolean bullish) {
    return bullish
        ? c.low().compareTo(origin) <= 0
        : c.high().compareTo(origin) >= 0;
  }
}
