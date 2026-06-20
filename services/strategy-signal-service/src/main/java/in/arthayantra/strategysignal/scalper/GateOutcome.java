package in.arthayantra.strategysignal.scalper;

import java.math.BigDecimal;

/**
 * One §0B gate's verdict. {@code reason} rides the signal side-channel (never the frozen
 * {@code ScoreBreakdown}); {@code operand} is the value the gate tested (nullable for enum-/time-only
 * gates).
 */
public record GateOutcome(boolean pass, BigDecimal operand, String reason) {

  /** A passing verdict carrying the tested operand and a human reason. */
  public static GateOutcome pass(BigDecimal operand, String reason) {
    return new GateOutcome(true, operand, reason);
  }

  /** A failing verdict carrying the tested operand and a human reason. */
  public static GateOutcome fail(BigDecimal operand, String reason) {
    return new GateOutcome(false, operand, reason);
  }
}
