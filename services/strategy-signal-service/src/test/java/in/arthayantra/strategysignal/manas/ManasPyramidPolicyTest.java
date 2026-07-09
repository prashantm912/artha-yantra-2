package in.arthayantra.strategysignal.manas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The Manas §3.4 pyramiding math (moved verbatim from the old engine): the +gain trigger and the ≤6%
 * open-risk cap, both pure and unit-testable.
 */
class ManasPyramidPolicyTest {

  @Test
  void movedUpTriggersOnlyAtOrAboveTheGainThreshold() {
    BigDecimal five = new BigDecimal("5.0");
    assertThat(ManasPyramidPolicy.movedUp(bd(105), bd(100), five)).as("exactly +5%").isTrue();
    assertThat(ManasPyramidPolicy.movedUp(new BigDecimal("104.99"), bd(100), five))
        .as("just under +5%")
        .isFalse();
    assertThat(ManasPyramidPolicy.movedUp(bd(110), bd(100), five)).as("+10%").isTrue();
    assertThat(ManasPyramidPolicy.movedUp(bd(150), null, five)).isFalse();
    assertThat(ManasPyramidPolicy.movedUp(null, bd(100), five)).isFalse();
    assertThat(ManasPyramidPolicy.movedUp(bd(150), BigDecimal.ZERO, five)).isFalse();
  }

  @Test
  void breachesRiskCapAddsTheNewLotRiskToTheExistingBookRisk() {
    BigDecimal equity = bd(1_000_000);
    BigDecimal cap = new BigDecimal("6.0");
    // existing 4% (₹40k) + new lot 1% (100 × ₹100 stop = ₹10k) = 5% ≤ 6% → allowed.
    assertThat(ManasPyramidPolicy.breachesRiskCap(bd(40_000), bd(100), bd(100), equity, cap))
        .as("5% total is within the 6% cap")
        .isFalse();
    // existing 5.5% (₹55k) + new lot 1% = 6.5% > 6% → blocked.
    assertThat(ManasPyramidPolicy.breachesRiskCap(bd(55_000), bd(100), bd(100), equity, cap))
        .as("6.5% total breaches the 6% cap")
        .isTrue();
    assertThat(ManasPyramidPolicy.breachesRiskCap(bd(55_000), null, bd(100), equity, cap)).isFalse();
    assertThat(ManasPyramidPolicy.breachesRiskCap(bd(55_000), bd(100), bd(100), null, cap)).isFalse();
    assertThat(ManasPyramidPolicy.breachesRiskCap(bd(55_000), bd(100), BigDecimal.ZERO, equity, cap))
        .isFalse();
  }

  private static BigDecimal bd(long v) {
    return BigDecimal.valueOf(v);
  }
}
