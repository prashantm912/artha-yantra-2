package in.arthayantra.strategysignal.scalper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * E8 §3.2 probability-graded position sizing (master plan §2.14 r65 / §3.9 S21(d): "full lot when the
 * confluence aligns, a reduced lot when it is weak / opposing / neutral"). A pure advisory multiplier
 * in {@code [SIZE_FLOOR, 1.0]} applied to the already-advisory {@code suggested_qty} — it NEVER changes
 * which signal fires, only how large the stamped size is, so it is parity-irrelevant ({@code [S]}). It
 * defaults to {@code 1.0} (a null aggregate, or one at/above the full cut) so the stamped qty stays
 * byte-identical to today's flat budget until a weak-vs-strong confluence spread is present.
 *
 * <p>The grading knobs are static constants (DB-promotable later, the same convention the
 * {@link ScalperGates} thresholds use). v1 grades ONLY off the confluence aggregate — the OI-gap and
 * VIX factors (§2.14 r65) need the per-bar imbalance%/VIX surfaced onto the {@code Decision}, deferred
 * to a follow-up so this PR carries no record-constructor fan-out.
 */
public final class ScalperSizing {

  private ScalperSizing() {}

  // FULL size (1.0) at/above this confluence aggregate; the floor at/below the entry threshold; a
  // linear taper between. The deck states "full aligned / reduced weak" with no exact curve — 0.75
  // full, 0.60 threshold, 0.50 floor is a conservative v1, forward-paper-/optimizer-tunable.
  static final BigDecimal SIZE_FULL_AGGREGATE = new BigDecimal("0.75");
  static final BigDecimal SIZE_THRESHOLD = new BigDecimal("0.60");
  static final BigDecimal SIZE_FLOOR = new BigDecimal("0.50");

  /**
   * The confluence-graded size multiplier in {@code [SIZE_FLOOR, 1.0]}: FULL (1.0) at/above
   * {@link #SIZE_FULL_AGGREGATE}, {@link #SIZE_FLOOR} at/below {@link #SIZE_THRESHOLD}, a linear taper
   * between. A null aggregate returns {@code 1.0} (no grading → byte-identical to the flat budget).
   */
  public static BigDecimal sizeMultiplier(BigDecimal aggregate) {
    if (aggregate == null || aggregate.compareTo(SIZE_FULL_AGGREGATE) >= 0) {
      return BigDecimal.ONE;
    }
    if (aggregate.compareTo(SIZE_THRESHOLD) <= 0) {
      return SIZE_FLOOR;
    }
    BigDecimal span = SIZE_FULL_AGGREGATE.subtract(SIZE_THRESHOLD);
    BigDecimal frac = aggregate.subtract(SIZE_THRESHOLD).divide(span, 6, RoundingMode.HALF_UP);
    return SIZE_FLOOR.add(BigDecimal.ONE.subtract(SIZE_FLOOR).multiply(frac));
  }
}
