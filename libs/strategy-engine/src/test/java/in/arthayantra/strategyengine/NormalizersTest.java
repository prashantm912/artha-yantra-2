package in.arthayantra.strategyengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategyengine.normalize.NormalizeSpec;
import in.arthayantra.strategyengine.normalize.Normalizers;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Normalizer truth tables (plan §10.3 indicator-math row): s ∈ [0,1], clamped, exact. */
class NormalizersTest {

  private static BigDecimal apply(NormalizeSpec spec, String raw) {
    return Normalizers.apply(spec, new BigDecimal(raw));
  }

  @Test
  void linearMapsAndClamps() {
    NormalizeSpec linear = NormalizeSpec.linear(new BigDecimal("1.0"), new BigDecimal("3.0"));

    assertThat(apply(linear, "1.0")).isEqualByComparingTo("0");
    assertThat(apply(linear, "2.0")).isEqualByComparingTo("0.5");
    assertThat(apply(linear, "3.0")).isEqualByComparingTo("1");
    assertThat(apply(linear, "0.5")).as("below from clamps to 0").isEqualByComparingTo("0");
    assertThat(apply(linear, "9.9")).as("above to clamps to 1").isEqualByComparingTo("1");
  }

  @Test
  void linearInvertsWhenFromExceedsTo() {
    NormalizeSpec inverse = NormalizeSpec.linear(new BigDecimal("40"), new BigDecimal("15"));

    assertThat(apply(inverse, "40")).isEqualByComparingTo("0");
    assertThat(apply(inverse, "15")).isEqualByComparingTo("1");
    assertThat(apply(inverse, "27.5")).isEqualByComparingTo("0.5");
    assertThat(apply(inverse, "50")).as("beyond from clamps to 0").isEqualByComparingTo("0");
    assertThat(apply(inverse, "10")).as("beyond to clamps to 1").isEqualByComparingTo("1");
  }

  @Test
  void linearRejectsDegenerateBounds() {
    assertThatThrownBy(
            () -> apply(NormalizeSpec.linear(BigDecimal.ONE, BigDecimal.ONE), "1.0"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stepBandsPickTheFirstMatchingBand() {
    NormalizeSpec step =
        NormalizeSpec.step(
            List.of(
                new NormalizeSpec.Band(new BigDecimal("0"), new BigDecimal("0")),
                new NormalizeSpec.Band(new BigDecimal("2"), new BigDecimal("0.4")),
                new NormalizeSpec.Band(null, new BigDecimal("1"))));

    assertThat(apply(step, "-1")).isEqualByComparingTo("0");
    assertThat(apply(step, "0")).isEqualByComparingTo("0");
    assertThat(apply(step, "1.5")).isEqualByComparingTo("0.4");
    assertThat(apply(step, "2")).isEqualByComparingTo("0.4");
    assertThat(apply(step, "99")).as("catch-all band").isEqualByComparingTo("1");
  }

  @Test
  void directionMapsSignToBinary() {
    NormalizeSpec direction = NormalizeSpec.direction();

    assertThat(apply(direction, "1")).isEqualByComparingTo("1");
    assertThat(apply(direction, "0.0001")).isEqualByComparingTo("1");
    assertThat(apply(direction, "0")).isEqualByComparingTo("0");
    assertThat(apply(direction, "-1")).isEqualByComparingTo("0");
  }

  @Test
  void rsiMomentumIsTheFiftyToSeventyPreset() {
    NormalizeSpec rsi = NormalizeSpec.rsiMomentum();

    assertThat(apply(rsi, "50")).isEqualByComparingTo("0");
    assertThat(apply(rsi, "60")).isEqualByComparingTo("0.5");
    assertThat(apply(rsi, "70")).isEqualByComparingTo("1");
    assertThat(apply(rsi, "30")).isEqualByComparingTo("0");
    assertThat(apply(rsi, "85")).isEqualByComparingTo("1");
  }

  @Test
  void nullRawValueNeverScores() {
    assertThat(Normalizers.apply(NormalizeSpec.direction(), null)).isNull();
  }
}
