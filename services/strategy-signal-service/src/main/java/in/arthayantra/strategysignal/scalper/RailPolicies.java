package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.strategysignal.scalper.FailPolicy.FAIL_CLOSED;
import static in.arthayantra.strategysignal.scalper.FailPolicy.FAIL_OPEN;

import java.util.Map;

/**
 * The declared missing-data polarity of EVERY confluence-gate rail (audit P2). Each entry states
 * what the rail does when its input is null/unwarmed/unavailable, matching the CURRENT behavior of
 * the implementing check (this registry documents and enforces — it never changes gate behavior;
 * the deciding code/comment is cited per entry). {@code Diag} resolves the policy at record time,
 * so a rail whose name is missing here throws the moment it is exercised, and the
 * {@code RailPolicyTableTest} source-scan fails the build for one that is never exercised.
 */
final class RailPolicies {

  private RailPolicies() {}

  private static final Map<String, FailPolicy> POLICIES =
      Map.ofEntries(
          // -- hard preconditions: recorded only WHEN blocking (fail-closed by construction) -----
          Map.entry("chain-unavailable", FAIL_CLOSED), // gate L321: no chain -> no entry
          Map.entry("context-unavailable", FAIL_CLOSED), // gate L660: no OI/macro context -> block
          Map.entry("strike-pick", FAIL_CLOSED), // StrikePicker: no strike in band -> block
          Map.entry("confluence-composite", FAIL_CLOSED), // invalid/unreached composite -> block
          // -- protective rails: absence of confirmation = no trade ------------------------------
          Map.entry("time-window", FAIL_CLOSED), // ScalperGates L105: outside window blocks
          Map.entry("time-of-day-preference", FAIL_CLOSED), // L142
          Map.entry("volume-floor", FAIL_CLOSED), // L161: below-floor volume blocks
          Map.entry("rising-volume", FAIL_CLOSED), // L181
          Map.entry("rsi-band", FAIL_CLOSED), // L196: null RSI fails
          Map.entry("rsi-recovery", FAIL_CLOSED), // L291
          Map.entry("rsi-cooloff", FAIL_CLOSED), // L312
          Map.entry("rsi-5m-cap", FAIL_CLOSED), // L363: "Fail-closed on a null higher-TF read"
          Map.entry("rsi-daily-cap", FAIL_CLOSED), // L363 (same helper)
          Map.entry("morning-opening-formation", FAIL_CLOSED), // L480
          Map.entry("indicator-alignment-gate", FAIL_CLOSED), // L510
          Map.entry("futures-oi-gate", FAIL_CLOSED), // L529: NEUTRAL quadrant is non-supporting
          Map.entry("breadth-gate", FAIL_CLOSED), // L536: "fail-closed on a 0/0 / unavailable read"
          Map.entry("oi-cross-required", FAIL_CLOSED), // L595: "Null deltas FAIL (fail-closed)"
          Map.entry("oi-slope-agree", FAIL_CLOSED), // L616: "Null level/slope FAILS (fail-closed)"
          Map.entry("oi-divergence-magnitude", FAIL_CLOSED), // L641
          Map.entry("flat-oi-stand-aside", FAIL_CLOSED), // L679: flat/absent OI -> stand aside
          Map.entry("divergence-vol-gate", FAIL_CLOSED), // L841: "the confirm is required"
          Map.entry("directional-change-gate", FAIL_CLOSED), // L872: precondition unmet blocks
          Map.entry("gap-fill", FAIL_CLOSED), // gate L603: gap not filled/present blocks
          Map.entry("trendline-break", FAIL_CLOSED), // gate L586: no confirmed break blocks
          Map.entry("two-candle", FAIL_CLOSED), // gate L593: formation absent blocks
          Map.entry("trend-change", FAIL_CLOSED), // TrendChangeGate L68: "any missing leg blocks"
          Map.entry("open-high-low", FAIL_CLOSED), // OpenHighLowGate L94: null OI/stats blocks
          Map.entry("hero-zero", FAIL_CLOSED), // HeroZeroGate L165: "can never confirm -> never fire"
          // -- advisory rails: missing input degrades to PASS (can lower conviction, never block) -
          Map.entry("supertrend-15m", FAIL_OPEN), // L378: "UNKNOWN direction PASSES"
          Map.entry("psar-durability", FAIL_OPEN), // L392: "fail-OPEN on a null PSAR"
          Map.entry("vwap-distance", FAIL_OPEN), // L413: "DEGRADES to pass"
          Map.entry("pct-price-move", FAIL_OPEN), // L341: "null operand PASSES"
          Map.entry("directional-vix-gate", FAIL_OPEN), // L545: "fail-OPEN on unknown direction"
          Map.entry("call-put-delta-filter", FAIL_OPEN), // L576: "null imbalance DEGRADES to PASS"
          Map.entry("oi-interval-and-60m-trend", FAIL_OPEN), // L662: "Fail-OPEN on 0"
          Map.entry("max-oi-sr-gate", FAIL_OPEN), // L694: "missing ladder never blocks"
          Map.entry("iv-buyer-cap", FAIL_OPEN), // L710: "null side IV DEGRADES to pass"
          Map.entry("volume-pump", FAIL_OPEN), // L728: "degrade to pass"
          Map.entry("fii-bias", FAIL_OPEN), // L746: "fail-open on a null/neutral read"
          Map.entry("fii-dii-gate", FAIL_OPEN), // L765
          Map.entry("constituent-gate", FAIL_OPEN), // L782: "fail-open on null/0"
          Map.entry("basis-gate", FAIL_OPEN), // L793: "fail-OPEN on a null basis"
          Map.entry("indicator-distance-veto", FAIL_OPEN), // L824: "DEGRADES to pass"
          Map.entry("overbought-defer", FAIL_OPEN), // L855: "null RSI DEGRADES to pass"
          Map.entry("gap-size-side-gate", FAIL_OPEN), // L898: "null gap PASSES"
          Map.entry("low-iv-straddle", FAIL_OPEN), // gate L350: missing IV avg is skipped
          Map.entry("morning-eod-precondition", FAIL_OPEN)); // gate L539: null extremes pass

  /** The declared policy; throws for an undeclared rail so a new rail cannot ship without one. */
  static FailPolicy of(String rail) {
    FailPolicy policy = POLICIES.get(rail);
    if (policy == null) {
      throw new IllegalStateException(
          "rail '"
              + rail
              + "' has no declared FailPolicy — add it to RailPolicies with its missing-data"
              + " polarity (FAIL_OPEN degrades to pass, FAIL_CLOSED blocks) before shipping");
    }
    return policy;
  }

  /** The full registry (the table test pins it to the rails found in the gate source). */
  static Map<String, FailPolicy> all() {
    return POLICIES;
  }
}
