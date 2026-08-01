package in.arthayantra.strategysignal.scalper;

import java.math.BigDecimal;

/**
 * One §0B gate's verdict. {@code reason} rides the signal side-channel (never the frozen
 * {@code ScoreBreakdown}); {@code operand} is the value the gate tested (nullable for enum-/time-only
 * gates); {@code marginSign} is the sign a BLOCKED {@code operand − threshold} carries under this
 * gate's own comparison (G17/T14) — declared BY the gate that did the comparing, so the rejection
 * seam's self-contradiction check is DERIVED from the operator instead of from a hand-kept
 * name→sign table that can drift out of step with the wiring.
 *
 * <p>Defaults to {@link RailMarginSign#UNSIGNED}: a gate that says nothing asserts nothing, so a
 * new or unconsidered gate can never produce a false accusation. A gate whose direction depends on
 * runtime CONFIG must compute the sign from that config — see {@link ScalperGates#vwapDistance},
 * a ceiling normally but UNSIGNED once its min clause is armed and the rail becomes two-bound.
 */
public record GateOutcome(
    boolean pass, BigDecimal operand, String reason, RailMarginSign marginSign) {

  /** The pre-G17 3-arg form: {@code marginSign} defaults to UNSIGNED (asserts nothing). */
  public GateOutcome(boolean pass, BigDecimal operand, String reason) {
    this(pass, operand, reason, RailMarginSign.UNSIGNED);
  }

  /** A passing verdict carrying the tested operand and a human reason. */
  public static GateOutcome pass(BigDecimal operand, String reason) {
    return new GateOutcome(true, operand, reason);
  }

  /** A failing verdict carrying the tested operand and a human reason. */
  public static GateOutcome fail(BigDecimal operand, String reason) {
    return new GateOutcome(false, operand, reason);
  }

  /** This verdict with its comparison direction declared (G17/T14). */
  public GateOutcome signed(RailMarginSign sign) {
    return new GateOutcome(pass, operand, reason, sign);
  }
}
