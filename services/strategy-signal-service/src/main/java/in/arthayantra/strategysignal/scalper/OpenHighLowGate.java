package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategysignal.scalper.MarketOiClient.OpenHighStats;
import in.arthayantra.strategysignal.scalper.OpenHighLow.Marks;
import in.arthayantra.strategysignal.scalper.OpenHighLow.Tier;
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
 * <p><b>Source-faithful grading (the v1 OI-quadrant proxy is REPLACED):</b> the gate now reads the
 * per-strike option-premium footprint from the {@code /options/strike-session-stats} market-data
 * endpoint and grades it through the deck's Table-1/Table-2 in {@link OpenHighLow#tier} - it no longer
 * uses the underlying OI quadrant for the OH probability (the source does not). The stats are fetched
 * by the dispatching {@link ScalperConfluenceGate} (#2-only, not in the shared context fan-out) and
 * handed in.
 *
 * <p>All gating legs are required; any failing leg BLOCKS (an OH/OL scalp is high-conviction-only -
 * never a false fire):
 *
 * <ol>
 *   <li><b>Tier == HIGH</b> for the side (the Table-1 footprint, possibly downgraded by Table-2 / the
 *       &gt;50% prev-close-fall LOW rule). MILD, LOW or STAND_ASIDE - including a two-sided OH
 *       footprint, a future-only mark, or no mark - blocks.
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

  /**
   * Whether the OH/OL entry may proceed, the VWAP structural stop, and the graded tier + a short
   * reason for the signal side-channel (explainability).
   */
  public record Verdict(boolean pass, BigDecimal stopLevel, Tier tier, String reason) {}

  private static Verdict block(Tier tier, String reason) {
    return new Verdict(false, null, tier, reason);
  }
  /** section 3.2: the >50% premium-move / >50% OI-change reject barrier (magnitude). */
  private static final BigDecimal REJECT_PCT = new BigDecimal("50");
  /** section 3.2: 1st-half preference - avoid initiating a fresh OH/OL scalp after ~12:00. */
  private static final LocalTime FIRST_HALF_CUTOFF = LocalTime.of(12, 0);

  /**
   * Evaluate the Open=High / Open=Low pre-gate at the just-closed deploy bar.
   *
   * @param futureMarks the front-future OH/OL marks ({@link OpenHighLow#marks})
   * @param stats the #2-only per-strike OH/OL footprint (null/empty degrades to a block)
   * @param side CE (bullish: Open=High) or PE (bearish: Open=Low)
   * @param props the #2 grading thresholds (min-strikes / fall-volume floor / prev-close fall %)
   * @param oi the Tier-1 OI snapshot (the spurt reject magnitudes); null degrades to a block
   * @param vwap the front-future session VWAP (the SL anchor); null when the engine has none yet
   * @param istTime the bar's IST wall-clock (the 1st-half cutoff)
   */
  public static Verdict evaluate(
      Marks futureMarks,
      OpenHighStats stats,
      OptionType side,
      ScalperOiProps props,
      Oi oi,
      BigDecimal vwap,
      LocalTime istTime) {
    if (oi == null) {
      return block(null, "no OI snapshot"); // cannot run the spurt reject -> never fire
    }
    // (d) 1st-half preference: avoid a fresh OH/OL scalp after ~12:00 (2nd-half premium erosion).
    if (istTime != null && !istTime.isBefore(FIRST_HALF_CUTOFF)) {
      return block(null, "2nd-half (>=12:00) cutoff");
    }
    // (a) Table-1/Table-2 tier == HIGH for the side. MILD, LOW or STAND_ASIDE (two-sided OH footprint,
    // a future-only mark, a fall-on-volume / >50% prev-close-fall downgrade, or no mark) blocks.
    Tier tier = OpenHighLow.tier(futureMarks, stats, side, props);
    if (tier != Tier.HIGH) {
      return block(tier, "tier " + tier + " (not HIGH)");
    }
    // (b) reject rules: the move must not already be exhausted - >50% premium move OR >50% OI change.
    // Reuse the Tier-1 spurt magnitudes; a null magnitude is unavailable and does NOT block.
    if (exceedsReject(oi.spurtPricePct()) || exceedsReject(oi.spurtOiPct())) {
      return block(tier, "spurt >50% reject");
    }
    return new Verdict(true, vwap, tier, "HIGH footprint");
  }

  /** True only when the magnitude is present AND strictly above the 50% reject barrier. Null = pass. */
  private static boolean exceedsReject(BigDecimal pct) {
    return pct != null && pct.abs().compareTo(REJECT_PCT) > 0;
  }
}
