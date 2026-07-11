package in.arthayantra.strategyengine.eval;

import in.arthayantra.strategyengine.config.GateNode;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.normalize.NormalizeSpec;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared Phase 20 test scaffolding: stub BarValues + compact definition builders. */
final class EvalFixtures {

  private EvalFixtures() {}

  /** Map-backed BarValues; previous values default to current unless set. */
  static final class StubValues implements BarValues {
    private final Map<String, BigDecimal> current = new HashMap<>();
    private final Map<String, BigDecimal> previous = new HashMap<>();
    private final Map<String, BigDecimal> builtins = new HashMap<>();

    StubValues with(String alias, String value) {
      current.put(alias, value == null ? null : new BigDecimal(value));
      return this;
    }

    StubValues withPrevious(String alias, String value) {
      previous.put(alias, value == null ? null : new BigDecimal(value));
      return this;
    }

    StubValues withBuiltin(String name, String value) {
      builtins.put(name, new BigDecimal(value));
      return this;
    }

    @Override
    public BigDecimal valueAt(String alias, int primaryIndex) {
      return current.get(alias);
    }

    @Override
    public BigDecimal previousValueAt(String alias, int primaryIndex) {
      return previous.containsKey(alias) ? previous.get(alias) : current.get(alias);
    }

    @Override
    public BigDecimal builtin(String name, int primaryIndex) {
      return builtins.get(name);
    }
  }

  /** A scoring indicator whose linear(0,1) normalize makes score == clamped raw value. */
  static StrategyDefinition.IndicatorSpec scoring(
      String alias, String weight, boolean optional) {
    return new StrategyDefinition.IndicatorSpec(
        "RSI",
        alias,
        "1m",
        Map.of(),
        new BigDecimal(weight),
        optional,
        NormalizeSpec.linear(BigDecimal.ZERO, BigDecimal.ONE),
        null);
  }

  /** A gate-only indicator (no normalize - excluded from the composite). */
  static StrategyDefinition.IndicatorSpec gateOnly(String alias) {
    return new StrategyDefinition.IndicatorSpec(
        "EMA", alias, "1m", Map.of(), BigDecimal.ONE, false, null, null);
  }

  /** A definition with the given indicators/scoring and a trivially-true gate. */
  static StrategyDefinition definition(
      List<StrategyDefinition.IndicatorSpec> indicators,
      String threshold,
      String optionalMinScore,
      String optionalGateMargin) {
    return new StrategyDefinition(
        "test-strategy",
        "1.0.0",
        "1m",
        List.of(),
        indicators,
        StrategyDefinition.Direction.LONG,
        new GateNode.Expression(
            "close", GateNode.Comparison.GT, null, new BigDecimal("-1")),
        new StrategyDefinition.Scoring(
            new BigDecimal(threshold),
            new BigDecimal(optionalMinScore),
            new BigDecimal(optionalGateMargin)),
        List.of(),
        new StrategyDefinition.SizingSpec("fixed_quantity", Map.of("quantity", 1L)),
        new StrategyDefinition.Session(
            "intraday", null, null, null, "15:20", "next_open", false, null, null, null, null));
  }
}
