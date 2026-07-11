package in.arthayantra.backtest.replay;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.fills.TouchBasis;

/**
 * Classifies how a replay exit level was detected (A9 [FP-5]) — the value persisted on every closed
 * trade. The parity floor is the closed 1m bar. {@code oneMinuteCovered=false} is the opt-in
 * {@code session.touch_basis: bar_hl_worstof} model (B3 / P1-10): the exit resolves on the bar's
 * high/low worst-of ({@link TouchBasis#BAR_HL_WORSTOF}), so it takes precedence over the exit mode.
 * Otherwise, when {@code exit_intrabar} drills into 1m bars it is {@link TouchBasis#INTRABAR_1M};
 * a plain primary-bar close exit is {@link TouchBasis#CLOSE_EVAL}.
 */
public final class TouchBasisClassifier {

  private TouchBasisClassifier() {}

  /** Classifies by the strategy's exit mode and whether 1m coverage backed the exit window. */
  public static TouchBasis classify(StrategyDefinition definition, boolean oneMinuteCovered) {
    if (!oneMinuteCovered) {
      return TouchBasis.BAR_HL_WORSTOF; // touch_basis: bar_hl_worstof — H/L worst-of on the eval bar
    }
    boolean intrabar =
        definition.session().exitIntrabar() && !"1m".equals(definition.primaryTimeframe());
    return intrabar ? TouchBasis.INTRABAR_1M : TouchBasis.CLOSE_EVAL;
  }
}
