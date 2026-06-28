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
