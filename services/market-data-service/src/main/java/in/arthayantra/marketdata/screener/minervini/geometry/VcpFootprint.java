package in.arthayantra.marketdata.screener.minervini.geometry;

/**
 * The result of {@link VcpDetector}: a base's Technical Footprint (§4.4) plus the buy pivot and the
 * volume/shakeout flags. {@code footprint} is the {@code [weeks]W [deepest%/tightest%] [count]T}
 * notation (canonical example {@code "40W 31/3 4T"}). When {@code vcp} is false the base is not a
 * valid VCP and {@code rejectReason} says why (all numeric fields are then zero/false).
 *
 * <p>{@code cheatPivot} is the cheat-area pause high — an earlier/lower breakout trigger than the
 * final {@code pivot} (§6.3, the C of the A-B-C-D turn), the peak of the penultimate contraction;
 * {@code thrust} is the power-play precondition (§6.4/§6.5): a prior ≈+100% move in &lt;8 weeks before
 * the base. Both feed the Phase-9 live seeding of the {@code cheat_3c} / {@code power_play} setups.
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
    double cheatPivot,
    boolean thrust,
    String footprint,
    String rejectReason) {

  /** A non-VCP verdict carrying only the reason. */
  public static VcpFootprint rejected(String reason) {
    return new VcpFootprint(false, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, false, null, reason);
  }
}
