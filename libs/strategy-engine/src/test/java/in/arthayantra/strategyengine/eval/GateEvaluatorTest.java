package in.arthayantra.strategyengine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.GateNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Gate-grammar truth tables: nesting, crossover boundaries, warm-up leaves, operand capture. */
class GateEvaluatorTest {

  @Test
  void allAnyNotNestingComposes() {
    GateNode gate =
        new GateNode.All(
            List.of(
                StrategyCompiler.compileLeafText("rsi > 50"),
                new GateNode.Any(
                    List.of(
                        StrategyCompiler.compileLeafText("adx > 90"),
                        StrategyCompiler.compileLeafText("close > vwap"))),
                new GateNode.Not(StrategyCompiler.compileLeafText("rsi > 75"))));
    BarValues values =
        new EvalFixtures.StubValues()
            .with("rsi", "60")
            .with("adx", "20")
            .withBuiltin("close", "101")
            .withBuiltin("vwap", "100.5");

    ScoreBreakdown.GateResult result = GateEvaluator.evaluate(gate, values, 5);

    assertThat(result.passed()).isTrue();
    assertThat(result.children()).hasSize(3);
    assertThat(result.children().get(1).kind()).isEqualTo("any");
    assertThat(result.children().get(1).children().get(0).passed()).isFalse();
    assertThat(result.children().get(2).kind()).isEqualTo("not");
  }

  @Test
  void crossoverFiresOnlyOnTheCrossingBar() {
    GateNode crossover = new GateNode.Crossover("fast", "slow");

    // previous bar at-or-below, now above -> fires
    BarValues crossing =
        new EvalFixtures.StubValues()
            .with("fast", "101").withPrevious("fast", "99")
            .with("slow", "100").withPrevious("slow", "100");
    assertThat(GateEvaluator.evaluate(crossover, crossing, 5).passed()).isTrue();

    // already above on both bars -> no new cross
    BarValues alreadyAbove =
        new EvalFixtures.StubValues()
            .with("fast", "102").withPrevious("fast", "101")
            .with("slow", "100").withPrevious("slow", "100");
    assertThat(GateEvaluator.evaluate(crossover, alreadyAbove, 5).passed()).isFalse();

    // touching previous bar (equal) then above -> fires (at-or-below leg)
    BarValues fromEqual =
        new EvalFixtures.StubValues()
            .with("fast", "101").withPrevious("fast", "100")
            .with("slow", "100").withPrevious("slow", "100");
    assertThat(GateEvaluator.evaluate(crossover, fromEqual, 5).passed()).isTrue();
  }

  @Test
  void crossunderMirrorsCrossover() {
    GateNode crossunder = new GateNode.Crossunder("fast", "slow");
    BarValues crossing =
        new EvalFixtures.StubValues()
            .with("fast", "99").withPrevious("fast", "100")
            .with("slow", "100").withPrevious("slow", "100");

    ScoreBreakdown.GateResult result = GateEvaluator.evaluate(crossunder, crossing, 5);

    assertThat(result.passed()).isTrue();
    assertThat(result.rule()).isEqualTo("crossunder(fast, slow)");
  }

  @Test
  void warmupOperandFailsItsLeafAndRecordsNull() {
    GateNode gate = StrategyCompiler.compileLeafText("rsi > 50");
    BarValues values = new EvalFixtures.StubValues().with("rsi", null);

    ScoreBreakdown.GateResult result = GateEvaluator.evaluate(gate, values, 5);

    assertThat(result.passed()).isFalse();
    assertThat(result.operands()).containsKey("rsi");
    assertThat(result.operands().get("rsi")).isNull();
  }

  @Test
  void leavesCaptureActualOperandValues() {
    GateNode gate = StrategyCompiler.compileLeafText("close > vwap");
    BarValues values =
        new EvalFixtures.StubValues().withBuiltin("close", "101.25").withBuiltin("vwap", "100.50");

    ScoreBreakdown.GateResult result = GateEvaluator.evaluate(gate, values, 5);

    assertThat(result.passed()).isTrue();
    assertThat(result.rule()).isEqualTo("close > vwap");
    assertThat(result.operands().get("close")).isEqualByComparingTo("101.25");
    assertThat(result.operands().get("vwap")).isEqualByComparingTo("100.50");
  }

  @Test
  void comparisonOperatorsBehave() {
    BarValues values = new EvalFixtures.StubValues().with("x", "10");

    assertThat(evaluate("x >= 10", values)).isTrue();
    assertThat(evaluate("x <= 10", values)).isTrue();
    assertThat(evaluate("x == 10", values)).isTrue();
    assertThat(evaluate("x != 10", values)).isFalse();
    assertThat(evaluate("x < 10", values)).isFalse();
    assertThat(evaluate("x > 9.99", values)).isTrue();
  }

  private static boolean evaluate(String leaf, BarValues values) {
    return GateEvaluator.evaluate(StrategyCompiler.compileLeafText(leaf), values, 0).passed();
  }
}
