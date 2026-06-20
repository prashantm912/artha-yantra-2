package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Siva #2 Open=High / Open=Low pre-gate (master plan section 12, doc section 3.2). A HARD pre-entry
 * filter consulted in {@link ScalperConfluenceGate} for strategies that declare the {@code
 * open-high-low} tag. It reads big-players' game plan: a strike opening at its high (Open=High on Calls)
 * is a magnet the institution will drag the premium back toward, so the trade is WITH the open-extreme
 * intent, targeting that extreme.
 *
 * <p><b>v1 scope (the Step-0 feasibility outcome):</b> the doc's full configuration needs per-strike
 * ATM+-3 OH/OL confluence (>=3 strikes matching) AND an external "OI-Pulse AI badge &gt;=90%". Neither
 * is available to strategy-signal-service: it holds no marketdata grant and reads only via market-data
 * REST, whose {@code /options/chain} returns per-strike point-in-time LTP, NOT per-strike OHLC - so the
 * strike-count confluence cannot be computed without a NEW market-data endpoint (out of scope). And the
 * OI-Pulse badge is a Phase-4 OiPulse-parity model we do not have. So v1 gates on the HONEST equivalent:
 * the deterministic FNO-structure tier {@link OpenHighLow#tier} (front-future OH/OL x the OI quadrant)
 * being {@link OpenHighLow.Tier#HIGH}, with the badge treated as an optional, currently-unavailable
 * confirmation we degrade around (never require). The per-strike strike-count confluence is a DOCUMENTED
 * REFINEMENT (see the strategy YAML) deferred to a future per-strike-OHLC market-data endpoint.
 *
 * <p>All gating legs are required; any failing leg BLOCKS (an OH/OL scalp is high-conviction-only -
 * never a false fire):
 *
 * <ol>
 *   <li><b>Tier &gt;= HIGH</b> for the side (the FNO-structure proxy for the badge). MILD or
 *       STAND_ASIDE - including a two-sided OH+OL session or no mark - blocks.
 *   <li><b>OI build-up</b> in the side's direction (folded into the tier: CE needs LB/SC, PE needs
 *       SB/LU via {@link OiQuadrant#bullish()}/{@link OiQuadrant#bearish()}).
 *   <li><b>Reject rules (section 3.2 L444/L445/L472):</b> the move must not have already exhausted -
 *       the option premium must not have moved &gt;50% from the previous close AND the identified-strike
 *       OI change must not be &gt;50%. v1 reuses the Tier-1 spurt magnitudes: {@code spurtPricePct} and
 *       {@code spurtOiPct} each within 50% in magnitude. A null magnitude is UNAVAILABLE = does not
 *       block (we never block on a missing derivation; degrade-around).
 *   <li><b>1st-half window refinement (section 3.2 L446/L471):</b> probability is in the 1st half;
 *       avoid INITIATING in the 2nd half (time-value erosion). The general &gt;=09:45 pre-flight still
 *       applies (enforced upstream); this adds a "no fresh entry after ~12:00" 1st-half cap.
 * </ol>
 *
 * <p><b>Structural stop:</b> the doc's stop for a live OH momentum scalp is the VWAP (section 3.2 S22
 * L434(d) "on a live OH momentum scalp the VWAP is the stop-loss"). v1 anchors the SL on the
 * front-future session VWAP supplied by the engine (replay-safe - the same VWAP the chart dots use); a
 * null VWAP yields a null stop (the engine then sizes off structure as it does for the other gates).
 *
 * <p><b>Exit target (documented, not engine-carried):</b> the doc's target is the OH/OL extreme itself
 * but NEVER beyond it - exit ~5 points INSIDE (below the OH for a CE, above the OL for a PE). The
 * confluence {@code Decision} carries only a structural stop, not a target, so the ~5-pt-inside target is
 * a documented live-management rule (the strategy YAML records it), not persisted metadata.
 *
 * <p>Pure + deterministic over fixed closed bars + a fixed OI snapshot, so a replay recomputes it
 * byte-identically (section 12.9).
 */
public final class OpenHighLowGate {

  private OpenHighLowGate() {}

  /** Whether the OH/OL entry may proceed and, if so, the VWAP structural stop. */
  public record Verdict(boolean pass, BigDecimal stopLevel) {}

  private static final Verdict BLOCK = new Verdict(false, null);
  /** section 3.2: the >50% premium-move / >50% OI-change reject barrier (magnitude). */
  private static final BigDecimal REJECT_PCT = new BigDecimal("50");
  /** section 3.2: 1st-half preference - avoid initiating a fresh OH/OL scalp after ~12:00. */
  private static final LocalTime FIRST_HALF_CUTOFF = LocalTime.of(12, 0);

  /**
   * Evaluate the Open=High / Open=Low pre-gate at the just-closed deploy bar.
   *
   * @param future the index-future 3-min series the scalper evaluates on
   * @param index the just-closed deploy bar index
   * @param side CE (bullish: Open=High) or PE (bearish: Open=Low)
   * @param oi the Tier-1 OI snapshot (the underlying quadrant + the spurt reject magnitudes)
   * @param vwap the front-future session VWAP (the SL anchor); null when the engine has none yet
   * @param istTime the bar's IST wall-clock (the 1st-half cutoff)
   */
  public static Verdict evaluate(
      EngineSeries future,
      int index,
      OptionType side,
      Oi oi,
      BigDecimal vwap,
      LocalTime istTime) {
    if (oi == null) {
      return BLOCK; // no OI snapshot -> cannot confirm the quadrant -> never fire
    }
    // (d) 1st-half preference: avoid a fresh OH/OL scalp after ~12:00 (2nd-half premium erosion).
    if (istTime != null && !istTime.isBefore(FIRST_HALF_CUTOFF)) {
      return BLOCK;
    }
    // (a)+(b) tier >= HIGH for the side (the FNO-structure proxy + the folded-in OI build-up). MILD or
    // STAND_ASIDE (two-sided OH+OL, no mark, or no quadrant confirmation) blocks.
    if (OpenHighLow.tier(future, index, side, oi) != OpenHighLow.Tier.HIGH) {
      return BLOCK;
    }
    // (c) reject rules: the move must not already be exhausted - >50% premium move OR >50% OI change.
    // Reuse the Tier-1 spurt magnitudes; a null magnitude is unavailable and does NOT block.
    if (exceedsReject(oi.spurtPricePct()) || exceedsReject(oi.spurtOiPct())) {
      return BLOCK;
    }
    return new Verdict(true, vwap);
  }

  /** True only when the magnitude is present AND strictly above the 50% reject barrier. Null = pass. */
  private static boolean exceedsReject(BigDecimal pct) {
    return pct != null && pct.abs().compareTo(REJECT_PCT) > 0;
  }
}
