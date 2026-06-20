package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyDefinition.ExitRuleSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** §0B hard-stop rule — a scalper must carry a fixed SL or a time-stop, not just a structural exit. */
class ScalperRiskTest {

  private static final ExitRuleSpec TIME = new ExitRuleSpec("time_stop", Map.of("max_bars", 10));
  private static final ExitRuleSpec STOP =
      new ExitRuleSpec("stop_loss", Map.of("basis", "premium_pct", "value", 30));
  private static final ExitRuleSpec SIGNAL =
      new ExitRuleSpec("signal_exit", Map.of("rule", "close < vwap"));

  @Test
  void aTimeStopSatisfiesTheBoundingExitRule() {
    assertThat(ScalperRisk.hasBoundingExit(List.of(SIGNAL, TIME))).isTrue();
  }

  @Test
  void aFixedStopLossSatisfiesIt() {
    assertThat(ScalperRisk.hasBoundingExit(List.of(STOP))).isTrue();
  }

  @Test
  void aStructuralExitAloneDoesNot() {
    assertThat(ScalperRisk.hasBoundingExit(List.of(SIGNAL))).isFalse();
    assertThat(ScalperRisk.hasBoundingExit(List.of())).isFalse();
  }
}
