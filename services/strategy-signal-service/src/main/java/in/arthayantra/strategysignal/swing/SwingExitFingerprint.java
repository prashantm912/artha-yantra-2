package in.arthayantra.strategysignal.swing;

import in.arthayantra.strategyengine.config.GateNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.BarValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything the exit decision reads, in a form two strategy versions can be compared on.
 *
 * <p><b>Why not just {@code exitRules}.</b> Comparing the rule specs alone has a real
 * FALSE-NEGATIVE, and it lands on the book most likely to hit it. Minervini's trail is {@code
 * {trailing_stop, basis: indicator, alias: sma50}} — textually identical across all four of its
 * strategies, while the exit LEVEL is whatever each version declares {@code sma50} to be ({@code
 * ExitEvaluator#indicatorLevel} → {@code bank.valueAt(alias)}). Change an indicator's period,
 * timeframe or instrument and the exit genuinely moves while the rule records stay equal. Operands
 * named inside a {@code signal_exit} rule STRING resolve the same way, so {@code crossunder(ema20,
 * ema50)} pulls {@code ema50}'s declaration in exactly as an {@code alias} field would.
 *
 * <p><b>⚠️ This mirrors {@code SwingFamilyExitDoctrineTest#exitFingerprint}, which is the canonical
 * written definition of "everything the exit reads" and carries the full reasoning — including the
 * measurement that {@code ExitEvaluator} reads 19 exit-rule param keys and that only {@code alias}
 * and {@code rule} can name an indicator.</b> The two are deliberately NOT shared code: that one
 * fingerprints the raw YAML {@code JsonNode} (it guards what is authored), this one fingerprints the
 * compiled {@link StrategyDefinition} (it guards what a live anchor resolves to), and forcing one
 * representation on both would mean either re-parsing config the engine has already compiled or
 * destabilising a load-bearing guard to suit a detector. They can drift; if you change what the exit
 * reads, change both, and read that javadoc first — it is the authority.
 *
 * <p>Only the indicator fields {@code IndicatorBank} computes a VALUE from are folded in ({@code
 * name}, {@code timeframe}, {@code params}, {@code instrument}). {@code weight}, {@code optional} and
 * {@code normalize} feed entry SCORING and never an exit level, so including them would report a
 * divergence on a legitimate entry-only tune — the false-alarm direction this whole comparison exists
 * to avoid.
 */
record SwingExitFingerprint(
    List<StrategyDefinition.ExitRuleSpec> rules, Map<String, Operand> operands) {

  /**
   * One indicator declaration as the exit evaluator would resolve it. An operand with no matching
   * declaration keeps an all-null entry rather than being dropped: "declared here, absent there" is
   * itself a divergence, and dropping it would silently equate the two.
   */
  record Operand(
      String name,
      String timeframe,
      Map<String, Object> params,
      StrategyDefinition.InstrumentRef instrument) {}

  static SwingExitFingerprint of(StrategyDefinition definition) {
    Set<String> names = new TreeSet<>(); // sorted → stable regardless of rule order
    for (StrategyDefinition.ExitRuleSpec rule : definition.exitRules()) {
      Object alias = rule.params().get("alias");
      if (alias instanceof String text) {
        names.add(text);
      }
      Object ruleText = rule.params().get("rule");
      if (ruleText instanceof String text) {
        collectOperands(StrategyCompiler.compileLeafText(text), names);
      }
    }

    Map<String, Operand> operands = new LinkedHashMap<>();
    for (String name : names) {
      if (BarValues.isBuiltin(name)) {
        continue; // resolves via bank.builtin and shadows any same-named declaration
      }
      operands.put(name, declarationOf(definition, name));
    }
    return new SwingExitFingerprint(definition.exitRules(), Map.copyOf(operands));
  }

  private static Operand declarationOf(StrategyDefinition definition, String alias) {
    for (StrategyDefinition.IndicatorSpec indicator : definition.indicators()) {
      if (alias.equals(indicator.alias())) {
        return new Operand(
            indicator.name(), indicator.timeframe(), indicator.params(), indicator.instrument());
      }
    }
    return new Operand(null, null, null, null);
  }

  /**
   * Every operand name a compiled exit expression resolves against the bank.
   *
   * <p>No {@code default} branch on purpose: {@link GateNode} is sealed, so adding a variant makes
   * this switch fail to COMPILE rather than silently skip the new node's operands at runtime.
   */
  private static void collectOperands(GateNode node, Set<String> into) {
    switch (node) {
      case GateNode.All all -> all.children().forEach(c -> collectOperands(c, into));
      case GateNode.Any any -> any.children().forEach(c -> collectOperands(c, into));
      case GateNode.Not not -> collectOperands(not.child(), into);
      case GateNode.Crossover cross -> {
        into.add(cross.fast());
        into.add(cross.slow());
      }
      case GateNode.Crossunder cross -> {
        into.add(cross.fast());
        into.add(cross.slow());
      }
      case GateNode.Expression expression -> {
        into.add(expression.left());
        if (expression.rightOperand() != null) {
          into.add(expression.rightOperand()); // null ⇒ a numeric literal, nothing to resolve
        }
      }
    }
  }
}
