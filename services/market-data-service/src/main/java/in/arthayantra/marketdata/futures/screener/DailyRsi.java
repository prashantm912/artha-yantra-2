package in.arthayantra.marketdata.futures.screener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Wilder RSI({@code period}) over a daily-close series (oldest → newest) — the §3.3 Market-Movers
 * daily-RSI filter (deck Day-10: {@code >70/75} = no fresh long; a falling stock at {@code 25-30} =
 * oversold, careful). Pure; {@code null} when there are too few closes for a full seed.
 */
public final class DailyRsi {

  private DailyRsi() {}

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** Wilder RSI on the close series, or {@code null} when {@code closes.size() <= period}. */
  public static BigDecimal wilder(List<BigDecimal> closes, int period) {
    if (closes == null || closes.size() <= period) {
      return null;
    }
    BigDecimal gain = BigDecimal.ZERO;
    BigDecimal loss = BigDecimal.ZERO;
    for (int i = 1; i <= period; i++) {
      BigDecimal d = closes.get(i).subtract(closes.get(i - 1));
      if (d.signum() >= 0) {
        gain = gain.add(d);
      } else {
        loss = loss.subtract(d);
      }
    }
    BigDecimal p = BigDecimal.valueOf(period);
    BigDecimal pm1 = BigDecimal.valueOf(period - 1L);
    BigDecimal avgGain = gain.divide(p, 10, RoundingMode.HALF_UP);
    BigDecimal avgLoss = loss.divide(p, 10, RoundingMode.HALF_UP);
    for (int i = period + 1; i < closes.size(); i++) {
      BigDecimal d = closes.get(i).subtract(closes.get(i - 1));
      BigDecimal g = d.signum() >= 0 ? d : BigDecimal.ZERO;
      BigDecimal l = d.signum() < 0 ? d.negate() : BigDecimal.ZERO;
      avgGain = avgGain.multiply(pm1).add(g).divide(p, 10, RoundingMode.HALF_UP);
      avgLoss = avgLoss.multiply(pm1).add(l).divide(p, 10, RoundingMode.HALF_UP);
    }
    if (avgLoss.signum() == 0) {
      return HUNDRED;
    }
    BigDecimal rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP);
    return HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(rs), 4, RoundingMode.HALF_UP));
  }
}
