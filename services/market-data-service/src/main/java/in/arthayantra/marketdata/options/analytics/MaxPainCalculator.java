package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.util.List;

/** Max pain: the listed strike minimising total writer payout at expiry. */
public final class MaxPainCalculator {

  private MaxPainCalculator() {}

  public record StrikeOi(BigDecimal strike, long ceOi, long peOi) {}

  public static BigDecimal maxPain(List<StrikeOi> chain) {
    BigDecimal best = null;
    BigDecimal bestPain = null;
    for (StrikeOi candidate : chain) {
      BigDecimal p = candidate.strike();
      BigDecimal pain = BigDecimal.ZERO;
      for (StrikeOi s : chain) {
        if (p.compareTo(s.strike()) > 0) { // CE in the money: P - strike
          pain = pain.add(p.subtract(s.strike()).multiply(BigDecimal.valueOf(s.ceOi())));
        }
        if (s.strike().compareTo(p) > 0) { // PE in the money: strike - P
          pain = pain.add(s.strike().subtract(p).multiply(BigDecimal.valueOf(s.peOi())));
        }
      }
      if (bestPain == null || pain.compareTo(bestPain) < 0) {
        bestPain = pain;
        best = p;
      }
    }
    return best;
  }
}
