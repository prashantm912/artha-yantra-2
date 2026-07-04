package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Pins the RS-rank percentile math ({@link MinerviniBacktestService#percentile}). The v2 gate is
 * "RS-rank ≥ 70 = top 30%", so the convention MUST let the strongest name clear 70 and must rank ties
 * symmetrically. An earlier "count strictly below" implementation gave the sole top name in a 3-name
 * distribution only 66.7 (so it could never clear 70) and an all-equal block 0 — this locks the
 * midpoint convention that fixes both.
 */
class MinerviniBacktestPercentileTest {

  @Test
  void theSoleTopNameClearsTheSeventyGate() {
    double[] dist = {1, 2, 3};
    assertThat(MinerviniBacktestService.percentile(dist, 3))
        .as("the strongest name must clear the RS≥70 gate")
        .isGreaterThanOrEqualTo(70.0)
        .isCloseTo(83.333, within(0.01));
    assertThat(MinerviniBacktestService.percentile(dist, 2)).isCloseTo(50.0, within(0.01));
    assertThat(MinerviniBacktestService.percentile(dist, 1)).isCloseTo(16.667, within(0.01));
  }

  @Test
  void allEqualScoresFiftyNotZero() {
    double[] dist = {5, 5, 5};
    assertThat(MinerviniBacktestService.percentile(dist, 5)).isEqualTo(50.0);
  }

  @Test
  void belowTheMinimumIsZeroAboveTheMaximumIsHundred() {
    double[] dist = {10, 20, 30};
    assertThat(MinerviniBacktestService.percentile(dist, 5)).isEqualTo(0.0);
    assertThat(MinerviniBacktestService.percentile(dist, 35)).isEqualTo(100.0);
  }

  @Test
  void aTieBlockAtTheTopIsRankedByMidpoint() {
    double[] dist = {1, 2, 3, 3}; // x=3 tied with the top: below=2, equal=2 → (2 + 1) / 4 = 75
    assertThat(MinerviniBacktestService.percentile(dist, 3)).isEqualTo(75.0);
  }
}
