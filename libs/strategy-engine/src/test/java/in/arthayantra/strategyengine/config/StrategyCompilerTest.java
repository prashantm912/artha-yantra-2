package in.arthayantra.strategyengine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategyschema.StrategyDocuments;
import org.junit.jupiter.api.Test;

/** Canonical tree → StrategyDefinition: shapes, A1/A7 defaults, gate compilation. */
class StrategyCompilerTest {

  private static final String BTST_YAML =
      """
      schema: strategy-schema/v1
      id: compiler-btst
      name: "Compiler BTST"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: RELIANCE }
      timeframes: { primary: 1d, additional: [1m] }
      indicators:
        - { name: EMA, alias: ema20, timeframe: 1d, params: { period: 20 }, weight: 1.0 }
        - { name: RSI, alias: rsi14, timeframe: 1d, params: { period: 14 }, weight: 0.8,
            normalize: { type: linear, from: 40, to: 65 } }
      entry_rules:
        direction: long
        gate:
          all:
            - "close > ema20"
            - not: "rsi14 > 75"
            - any:
                - crossover: { fast: ema20, slow: rsi14 }
                - "rsi14 >= 55"
        scoring: { threshold: 0.6 }
      exit_rules:
        - { type: stop_loss, params: { basis: atr_multiple, value: 2.0, atr_period: 14 } }
        - { type: signal_exit, params: { rule: "crossunder(ema20, rsi14)" } }
      risk:
        position_sizing: { method: percent_equity, params: { percent: 10 } }
        max_positions: 2
        session: { style: btst }
      """;

  @Test
  void compilesShapesAndA7Defaults() {
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(BTST_YAML).config());

    assertThat(definition.id()).isEqualTo("compiler-btst");
    assertThat(definition.primaryTimeframe()).isEqualTo("1d");
    assertThat(definition.additionalTimeframes()).containsExactly("1m");
    assertThat(definition.direction()).isEqualTo(StrategyDefinition.Direction.LONG);

    // A1 scoring defaults
    assertThat(definition.scoring().optionalMinScore()).isEqualByComparingTo("0.6");
    assertThat(definition.scoring().optionalGateMargin()).isEqualByComparingTo("0.15");

    // A7 session defaults: btst -> at_close; pre_close_at 15:20; primary 1d -> exit_intrabar
    assertThat(definition.session().style()).isEqualTo("btst");
    assertThat(definition.session().fillTiming()).isEqualTo("at_close");
    assertThat(definition.session().preCloseAt()).isEqualTo("15:20");
    assertThat(definition.session().exitIntrabar()).isTrue();

    // indicator typing
    assertThat(definition.indicators()).hasSize(2);
    assertThat(definition.indicators().get(0).scoring()).isFalse();
    assertThat(definition.indicators().get(1).scoring()).isTrue();
    assertThat(definition.indicators().get(1).params()).containsEntry("period", 14L);
  }

  @Test
  void compilesNestedGateTree() {
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(BTST_YAML).config());

    GateNode.All root = (GateNode.All) definition.gate();
    assertThat(root.children()).hasSize(3);
    assertThat(root.children().get(0)).isInstanceOf(GateNode.Expression.class);
    assertThat(root.children().get(1)).isInstanceOf(GateNode.Not.class);
    GateNode.Any any = (GateNode.Any) root.children().get(2);
    assertThat(any.children().get(0)).isInstanceOf(GateNode.Crossover.class);

    GateNode.Expression first = (GateNode.Expression) root.children().get(0);
    assertThat(first.left()).isEqualTo("close");
    assertThat(first.op()).isEqualTo(GateNode.Comparison.GT);
    assertThat(first.rightOperand()).isEqualTo("ema20");
  }

  @Test
  void compilesSignalExitLeafText() {
    GateNode node = StrategyCompiler.compileLeafText("crossunder(ema20, rsi14)");
    assertThat(node).isInstanceOf(GateNode.Crossunder.class);

    GateNode expr = StrategyCompiler.compileLeafText("rsi14 < 40.5");
    GateNode.Expression e = (GateNode.Expression) expr;
    assertThat(e.rightLiteral()).isEqualByComparingTo("40.5");
    assertThat(e.rightOperand()).isNull();
  }

  @Test
  void touchBasisDefaultsAbsentAndParsesTheOptIn() {
    // absent → null (the byte-identical close-basis default)
    StrategyDefinition plain =
        StrategyCompiler.compile(StrategyDocuments.parse(BTST_YAML).config());
    assertThat(plain.session().touchBasis()).isNull();

    // opt-in → carried verbatim on the compiled session (P1-10)
    String optIn =
        BTST_YAML.replace(
            "session: { style: btst }",
            "session: { style: btst, touch_basis: bar_hl_worstof }");
    StrategyDefinition worstOf =
        StrategyCompiler.compile(StrategyDocuments.parse(optIn).config());
    assertThat(worstOf.session().touchBasis()).isEqualTo("bar_hl_worstof");
  }

  @Test
  void touchBasisRejectsAnUnknownValueAtCompile() {
    String bad =
        BTST_YAML.replace(
            "session: { style: btst }", "session: { style: btst, touch_basis: bar_close }");
    assertThatThrownBy(() -> StrategyCompiler.compile(StrategyDocuments.parse(bad).config()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("touch_basis");
  }

  @Test
  void intradayDefaultsDifferFromBtst() {
    String intraday = BTST_YAML.replace("style: btst", "style: intraday")
        .replace("primary: 1d", "primary: 1m");
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(intraday).config());

    assertThat(definition.session().fillTiming()).isEqualTo("next_open");
    assertThat(definition.session().exitIntrabar())
        .as("1m primary defaults exit_intrabar to false (already at the floor)")
        .isFalse();
  }
}
