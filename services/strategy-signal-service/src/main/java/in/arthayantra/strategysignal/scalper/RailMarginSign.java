package in.arthayantra.strategysignal.scalper;

import java.math.BigDecimal;

/**
 * The sign a confluence-gate rail's recorded {@code margin = operand − threshold} must carry on a
 * BLOCKED check, as implied by the rail's OWN comparison operator (G17 / T14, signal-analysis
 * 2026-07-20 §6.3 refined 2026-07-23 §2.3). A floor rail (pass ⟺ operand at/above the threshold)
 * can only block with a negative margin; a ceiling rail (pass ⟺ operand at/below) only with a
 * positive one — so a persisted first-block whose margin sits STRICTLY on the passing side of its
 * rail's operator is self-contradictory (the row claims a block the operand did not justify).
 *
 * <p>The value is set BY the gate function that performed the comparison and travels on
 * {@link GateOutcome} → the rejection diagnostic → the persist seam, so the invariant is DERIVED
 * from the wiring. Review round 1 rejected the first design — a static rail-name→sign table — for
 * exactly the drift this row exists to eliminate: it could not see that arming
 * {@code vwap_distance_min_frac} turns vwap-distance into a two-bound band whose legitimate
 * too-close blocks carry a NEGATIVE margin, so the diagnostic would have cried wolf on correct
 * behaviour. A config-dependent gate now computes its own sign per evaluation.
 *
 * <p>DIAGNOSTIC ONLY — consumed at the {@code signal_rejections} persist seam
 * ({@code RejectionWriter}), where a contradiction is counted + WARNed, never thrown.
 */
public enum RailMarginSign {
  /** Floor-type operator: a blocked check's margin is negative (operand fell short). */
  NEGATIVE_WHEN_BLOCKED,
  /** Ceiling/inverted operator (e.g. an unarmed vwap-distance): blocked margin is positive. */
  POSITIVE_WHEN_BLOCKED,
  /**
   * No single blocked-margin sign exists, so nothing is asserted. The rail records no scalar
   * threshold at the seam (margin always null); or its direction flips with the option side
   * (fii-bias, pct-price-move); or the verdict is a conjunction where a non-operand leg can block
   * with the operand clear (oi-divergence-magnitude); or it is two-bound at this configuration
   * (vwap-distance with its min clause armed). Also the DEFAULT for any gate that declares
   * nothing — silence must never become an accusation.
   */
  UNSIGNED;

  /**
   * True when a persisted first-block would contradict its own rail's operator: the blocked margin
   * sits STRICTLY on the passing side (floor rail with margin &gt; 0, ceiling rail with margin
   * &lt; 0). A null margin or an {@link #UNSIGNED} rail is never a contradiction; zero is never
   * flagged, because strict operators legitimately block AT the threshold (breadth is
   * {@code advances > 32} and G16 measured a session whose max was exactly 32). The failure
   * direction is deliberately a false PASS of the invariant, never a false accusation.
   */
  public static boolean contradicts(RailMarginSign sign, BigDecimal blockedMargin) {
    if (sign == null || blockedMargin == null) {
      return false;
    }
    return switch (sign) {
      case NEGATIVE_WHEN_BLOCKED -> blockedMargin.signum() > 0;
      case POSITIVE_WHEN_BLOCKED -> blockedMargin.signum() < 0;
      case UNSIGNED -> false;
    };
  }
}
