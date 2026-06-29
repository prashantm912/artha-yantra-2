package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * §3.12 Trend-Change TRENDLINE-break primitive (doc §3.12 "multi-timeframe S&amp;R + chart-pattern
 * vocabulary"; the Day-6/Day-10 deck "trendline support" entries). Distinct from the single-pivot
 * {@link MarketStructure} structure break: a trendline is the SLOPED line through the two most recent
 * same-side {@link MarketStructure} fractal pivots, and the break fires when the deploy bar's CLOSE
 * crosses that PROJECTED line in the side's reversal direction —
 *
 * <ul>
 *   <li>CE (up-reversal): two DESCENDING swing-highs = a falling resistance line; a close ABOVE the
 *       projected line breaks the downtrend.
 *   <li>PE (down-reversal): two ASCENDING swing-lows = a rising support line; a close BELOW the
 *       projected line breaks the uptrend.
 * </ul>
 *
 * The projected line level at the deploy bar is handed back as the trendline SL anchor. Scan is bounded
 * to {@code index}'s IST session (a reversal is read against the day's own structure, mirroring {@link
 * MarketStructure}/{@link GapState}). Pure + deterministic over fixed closed bars, so a replay
 * recomputes it byte-identically (§12.9). Default-OFF: consumed only behind a {@code trendline-break}
 * tag, so no shipped strategy's behaviour changes until the owner arms it.
 */
public final class Trendline {

  private Trendline() {}

  /** Whether a trendline break printed in the side's direction and, if so, the projected line level. */
  public record Break(boolean broke, BigDecimal lineLevel) {}

  private static final Break NONE = new Break(false, null);

  /**
   * Detect a trendline break in the side's reversal direction at the just-closed deploy bar.
   *
   * @param future the index-future series the scalper evaluates on
   * @param index the just-closed deploy bar index
   * @param side CE (break a falling resistance up) or PE (break a rising support down)
   */
  public static Break detect(EngineSeries future, int index, OptionType side) {
    if (future == null || index < 1 || index >= future.size()) {
      return NONE;
    }
    int sessionStart = future.sessionStart(index);
    boolean ce = side == OptionType.CE;
    // collect the two most recent same-side fractal pivots within the session: later (p2) then earlier
    // (p1). A fractal needs a bar on each side, and the deploy bar is the break candle, so the latest
    // candidate pivot is index-2.
    int p2 = -1;
    int p1 = -1;
    for (int p = index - 2; p > sessionStart; p--) {
      boolean isPivot = ce ? MarketStructure.isSwingHigh(future, p) : MarketStructure.isSwingLow(future, p);
      if (!isPivot) {
        continue;
      }
      if (p2 < 0) {
        p2 = p;
      } else {
        p1 = p;
        break;
      }
    }
    if (p1 < 0) {
      return NONE; // need two pivots to draw a line
    }
    BigDecimal v1 = ce ? future.candle(p1).high() : future.candle(p1).low();
    BigDecimal v2 = ce ? future.candle(p2).high() : future.candle(p2).low();
    // require the correct slope: CE = DESCENDING highs (a falling resistance to break up); PE = ASCENDING
    // lows (a rising support to break down). A flat/wrong-slope pair is not a tradeable trendline.
    if (ce ? v2.compareTo(v1) >= 0 : v2.compareTo(v1) <= 0) {
      return NONE;
    }
    // project the p1->p2 line to the deploy bar: slope/bar = (v2-v1)/(p2-p1); level = v2 + slope*(index-p2).
    BigDecimal slope = v2.subtract(v1).divide(BigDecimal.valueOf(p2 - p1), 6, RoundingMode.HALF_UP);
    BigDecimal level = v2.add(slope.multiply(BigDecimal.valueOf(index - p2)));
    BigDecimal close = future.candle(index).close();
    boolean broke = ce ? close.compareTo(level) > 0 : close.compareTo(level) < 0;
    return broke ? new Break(true, level) : NONE;
  }
}
