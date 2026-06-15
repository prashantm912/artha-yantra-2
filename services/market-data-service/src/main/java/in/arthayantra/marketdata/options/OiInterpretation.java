package in.arthayantra.marketdata.options;

import java.math.BigDecimal;

/**
 * The oipulse 4-state OI interpretation (primitive #1): price direction × OI direction. Boundary
 * convention (delta == 0 counts as the "up" side) matches the pre-existing {@code
 * ScreenerService.oiBuildup()} rule it replaces, so the screener output is byte-stable.
 */
public enum OiInterpretation {
  LONG_BUILDUP, // price up, OI up
  SHORT_BUILDUP, // price down, OI up
  SHORT_COVERING, // price up, OI down
  LONG_UNWINDING; // price down, OI down

  public static OiInterpretation classify(BigDecimal priceDelta, long oiDelta) {
    boolean priceUp = priceDelta.signum() >= 0;
    boolean oiUp = oiDelta >= 0;
    if (priceUp) {
      return oiUp ? LONG_BUILDUP : SHORT_COVERING;
    }
    return oiUp ? SHORT_BUILDUP : LONG_UNWINDING;
  }
}
