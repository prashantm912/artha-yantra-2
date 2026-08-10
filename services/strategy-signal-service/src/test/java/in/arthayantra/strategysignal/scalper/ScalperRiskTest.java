package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyDefinition.ExitRuleSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * §0B hard-stop rule, hardened post-T21 (#990 round-3): a scalper must carry a bounding exit the
 * ENGINE can fire — a time_stop or an index-side stop_loss. A premium_pct stop is an option-leg
 * band (paper bracket path only) and does NOT count on its own.
 *
 * <p>⚠️ Unit-level only: these cases build {@link ExitRuleSpec} objects directly, so nothing here
 * touches validation and nothing here can notice {@code ScalperRisk} drifting apart from
 * {@code SemanticValidator}'s view of which bases may be declared on an options strategy. That
 * coupling is pinned by {@link ScalperStopBasisCouplingTest}, which drives real configs through the
 * publish-path validator — add a case THERE, not here, when a level basis changes meaning.
 */
class ScalperRiskTest {

  private static final ExitRuleSpec TIME = new ExitRuleSpec("time_stop", Map.of("max_bars", 10));
  private static final ExitRuleSpec PREMIUM_STOP =
      new ExitRuleSpec("stop_loss", Map.of("basis", "premium_pct", "value", 30));
  private static final ExitRuleSpec POINT_STOP =
      new ExitRuleSpec("stop_loss", Map.of("basis", "index_points", "value", 60));
  private static final ExitRuleSpec PERCENT_STOP =
      new ExitRuleSpec("stop_loss", Map.of("basis", "percent", "value", 1));
  private static final ExitRuleSpec ATR_STOP =
      new ExitRuleSpec("stop_loss", Map.of("basis", "atr_multiple", "value", 2));
  private static final ExitRuleSpec BASISLESS_STOP =
      new ExitRuleSpec("stop_loss", Map.of("value", 30));
  private static final ExitRuleSpec SIGNAL =
      new ExitRuleSpec("signal_exit", Map.of("rule", "close < vwap"));

  @Test
  void aTimeStopSatisfiesTheBoundingExitRule() {
    assertThat(ScalperRisk.hasBoundingExit(List.of(SIGNAL, TIME))).isTrue();
  }

  @Test
  void anEngineSideStopLossSatisfiesIt() {
    assertThat(ScalperRisk.hasBoundingExit(List.of(POINT_STOP))).isTrue();
    assertThat(ScalperRisk.hasBoundingExit(List.of(ATR_STOP))).isTrue();
  }

  @Test
  void aPremiumPctOnlyStopDoesNot() {
    // T21: premium_pct is enforced on the OPTION leg by the paper bracket path, which does not run
    // when a signal is not taken into paper — no engine-side floor, so it cannot bound on its own.
    assertThat(ScalperRisk.hasBoundingExit(List.of(PREMIUM_STOP))).isFalse();
    assertThat(ScalperRisk.hasBoundingExit(List.of(PREMIUM_STOP, SIGNAL))).isFalse();
    assertThat(ScalperRisk.hasBoundingExit(List.of(PREMIUM_STOP, TIME))).isTrue();
    assertThat(ScalperRisk.hasBoundingExit(List.of(PREMIUM_STOP, POINT_STOP))).isTrue();
  }

  @Test
  void aPercentOnlyStopDoesNot() {
    // `percent` names no plane on an options config, so SemanticValidator refuses it there (#1284)
    // and it is no longer counted here. NOT because it is mechanically inert: ExitEvaluator
    // computes `percent` and `premium_pct` with ONE shared entryPrice×value÷100 arm, so {percent,
    // 0.3} on NIFTY is ~72 index points and would fire normally. It is excluded so that widening
    // or relaxing that refusal cannot silently restore the §0B hole — a config whose only stop is
    // {percent, 25} loading as bounded with no enforceable stop on either plane. The two gates are
    // held together by ScalperStopBasisCouplingTest.
    assertThat(ScalperRisk.hasBoundingExit(List.of(PERCENT_STOP))).isFalse();
    assertThat(ScalperRisk.hasBoundingExit(List.of(PERCENT_STOP, SIGNAL))).isFalse();
    assertThat(ScalperRisk.hasBoundingExit(List.of(PERCENT_STOP, TIME))).isTrue();
  }

  @Test
  void anUnknownOrAbsentBasisStopDoesNot() {
    // ExitEvaluator.levelDistance nulls the distance for an unrecognized basis — the rule is inert.
    assertThat(ScalperRisk.hasBoundingExit(List.of(BASISLESS_STOP))).isFalse();
  }

  @Test
  void aStructuralExitAloneDoesNot() {
    assertThat(ScalperRisk.hasBoundingExit(List.of(SIGNAL))).isFalse();
    assertThat(ScalperRisk.hasBoundingExit(List.of())).isFalse();
  }
}
