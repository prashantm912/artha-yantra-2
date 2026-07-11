package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.config.StrategyDefinition.Session;
import in.arthayantra.strategyengine.fills.TouchBasis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The A9 [FP-5] touch-basis acceptance: an exit_intrabar strategy run with 1m coverage records
 * INTRABAR_1M; the same run with the 1m slice withheld falls back to BAR_HL_WORSTOF; a plain
 * primary-close exit is CLOSE_EVAL.
 */
class TouchBasisClassifierTest {

  private static StrategyDefinition def(String primaryTimeframe, boolean exitIntrabar) {
    return new StrategyDefinition(
        "demo",
        "1.0.0",
        primaryTimeframe,
        List.of(),
        List.of(),
        StrategyDefinition.Direction.LONG,
        null,
        null,
        List.of(),
        null,
        new Session(
            "intraday", null, null, null, "15:20", null, exitIntrabar, null, null, null, null));
  }

  @Test
  void intrabarWith1mCoverageIsIntrabar1m() {
    assertThat(TouchBasisClassifier.classify(def("5m", true), true)).isEqualTo(TouchBasis.INTRABAR_1M);
  }

  @Test
  void intrabarWithoutCoverageFallsBackToWorstOf() {
    assertThat(TouchBasisClassifier.classify(def("5m", true), false))
        .isEqualTo(TouchBasis.BAR_HL_WORSTOF);
  }

  @Test
  void primaryCloseExitIsCloseEval() {
    assertThat(TouchBasisClassifier.classify(def("5m", false), true)).isEqualTo(TouchBasis.CLOSE_EVAL);
    // exit_intrabar is a no-op on a 1m primary (entry/exit already at the 1m floor)
    assertThat(TouchBasisClassifier.classify(def("1m", true), true)).isEqualTo(TouchBasis.CLOSE_EVAL);
  }
}
