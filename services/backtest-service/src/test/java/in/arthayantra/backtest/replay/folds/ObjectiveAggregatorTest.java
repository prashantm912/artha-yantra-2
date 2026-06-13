package in.arthayantra.backtest.replay.folds;

import static in.arthayantra.backtest.replay.folds.FoldTestFixtures.fold;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Aggregation tests (guards 2 + 3, §D.4): headline objective = mean of valid OOS folds; dispersion
 * = sample (n−1) std; under-{@code min_trades} folds are excluded AND the exclusion is surfaced as
 * an explicit count — never a silent drop.
 */
class ObjectiveAggregatorTest {

  private final ObjectiveAggregator aggregator = new ObjectiveAggregator();

  @Test
  void headlineIsMeanOfValidOosObjectives() {
    FoldAggregate agg =
        aggregator.aggregate(
            List.of(fold(0, 1.0, 1.0, 40), fold(1, 1.0, 2.0, 40), fold(2, 1.0, 3.0, 40)),
            "sharpe",
            30);
    // oos {1,2,3} -> mean 2.0.
    assertThat(agg.headlineObjective()).isEqualByComparingTo("2.0");
    assertThat(agg.oosFoldMean()).isEqualByComparingTo("2.0");
    assertThat(agg.excludedFoldCount()).isZero();
    assertThat(agg.validFolds()).hasSize(3);
  }

  @Test
  void oosFoldStdIsSampleStandardDeviation() {
    FoldAggregate agg =
        aggregator.aggregate(
            List.of(fold(0, 1.0, 1.0, 40), fold(1, 1.0, 2.0, 40), fold(2, 1.0, 3.0, 40)),
            "sharpe",
            30);
    // oos {1,2,3}: sample variance = ((1)^2+(0)^2+(1)^2)/(3-1)=1 -> std 1.0 (NOT population 0.8165).
    assertThat(agg.oosFoldStd()).isEqualByComparingTo("1.0");
  }

  @Test
  void singleValidFoldHasZeroStd() {
    FoldAggregate agg = aggregator.aggregate(List.of(fold(0, 1.0, 1.5, 40)), "sharpe", 30);
    assertThat(agg.oosFoldMean()).isEqualByComparingTo("1.5");
    assertThat(agg.oosFoldStd()).isEqualByComparingTo("0");
  }

  @Test
  void underTradingFoldsAreExcludedAndFlaggedNotSilent() {
    // folds 1 and 3 close < 30 OOS trades -> excluded; the count is reported explicitly.
    FoldAggregate agg =
        aggregator.aggregate(
            List.of(
                fold(0, 1.0, 2.0, 40),
                fold(1, 1.0, 9.0, 3), // under-trading "Sharpe 9" — must NOT count
                fold(2, 1.0, 4.0, 35),
                fold(3, 1.0, 8.0, 10)), // under-trading — must NOT count
            "sharpe",
            30);
    // only folds 0 + 2 survive: oos {2,4} -> mean 3.0; the spike folds are gone.
    assertThat(agg.headlineObjective()).isEqualByComparingTo("3.0");
    assertThat(agg.validFolds()).hasSize(2);
    assertThat(agg.excludedFoldCount()).isEqualTo(2);
  }

  @Test
  void allFoldsExcludedYieldsNullHeadlineAndExplicitCount() {
    FoldAggregate agg =
        aggregator.aggregate(
            List.of(fold(0, 1.0, 5.0, 2), fold(1, 1.0, 6.0, 5)), "sharpe", 30);
    assertThat(agg.headlineObjective()).isNull();
    assertThat(agg.oosFoldMean()).isNull();
    assertThat(agg.oosFoldStd()).isNull();
    assertThat(agg.sharpeDegradation()).isNull();
    assertThat(agg.validFolds()).isEmpty();
    assertThat(agg.excludedFoldCount()).isEqualTo(2); // surfaced, not swallowed
  }

  @Test
  void defaultAggregationSeamIsMean() {
    assertThat(ObjectiveAggregator.SEAM).isEqualTo("mean");
  }

  @Test
  void minTradesBoundaryIsInclusive() {
    // exactly min_trades closed -> VALID (>= threshold).
    FoldAggregate agg = aggregator.aggregate(List.of(fold(0, 1.0, 2.5, 30)), "sharpe", 30);
    assertThat(agg.validFolds()).hasSize(1);
    assertThat(agg.excludedFoldCount()).isZero();
    assertThat(agg.headlineObjective()).isEqualByComparingTo("2.5");
  }
}
