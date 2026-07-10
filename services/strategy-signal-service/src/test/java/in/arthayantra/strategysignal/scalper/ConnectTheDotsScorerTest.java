package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

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
  void oiSpurtNeedsQuadrantAndMagnitudeAndDegradesOnNull() {
    // bullish quadrant + both magnitudes >= 50 -> CE oi_spurt supports
    Oi spurt = oiWithSpurt(OiQuadrant.LONG_BUILDUP, bd("60"), bd("60"));
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, spurt, BULL_MACRO), CE, 1, T, P, true), "oi_spurt"))
        .isTrue();
    // magnitude below the floor -> no support
    Oi weak = oiWithSpurt(OiQuadrant.LONG_BUILDUP, bd("40"), bd("60"));
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
  void presentIvRankStillCountsInTheDenominator() {
    // ivRank 80 (>= the 50 "low" cut) is PRESENT, so it stays in the denominator scoring supports=false
    // (not absent). The all-else-aligned aggregate is 18.8/19.6 = 0.9592 — the contrast that proves a
    // PRESENT rank is still counted, only a NULL one is withheld.
    Confluence r =
        ConnectTheDotsScorer.score(
            ctx(BULL_CHART, BULL_OI, bullMacroWithIvRank(bd("80"))), CE, 1, T, P, true);

    DotScore ivRank =
        r.dots().stream().filter(d -> d.dot().equals("iv_rank")).findFirst().orElseThrow();
    assertThat(ivRank.absent()).isFalse();
    assertThat(ivRank.supports()).isFalse();
    assertThat(r.aggregate()).isEqualByComparingTo("0.9592");
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
}
