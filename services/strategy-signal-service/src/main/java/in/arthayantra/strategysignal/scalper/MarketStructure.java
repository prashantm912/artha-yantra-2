package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import java.math.BigDecimal;

/**
 * Siva #12 Trend-Change market-structure primitive (master plan section 12, doc section 3.12). The chart-only
 * YAML gate grammar cannot express a multi-bar swing-pivot break, so the namesake price-structure
 * trigger lives here as a pure detector consumed by {@link TrendChangeGate}.
 *
 * <p>A trend change is read off a swing-structure break: a confirmed swing-high being taken out to the
 * upside (up-reversal) or a confirmed swing-low taken out to the downside (down-reversal). v1 uses a
 * <b>3-bar fractal pivot</b> (the smallest swing the higher-high/lower-low language describes): a bar
 * is a swing-high when its high is strictly above the highs of the bar on each side, a swing-low when
 * its low is strictly below the lows on each side. The break fires when the deploy bar's CLOSE prints
 * beyond the MOST RECENT confirmed opposing pivot (close &gt; the recent swing-high for CE, close
 * &lt; the recent swing-low for PE). That broken pivot is handed back as the structural-stop anchor.
 *
 * <p>The scan is bounded to {@code index}'s IST session (a reversal is read against the day's own
 * structure; mirrors how {@link GapState}/{@link GapTheoryGate} bound to the session). Pure +
 * deterministic over fixed closed bars, so a replay recomputes it byte-identically (section 12.9).
 */
public final class MarketStructure {

  private MarketStructure() {}

  /** Whether a structure break printed in the side's direction and, if so, the broken pivot level. */
  public record Break(boolean broke, BigDecimal pivot) {}

  private static final Break NONE = new Break(false, null);

  /**
   * Detect a structure break in the side's reversal direction at the just-closed deploy bar.
   *
   * @param future the index-future 3-min series the scalper evaluates on
   * @param index the just-closed deploy bar index
   * @param side CE (up-reversal: clear a swing-high) or PE (down-reversal: take out a swing-low)
   */
  public static Break detect(EngineSeries future, int index, OptionType side) {
    if (future == null || index < 1 || index >= future.size()) {
      return NONE;
    }
    int sessionStart = future.sessionStart(index);
    BigDecimal close = future.candle(index).close();
    boolean ce = side == OptionType.CE;
    // walk back from the most recent confirmable pivot (a fractal needs a bar on each side, and the
    // deploy bar itself is the break candle, so the latest candidate is index-2) to the session start.
    for (int pivot = index - 2; pivot > sessionStart; pivot--) {
      if (ce && isSwingHigh(future, pivot)) {
        BigDecimal level = future.candle(pivot).high();
        return close.compareTo(level) > 0 ? new Break(true, level) : NONE;
      }
      if (!ce && isSwingLow(future, pivot)) {
        BigDecimal level = future.candle(pivot).low();
        return close.compareTo(level) < 0 ? new Break(true, level) : NONE;
      }
    }
    return NONE;
  }

  /** A 3-bar fractal swing-high: the high is strictly above the immediate neighbours' highs. */
  private static boolean isSwingHigh(EngineSeries future, int i) {
    EngineCandle prev = future.candle(i - 1);
    EngineCandle here = future.candle(i);
    EngineCandle next = future.candle(i + 1);
    return here.high().compareTo(prev.high()) > 0 && here.high().compareTo(next.high()) > 0;
  }

  /** A 3-bar fractal swing-low: the low is strictly below the immediate neighbours' lows. */
  private static boolean isSwingLow(EngineSeries future, int i) {
    EngineCandle prev = future.candle(i - 1);
    EngineCandle here = future.candle(i);
    EngineCandle next = future.candle(i + 1);
    return here.low().compareTo(prev.low()) < 0 && here.low().compareTo(next.low()) < 0;
  }
}
