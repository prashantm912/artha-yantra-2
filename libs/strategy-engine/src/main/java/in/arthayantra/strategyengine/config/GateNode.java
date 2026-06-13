package in.arthayantra.strategyengine.config;

import java.math.BigDecimal;
import java.util.List;

/**
 * The compiled gate grammar (§C-2.4): {@code all}/{@code any}/{@code not} nesting over typed
 * crossover/crossunder leaves and threshold expressions — comparison operators only, no
 * arithmetic (validated upstream by strategy-schema).
 */
public sealed interface GateNode {

  /** Conjunction. */
  record All(List<GateNode> children) implements GateNode {}

  /** Disjunction. */
  record Any(List<GateNode> children) implements GateNode {}

  /** Negation. */
  record Not(GateNode child) implements GateNode {}

  /** Typed crossover: fast crossed ABOVE slow at this bar. */
  record Crossover(String fast, String slow) implements GateNode {}

  /** Typed crossunder: fast crossed BELOW slow at this bar. */
  record Crossunder(String fast, String slow) implements GateNode {}

  /**
   * Threshold expression leaf: {@code left op right}; right is either an operand name or a
   * numeric literal (exactly one of {@code rightOperand}/{@code rightLiteral} is set).
   */
  record Expression(String left, Comparison op, String rightOperand, BigDecimal rightLiteral)
      implements GateNode {}

  /** Comparison operators of the closed grammar. */
  enum Comparison {
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    EQ("=="),
    NEQ("!=");

    private final String symbol;

    Comparison(String symbol) {
      this.symbol = symbol;
    }

    /** The source symbol. */
    public String symbol() {
      return symbol;
    }

    /** Parses a source symbol. */
    public static Comparison of(String symbol) {
      for (Comparison c : values()) {
        if (c.symbol.equals(symbol)) {
          return c;
        }
      }
      throw new IllegalArgumentException("unknown comparison '" + symbol + "'");
    }
  }
}
