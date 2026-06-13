package in.arthayantra.backtest.replay.folds;

import static in.arthayantra.backtest.replay.folds.FoldTestFixtures.fold;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guard-6 {@code fold_aggregation} truth table (§D.4): {@code mean | min | mean_minus_std} over
 * the valid OOS folds, the min-trades exclusion flag, and the pruned-trial partial-coverage flag.
 * {@code oosFoldMean}/{@code oosFoldStd} are reported regardless of the aggregation mode (mode-
 * independent dispersion); the chosen mode only moves {@code headlineObjective}.
 */
class FoldAggregationTest {

  private final ObjectiveAggregator aggregator = new ObjectiveAggregator();

  private static final List<FoldResult> FOLDS =
      List.of(fold(0, 1.0, 1.0, 40), fold(1, 1.0, 2.0, 40), fold(2, 1.0, 4.0, 40));
  // OOS objectives {1, 2, 4}: mean = 2.333333, sample std = sqrt(((−1.33)^2+(−0.33)^2+(1.67)^2)/2)
  //                                                        = sqrt((1.778+0.111+2.778)/2) = 1.527525.

  @Test
  void meanAggregationIsTheArithmeticMean() {
    FoldAggregate agg = aggregator.aggregate(FOLDS, "sharpe", 30, "mean", 0);
    assertThat(agg.aggregation()).isEqualTo("mean");
    assertThat(agg.headlineObjective()).isEqualByComparingTo("2.333333");
    assertThat(agg.oosFoldMean()).isEqualByComparingTo("2.333333");
    assertThat(agg.oosFoldStd()).isEqualByComparingTo("1.527525");
  }

  @Test
  void minAggregationIsTheWorstValidFold() {
    FoldAggregate agg = aggregator.aggregate(FOLDS, "sharpe", 30, "min", 0);
    assertThat(agg.aggregation()).isEqualTo("min");
    // maximin: the worst OOS fold objective is 1.0 (NOT the mean) — a strategy is only as good as its
    // weakest tested regime.
    assertThat(agg.headlineObjective()).isEqualByComparingTo("1.0");
    // dispersion is mode-independent: still the mean/std of {1,2,4}.
    assertThat(agg.oosFoldMean()).isEqualByComparingTo("2.333333");
    assertThat(agg.oosFoldStd()).isEqualByComparingTo("1.527525");
  }

  @Test
  void meanMinusStdAggregationPenalizesDispersion() {
    FoldAggregate agg = aggregator.aggregate(FOLDS, "sharpe", 30, "mean_minus_std", 0);
    assertThat(agg.aggregation()).isEqualTo("mean_minus_std");
    // 2.333333 − 1.527525 = 0.805808.
    assertThat(agg.headlineObjective()).isEqualByComparingTo("0.805808");
  }

  @Test
  void minExcludesUnderTradingFoldsBeforeTakingTheWorst() {
    // The 3-trade "Sharpe -5.0" fold is under min_trades and must be excluded BEFORE the min — the
    // minimum of small-sample Sharpes is noise. Without exclusion, min would wrongly return −5.0.
    FoldAggregate agg =
        aggregator.aggregate(
            List.of(
                fold(0, 1.0, 1.0, 40),
                fold(1, 1.0, -5.0, 3), // under-trading garbage — must NOT become the min
                fold(2, 1.0, 4.0, 40)),
            "sharpe",
            30,
            "min",
            0);
    assertThat(agg.headlineObjective()).isEqualByComparingTo("1.0"); // worst VALID fold, not −5.0
    assertThat(agg.excludedFoldCount()).isEqualTo(1); // surfaced, never silent
    assertThat(agg.validFolds()).hasSize(2);
  }

  @Test
  void prunedFoldCountIsCarriedAsPartialCoverageFlag() {
    // A pruned trial: 2 folds ran (both valid) and 3 more were pruned away. The pruned count is
    // surfaced as a partial-coverage flag, never silently treated as a complete fold set.
    FoldAggregate agg =
        aggregator.aggregate(
            List.of(fold(0, 1.0, 2.0, 40), fold(1, 1.0, 3.0, 40)), "sharpe", 30, "min", 3);
    assertThat(agg.prunedFoldCount()).isEqualTo(3);
    assertThat(agg.headlineObjective()).isEqualByComparingTo("2.0"); // min over the folds that ran
    assertThat(agg.validFolds()).hasSize(2);
  }

  @Test
  void unknownAggregationFallsBackToMean() {
    // The schema validates the enum, so this is a defensive default only — never a silent garbage.
    FoldAggregate agg = aggregator.aggregate(FOLDS, "sharpe", 30, "bogus", 0);
    assertThat(agg.aggregation()).isEqualTo("mean");
    assertThat(agg.headlineObjective()).isEqualByComparingTo("2.333333");
  }

  @Test
  void nullOrBlankAggregationDefaultsToMean() {
    assertThat(aggregator.aggregate(FOLDS, "sharpe", 30, null, 0).aggregation()).isEqualTo("mean");
    assertThat(aggregator.aggregate(FOLDS, "sharpe", 30, "", 0).aggregation()).isEqualTo("mean");
  }

  @Test
  void legacyThreeArgOverloadStillDefaultsToMean() {
    // The Phase-31 entry point (no aggregation/pruning args) must keep working unchanged.
    FoldAggregate agg = aggregator.aggregate(FOLDS, "sharpe", 30);
    assertThat(agg.aggregation()).isEqualTo("mean");
    assertThat(agg.prunedFoldCount()).isZero();
    assertThat(agg.headlineObjective()).isEqualByComparingTo("2.333333");
  }

  @Test
  void allExcludedYieldsNullHeadlineUnderEveryMode() {
    for (String mode : List.of("mean", "min", "mean_minus_std")) {
      FoldAggregate agg =
          aggregator.aggregate(
              List.of(fold(0, 1.0, 5.0, 2), fold(1, 1.0, 6.0, 5)), "sharpe", 30, mode, 0);
      assertThat(agg.headlineObjective()).as("mode %s", mode).isNull();
      assertThat(agg.oosFoldMean()).isNull();
      assertThat(agg.excludedFoldCount()).isEqualTo(2);
    }
  }
}
