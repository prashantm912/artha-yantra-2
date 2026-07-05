package in.arthayantra.marketdata.screener.manas;

/**
 * The result of {@link ConsolidationBreakout}: a Manas §3.2 breakout base. {@code pivot} is the
 * nearest swing high of a ~4–8-week (min ~2-week) range-bound consolidation — the buy trigger. When
 * {@code valid} is false the recent price action is not a genuine consolidation and {@code
 * rejectReason} says why (all numeric fields are then zero).
 *
 * <p>{@code rangePct} is the consolidation's high-to-low spread as a % of its pivot (a tighter range
 * is a stronger setup, §3.2 "shorter/tighter is better"); {@code baseWeeks} / {@code baseDurationDays}
 * size the pause.
 */
public record BreakoutFootprint(
    boolean valid,
    double pivot,
    double rangePct,
    int baseWeeks,
    int baseDurationDays,
    String footprint,
    String rejectReason) {

  /** A non-breakout verdict carrying only the reason. */
  public static BreakoutFootprint rejected(String reason) {
    return new BreakoutFootprint(false, 0, 0, 0, 0, null, reason);
  }
}
