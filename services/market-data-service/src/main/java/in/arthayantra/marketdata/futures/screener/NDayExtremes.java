package in.arthayantra.marketdata.futures.screener;

import java.math.BigDecimal;
import java.util.List;

/**
 * §3.3 Market-Movers "minimum breakout days" — the count of immediately-prior daily sessions the
 * latest bar's extreme exceeds (the Day-10 deck's "8-9 day high / low" maker). Pure helpers over a
 * daily high/low series (oldest → newest, the last element = today). {@code breakoutDaysHigh >= 8} is
 * the long trigger; {@code breakoutDaysLow >= 8} the short trigger.
 */
public final class NDayExtremes {

  private NDayExtremes() {}

  /** How many immediately-prior sessions today's HIGH exceeds (stops at the first prior high >= today). */
  public static int breakoutDaysHigh(List<BigDecimal> highs) {
    if (highs == null || highs.size() < 2) {
      return 0;
    }
    BigDecimal today = highs.get(highs.size() - 1);
    int days = 0;
    for (int i = highs.size() - 2; i >= 0; i--) {
      if (highs.get(i).compareTo(today) < 0) {
        days++;
      } else {
        break;
      }
    }
    return days;
  }

  /** How many immediately-prior sessions today's LOW undercuts (stops at the first prior low <= today). */
  public static int breakoutDaysLow(List<BigDecimal> lows) {
    if (lows == null || lows.size() < 2) {
      return 0;
    }
    BigDecimal today = lows.get(lows.size() - 1);
    int days = 0;
    for (int i = lows.size() - 2; i >= 0; i--) {
      if (lows.get(i).compareTo(today) > 0) {
        days++;
      } else {
        break;
      }
    }
    return days;
  }
}
