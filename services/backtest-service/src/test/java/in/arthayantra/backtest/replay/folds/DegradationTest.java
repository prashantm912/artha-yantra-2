package in.arthayantra.backtest.replay.folds;

import static in.arthayantra.backtest.replay.folds.FoldTestFixtures.fold;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sign-convention + suppression tests for {@code sharpe_degradation} (guard 7, §D.4): a DIFFERENCE
 * {@code mean(train) − mean(oos)}, never a ratio. Min trades is 1 here so every fold stays valid and
 * the focus is the degradation arithmetic.
 */
class DegradationTest {

  private final ObjectiveAggregator aggregator = new ObjectiveAggregator();

  private BigDecimal degradation(List<FoldResult> folds) {
    return aggregator.aggregate(folds, "sharpe", 1).sharpeDegradation();
  }

  @Test
  void positiveDegradationWhenOosTrailsTrain() {
    // mean train 2.0, mean oos 0.5 -> degradation 1.5 (the strategy decays out of sample).
    BigDecimal deg = degradation(List.of(fold(0, 2.0, 0.5, 50), fold(1, 2.0, 0.5, 50)));
    assertThat(deg).isEqualByComparingTo("1.5");
  }

  @Test
  void negativeDegradationWhenOosBeatsTrain() {
    // mean train 1.0, mean oos 1.8 -> degradation -0.8 (OOS outperformed; a difference handles it).
    BigDecimal deg = degradation(List.of(fold(0, 1.0, 1.8, 50)));
    assertThat(deg).isEqualByComparingTo("-0.8");
  }

  @Test
  void negativeSharpesAreStableUnderDifference() {
    // train -0.5, oos -2.0 -> difference 1.5. A ratio (train−oos)/train would sign-flip here.
    // mean train −0.5 < 0.5 -> the weak-train suppression fires first, so this is n/a, NOT a
    // garbage ratio. (The difference itself is well-defined; suppression is the guard.)
    BigDecimal deg = degradation(List.of(fold(0, -0.5, -2.0, 50)));
    assertThat(deg).isNull();
  }

  @Test
  void strongNegativeOosWithStrongTrainGivesLargeDistrustDifference() {
    // train 1.5 (>= 0.5, not suppressed), oos -2.0 -> degradation 3.5 ("distrust" badge > 1.0).
    BigDecimal deg = degradation(List.of(fold(0, 1.5, -2.0, 50)));
    assertThat(deg).isEqualByComparingTo("3.5");
  }

  @Test
  void zeroSharpeIsStable() {
    // train 0.6, oos 0.0 -> degradation 0.6; no division-by-zero (a ratio would blow up at oos=0
    // only if it divided by oos, but the rejected formula divides by TRAIN — still unstable at 0).
    BigDecimal deg = degradation(List.of(fold(0, 0.6, 0.0, 50)));
    assertThat(deg).isEqualByComparingTo("0.6");
  }

  @Test
  void suppressedWhenMeanTrainSharpeBelowHalf() {
    // mean train 0.49 < 0.5 -> "n/a — weak train signal".
    BigDecimal deg = degradation(List.of(fold(0, 0.49, 0.1, 50)));
    assertThat(deg).isNull();
  }

  @Test
  void notSuppressedExactlyAtHalf() {
    // mean train 0.5 is the boundary: NOT below 0.5 -> degradation computed (0.5 − 0.2 = 0.3).
    BigDecimal deg = degradation(List.of(fold(0, 0.5, 0.2, 50)));
    assertThat(deg).isEqualByComparingTo("0.3");
  }

  @Test
  void suppressedWhenNoValidFolds() {
    // every fold under-trades (min_trades default 30 here) -> no valid folds -> n/a.
    BigDecimal deg = aggregator.aggregate(List.of(fold(0, 2.0, 1.0, 3)), "sharpe", 30)
        .sharpeDegradation();
    assertThat(deg).isNull();
  }

  @Test
  void degradationAveragesAcrossValidFoldsOnly() {
    // valid folds: train {2.0, 1.0} mean 1.5; oos {0.0, 1.0} mean 0.5 -> 1.0. The under-trading
    // fold (train 9.0) is excluded and never drags the mean.
    BigDecimal deg =
        aggregator
            .aggregate(
                List.of(fold(0, 2.0, 0.0, 50), fold(1, 1.0, 1.0, 50), fold(2, 9.0, 9.0, 2)),
                "sharpe",
                30)
            .sharpeDegradation();
    assertThat(deg).isEqualByComparingTo("1.0");
  }
}
