package in.arthayantra.strategyengine.eval;

import in.arthayantra.strategyengine.config.GateNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bar-close gate evaluation (C-2.4). Every leaf records its actual operand values for the
 * reasoning checklist; an operand that is still warming up (null) fails its leaf - a signal can
 * never fire on unevaluated preconditions. Crossover semantics: fast above slow at this bar AND
 * fast at-or-below slow at the previous bar (both legs on the leaf's own mapped series).
 */
public final class GateEvaluator {

  private GateEvaluator() {}

  /** Evaluates a gate tree at a primary bar index. */
  public static ScoreBreakdown.GateResult evaluate(
      GateNode node, BarValues bank, int primaryIndex) {
    if (node instanceof GateNode.All all) {
      List<ScoreBreakdown.GateResult> children = new ArrayList<>(all.children().size());
      boolean passed = true;
      for (GateNode child : all.children()) {
        ScoreBreakdown.GateResult result = evaluate(child, bank, primaryIndex);
        children.add(result);
        passed &= result.passed();
      }
      return new ScoreBreakdown.GateResult("all", null, passed, Map.of(), List.copyOf(children));
    }
    if (node instanceof GateNode.Any any) {
      List<ScoreBreakdown.GateResult> children = new ArrayList<>(any.children().size());
      boolean passed = false;
      for (GateNode child : any.children()) {
        ScoreBreakdown.GateResult result = evaluate(child, bank, primaryIndex);
        children.add(result);
        passed |= result.passed();
      }
      return new ScoreBreakdown.GateResult("any", null, passed, Map.of(), List.copyOf(children));
    }
    if (node instanceof GateNode.Not not) {
      ScoreBreakdown.GateResult child = evaluate(not.child(), bank, primaryIndex);
      return new ScoreBreakdown.GateResult(
          "not", null, !child.passed(), Map.of(), List.of(child));
    }
    if (node instanceof GateNode.Crossover crossover) {
      return cross(bank, primaryIndex, crossover.fast(), crossover.slow(), true);
    }
    if (node instanceof GateNode.Crossunder crossunder) {
      return cross(bank, primaryIndex, crossunder.fast(), crossunder.slow(), false);
    }
    GateNode.Expression expr = (GateNode.Expression) node;
    return expression(bank, primaryIndex, expr);
  }

  private static ScoreBreakdown.GateResult cross(
      BarValues bank, int index, String fast, String slow, boolean over) {
    BigDecimal fastNow = operand(bank, fast, index);
    BigDecimal slowNow = operand(bank, slow, index);
    BigDecimal fastPrev = previousOperand(bank, fast, index);
    BigDecimal slowPrev = previousOperand(bank, slow, index);
    Map<String, BigDecimal> operands = new LinkedHashMap<>();
    operands.put(fast, fastNow);
    operands.put(slow, slowNow);
    boolean passed = false;
    if (fastNow != null && slowNow != null && fastPrev != null && slowPrev != null) {
      passed =
          over
              ? fastNow.compareTo(slowNow) > 0 && fastPrev.compareTo(slowPrev) <= 0
              : fastNow.compareTo(slowNow) < 0 && fastPrev.compareTo(slowPrev) >= 0;
    }
    String kind = over ? "crossover" : "crossunder";
    return new ScoreBreakdown.GateResult(
        kind, kind + "(" + fast + ", " + slow + ")", passed, operands, List.of());
  }

  private static ScoreBreakdown.GateResult expression(
      BarValues bank, int index, GateNode.Expression expr) {
    BigDecimal left = operand(bank, expr.left(), index);
    BigDecimal right =
        expr.rightLiteral() != null
            ? expr.rightLiteral()
            : operand(bank, expr.rightOperand(), index);
    Map<String, BigDecimal> operands = new LinkedHashMap<>();
    operands.put(expr.left(), left);
    if (expr.rightOperand() != null) {
      operands.put(expr.rightOperand(), right);
    }
    boolean passed = false;
    if (left != null && right != null) {
      int cmp = left.compareTo(right);
      passed =
          switch (expr.op()) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            case EQ -> cmp == 0;
            case NEQ -> cmp != 0;
          };
    }
    String rule =
        expr.left()
            + " "
            + expr.op().symbol()
            + " "
            + (expr.rightOperand() != null
                ? expr.rightOperand()
                : expr.rightLiteral().toPlainString());
    return new ScoreBreakdown.GateResult("expression", rule, passed, operands, List.of());
  }

  private static BigDecimal operand(BarValues bank, String name, int index) {
    return BarValues.isBuiltin(name) ? bank.builtin(name, index) : bank.valueAt(name, index);
  }

  private static BigDecimal previousOperand(BarValues bank, String name, int index) {
    if (BarValues.isBuiltin(name)) {
      return index < 1 ? null : bank.builtin(name, index - 1);
    }
    return bank.previousValueAt(name, index);
  }
}
