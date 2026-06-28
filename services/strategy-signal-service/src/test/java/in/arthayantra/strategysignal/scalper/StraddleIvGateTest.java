package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** E4 LONG-straddle LOW-IV gate (§3.11/§4.6) — too rich when EITHER 6-strike IV avg >= the 0.40 floor. */
class StraddleIvGateTest {

  private static final ScalperOiProps P = ScalperOiProps.defaults(); // ivBothHighFloor = 0.40

  private static Macro macro(BigDecimal ceAvg6, BigDecimal peAvg6) {
    return new Macro(
        new BigDecimal("0.14"), new BigDecimal("30"), new BigDecimal("12"), Boolean.FALSE, 40, 10,
        new BigDecimal("50"), ceAvg6, peAvg6);
  }

  @Test
  void tooRichWhenEitherSideIsAtOrAboveTheFloor() {
    assertThat(StraddleIvGate.tooRichForLong(macro(new BigDecimal("0.45"), new BigDecimal("0.10")), P)).isTrue();
    assertThat(StraddleIvGate.tooRichForLong(macro(new BigDecimal("0.10"), new BigDecimal("0.45")), P)).isTrue();
    assertThat(StraddleIvGate.tooRichForLong(macro(new BigDecimal("0.40"), new BigDecimal("0.10")), P)).isTrue();
  }

  @Test
  void notTooRichWhenBothSidesAreLow() {
    assertThat(StraddleIvGate.tooRichForLong(macro(new BigDecimal("0.10"), new BigDecimal("0.12")), P)).isFalse();
  }

  @Test
  void aNullMacroOrNullAverageNeverBlocks() {
    assertThat(StraddleIvGate.tooRichForLong(null, P)).isFalse();
    assertThat(StraddleIvGate.tooRichForLong(macro(null, null), P)).isFalse();
    assertThat(StraddleIvGate.tooRichForLong(macro(null, new BigDecimal("0.10")), P)).isFalse();
  }
}
