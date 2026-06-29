package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ScalperSizingTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  @Test
  void multiplierIsOneAtDefaultsAndAtOrAboveFullAggregate() {
    // null aggregate (non-scalper / no grading data) and an at/above-full aggregate -> 1.0 (byte-identical).
    assertThat(ScalperSizing.sizeMultiplier(null)).isEqualByComparingTo("1.0");
    assertThat(ScalperSizing.sizeMultiplier(bd("0.75"))).isEqualByComparingTo("1.0");
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"))).isEqualByComparingTo("1.0");
  }

  @Test
  void weakConfluenceFloorsAtOrBelowTheEntryThreshold() {
    assertThat(ScalperSizing.sizeMultiplier(bd("0.60"))).isEqualByComparingTo("0.50");
    assertThat(ScalperSizing.sizeMultiplier(bd("0.55"))).isEqualByComparingTo("0.50");
  }

  @Test
  void oiGapFactorTrimsOnlyAThinImbalanceAndIsNullSafe() {
    // null imbalance / wide gap (>= 50%) leaves the confluence base untrimmed.
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), null)).isEqualByComparingTo("1.0");
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), bd("60"))).isEqualByComparingTo("1.0");
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), bd("50"))).isEqualByComparingTo("1.0"); // band inclusive
    // a thin gap (< 50%) trims the FULL base by 0.85.
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), bd("30"))).isEqualByComparingTo("0.85");
    // the product is clamped to the floor: weak confluence (base 0.5) * thin OI (0.85) = 0.425 -> 0.5.
    assertThat(ScalperSizing.sizeMultiplier(bd("0.60"), bd("10"))).isEqualByComparingTo("0.50");
    // the 1-arg overload == the 2-arg with a null imbalance (back-compat, byte-identical).
    assertThat(ScalperSizing.sizeMultiplier(bd("0.675")))
        .isEqualByComparingTo(ScalperSizing.sizeMultiplier(bd("0.675"), null));
  }

  @Test
  void vixFactorTrimsOnlyInElevatedVolBandsAndIsNullSafe() {
    // E8 §2.8 (Siva VIX bands): a FULL-confluence base (1.0) is untrimmed below the 15 sellers' band,
    // trimmed to 0.85 in 15-16 (sellers' market), and to 0.70 at 17+ (active shorts / sell-off).
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), null, null)).isEqualByComparingTo("1.0"); // null VIX
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), null, bd("11"))).isEqualByComparingTo("1.0"); // lower
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), null, bd("14"))).isEqualByComparingTo("1.0"); // medium
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), null, bd("15"))).isEqualByComparingTo("0.85"); // sellers (inclusive)
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), null, bd("16"))).isEqualByComparingTo("0.85");
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), null, bd("17"))).isEqualByComparingTo("0.70"); // shorts (inclusive)
    assertThat(ScalperSizing.sizeMultiplier(bd("0.90"), null, bd("24.75"))).isEqualByComparingTo("0.70");
    // stacked factors clamp to the floor: weak base (0.5) * sellers VIX (0.85) = 0.425 -> 0.50.
    assertThat(ScalperSizing.sizeMultiplier(bd("0.60"), null, bd("16"))).isEqualByComparingTo("0.50");
    // the 2-arg overload == the 3-arg with a null VIX (back-compat, byte-identical).
    assertThat(ScalperSizing.sizeMultiplier(bd("0.675"), bd("30")))
        .isEqualByComparingTo(ScalperSizing.sizeMultiplier(bd("0.675"), bd("30"), null));
  }

  @Test
  void multiplierTapersLinearlyAndMonotonicallyBetween() {
    // midpoint 0.675 -> floor + 0.5*(1 - floor) = 0.50 + 0.25 = 0.75
    assertThat(ScalperSizing.sizeMultiplier(bd("0.675"))).isEqualByComparingTo("0.75");
    BigDecimal lo = ScalperSizing.sizeMultiplier(bd("0.63"));
    BigDecimal mid = ScalperSizing.sizeMultiplier(bd("0.675"));
    BigDecimal hi = ScalperSizing.sizeMultiplier(bd("0.72"));
    assertThat(lo).isLessThan(mid);
    assertThat(mid).isLessThan(hi);
    assertThat(lo).isGreaterThanOrEqualTo(bd("0.50"));
    assertThat(hi).isLessThanOrEqualTo(BigDecimal.ONE);
  }
}
