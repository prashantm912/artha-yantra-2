package in.arthayantra.strategyengine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A1 truth tables (the normative formula, §C-2.5): weight-normalized averaging, optional
 * activation needs BOTH its own score >= min AND the required-only composite within the margin;
 * optionals reinforce but never gate or carry alone; warm-up blocks scoring entirely.
 */
class CompositeScorerTest {

  @Test
  void requiredOnlyIsTheWeightNormalizedAverage() {
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(EvalFixtures.scoring("a", "1", false), EvalFixtures.scoring("b", "3", false)),
            "0.5", "0.6", "0.15");
    BarValues values = new EvalFixtures.StubValues().with("a", "0.8").with("b", "0.4");

    CompositeScorer.Result result = CompositeScorer.score(def, values, 0).orElseThrow();

    assertThat(result.composite()).isEqualByComparingTo("0.5"); // (0.8 + 1.2) / 4
    assertThat(result.requiredComposite()).isEqualByComparingTo("0.5");
    assertThat(result.weightDenominator()).isEqualByComparingTo("4");
    assertThat(result.indicators())
        .allSatisfy(
            e -> {
              assertThat(e.activated()).isTrue();
              assertThat(e.activationReason())
                  .isEqualTo(ScoreBreakdown.ActivationReason.REQUIRED);
            });
  }

  @Test
  void optionalActivatesWhenScoreAndMarginBothHold() {
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(EvalFixtures.scoring("req", "1", false), EvalFixtures.scoring("opt", "1", true)),
            "0.75", "0.6", "0.15");
    BarValues values = new EvalFixtures.StubValues().with("req", "0.8").with("opt", "0.7");

    CompositeScorer.Result result = CompositeScorer.score(def, values, 0).orElseThrow();

    assertThat(result.composite()).isEqualByComparingTo("0.75"); // (0.8 + 0.7) / 2
    assertThat(result.weightDenominator()).isEqualByComparingTo("2");
    assertThat(entry(result, "opt").activationReason())
        .isEqualTo(ScoreBreakdown.ActivationReason.ACTIVATED);
    assertThat(entry(result, "opt").contribution()).isEqualByComparingTo("0.7");
  }

  @Test
  void optionalBelowMinScoreIsExcludedFromBothSums() {
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(EvalFixtures.scoring("req", "1", false), EvalFixtures.scoring("opt", "1", true)),
            "0.75", "0.6", "0.15");
    BarValues values = new EvalFixtures.StubValues().with("req", "0.8").with("opt", "0.59");

    CompositeScorer.Result result = CompositeScorer.score(def, values, 0).orElseThrow();

    assertThat(result.composite()).isEqualByComparingTo("0.8"); // required only
    assertThat(result.weightDenominator()).isEqualByComparingTo("1");
    assertThat(entry(result, "opt").activationReason())
        .isEqualTo(ScoreBreakdown.ActivationReason.SCORE_BELOW_MIN);
    assertThat(entry(result, "opt").contribution()).isEqualByComparingTo("0");
  }

  @Test
  void optionalCannotRescueAWeakRequiredCore() {
    // required-only composite 0.5 < threshold - margin (0.8 - 0.15): a convincing optional
    // (0.9) still MUST NOT count - optionals reinforce, never carry (A1)
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(EvalFixtures.scoring("req", "1", false), EvalFixtures.scoring("opt", "2", true)),
            "0.8", "0.6", "0.15");
    BarValues values = new EvalFixtures.StubValues().with("req", "0.5").with("opt", "0.9");

    CompositeScorer.Result result = CompositeScorer.score(def, values, 0).orElseThrow();

    assertThat(result.composite()).isEqualByComparingTo("0.5");
    assertThat(entry(result, "opt").activationReason())
        .isEqualTo(ScoreBreakdown.ActivationReason.MARGIN_NOT_MET);
  }

  @Test
  void boundaryEqualitiesActivate() {
    // required-only exactly at threshold - margin AND optional exactly at min score
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(EvalFixtures.scoring("req", "1", false), EvalFixtures.scoring("opt", "1", true)),
            "0.75", "0.6", "0.15");
    BarValues values = new EvalFixtures.StubValues().with("req", "0.6").with("opt", "0.6");

    CompositeScorer.Result result = CompositeScorer.score(def, values, 0).orElseThrow();

    assertThat(entry(result, "opt").activationReason())
        .isEqualTo(ScoreBreakdown.ActivationReason.ACTIVATED);
    assertThat(result.composite()).isEqualByComparingTo("0.6"); // (0.6 + 0.6) / 2
  }

  @Test
  void requiredWarmupMakesTheBarUnscoreable() {
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(EvalFixtures.scoring("req", "1", false)), "0.5", "0.6", "0.15");
    BarValues values = new EvalFixtures.StubValues().with("req", null);

    Optional<CompositeScorer.Result> result = CompositeScorer.score(def, values, 0);

    assertThat(result).as("never a silent zero - the bar simply cannot be scored").isEmpty();
  }

  @Test
  void optionalWarmupCountsAsBelowMin() {
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(EvalFixtures.scoring("req", "1", false), EvalFixtures.scoring("opt", "1", true)),
            "0.5", "0.6", "0.15");
    BarValues values = new EvalFixtures.StubValues().with("req", "0.7").with("opt", null);

    CompositeScorer.Result result = CompositeScorer.score(def, values, 0).orElseThrow();

    assertThat(result.composite()).isEqualByComparingTo("0.7");
    assertThat(entry(result, "opt").score()).isNull();
    assertThat(entry(result, "opt").activationReason())
        .isEqualTo(ScoreBreakdown.ActivationReason.SCORE_BELOW_MIN);
  }

  @Test
  void gateOnlyIndicatorsNeverEnterTheComposite() {
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(EvalFixtures.gateOnly("ema_fast"), EvalFixtures.scoring("req", "1", false)),
            "0.5", "0.6", "0.15");
    BarValues values =
        new EvalFixtures.StubValues().with("ema_fast", "101.5").with("req", "0.7");

    CompositeScorer.Result result = CompositeScorer.score(def, values, 0).orElseThrow();

    assertThat(result.indicators()).hasSize(1);
    assertThat(result.indicators().get(0).alias()).isEqualTo("req");
    assertThat(result.weightDenominator()).isEqualByComparingTo("1");
  }

  @Test
  void bpbProvenanceNumbersResolveToTheNormalizedAverage() {
    // the review's example: contributions 0.85 + 0.52 over weights 1.0 + 0.8
    // = 1.37 / 1.8 = 0.76111... - NOT the 0.72 its original DTO claimed (ADR A1)
    StrategyDefinition def =
        EvalFixtures.definition(
            List.of(
                EvalFixtures.scoring("r1", "1.0", false), EvalFixtures.scoring("r2", "0.8", false)),
            "0.7", "0.6", "0.15");
    BarValues values = new EvalFixtures.StubValues().with("r1", "0.85").with("r2", "0.65");

    CompositeScorer.Result result = CompositeScorer.score(def, values, 0).orElseThrow();

    assertThat(result.composite()).isEqualByComparingTo("0.76111111");
    assertThat(entry(result, "r2").contribution()).isEqualByComparingTo("0.52");
  }

  private static ScoreBreakdown.IndicatorEntry entry(CompositeScorer.Result result, String alias) {
    return result.indicators().stream()
        .filter(e -> e.alias().equals(alias))
        .findFirst()
        .orElseThrow();
  }
}
