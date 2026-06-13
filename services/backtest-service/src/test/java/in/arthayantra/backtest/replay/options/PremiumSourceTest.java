package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@link PremiumSource} provenance enum and the mixed-source like-for-like flag — comparing
 * runs of differing premium provenance is flagged exactly like {@code dataHash} mismatches (§D.9).
 */
class PremiumSourceTest {

  @Test
  void enumCarriesTheThreeContractValues() {
    assertThat(PremiumSource.values())
        .containsExactly(
            PremiumSource.SNAPSHOT, PremiumSource.SYNTHETIC_B76, PremiumSource.NA);
  }

  @Test
  void sameSourceIsLikeForLike() {
    assertThat(PremiumSource.likeForLike(PremiumSource.SNAPSHOT, PremiumSource.SNAPSHOT)).isTrue();
    assertThat(PremiumSource.likeForLike(PremiumSource.SYNTHETIC_B76, PremiumSource.SYNTHETIC_B76))
        .isTrue();
    assertThat(PremiumSource.likeForLike(PremiumSource.NA, PremiumSource.NA)).isTrue();
  }

  @Test
  void snapshotVsSyntheticIsNotLikeForLike() {
    assertThat(PremiumSource.likeForLike(PremiumSource.SNAPSHOT, PremiumSource.SYNTHETIC_B76))
        .as("a snapshot-grade run is never comparable to a synthetic run")
        .isFalse();
    assertThat(PremiumSource.likeForLike(PremiumSource.SYNTHETIC_B76, PremiumSource.SNAPSHOT))
        .isFalse();
  }

  @Test
  void naVsAnyRealSourceIsNotLikeForLike() {
    assertThat(PremiumSource.likeForLike(PremiumSource.NA, PremiumSource.SNAPSHOT)).isFalse();
    assertThat(PremiumSource.likeForLike(PremiumSource.NA, PremiumSource.SYNTHETIC_B76)).isFalse();
  }

  @Test
  void nullSourcesAreNeverLikeForLike() {
    assertThat(PremiumSource.likeForLike(null, PremiumSource.SNAPSHOT)).isFalse();
    assertThat(PremiumSource.likeForLike(PremiumSource.SNAPSHOT, null)).isFalse();
    assertThat(PremiumSource.likeForLike(null, null)).isFalse();
  }
}
