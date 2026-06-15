package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaxPainCalculatorTest {

  @Test
  void picksStrikeMinimisingTotalIntrinsic() {
    // CE OI heavy at 22400, PE OI heavy at 22600 → max pain pulled to the middle (22500).
    List<MaxPainCalculator.StrikeOi> chain =
        List.of(
            new MaxPainCalculator.StrikeOi(new BigDecimal("22400"), 100, 900),
            new MaxPainCalculator.StrikeOi(new BigDecimal("22500"), 500, 500),
            new MaxPainCalculator.StrikeOi(new BigDecimal("22600"), 900, 100));
    assertThat(MaxPainCalculator.maxPain(chain)).isEqualByComparingTo("22500");
  }

  @Test
  void returnsNullForEmptyChain() {
    assertThat(MaxPainCalculator.maxPain(List.of())).isNull();
  }
}
