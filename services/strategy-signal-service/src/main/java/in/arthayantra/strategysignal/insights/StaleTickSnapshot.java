package in.arthayantra.strategysignal.insights;

import java.util.List;

/**
 * The stale-tick snapshot the RISK_STALE_TICK generator consumes (INT design §2.2/§7.4, the interim
 * health/data fallback trigger). Assembled at the engine edge by {@link PortfolioReader} +
 * {@link ContextClient}: open BRACKETED positions (SL or TP set) whose instrument the live-capture
 * canary currently flags with a bar stall — so the bracket's bar-driven SL/TP evaluation is degraded.
 *
 * <p><b>Interim (§12).</b> The health/data canary reports a bar stall (ticks flowing, 1m bars not
 * closing), not a raw tick starvation — a silently-dead token produces no problem row. This is the
 * honest subset of "SL/TP unevaluated" the health/data rail can prove today; it upgrades to the V3
 * bracket-starvation counter when that lands.
 *
 * @param stalled the bracketed positions whose instrument the canary flags
 */
public record StaleTickSnapshot(List<StaleBracket> stalled) {

  /**
   * One open bracketed position over a stalled feed instrument.
   *
   * @param staleKey the {@code exchange:tradingsymbol} health-canary key that matched
   * @param detail the canary's per-instrument problem detail (e.g. "no 1m bar closed for 240s")
   */
  public record StaleBracket(
      long positionId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      boolean hasStop,
      boolean hasTarget,
      String staleKey,
      String detail) {}
}
