package in.arthayantra.backtest.replay;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.fills.TouchBasis;

/**
 * Classifies how a replay exit level was detected (A9 [FP-5]) — the value persisted on every closed
 * trade. The parity floor is the closed 1m bar: when {@code exit_intrabar} drills into 1m bars it is
 * {@link TouchBasis#INTRABAR_1M}; where 1m coverage is missing it falls back to the primary-bar
 * high/low worst-of ({@link TouchBasis#BAR_HL_WORSTOF}); otherwise the exit is a primary-bar close
 * evaluation ({@link TouchBasis#CLOSE_EVAL}).
 */
public final class TouchBasisClassifier {

  private TouchBasisClassifier() {}

  /** Classifies by the strategy's exit mode and whether 1m coverage backed the exit window. */
  public static TouchBasis classify(StrategyDefinition definition, boolean oneMinuteCovered) {
    boolean intrabar =
        definition.session().exitIntrabar() && !"1m".equals(definition.primaryTimeframe());
    if (!intrabar) {
      return TouchBasis.CLOSE_EVAL;
    }
    return oneMinuteCovered ? TouchBasis.INTRABAR_1M : TouchBasis.BAR_HL_WORSTOF;
  }
}
