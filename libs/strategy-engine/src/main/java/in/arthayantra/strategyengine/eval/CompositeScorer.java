package in.arthayantra.strategyengine.eval;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.indicators.EngineMath;
import in.arthayantra.strategyengine.normalize.Normalizers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The NORMATIVE A1 composite (C-2.5):
 *
 * <pre>
 * composite = ( sum_required w*s + sum_activated-optional w*s )
 *             / ( sum_required w + sum_activated-optional w )
 * </pre>
 *
 * An optional activates iff (a) its own score &gt;= optional_min_score AND (b) the REQUIRED-ONLY
 * composite &gt;= threshold - optional_gate_margin. Optionals reinforce - they can never gate or
 * carry a signal alone. Scoring participants are the indicators WITH a normalize mapping;
 * a required participant that is still warming up makes the bar unscoreable (empty result),
 * never a silent zero.
 */
public final class CompositeScorer {

  /** The scored bar: composite pieces + per-indicator entries (C-2.6 fields). */
  public record Result(
      BigDecimal composite,
      BigDecimal requiredComposite,
      BigDecimal weightDenominator,
      List<ScoreBreakdown.IndicatorEntry> indicators) {}

  private CompositeScorer() {}

  /** Scores one primary bar; empty while any required participant is warming up. */
  public static Optional<Result> score(
      StrategyDefinition definition, BarValues bank, int primaryIndex) {
    List<StrategyDefinition.IndicatorSpec> participants =
        definition.indicators().stream().filter(StrategyDefinition.IndicatorSpec::scoring).toList();

    BigDecimal requiredNumerator = BigDecimal.ZERO;
    BigDecimal requiredWeight = BigDecimal.ZERO;
    List<Scored> scored = new ArrayList<>(participants.size());
    for (StrategyDefinition.IndicatorSpec spec : participants) {
      BigDecimal raw = bank.valueAt(spec.alias(), primaryIndex);
      BigDecimal score = Normalizers.apply(spec.normalize(), raw);
      if (!spec.optional() && score == null) {
        return Optional.empty(); // required indicator not ready - the bar cannot be scored
      }
      scored.add(new Scored(spec, raw, score));
      if (!spec.optional()) {
        requiredNumerator = requiredNumerator.add(spec.weight().multiply(score, EngineMath.MC));
        requiredWeight = requiredWeight.add(spec.weight());
      }
    }

    BigDecimal requiredComposite =
        requiredWeight.signum() == 0
            ? BigDecimal.ZERO
            : requiredNumerator.divide(requiredWeight, EngineMath.MC);
    BigDecimal activationFloor =
        definition.scoring().threshold().subtract(definition.scoring().optionalGateMargin());
    boolean marginMet = requiredComposite.compareTo(activationFloor) >= 0;

    BigDecimal numerator = requiredNumerator;
    BigDecimal denominator = requiredWeight;
    List<ScoreBreakdown.IndicatorEntry> entries = new ArrayList<>(scored.size());
    for (Scored s : scored) {
      if (!s.spec().optional()) {
        entries.add(entry(s, true, ScoreBreakdown.ActivationReason.REQUIRED));
        continue;
      }
      boolean scoreOk =
          s.score() != null
              && s.score().compareTo(definition.scoring().optionalMinScore()) >= 0;
      if (scoreOk && marginMet) {
        numerator = numerator.add(s.spec().weight().multiply(s.score(), EngineMath.MC));
        denominator = denominator.add(s.spec().weight());
        entries.add(entry(s, true, ScoreBreakdown.ActivationReason.ACTIVATED));
      } else if (!scoreOk) {
        entries.add(entry(s, false, ScoreBreakdown.ActivationReason.SCORE_BELOW_MIN));
      } else {
        entries.add(entry(s, false, ScoreBreakdown.ActivationReason.MARGIN_NOT_MET));
      }
    }

    BigDecimal composite =
        denominator.signum() == 0
            ? BigDecimal.ZERO
            : numerator.divide(denominator, EngineMath.MC);
    return Optional.of(
        new Result(
            EngineMath.round(composite),
            EngineMath.round(requiredComposite),
            EngineMath.round(denominator),
            List.copyOf(entries)));
  }

  private static ScoreBreakdown.IndicatorEntry entry(
      Scored s, boolean activated, ScoreBreakdown.ActivationReason reason) {
    BigDecimal contribution =
        activated && s.score() != null
            ? EngineMath.round(s.spec().weight().multiply(s.score(), EngineMath.MC))
            : EngineMath.round(BigDecimal.ZERO);
    return new ScoreBreakdown.IndicatorEntry(
        s.spec().alias(),
        s.spec().name(),
        s.spec().timeframe(),
        s.score(),
        s.spec().weight(),
        contribution,
        s.spec().optional(),
        activated,
        reason,
        s.raw(),
        s.spec().params());
  }

  private record Scored(StrategyDefinition.IndicatorSpec spec, BigDecimal raw, BigDecimal score) {}
}
