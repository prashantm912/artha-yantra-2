package in.arthayantra.marketdata.screener.minervini.geometry;

/**
 * The result of {@link VcpDetector}: a base's Technical Footprint (§4.4) plus the buy pivot and the
 * volume/shakeout flags. {@code footprint} is the {@code [weeks]W [deepest%/tightest%] [count]T}
 * notation (canonical example {@code "40W 31/3 4T"}). When {@code vcp} is false the base is not a
 * valid VCP and {@code rejectReason} says why (all numeric fields are then zero/false).
 */
public record VcpFootprint(
    boolean vcp,
    int contractionCount,
    double deepestPct,
    double tightestPct,
    int baseWeeks,
    int baseDurationDays,
    double pivot,
    double baseDepthPct,
    boolean volumeDryUp,
    boolean shakeout,
    int baseCount,
    String footprint,
    String rejectReason) {

  /** A non-VCP verdict carrying only the reason. */
  public static VcpFootprint rejected(String reason) {
    return new VcpFootprint(false, 0, 0, 0, 0, 0, 0, 0, false, false, 0, null, reason);
  }
}
