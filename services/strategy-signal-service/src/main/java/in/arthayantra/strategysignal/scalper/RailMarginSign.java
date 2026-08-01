package in.arthayantra.strategysignal.scalper;

/**
 * The sign a confluence-gate rail's recorded {@code margin = operand − threshold} must carry on a
 * BLOCKED check, as implied by the rail's OWN comparison operator (G17 / T14, signal-analysis
 * 2026-07-20 §6.3 refined 2026-07-23 §2.3). A floor rail (pass ⟺ operand at/above the threshold)
 * can only block with a negative margin; a ceiling rail (pass ⟺ operand at/below) only with a
 * positive one — so a persisted first-block whose margin sits STRICTLY on the passing side of its
 * rail's operator is self-contradictory (the row claims a block the operand did not justify).
 *
 * <p>{@link RailMarginSigns} is the registry; {@code RailMarginSignTableTest} derives every
 * declared sign from the gate's own operator by probing the real {@link ScalperGates} function on
 * both sides of its threshold, and source-scans {@code ScalperConfluenceGate} so a new rail cannot
 * ship undeclared. The invariant is DIAGNOSTIC ONLY — checked at the {@code signal_rejections}
 * persist seam ({@code RejectionWriter}), where a contradiction is counted + WARNed, never thrown.
 */
public enum RailMarginSign {
  /** Floor-type operator: a blocked check's margin is negative (operand fell short). */
  NEGATIVE_WHEN_BLOCKED,
  /** Ceiling/inverted operator (e.g. vwap-distance): a blocked check's margin is positive. */
  POSITIVE_WHEN_BLOCKED,
  /**
   * No single blocked-margin sign exists: the rail records no scalar threshold at the seam (margin
   * is always null), its direction flips with the option side (fii-bias, pct-price-move), or the
   * verdict is a conjunction where a non-operand leg can block with the operand clear
   * (oi-divergence-magnitude). The invariant is not evaluable from the persisted row alone.
   */
  UNSIGNED
}
