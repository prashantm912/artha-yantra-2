package in.arthayantra.strategyengine.eval;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import java.util.Optional;

/**
 * Flow-5 entry evaluation at one primary bar close: gates first, then the A1 composite. Entry
 * fires when ALL gates pass AND composite &gt;= threshold; the full breakdown is returned either
 * way (the reasoning panel shows near-misses), but only when the bar is scoreable.
 */
public final class EntryEvaluator {

  /** One evaluated bar. */
  public record Evaluation(boolean entry, ScoreBreakdown breakdown) {}

  private EntryEvaluator() {}

  /** Evaluates entry at a primary bar; empty while required indicators warm up. */
  public static Optional<Evaluation> evaluate(
      StrategyDefinition definition, BarValues bank, int primaryIndex) {
    Optional<CompositeScorer.Result> scored =
        CompositeScorer.score(definition, bank, primaryIndex);
    if (scored.isEmpty()) {
      return Optional.empty();
    }
    ScoreBreakdown.GateResult gate =
        GateEvaluator.evaluate(definition.gate(), bank, primaryIndex);
    CompositeScorer.Result result = scored.get();
    boolean thresholdMet =
        result.composite().compareTo(definition.scoring().threshold()) >= 0;
    boolean entry = gate.passed() && thresholdMet;
    ScoreBreakdown breakdown =
        new ScoreBreakdown(
            result.composite(),
            definition.scoring().threshold(),
            entry,
            result.requiredComposite(),
            definition.scoring().optionalMinScore(),
            definition.scoring().optionalGateMargin(),
            result.weightDenominator(),
            gate,
            result.indicators());
    return Optional.of(new Evaluation(entry, breakdown));
  }
}
