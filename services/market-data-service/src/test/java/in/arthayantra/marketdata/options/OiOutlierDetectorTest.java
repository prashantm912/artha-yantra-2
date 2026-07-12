package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the OI-outlier plausibility judge (audit §8 V6). Pure — no Spring, no DB. */
class OiOutlierDetectorTest {

  // jumpFactor=20, minPriorOi=5000 (the defaults).
  private final OiOutlierDetector detector = new OiOutlierDetector(true, 20.0, 5000);

  @Test
  void flagsNegativeOi() {
    assertThat(detector.isOutlier(-5L, null)).isTrue();
    assertThat(detector.isOutlier(-1L, -100L)).isTrue();
  }

  @Test
  void doesNotFlagWhenChangeIsUnknown() {
    assertThat(detector.isOutlier(100_000L, null)).isFalse();
  }

  @Test
  void doesNotJudgeIlliquidPriorBelowFloor() {
    // prior = oi - oiChange = 100 - 90 = 10 (< 5000 floor) — never judged even though it 10x'd
    assertThat(detector.isOutlier(100L, 90L)).isFalse();
  }

  @Test
  void flagsAGrossSingleBucketJump() {
    // prior = 10_000 (>= floor); +250_000 change is 25x the prior (> 20x) → outlier
    assertThat(detector.isOutlier(260_000L, 250_000L)).isTrue();
  }

  @Test
  void passesANormalMove() {
    // prior = 100_000; +10_000 change is 0.1x (well under 20x) → fine
    assertThat(detector.isOutlier(110_000L, 10_000L)).isFalse();
  }

  @Test
  void flagsAGrossNegativeJump() {
    // prior = 300_000; a -280_000 drop... 0.93x, not an outlier. A -6.5M drop from 300k would be.
    assertThat(detector.isOutlier(20_000L, -280_000L)).isFalse();
    // prior = 300_000; oiChange -6_100_000 → oi = -5_800_000 (negative) → flagged as impossible OI
    assertThat(detector.isOutlier(-5_800_000L, -6_100_000L)).isTrue();
  }

  @Test
  void disabledNeverFlags() {
    OiOutlierDetector off = new OiOutlierDetector(false, 20.0, 5000);
    assertThat(off.isOutlier(-100L, null)).isFalse();
    assertThat(off.isOutlier(260_000L, 250_000L)).isFalse();
  }
}
