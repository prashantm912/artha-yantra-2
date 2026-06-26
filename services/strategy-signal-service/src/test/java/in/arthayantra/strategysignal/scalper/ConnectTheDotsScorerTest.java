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
    // CE IV 0.20 vs PE 0.05 -> gap 0.15 >= 0.10 -> CE iv_pair supports
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, macroIv(bd("0.20"), bd("0.05"))), CE, 1, T, P, true), "iv_pair"))
        .isTrue();
    // gap too small -> no support
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, macroIv(bd("0.15"), bd("0.10"))), CE, 1, T, P, true), "iv_pair"))
        .isFalse();
    // null averages -> degrade to false
    assertThat(dot(ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, macroIv(null, null)), CE, 1, T, P, true), "iv_pair"))
        .isFalse();
  }

  @Test
  void fortyFortyBothHighForcesStandAsideAndSuppressesTheSignal() {
    // both IVs >= 0.40 and within the gap -> stand aside: iv_pair withholds AND the signal is invalid
    Macro highChop = macroIv(bd("0.45"), bd("0.42"));
    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, highChop), CE, 1, T, P, true);

    assertThat(r.standAside()).isTrue();
    assertThat(dot(r, "iv_pair")).isFalse();
    assertThat(r.bullish()).isFalse(); // suppressed regardless of the otherwise-strong aggregate
    assertThat(r.dots()).filteredOn(d -> d.dot().equals("iv_pair")).first()
        .extracting(DotScore::reason).isEqualTo("iv pair 40/40 stand-aside");
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
