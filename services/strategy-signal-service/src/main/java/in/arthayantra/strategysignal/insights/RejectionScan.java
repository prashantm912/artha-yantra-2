package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.util.List;

/**
 * The parsed rejection-intelligence bundle the REJECTION_NEARMISS + REJECTION_RAIL_TREND generators
 * consume (INT design §4.2). Assembled at the engine edge by {@link RejectionReader} from the
 * existing {@code signal_rejections} rows (P1-§11.2) — no new forensics, only ordering + trend. The
 * near-miss window is populated on the 15-min sweep; the rail-trend list on the EOD sweep; each
 * generator reads only its part (the other is empty → no candidates).
 */
public record RejectionScan(List<NearMiss> nearMisses, List<RailShare> railTrend) {

  /**
   * One rejection ranked by closeness-to-firing (INT design §4.2 near-miss). {@code closeness} =
   * |blockingMargin| / |blockingThreshold| for a numeric rail with a non-null operand/threshold and
   * threshold ≠ 0 (enum/time-window rails are excluded upstream, never ratio-ranked) — small = closest
   * to firing.
   */
  public record NearMiss(
      long rejectionId,
      String strategySlug,
      String tradingsymbol,
      String side,
      String blockingRail,
      BigDecimal blockingOperand,
      BigDecimal blockingThreshold,
      BigDecimal blockingMargin,
      BigDecimal closeness) {}

  /**
   * One rail's block-share today vs its trailing-session mean (INT design §4.2 rail trend). Flags a
   * rail that suddenly dominates (gate drift or a data defect).
   *
   * @param todayCount rejections blocked by this rail today
   * @param todayShare todayCount ÷ today's total rejections
   * @param meanShare mean daily share over the trailing {@code sessions} sessions
   * @param ratio todayShare ÷ meanShare (∞-safe: null when meanShare is 0)
   */
  public record RailShare(
      String rail,
      long todayCount,
      long todayTotal,
      BigDecimal todayShare,
      BigDecimal meanShare,
      BigDecimal ratio,
      int sessions) {}
}
