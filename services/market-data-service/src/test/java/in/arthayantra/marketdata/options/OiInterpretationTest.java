package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OiInterpretationTest {

  @Test
  void classifiesAllFourQuadrants() {
    // priceDelta sign × oiDelta sign (boundary 0 counts as the "up"/build side, matching screener)
    assertThat(OiInterpretation.classify(new BigDecimal("1.0"), 100))
        .isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(OiInterpretation.classify(new BigDecimal("-1.0"), 100))
        .isEqualTo(OiInterpretation.SHORT_BUILDUP);
    assertThat(OiInterpretation.classify(new BigDecimal("1.0"), -100))
        .isEqualTo(OiInterpretation.SHORT_COVERING);
    assertThat(OiInterpretation.classify(new BigDecimal("-1.0"), -100))
        .isEqualTo(OiInterpretation.LONG_UNWINDING);
  }

  @Test
  void boundaryZeroIsBuildSide() {
    assertThat(OiInterpretation.classify(BigDecimal.ZERO, 0)).isEqualTo(OiInterpretation.LONG_BUILDUP);
  }
}
