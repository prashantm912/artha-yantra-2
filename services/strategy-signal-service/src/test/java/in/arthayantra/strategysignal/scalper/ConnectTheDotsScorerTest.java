package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.Confluence;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.DotScore;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** Connect-the-Dots confluence scorer (§12.3) — VWAP decisive, 60m bias must agree. */
class ConnectTheDotsScorerTest {

  private static final BigDecimal T = new BigDecimal("0.6");
  private static final ScalperOiProps P = ScalperOiProps.defaults();

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static ScalperGateContext ctx(Chart c, Oi oi, Macro m) {
    return new ScalperGateContext("NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), c, oi, m);
  }

  private static boolean dot(Confluence r, String name) {
    return r.dots().stream().filter(d -> d.dot().equals(name)).map(DotScore::supports).findFirst().orElseThrow();
  }

  // fully-bullish dots for a CE signal — every chart/OI/macro dot, including the Tier-1 temporal
  // ones, points CE: deltas crossed (PE-OI rising / CE-OI falling), drastic + imbalanced toward CE,
  // sentiment slope up, an OI spurt with magnitude, CE IV richer than PE by > the gap.
  private static final Chart BULL_CHART =
      new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000"));
  private static final Oi BULL_OI =
      new Oi(
          OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"),
          bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"));
  private static final Macro BULL_MACRO =
      new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), bd("0.20"), bd("0.05"));

  /** BULL_MACRO plus an E7 premium skew ({@code >0} ⇒ CE the richer side); null ⇒ identical to BULL_MACRO. */
  private static Macro macroWithSkew(BigDecimal skew) {
    return new Macro(
        bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), bd("0.20"), bd("0.05"),
        null, null, null, skew);
  }

  /** BULL_MACRO plus an E3 Dow cue ({@code true} = up, {@code false} = down, null = unknown). */
  private static Macro macroWithDow(Boolean dowUp) {
    return new Macro(
        bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), bd("0.20"), bd("0.05"),
        null, null, null, null, dowUp);
  }

  /** The S24-inert OI (no trending_cross / oi_spurt cue) — for isolating the premium-skew warning. */
  private static final Oi NO_CUE_OI =
      new Oi(
          OiQuadrant.NEUTRAL, OiQuadrant.NEUTRAL, null, null, bd("5"), null, null, null,
          false, false, null, null, null);

  /**
   * T24 — the {@code volume} DOT must test the SAME floor the rail tested (root-caused 2026-07-28).
   *
   * <p>The dot called the two-argument {@code ScalperGates.volume}, which resolves the STATIC
   * per-index default (NIFTY 125,000), while the {@code relative-volume-floor} tag substitutes a
   * banded floor at the RAIL call site only. The tag has been armed on all 21 NIFTY scalpers since
   * #605, so 1.0 of weight was gated at roughly p95 of its own operand: on 2026-07-27 the 3m series
   * max was 117,000 — no bar could clear 125,000 and the dot scored 0/909.
   *
   * <p>The numbers below are that session's real shape: a 30,420-volume bar (the 07-28 3m median)
   * against a relative floor of 45,630 (k=1.5 × median) versus the static 125,000.
   */
  @Test
  void volumeDotTestsTheResolvedFloorNotTheStaticDefault() {
    Chart thinTape = new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("30420"));
    ScalperGateContext c = ctx(thinTape, BULL_OI, BULL_MACRO);

    // No override ⇒ the static NIFTY default (125,000). 30,420 cannot clear it — the live behaviour
    // that produced nine sessions of a permanently dead dot.
    assertThat(dot(ConnectTheDotsScorer.score(c, CE, 1, T, P, true, false, false, false), "volume"))
        .as("static 125,000 default — the pre-T24 reading")
        .isFalse();

    // The RESOLVED floor the armed rail actually used: k=1.5 × a 20,280 median = 30,420 → met.
    assertThat(
            dot(
                ConnectTheDotsScorer.score(
                    c, CE, 1, T, P, true, false, false, false, bd("30420")),
                "volume"))
        .as("the dot now agrees with the rail it is supposed to mirror")
        .isTrue();
    // A floor the bar genuinely misses still withholds support — the fix threads the value through,
    // it does not make the dot permissive.
    assertThat(
            dot(
                ConnectTheDotsScorer.score(
                    c, CE, 1, T, P, true, false, false, false, bd("45630")),
                "volume"))
        .as("a real miss is still a miss")
        .isFalse();
  }

  @Test
  void allDotsAlignedFiresBullishCe() {
    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, BULL_MACRO), CE, 1, T, P, true);

    assertThat(r.bullish()).isTrue();
    assertThat(r.bearish()).isFalse();
    assertThat(r.vwapAligned()).isTrue();
    assertThat(r.biasAligned()).isTrue();
    assertThat(r.standAside()).isFalse();
    assertThat(r.aggregate()).isEqualByComparingTo("1.0");
    assertThat(r.dots()).hasSize(18);
  }

  @Test
  void suppressedMonthlyExpiryOiDegradesEveryOiDotWithoutBlocking() {
    // The exact inert OI the MarketOiClient returns on a monthly-expiry day (S24 caveat): NEUTRAL
    // quadrants, null soft-numerics, false flags — only the price-derived basis survives.
    Oi inert =
        new Oi(
            OiQuadrant.NEUTRAL, OiQuadrant.NEUTRAL, null, null, bd("5"), null, null, null,
            false, false, null, null, null);

    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, inert, BULL_MACRO), CE, 1, T, P, true);

    // Every chain-OI dot is non-confirming — it neither blocks nor falsely confirms.
    for (String oiDot :
        new String[] {
          "futures_oi", "underlying_oi", "trending_cross", "sentiment",
          "drastic_oi", "sentiment_slope", "oi_spurt"
        }) {
      assertThat(dot(r, oiDot)).as(oiDot).isFalse();
    }
    // The price-derived basis dot still works, and the scorer never throws / hard-blocks.
    assertThat(dot(r, "basis")).isTrue();
    assertThat(r.bearish()).isFalse();
  }

  @Test
  void wrongVwapSideBlocksEvenWithStrongRest() {
    // price below VWAP but still above VWMA/PSAR so only the decisive VWAP dot flips
    Chart c = new Chart(bd("98"), bd("99"), bd("96"), bd("95"), 1, bd("65"), bd("130000"));

    Confluence r = ConnectTheDotsScorer.score(ctx(c, BULL_OI, BULL_MACRO), CE, 1, T, P, true);

    assertThat(r.vwapAligned()).isFalse();
    assertThat(r.bullish()).isFalse();
  }

  @Test
  void vwapHardGateOffMakesTheVwapDegradeOptInWithoutAffectingCores() {
    // #9 Morning Trade: price below VWAP (vwapSide false) but the OI/macro confluence is strong and
    // the 60m bias agrees — with vwapHardGate=false (the opening-tick-before-10:30 path) VWAP degrades
    // to a soft dot and the signal is VALID; with vwapHardGate=true (every core) it stays INVALID.
    Chart belowVwap = new Chart(bd("98"), bd("99"), bd("96"), bd("95"), 1, bd("65"), bd("130000"));

    Confluence soft =
        ConnectTheDotsScorer.score(ctx(belowVwap, BULL_OI, BULL_MACRO), CE, 1, T, P, false);
    assertThat(soft.vwapAligned()).isFalse(); // VWAP is still reported off
    assertThat(soft.biasAligned()).isTrue();
    assertThat(soft.aggregate()).isGreaterThanOrEqualTo(T); // the rest carries it past threshold
    assertThat(soft.bullish()).isTrue(); // degrade => fires

    Confluence hard =
        ConnectTheDotsScorer.score(ctx(belowVwap, BULL_OI, BULL_MACRO), CE, 1, T, P, true);
    assertThat(hard.aggregate()).isEqualByComparingTo(soft.aggregate()); // identical scoring
    assertThat(hard.bullish()).isFalse(); // hard VWAP gate (the core default) blocks
  }

  @Test
  void opposing60mBiasBlocks() {
    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, BULL_MACRO), CE, -1, T, P, true);

    assertThat(r.biasAligned()).isFalse();
    assertThat(r.bullish()).isFalse();
  }

  @Test
  void premiumSkewDotAbsentWhenUnarmedIsByteIdentical() {
    // E7 parity: a CE-richer skew present in the macro is IGNORED when the dot is unarmed — the dot is
    // not added (conditional-add), so the 18-dot list + aggregate match the all-aligned baseline exactly.
    Confluence r =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, macroWithSkew(bd("10"))), CE, 1, T, P, true, false, false);

    assertThat(r.dots()).hasSize(18);
    assertThat(r.dots().stream().anyMatch(d -> d.dot().equals("premium_skew"))).isFalse();
    assertThat(r.aggregate()).isEqualByComparingTo("1.0");
  }

  @Test
  void premiumSkewDotWithholdsSupportChasingRicherSideNoCues() {
    // armed, trading CE, CE is the richer side (skew>0), and no trending_cross/oi_spurt cue → the dot
    // does NOT support → it lowers the aggregate vs the same unarmed context (discourages the chase).
    Confluence armed =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, NO_CUE_OI, macroWithSkew(bd("10"))), CE, 1, T, P, true, false, true);
    Confluence unarmed =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, NO_CUE_OI, macroWithSkew(bd("10"))), CE, 1, T, P, true, false, false);

    assertThat(dot(armed, "premium_skew")).isFalse();
    assertThat(armed.aggregate()).isLessThan(unarmed.aggregate());
  }

  @Test
  void premiumSkewDotSupportsWhenRicherButCorroborated() {
    // armed, CE richer, but BULL_OI carries a supporting trending_cross + oi_spurt cue → the dot
    // SUPPORTS (the cue overrides the "richer side" warning) — chasing is justified.
    Confluence r =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, macroWithSkew(bd("10"))), CE, 1, T, P, true, false, true);

    assertThat(dot(r, "premium_skew")).isTrue();
  }

  @Test
  void premiumSkewDotNeutralOnNullSkew() {
    // armed but the skew is null (missing feed) → the dot SUPPORTS (neutral) → it never blocks.
    Confluence r =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, macroWithSkew(null)), CE, 1, T, P, true, false, true);

    assertThat(dot(r, "premium_skew")).isTrue();
  }

  @Test
  void dowDotAbsentWhenUnarmedIsByteIdentical() {
    // E3 parity: a Dow cue present in the macro is IGNORED when unarmed — the dot is not added
    // (conditional-add), so the 18-dot list + aggregate match the all-aligned baseline.
    Confluence r =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, macroWithDow(Boolean.FALSE)), CE, 1, T, P, true, false, false, false);

    assertThat(r.dots()).hasSize(18);
    assertThat(r.dots().stream().anyMatch(d -> d.dot().equals("dow"))).isFalse();
    assertThat(r.aggregate()).isEqualByComparingTo("1.0");
  }

  @Test
  void dowDotConfirmsCeWhenDowUpAndOpposesWhenDown() {
    // armed: Dow UP supports a CE; Dow DOWN does not (opposes the long global cue).
    assertThat(
            dot(
                ConnectTheDotsScorer.score(
                    ctx(BULL_CHART, BULL_OI, macroWithDow(Boolean.TRUE)), CE, 1, T, P, true, false, false, true),
                "dow"))
        .isTrue();
    assertThat(
            dot(
                ConnectTheDotsScorer.score(
                    ctx(BULL_CHART, BULL_OI, macroWithDow(Boolean.FALSE)), CE, 1, T, P, true, false, false, true),
                "dow"))
        .isFalse();
  }

  @Test
  void dowDotNeutralOnUnknownDirection() {
    // armed but the Dow direction is null (history / off-hours / unconfigured) → supports (neutral).
    Confluence r =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, macroWithDow(null)), CE, 1, T, P, true, false, false, true);

    assertThat(dot(r, "dow")).isTrue();
  }

  @Test
  void belowThresholdBlocksThoughVwapAligned() {
    // VWAP still aligned, but the OI/macro dots all oppose -> aggregate under threshold
    Oi oi =
        new Oi(
            OiQuadrant.SHORT_BUILDUP, OiQuadrant.SHORT_BUILDUP, bd("-10"), bd("-5"), bd("-5"),
            null, null, null, false, false, null, null, null);
    Macro m = new Macro(bd("14"), bd("80"), bd("12"), Boolean.TRUE, 10, 40, bd("50"), null, null);

    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, oi, m), CE, 1, T, P, true);

    assertThat(r.vwapAligned()).isTrue();
    assertThat(r.aggregate()).isLessThan(T);
    assertThat(r.bullish()).isFalse();
  }

  @Test
  void allDotsAlignedFiresBearishPe() {
    Chart c = new Chart(bd("98"), bd("99"), bd("100"), bd("101"), -1, bd("35"), bd("130000"));
    Oi oi =
        new Oi(
            OiQuadrant.SHORT_BUILDUP, OiQuadrant.SHORT_BUILDUP, bd("-10"), bd("-5"), bd("-5"),
            bd("70000"), bd("-60000"), bd("80"), true, false, bd("-5"), bd("60"), bd("60"));
    Macro m = new Macro(bd("14"), bd("30"), bd("12"), Boolean.TRUE, 10, 40, bd("50"), bd("0.05"), bd("0.20"));

    Confluence r = ConnectTheDotsScorer.score(ctx(c, oi, m), PE, -1, T, P, true);

    assertThat(r.bearish()).isTrue();
    assertThat(r.bullish()).isFalse();
    assertThat(r.aggregate()).isEqualByComparingTo("1.0");
  }

  // --- Tier-1 temporal dots: each supports on the right shape and degrades to false on nulls. ---

  @Test
  void trendingCrossNeedsAChangeNotAStaticTilt() {
    // a strong static PE-CE tilt but NO cross/widening and NO deltas -> the change-based dot stays off
    Oi staticTilt =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("50"), bd("5"),
            null, null, null, false, false, null, null, null);
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, staticTilt, BULL_MACRO), CE, 1, T, P, true), "trending_cross"))
        .isFalse();

    // the deltas crossed (PE-OI up / CE-OI down) -> CE supports
    Oi crossed =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
            bd("-1000"), bd("1000"), bd("50"), true, false, null, null, null);
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, crossed, BULL_MACRO), CE, 1, T, P, true), "trending_cross"))
        .isTrue();

    // gap widening (no explicit cross flag) also confirms the change
    Oi widening =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
            bd("-1000"), bd("1000"), bd("50"), false, true, null, null, null);
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, widening, BULL_MACRO), CE, 1, T, P, true), "trending_cross"))
        .isTrue();
  }

  @Test
  void drasticOiSupportsOnBothDrasticLegsAndDegradesOnNulls() {
    // both legs >= 50000 (default floor) and PE-OI grew more than CE-OI -> CE drastic_oi supports
    Oi drastic =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
            bd("-60000"), bd("70000"), bd("80"), false, false, null, null, null);
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, drastic, BULL_MACRO), CE, 1, T, P, true), "drastic_oi"))
        .isTrue();

    // one leg under the floor -> not drastic
    Oi tooSmall =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
            bd("-60000"), bd("10000"), bd("80"), false, false, null, null, null);
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, tooSmall, BULL_MACRO), CE, 1, T, P, true), "drastic_oi"))
        .isFalse();

    // null deltas -> degrade to false (never throws)
    Oi nullDeltas =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
            null, null, null, false, false, null, null, null);
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, nullDeltas, BULL_MACRO), CE, 1, T, P, true), "drastic_oi"))
        .isFalse();
  }

  @Test
  void sentimentSlopeFollowsSignAndDegradesOnNull() {
    Oi slopeUp = oiWithSlope(bd("5"));
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, slopeUp, BULL_MACRO), CE, 1, T, P, true), "sentiment_slope"))
        .isTrue(); // CE wants slope > 0
    Oi slopeDown = oiWithSlope(bd("-5"));
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, slopeDown, BULL_MACRO), CE, 1, T, P, true), "sentiment_slope"))
        .isFalse();
    Oi slopeNull = oiWithSlope(null);
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, slopeNull, BULL_MACRO), CE, 1, T, P, true), "sentiment_slope"))
        .isFalse();
  }

  @Test
  void vwapDotNeedsARealDistanceNotJustTheSide() {
    // T6 (owner 2026-07-25): the entry gate already enforces the VWAP side, so a side-only dot was
    // free (100% support, 5,225 rows). The dot now needs >=15 bps of |close-vwap|/close.
    Chart tenBps = new Chart(bd("100"), bd("99.90"), bd("98"), bd("97"), 1, bd("65"), bd("130000"));
    assertThat(dot(ConnectTheDotsScorer.score(ctx(tenBps, BULL_OI, BULL_MACRO), CE, 1, T, P, true), "vwap"))
        .as("right side but only 10 bps out — no support")
        .isFalse();
    Chart twentyBps = new Chart(bd("100"), bd("99.80"), bd("98"), bd("97"), 1, bd("65"), bd("130000"));
    assertThat(dot(ConnectTheDotsScorer.score(ctx(twentyBps, BULL_OI, BULL_MACRO), CE, 1, T, P, true), "vwap"))
        .as("right side and 20 bps out — supports")
        .isTrue();
  }

  @Test
  void oiSpurtNeedsQuadrantAndMagnitudeAndDegradesOnNull() {
    // bullish quadrant + both magnitudes over the (15, 3) floors (T22, owner 2026-07-25) -> supports
    Oi spurt = oiWithSpurt(OiQuadrant.LONG_BUILDUP, bd("60"), bd("60"));
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, spurt, BULL_MACRO), CE, 1, T, P, true), "oi_spurt"))
        .isTrue();
    // OI magnitude below the 15 floor -> no support
    Oi weak = oiWithSpurt(OiQuadrant.LONG_BUILDUP, bd("10"), bd("60"));
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, weak, BULL_MACRO), CE, 1, T, P, true), "oi_spurt"))
        .isFalse();
    // bearish quadrant for a CE side -> no support even with magnitude
    Oi wrongQuadrant = oiWithSpurt(OiQuadrant.SHORT_BUILDUP, bd("60"), bd("60"));
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, wrongQuadrant, BULL_MACRO), CE, 1, T, P, true), "oi_spurt"))
        .isFalse();
    // null magnitudes -> degrade to false
    Oi nullSpurt = oiWithSpurt(OiQuadrant.LONG_BUILDUP, null, null);
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, nullSpurt, BULL_MACRO), CE, 1, T, P, true), "oi_spurt"))
        .isFalse();
  }

  @Test
  void ivPairNeedsTheSideRicherByTheGapAndDegradesOnNull() {
    // CE IV 0.20 vs PE 0.05 -> gap 0.15 >= 0.02 (recalibrated min-gap, P1) -> CE iv_pair supports
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, macroIv(bd("0.20"), bd("0.05"))), CE, 1, T, P, true), "iv_pair"))
        .isTrue();
    // gap too small (0.01 < the 0.02 min-gap) -> no support
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, macroIv(bd("0.11"), bd("0.10"))), CE, 1, T, P, true), "iv_pair"))
        .isFalse();
    // null averages -> degrade to false
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, macroIv(null, null)), CE, 1, T, P, true), "iv_pair"))
        .isFalse();
  }

  @Test
  void fortyFortyBothHighForcesStandAsideAndSuppressesTheSignal() {
    // both IVs >= 0.40 and within the stand-aside gap -> stand aside: iv_pair withholds AND the signal
    // is invalid. |0.45 - 0.42| = 0.03 < the PINNED 0.10 stand-aside gap — and 0.03 >= the recalibrated
    // 0.02 SUPPORT min-gap, so this fixture also PROVES the two gaps are decoupled (a shared 0.02 gap
    // would have supported the richer side here instead of suppressing).
    Macro highChop = macroIv(bd("0.45"), bd("0.42"));
    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, highChop), CE, 1, T, P, true);

    assertThat(r.standAside()).isTrue();
    assertThat(dot(r, "iv_pair")).isFalse();
    assertThat(r.bullish()).isFalse(); // suppressed regardless of the otherwise-strong aggregate
    assertThat(r.dots()).filteredOn(d -> d.dot().equals("iv_pair")).first()
        .extracting(DotScore::reason).isEqualTo("iv pair 40/40 stand-aside");
  }

  // ----------------------------------------------------------------------- E4 iv-per-strike (armed)

  @Test
  void ivPerStrikeUnarmedAddsNoDotAndArmedAddsTwo() {
    Macro full = macroFull(bd("0.11"), bd("0.20"), bd("0.05"), bd("0.04"), bd("-0.02"));
    Confluence off = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, full), CE, 1, T, P, true);
    Confluence on = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, full), CE, 1, T, P, true, true);

    // unarmed: the dot list is byte-identical (no iv_slope / iv_abs_band) — the parity guard.
    assertThat(off.dots()).noneMatch(d -> d.dot().equals("iv_slope") || d.dot().equals("iv_abs_band"));
    // armed: exactly the two new SOFT IV dots are appended, both confirming on this bullish ctx.
    assertThat(on.dots()).hasSize(off.dots().size() + 2);
    assertThat(dot(on, "iv_slope")).isTrue(); // ceIvSlope 0.04 > 0 (CE-strike IV rising = demand)
    assertThat(dot(on, "iv_abs_band")).isTrue(); // atmIv 0.11 in the 0.10-0.12 trend-play band
  }

  @Test
  void ivSlopeArmedFollowsBuySideSignAndDegradesOnNull() {
    // rising CE-strike IV confirms a CE
    assertThat(dot(armedIv(bd("0.04"), bd("0")), "iv_slope")).isTrue();
    // falling CE-strike IV opposes
    assertThat(dot(armedIv(bd("-0.04"), bd("0")), "iv_slope")).isFalse();
    // null CE slope never confirms
    assertThat(dot(armedIv(null, bd("0")), "iv_slope")).isFalse();
  }

  @Test
  void ivAbsBandArmedSupportsOnlyInsideTheTenToTwelveBand() {
    assertThat(dot(armedAtmIv(bd("0.11")), "iv_abs_band")).isTrue(); // in band
    assertThat(dot(armedAtmIv(bd("0.08")), "iv_abs_band")).isFalse(); // below
    assertThat(dot(armedAtmIv(bd("0.15")), "iv_abs_band")).isFalse(); // above
  }

  @Test
  void perSideBuyIvOverFortySuppressesOnlyWhenArmed() {
    // CE buy-side IV 0.45 >= 0.40 while PE 0.10 (< 0.40, so NOT the symmetric both-high case).
    Macro richCe = macroFull(bd("0.11"), bd("0.45"), bd("0.10"), bd("0.04"), bd("-0.02"));
    Confluence off = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, richCe), CE, 1, T, P, true);
    Confluence on = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, richCe), CE, 1, T, P, true, true);

    assertThat(off.standAside()).isFalse(); // unarmed: a unilateral rich buy side is ignored
    assertThat(on.standAside()).isTrue(); // armed: "IV>40, buyer stays away"
    assertThat(on.bullish()).isFalse(); // suppressed regardless of the otherwise-strong aggregate
  }

  // --------------------------------------------------- P3: null-input dots withheld from the denominator

  /** BULL_MACRO with a custom ivRank (null exercises the P3 absent/withheld path). */
  private static Macro bullMacroWithIvRank(BigDecimal ivRank) {
    return new Macro(bd("14"), ivRank, bd("12"), Boolean.FALSE, 40, 10, bd("50"), bd("0.20"), bd("0.05"));
  }

  @Test
  void nullIvRankInputIsWithheldFromDenominatorNotScoredAgainst() {
    // Every dot aligns for a CE except iv_rank, whose INPUT is null. P3: the null dot is WITHHELD from
    // num AND den (absent), so the all-else-aligned aggregate stays 1.0. Were it (wrongly) scored
    // supports=false while still counted in the denominator, the aggregate would fall to 18.8/19.6.
    Confluence r =
        ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, bullMacroWithIvRank(null)), CE, 1, T, P, true);

    DotScore ivRank =
        r.dots().stream().filter(d -> d.dot().equals("iv_rank")).findFirst().orElseThrow();
    assertThat(ivRank.absent()).isTrue();
    assertThat(ivRank.supports()).isFalse(); // withheld => neither supports nor opposes
    assertThat(r.aggregate()).isEqualByComparingTo("1.0"); // excluded, NOT 0.9592 (scored-against)
    assertThat(r.bullish()).isTrue();
  }

  @Test
  void presentIvRankStillCountsInTheDenominatorWhenArmed() {
    // ARMED (A3 `iv-rank-dot`), ivRank 80 (>= the 50 "low" cut) is PRESENT, so it stays in the
    // denominator scoring supports=false (not absent). The all-else-aligned aggregate is
    // 18.8/19.6 = 0.9592 — the contrast that proves a PRESENT rank is counted once ARMED, while a NULL
    // one (and, per A3, any unarmed one) is withheld.
    Confluence r = armedIvRank(bd("80"));

    DotScore ivRank =
        r.dots().stream().filter(d -> d.dot().equals("iv_rank")).findFirst().orElseThrow();
    assertThat(ivRank.absent()).isFalse();
    assertThat(ivRank.supports()).isFalse();
    assertThat(r.aggregate()).isEqualByComparingTo("0.9592");

    // …and a LOW rank, armed, supports: arming restores the full "IV rank low = cheap premium" grade.
    DotScore low =
        armedIvRank(bd("30")).dots().stream()
            .filter(d -> d.dot().equals("iv_rank")).findFirst().orElseThrow();
    assertThat(low.absent()).isFalse();
    assertThat(low.supports()).isTrue();
  }

  /** CE score over the all-aligned baseline with the A3 {@code iv-rank-dot} ARMED. */
  private static Confluence armedIvRank(BigDecimal ivRank) {
    return ConnectTheDotsScorer.score(
        ctx(BULL_CHART, BULL_OI, bullMacroWithIvRank(ivRank)), CE, 1, T, P, true,
        false, false, false, null, true);
  }

  // ------------------------------------------------- A3: the iv_rank dot is default-OFF behind its tag

  @Test
  void ivRankDotUnarmedIsAbsentEvenWithAPresentRank() {
    // A3 (Architect, 2026-08-01) — the September self-arm this closes. `IvAnalyticsService` suppresses
    // the rank below 60 trading days of `marketdata.iv_daily_summary` history; live capture began
    // 2026-06-15, so the input is honest-NULL today. When the floor is reached the rank starts
    // resolving and, without this gate, a 0.8-weight dot would enter the composite denominator
    // (18.80 -> 19.60) fleet-wide on a CALENDAR trigger — no deploy, no owner arming decision.
    // Unarmed, a PRESENT rank is withheld from BOTH num and den in either direction:
    // a would-OPPOSE rank (80 >= the 50 "low" cut) does not drag the aggregate down …
    Confluence high =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, bullMacroWithIvRank(bd("80"))), CE, 1, T, P, true);
    DotScore highDot =
        high.dots().stream().filter(d -> d.dot().equals("iv_rank")).findFirst().orElseThrow();
    assertThat(highDot.absent()).as("unarmed => withheld, whatever the input says").isTrue();
    assertThat(highDot.supports()).isFalse();
    assertThat(high.aggregate()).isEqualByComparingTo("1.0"); // not 0.9592 (= counted, scoring against)

    // … and a would-SUPPORT rank (30 < 50) does not prop it up either — the dot is inert, not lenient.
    Confluence low =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, bullMacroWithIvRank(bd("30"))), CE, 1, T, P, true);
    DotScore lowDot =
        low.dots().stream().filter(d -> d.dot().equals("iv_rank")).findFirst().orElseThrow();
    assertThat(lowDot.absent()).isTrue();
    assertThat(lowDot.supports()).as("unarmed never contributes support either").isFalse();
  }

  @Test
  void unarmedWithANullRankIsByteIdenticalToTheUngatedForm() {
    // A3 parity guard — TODAY's shape. The input is null on every live and historical row, so an
    // unarmed deploy must be a no-op: the whole dot LIST (name/weight/supports/reason/absent, in
    // order) and the aggregate match the pre-gate reading exactly. The reason string is pinned too
    // because it rides the `scalper_detail` side-channel (SignalEngine#2351, FiredDiagnosticJson#80) —
    // the "no data" wording must survive the gate; only an unarmed PRESENT rank gets new wording.
    Confluence r =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, bullMacroWithIvRank(null)), CE, 1, T, P, true);

    assertThat(r.dots()).hasSize(18);
    assertThat(r.aggregate()).isEqualByComparingTo("1.0");
    assertThat(r.dots().stream().map(DotScore::dot))
        .containsExactly(
            "vwap", "supertrend", "vwma", "psar", "rsi", "volume", "futures_oi", "underlying_oi",
            "trending_cross", "sentiment", "drastic_oi", "sentiment_slope", "oi_spurt", "breadth",
            "vix", "basis", "iv_rank", "iv_pair");
    DotScore ivRank =
        r.dots().stream().filter(d -> d.dot().equals("iv_rank")).findFirst().orElseThrow();
    assertThat(ivRank)
        .isEqualTo(
            // U4b: `inputMissing` (the 6th component) is TRUE here — the rank really is absent data.
            // It is never serialized, so this widening does not touch the side-channel wording above.
            new DotScore("iv_rank", 0.8, false, "IV rank absent (no data — withheld)", true, true));
    // The armed reading on the SAME null input is identical too — arming cannot conjure a rank.
    assertThat(armedIvRank(null).dots()).isEqualTo(r.dots());
    assertThat(armedIvRank(null).aggregate()).isEqualByComparingTo(r.aggregate());
  }

  @Test
  void absentInputWithAWeakRestDegradesToNoConfluenceSupport() {
    // Edge: an absent dot is withheld even when the rest is weak. Here iv_rank is null (absent) and the
    // OI/macro dots oppose, so the aggregate stays below threshold — the confluence is fail-closed
    // (neither bullish nor bearish). The degenerate all-absent case (den == 0) hits the SAME ZERO
    // aggregate → below-any-threshold guard (see ConnectTheDotsScorer#score), so a confluence never
    // fires on inputs that are entirely missing.
    Oi oppose =
        new Oi(
            OiQuadrant.SHORT_BUILDUP, OiQuadrant.SHORT_BUILDUP, bd("-10"), bd("-5"), bd("-5"),
            null, null, null, false, false, null, null, null);
    Macro nullRankBearish =
        new Macro(bd("14"), null, bd("12"), Boolean.TRUE, 10, 40, bd("50"), null, null);

    Confluence r =
        ConnectTheDotsScorer.score(ctx(BULL_CHART, oppose, nullRankBearish), CE, 1, T, P, true);

    DotScore ivRank =
        r.dots().stream().filter(d -> d.dot().equals("iv_rank")).findFirst().orElseThrow();
    assertThat(ivRank.absent()).isTrue();
    assertThat(r.aggregate()).isLessThan(T);
    assertThat(r.bullish()).isFalse();
    assertThat(r.bearish()).isFalse();
  }

  /** Armed CE score over BULL_OI with the given CE/PE strike-IV slopes (atmIv in-band, avg6 benign). */
  private static Confluence armedIv(BigDecimal ceSlope, BigDecimal peSlope) {
    Macro m = macroFull(bd("0.11"), bd("0.20"), bd("0.05"), ceSlope, peSlope);
    return ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, m), CE, 1, T, P, true, true);
  }

  /** Armed CE score over BULL_OI with the given absolute ATM IV (slopes null, avg6 benign). */
  private static Confluence armedAtmIv(BigDecimal atmIv) {
    Macro m = macroFull(atmIv, bd("0.20"), bd("0.05"), null, null);
    return ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, m), CE, 1, T, P, true, true);
  }

  private static Macro macroFull(
      BigDecimal atmIv, BigDecimal ceAvg6, BigDecimal peAvg6, BigDecimal ceSlope, BigDecimal peSlope) {
    return new Macro(
        atmIv, bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), ceAvg6, peAvg6, null, ceSlope, peSlope);
  }

  private static Oi oiWithSlope(BigDecimal slope) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
        null, null, null, false, false, slope, null, null);
  }

  private static Oi oiWithSpurt(OiQuadrant quadrant, BigDecimal oiPct, BigDecimal pricePct) {
    return new Oi(
        quadrant, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
        null, null, null, false, false, null, oiPct, pricePct);
  }

  private static Macro macroIv(BigDecimal ceIv, BigDecimal peIv) {
    return new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"), ceIv, peIv);
  }

  // ------------------------------------------------------- F5 U4b: dot-null semantics unification
  //
  // One context, all THREE of the scorer's missing-input rules firing at once — the fixture the
  // unification is judged on. Every present dot supports the CE, and exactly four inputs are absent:
  //
  //   futures_oi (w 1.5)  quadrant NEUTRAL = the "snapshot unavailable" sentinel  -> OPPOSES in den
  //   vix        (w 1.0)  vixRising null                                          -> counts as SUPPORT
  //   basis      (w 1.0)  futuresBasis null                                       -> counts as SUPPORT
  //   iv_rank    (w 0.8)  ivRank null                                             -> WITHHELD (absent)
  //
  // LEGACY:   den 19.6 - 0.8 = 18.8, num 18.8 - 1.5 = 17.3  ->  17.3/18.8 = 0.9202
  // WITHHELD: all four absent -> den = num = 19.6 - 4.3 = 15.3  ->  1.0
  //
  // Note the two directions cancelling inside one bar: withholding futures_oi RAISES the composite,
  // withholding vix/basis LOWERS the numerator they were propping up. Net here is a loosening, which
  // is the adverse-prior direction — hence default-OFF plus the shadow lane.

  /** BULL_OI with a NEUTRAL futures quadrant (no snapshot) and a null basis; underlying still bullish. */
  private static final Oi GAPPY_OI =
      new Oi(
          OiQuadrant.LONG_BUILDUP, OiQuadrant.NEUTRAL, bd("10"), bd("5"), null,
          bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"));

  /** BULL_MACRO with a null IV rank (withheld) and an unknown VIX direction (counts as support). */
  private static final Macro GAPPY_MACRO =
      new Macro(bd("14"), null, bd("12"), null, 40, 10, bd("50"), bd("0.20"), bd("0.05"));

  private static DotScore dotOf(Confluence r, String name) {
    return r.dots().stream().filter(d -> d.dot().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void unarmedNullPolicyIsByteIdenticalAcrossAllThreeSemantics() {
    // The U4b parity guard, called through the UNCHANGED 6-arg public overload — the surface every
    // live caller used before the policy existed. All three missing-input rules are live in this one
    // bar, and each must still behave exactly as it did: futures_oi scored AGAINST the side at its
    // full 1.5 weight, vix + basis scored FOR it, iv_rank alone withheld.
    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, GAPPY_OI, GAPPY_MACRO), CE, 1, T, P, true);

    assertThat(r.dots()).hasSize(18);
    assertThat(r.dots())
        .extracting(DotScore::dot, DotScore::weight, DotScore::supports, DotScore::absent)
        .containsExactly(
            tuple("vwap", 2.5, true, false),
            tuple("supertrend", 1.0, true, false),
            tuple("vwma", 1.0, true, false),
            tuple("psar", 1.0, true, false),
            tuple("rsi", 1.0, true, false),
            tuple("volume", 1.0, true, false),
            tuple("futures_oi", 1.5, false, false), // NEUTRAL = no data, yet scored against the side
            tuple("underlying_oi", 1.0, true, false),
            tuple("trending_cross", 1.0, true, false),
            tuple("sentiment", 1.0, true, false),
            tuple("drastic_oi", 1.0, true, false),
            tuple("sentiment_slope", 1.0, true, false),
            tuple("oi_spurt", 1.0, true, false),
            tuple("breadth", 1.0, true, false),
            tuple("vix", 1.0, true, false), // unknown direction still counts as a PASSING dot
            tuple("basis", 1.0, true, false), // ditto: a missing basis props the numerator up
            tuple("iv_rank", 0.8, false, true), // the one dot already on the unified rule
            tuple("iv_pair", 0.8, true, false));
    assertThat(r.aggregate()).isEqualByComparingTo("0.9202"); // 17.3 / 18.8
    assertThat(r.bullish()).isTrue();
    // The reason strings ride the two diagnostics + the scalper_detail side-channel, so the wording of
    // the dots whose semantics U4b touches is pinned too.
    assertThat(dotOf(r, "iv_rank").reason()).isEqualTo("IV rank absent (no data — withheld)");
    assertThat(dotOf(r, "futures_oi").reason()).isEqualTo("futures OI quadrant");
    assertThat(dotOf(r, "vix").reason()).isEqualTo("VIX direction");
  }

  @Test
  void armedWithheldPolicyWithholdsEveryInputMissingDot() {
    Confluence r = withheld(BULL_CHART, GAPPY_OI, GAPPY_MACRO);

    // Class 3 (opposes-in-denominator): the NEUTRAL quadrant no longer counts 1.5 against the side.
    assertThat(dotOf(r, "futures_oi").absent()).isTrue();
    // Class 2 (counts as support): a dead VIX / basis feed no longer props the numerator up either —
    // withholding is inert in BOTH directions, which is the whole point of one rule.
    assertThat(dotOf(r, "vix").absent()).isTrue();
    assertThat(dotOf(r, "basis").absent()).isTrue();
    // Class 1 (already withheld): unchanged.
    assertThat(dotOf(r, "iv_rank").absent()).isTrue();
    // …and nothing whose input was PRESENT is withheld — arming is not a blanket amnesty.
    assertThat(dotOf(r, "underlying_oi").absent()).isFalse();
    assertThat(dotOf(r, "iv_pair").absent()).isFalse();

    assertThat(r.aggregate()).isEqualByComparingTo("1.0"); // 15.3 / 15.3, vs 0.9202 unarmed
    assertThat(r.dots()).hasSize(18); // withheld, not dropped — the side-channel keeps its shape
  }

  @Test
  void withheldAggregateRecordsTheUnifiedRuleWithoutMovingLiveScoring() {
    Confluence legacy = ConnectTheDotsScorer.score(ctx(BULL_CHART, GAPPY_OI, GAPPY_MACRO), CE, 1, T, P, true);

    // The shadow is what the composite WOULD have been — recorded on every bar, read by nothing on
    // the live path: the live aggregate and the CE/PE verdict are the unarmed ones.
    assertThat(legacy.withheldAggregate()).isEqualByComparingTo("1.0");
    assertThat(legacy.aggregate()).isEqualByComparingTo("0.9202");
    assertThat(legacy.aggregate()).isNotEqualByComparingTo(legacy.withheldAggregate());

    // Armed, the two coincide by construction (`absent` subsumes `inputMissing`).
    Confluence armed = withheld(BULL_CHART, GAPPY_OI, GAPPY_MACRO);
    assertThat(armed.withheldAggregate()).isEqualByComparingTo(armed.aggregate());

    // With NO gaps at all the shadow is the aggregate under either policy — the number only diverges
    // where data is actually missing, so a clean bar can never make the challenger look different.
    Confluence clean = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, BULL_MACRO), CE, 1, T, P, true);
    assertThat(clean.withheldAggregate()).isEqualByComparingTo(clean.aggregate());
  }

  @Test
  void withholdingCanLowerTheCompositeToo() {
    // The unification is NOT uniformly a loosening. Isolate class 2: every OI/chart input present and
    // opposing nothing, but the VIX direction unknown — LEGACY counts that null as a supporting 1.0,
    // WITHHELD removes it from both sides. With the rest of the bar imperfect, dropping a free
    // "support" LOWERS the composite, so an armed strategy can lose entries as well as gain them.
    Chart weakRsi = new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("50"), bd("130000"));
    Macro noVix = new Macro(bd("14"), bd("30"), bd("12"), null, 40, 10, bd("50"), bd("0.20"), bd("0.05"));

    Confluence legacy = ConnectTheDotsScorer.score(ctx(weakRsi, BULL_OI, noVix), CE, 1, T, P, true);
    Confluence armed = withheld(weakRsi, BULL_OI, noVix);

    assertThat(dotOf(legacy, "vix").supports()).isTrue(); // null read = a free passing dot today
    assertThat(dotOf(armed, "vix").absent()).isTrue();
    // rsi 50 is outside the CE 60-80 band, so 1.0 opposes under both policies. iv_rank (0.8) is
    // withheld under both — its input is PRESENT here (rank 30) but the A3 tag is unarmed:
    //   LEGACY   17.8 / 18.8 = 0.9468   (iv_rank the only withheld dot; vix a free support)
    //   WITHHELD 16.8 / 17.8 = 0.9438   (vix leaves BOTH sides, taking its support with it)
    assertThat(legacy.aggregate()).isEqualByComparingTo("0.9468");
    assertThat(armed.aggregate()).isEqualByComparingTo("0.9438");
    assertThat(armed.aggregate()).isLessThan(legacy.aggregate());
  }

  /** The all-defaults CE score with the U4b {@code dot-null-withheld} policy ARMED. */
  private static Confluence withheld(Chart c, Oi oi, Macro m) {
    return ConnectTheDotsScorer.score(
        ctx(c, oi, m), CE, 1, T, P, true, false, false, false, null, false, NullPolicy.WITHHELD);
  }
}
