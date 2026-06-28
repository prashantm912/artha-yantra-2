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

  // E8 §2.14 r65 "size to the Trending-OI gap": below this call-put OI imbalance % the gap is thin (no
  // conviction) and the size is TRIMMED by OI_GAP_THIN_FACTOR; at/above it the gap is wide → full. 50
  // mirrors the documented imbalance band (crossFilterPct); 0.85 is a gentle v1 trim, DB-tunable later.
  static final BigDecimal OI_GAP_BAND = new BigDecimal("50");
  static final BigDecimal OI_GAP_THIN_FACTOR = new BigDecimal("0.85");

  /**
   * The confluence-only size multiplier (the §3.9 S21(d) base term) — see
   * {@link #sizeMultiplier(BigDecimal, BigDecimal)} with a null OI imbalance. A null aggregate → 1.0.
   */
  public static BigDecimal sizeMultiplier(BigDecimal aggregate) {
    return sizeMultiplier(aggregate, null);
  }

  /**
   * The probability-graded size multiplier in {@code [SIZE_FLOOR, 1.0]} = the confluence base term
   * (§3.9 S21(d)) × the OI-gap factor (§2.14 r65), clamped. The base is FULL (1.0) at/above
   * {@link #SIZE_FULL_AGGREGATE}, {@link #SIZE_FLOOR} at/below {@link #SIZE_THRESHOLD}, a linear taper
   * between (null aggregate → 1.0). The OI-gap factor TRIMS by {@link #OI_GAP_THIN_FACTOR} when the
   * call-put imbalance is below {@link #OI_GAP_BAND} (a thin, low-conviction gap); a null imbalance
   * leaves it untrimmed. The product is clamped to {@code [SIZE_FLOOR, 1.0]} so stacked factors never
   * shrink the size below half a lot. A null aggregate AND null imbalance → {@code 1.0} (byte-identical).
   */
  public static BigDecimal sizeMultiplier(BigDecimal aggregate, BigDecimal oiImbalancePct) {
    BigDecimal m = aggregateFactor(aggregate).multiply(oiGapFactor(oiImbalancePct));
    if (m.compareTo(SIZE_FLOOR) < 0) {
      return SIZE_FLOOR;
    }
    return m.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : m;
  }

  private static BigDecimal aggregateFactor(BigDecimal aggregate) {
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

  private static BigDecimal oiGapFactor(BigDecimal oiImbalancePct) {
    if (oiImbalancePct == null || oiImbalancePct.compareTo(OI_GAP_BAND) >= 0) {
      return BigDecimal.ONE;
    }
    return OI_GAP_THIN_FACTOR;
  }
}
