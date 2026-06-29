package in.arthayantra.marketdata.futures.screener;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * §3.3 Market-Movers screener CORE (deterministic; the Day-10 deck rules). Given each radar stock's
 * recent DAILY bars (oldest → newest, the last = today's forming/EOD bar), it grades the high-probability
 * LONG / SHORT candidates the deck defines and ranks them by move × liquidity:
 *
 * <ul>
 *   <li><b>LONG</b> = an 8-9-day HIGH break ({@code breakoutDays >= 8}) + Open=Low (the day's low not
 *       tested = strength) + a long-side OI quadrant (long build-up / short covering) + daily-RSI not
 *       already overbought ({@code < 75}).
 *   <li><b>SHORT</b> = the mirror: 8-9-day LOW + Open=High + a short-side quadrant (short build-up /
 *       long unwinding) + daily-RSI not oversold ({@code > 25}).
 * </ul>
 *
 * <p>Pure — the per-stock daily bars are fetched on-demand from Upstox by the wiring layer; this core
 * does the WHICH-stock selection only (the engine entry_rules do the WHEN on the picked stock future).
 */
public final class MarketMoversScreener {

  /** Deck §3.3: a stock already at an 8-9-day high/low — the breakout trigger. */
  public static final int MIN_BREAKOUT_DAYS = 8;
  /** Deck Day-10: daily RSI > 70-75 by the open ⇒ no fresh long; a dip ~67-68 is the buy window. */
  static final BigDecimal RSI_LONG_CAP = new BigDecimal("75");
  /** Deck Day-10: a falling stock at RSI ~25-30 is oversold — don't fresh-short below the floor. */
  static final BigDecimal RSI_SHORT_FLOOR = new BigDecimal("25");
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private MarketMoversScreener() {}

  /** One stock's recent daily bar (oldest → newest); {@code oi} may be null when Upstox omits it. */
  public record DailyBar(
      BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume, Long oi) {}

  /** One graded mover row (LTP/move/OI%/quadrant/breakout/OH-OL/daily-RSI + the long/short verdicts). */
  public record ScreenerRow(
      String symbol,
      BigDecimal ltp,
      BigDecimal pricePct,
      BigDecimal oiPct,
      OiInterpretation interpretation,
      int breakoutDays,
      boolean openHigh,
      boolean openLow,
      BigDecimal dailyRsi,
      long volume,
      boolean longCandidate,
      boolean shortCandidate) {}

  /** The ranked long / short candidate lists. */
  public record Screen(List<ScreenerRow> longCandidates, List<ScreenerRow> shortCandidates) {}

  /** Grade one stock from its recent daily bars; {@code null} when there are fewer than 2 bars. */
  public static ScreenerRow classify(String symbol, List<DailyBar> bars) {
    if (bars == null || bars.size() < 2) {
      return null;
    }
    DailyBar t = bars.get(bars.size() - 1);
    DailyBar p = bars.get(bars.size() - 2);
    BigDecimal priceDelta = t.close().subtract(p.close());
    BigDecimal pricePct = pct(priceDelta, p.close());
    long oiDelta = t.oi() == null || p.oi() == null ? 0 : t.oi() - p.oi();
    BigDecimal oiPct =
        p.oi() == null || p.oi() == 0 ? null : pct(BigDecimal.valueOf(oiDelta), BigDecimal.valueOf(p.oi()));
    OiInterpretation interp = OiInterpretation.classify(priceDelta, oiDelta);
    boolean openLow = t.open().compareTo(t.low()) == 0; // low not tested ⇒ strength (long cue)
    boolean openHigh = t.open().compareTo(t.high()) == 0; // high not tested ⇒ weakness (short cue)
    int bdHigh = NDayExtremes.breakoutDaysHigh(highs(bars));
    int bdLow = NDayExtremes.breakoutDaysLow(lows(bars));
    BigDecimal rsi = DailyRsi.wilder(closes(bars), 14);
    boolean longC =
        bdHigh >= MIN_BREAKOUT_DAYS
            && openLow
            && (interp == OiInterpretation.LONG_BUILDUP || interp == OiInterpretation.SHORT_COVERING)
            && (rsi == null || rsi.compareTo(RSI_LONG_CAP) < 0);
    boolean shortC =
        bdLow >= MIN_BREAKOUT_DAYS
            && openHigh
            && (interp == OiInterpretation.SHORT_BUILDUP || interp == OiInterpretation.LONG_UNWINDING)
            && (rsi == null || rsi.compareTo(RSI_SHORT_FLOOR) > 0);
    return new ScreenerRow(
        symbol, t.close(), pricePct, oiPct, interp, longC ? bdHigh : bdLow, openHigh, openLow, rsi,
        t.volume(), longC, shortC);
  }

  /** Grade the whole radar set and return the long / short candidates ranked by |move| × liquidity. */
  public static Screen screen(Map<String, List<DailyBar>> byStock) {
    List<ScreenerRow> longs = new ArrayList<>();
    List<ScreenerRow> shorts = new ArrayList<>();
    for (Map.Entry<String, List<DailyBar>> e : byStock.entrySet()) {
      ScreenerRow r = classify(e.getKey(), e.getValue());
      if (r == null) {
        continue;
      }
      if (r.longCandidate()) {
        longs.add(r);
      }
      if (r.shortCandidate()) {
        shorts.add(r);
      }
    }
    Comparator<ScreenerRow> byConviction = Comparator.comparing(MarketMoversScreener::conviction).reversed();
    longs.sort(byConviction);
    shorts.sort(byConviction);
    return new Screen(longs, shorts);
  }

  /** Ranking weight: |price move %| × volume (the deck favours the biggest mover on the most liquidity). */
  private static BigDecimal conviction(ScreenerRow r) {
    BigDecimal move = r.pricePct() == null ? BigDecimal.ZERO : r.pricePct().abs();
    return move.multiply(BigDecimal.valueOf(r.volume()));
  }

  private static BigDecimal pct(BigDecimal delta, BigDecimal base) {
    if (base == null || base.signum() == 0) {
      return null;
    }
    return delta.multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP);
  }

  private static List<BigDecimal> highs(List<DailyBar> bars) {
    return bars.stream().map(DailyBar::high).toList();
  }

  private static List<BigDecimal> lows(List<DailyBar> bars) {
    return bars.stream().map(DailyBar::low).toList();
  }

  private static List<BigDecimal> closes(List<DailyBar> bars) {
    return bars.stream().map(DailyBar::close).toList();
  }
}
