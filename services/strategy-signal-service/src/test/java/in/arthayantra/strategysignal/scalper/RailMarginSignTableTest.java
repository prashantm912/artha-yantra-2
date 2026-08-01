package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * G17 / T14: a rail's expected blocked-margin sign must be DERIVED from the operator that produced
 * the verdict, never declared in a table that can drift from the wiring. Review round 1 killed the
 * first design — a static rail-name→sign map — because it matched only rail NAMES and so could not
 * see that arming {@code vwap_distance_min_frac} turns vwap-distance into a two-bound band whose
 * legitimate too-close blocks carry a NEGATIVE margin: the diagnostic would have cried wolf on
 * correct behaviour in the armed case.
 *
 * <p>The sign now travels on {@link GateOutcome}, stamped by the gate function itself. These tests
 * assert the CARRIED sign against the operator's OBSERVED behaviour on both sides of its threshold
 * — so if an operator's direction ever changes, a probe fails rather than an eyeball.
 */
class RailMarginSignTableTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // ------------------------------- floors: blocked margin is negative --------------------------

  @Test
  void volumeFloorCarriesTheFloorSignItsOperatorImplies() {
    BigDecimal floor = bd("100");
    assertThat(ScalperGates.volume("NIFTY 50", bd("99"), floor).pass()).isFalse(); // below blocks
    assertThat(ScalperGates.volume("NIFTY 50", bd("101"), floor).pass()).isTrue(); // above passes
    assertThat(ScalperGates.volume("NIFTY 50", bd("99"), floor).marginSign())
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
  }

  @Test
  void psarDurabilityCarriesTheFloorSignItsOperatorImplies() {
    // distPct = |close-psar|/close*100 vs PSAR_DISTANCE_MIN_PCT (0.05%): 0.01% blocks, 1% passes.
    assertThat(ScalperGates.psarDurable(bd("100"), bd("100.01")).pass()).isFalse();
    assertThat(ScalperGates.psarDurable(bd("100"), bd("99")).pass()).isTrue();
    assertThat(ScalperGates.psarDurable(bd("100"), bd("100.01")).marginSign())
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
  }

  @Test
  void rsiRecoveryCarriesTheFloorSignAndItsPeMirrorCanNeverBlock() {
    // A recent oversold trough (25 <= 30) arms it; RSI below the 40 recovery level blocks.
    assertThat(ScalperGates.rsiRecovery(bd("25"), bd("35"), CE, bd("30"), bd("40")).pass())
        .isFalse();
    assertThat(ScalperGates.rsiRecovery(bd("25"), bd("45"), CE, bd("30"), bd("40")).pass()).isTrue();
    assertThat(ScalperGates.rsiRecovery(bd("25"), bd("35"), CE, bd("30"), bd("40")).marginSign())
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
    assertThat(ScalperGates.rsiRecovery(bd("25"), bd("35"), PE, bd("30"), bd("40")).pass())
        .as("the PE mirror is inert — only the CE branch can block, so one sign covers every block")
        .isTrue();
  }

  @Test
  void callPutDeltaFilterCarriesTheFloorSignItsOperatorImplies() {
    assertThat(ScalperGates.callPutDeltaFilter(oiImbalance(bd("40")), bd("50")).pass()).isFalse();
    assertThat(ScalperGates.callPutDeltaFilter(oiImbalance(bd("60")), bd("50")).pass()).isTrue();
    assertThat(ScalperGates.callPutDeltaFilter(oiImbalance(bd("40")), bd("50")).marginSign())
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
  }

  @Test
  void breadthIsAStrictFloorSoAZeroMarginBlockIsLegitimateAndNeverFlagged() {
    // count > 32 (STRICT): 32 blocks with margin exactly 0 — G16 measured a session whose max was
    // exactly 32, so the check must not flag it. PE reads declines against the same bound.
    assertThat(ScalperGates.breadth(macroBreadth(32, 10), CE).pass()).isFalse();
    assertThat(ScalperGates.breadth(macroBreadth(33, 10), CE).pass()).isTrue();
    assertThat(ScalperGates.breadth(macroBreadth(10, 33), PE).pass())
        .as("side-independent: PE tests declines against the same 32, not an inverted bound")
        .isTrue();
    assertThat(ScalperGates.breadth(macroBreadth(32, 10), CE).marginSign())
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
    assertThat(RailMarginSign.contradicts(RailMarginSign.NEGATIVE_WHEN_BLOCKED, BigDecimal.ZERO))
        .as("a margin of exactly zero is never a contradiction")
        .isFalse();
  }

  @Test
  void compositeMarginIsNullOrNegativeOnABlockNeverPositive() {
    // The B5/#985 pin: a scalar shortfall keeps the negative margin; a decisive-leg block (the
    // aggregate cleared its threshold but a leg failed) records NULL — so any NON-null blocked
    // margin is the scalar shortfall and must be negative. A positive one is the §6.3 class.
    assertThat(ScalperConfluenceGate.compositeMargin(false, bd("0.5"), bd("0.6")))
        .isEqualByComparingTo("-0.1");
    assertThat(ScalperConfluenceGate.compositeMargin(false, bd("0.7"), bd("0.6")))
        .as("decisive-leg block records no scalar margin — a positive 'margin' would lie")
        .isNull();
  }

  // ----------------------------- ceilings: blocked margin is positive --------------------------

  @Test
  void vwapDistanceCarriesTheCeilingSignWhileItsMinClauseIsUnarmed() {
    // frac = |close-vwap|/close vs maxFrac: 0.01 > 0.004 blocks with a POSITIVE margin — the
    // 2026-07-23 §2.3 rows, which are CORRECT and which killed the blanket `margin < 0` invariant.
    var block = ScalperGates.vwapDistance(bd("100"), bd("99"), null, bd("0.004"));
    assertThat(block.pass()).isFalse();
    assertThat(block.operand().subtract(bd("0.004")).signum()).isPositive();
    assertThat(block.marginSign()).isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("99.9"), null, bd("0.004")).pass()).isTrue();
    // Explicit zero is the shipped default and must behave exactly like null (still one-bound).
    assertThat(
            ScalperGates.vwapDistance(bd("100"), bd("99"), BigDecimal.ZERO, bd("0.004"))
                .marginSign())
        .isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
  }

  /** THE review-round-1 defect: the armed knob makes the rail two-bound, so no single sign holds. */
  @Test
  void armingTheVwapMinClauseMakesTheRailTwoBoundAndItThenAssertsNothing() {
    BigDecimal minFrac = bd("0.002");
    BigDecimal maxFrac = bd("0.004");

    // Too CLOSE (frac 0.001 < min): a legitimate block whose margin vs the RECORDED max threshold
    // is NEGATIVE — the case the old name→sign table would have flagged as self-contradictory.
    var tooClose = ScalperGates.vwapDistance(bd("100"), bd("99.9"), minFrac, maxFrac);
    assertThat(tooClose.pass()).isFalse();
    assertThat(tooClose.operand().subtract(maxFrac).signum())
        .as("a too-close block undershoots the recorded max threshold")
        .isNegative();

    // Too FAR (frac 0.01 > max): also a legitimate block, margin POSITIVE. Both signs block.
    var tooFar = ScalperGates.vwapDistance(bd("100"), bd("99"), minFrac, maxFrac);
    assertThat(tooFar.pass()).isFalse();
    assertThat(tooFar.operand().subtract(maxFrac).signum()).isPositive();

    // Therefore the armed rail declares UNSIGNED, and neither block is ever flagged.
    assertThat(tooClose.marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
    assertThat(tooFar.marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
    assertThat(
            RailMarginSign.contradicts(tooClose.marginSign(), tooClose.operand().subtract(maxFrac)))
        .as("an armed two-bound VWAP block must never be reported as self-contradictory")
        .isFalse();
  }

  @Test
  void indicatorDistanceVetoCarriesTheCeilingSignItsOperatorImplies() {
    Chart far = new Chart(bd("100"), bd("99"), null, null, 1, bd("65"), bd("60000"));
    Chart near = new Chart(bd("100"), bd("99.9"), null, null, 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(far, bd("0.004")).pass()).isFalse(); // 0.01 > max
    assertThat(ScalperGates.indicatorDistance(near, bd("0.004")).pass()).isTrue(); // 0.001 <= max
    assertThat(ScalperGates.indicatorDistance(far, bd("0.004")).marginSign())
        .isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
  }

  @Test
  void ivBuyerCapCarriesTheCeilingSignItsOperatorImplies() {
    assertThat(ScalperGates.ivBuyerCap(macroIv(bd("0.45"), null), CE, bd("0.40")).pass()).isFalse();
    assertThat(ScalperGates.ivBuyerCap(macroIv(bd("0.35"), null), CE, bd("0.40")).pass()).isTrue();
    assertThat(ScalperGates.ivBuyerCap(macroIv(bd("0.45"), null), CE, bd("0.40")).marginSign())
        .isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
  }

  @Test
  void gapSizeSideGateBlocksOnlyAtOrAboveTheSuppressionPointsAndOnlyOnPe() {
    GapState.Gap bigDown = new GapState.Gap(true, false, bd("400"), bd("25000"), false);
    GapState.Gap smallDown = new GapState.Gap(true, false, bd("200"), bd("25000"), false);
    assertThat(ScalperGates.gapSizeSide(bigDown, PE, bd("300")).pass()).isFalse(); // 400 >= 300
    assertThat(ScalperGates.gapSizeSide(smallDown, PE, bd("300")).pass()).isTrue(); // 200 < 300
    assertThat(ScalperGates.gapSizeSide(bigDown, CE, bd("300")).pass())
        .as("CE never blocks here — only the PE side is suppressed")
        .isTrue();
    assertThat(ScalperGates.gapSizeSide(bigDown, PE, bd("300")).marginSign())
        .isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
  }

  // ------------------- side-dependent / conjunction operators declare nothing -------------------

  @Test
  void pctPriceMoveBlocksWithBothMarginSignsSoItDeclaresNothing() {
    // CE fail: move +0.5% < +1% floor -> margin vs the recorded threshold (+1) is NEGATIVE.
    var ceBlock = ScalperGates.pctPriceMove(bd("100.5"), bd("100"), bd("1"), CE);
    assertThat(ceBlock.pass()).isFalse();
    assertThat(ceBlock.operand().subtract(bd("1")).signum()).isNegative();
    // PE fail: move +2% (needs <= -1%) -> margin vs the SAME recorded threshold is POSITIVE.
    var peBlock = ScalperGates.pctPriceMove(bd("102"), bd("100"), bd("1"), PE);
    assertThat(peBlock.pass()).isFalse();
    assertThat(peBlock.operand().subtract(bd("1")).signum()).isPositive();
    assertThat(ceBlock.marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
    assertThat(peBlock.marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
  }

  @Test
  void fiiBiasBlocksWithBothMarginSignsSoItDeclaresNothing() {
    var ceBlock = ScalperGates.fiiBias(macroFii(bd("40")), CE); // 40 < 50 -> NEGATIVE
    var peBlock = ScalperGates.fiiBias(macroFii(bd("60")), PE); // 60 > 50 -> POSITIVE
    assertThat(ceBlock.pass()).isFalse();
    assertThat(peBlock.pass()).isFalse();
    assertThat(ceBlock.operand().subtract(ScalperGates.FII_NEUTRAL_PCT).signum()).isNegative();
    assertThat(peBlock.operand().subtract(ScalperGates.FII_NEUTRAL_PCT).signum()).isPositive();
    assertThat(ceBlock.marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
    assertThat(peBlock.marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
  }

  @Test
  void oiDivergenceMagnitudeIsAConjunctionSoTheOperandCanClearWhileTheRailBlocks() {
    // CE: divergence +30 clears the +20 floor but the price-impulse leg (10 < 50) blocks -> the
    // recorded div-margin is POSITIVE on a legitimate block.
    var legBlock =
        ScalperGates.oiDivergenceMagnitude(oiDivergence(bd("30"), bd("10")), bd("20"), bd("50"), CE);
    assertThat(legBlock.pass()).isFalse();
    assertThat(legBlock.operand().subtract(bd("20")).signum()).isPositive();
    // CE: divergence 10 short of the 20 floor -> margin NEGATIVE. Both signs block => UNSIGNED.
    var scalarBlock =
        ScalperGates.oiDivergenceMagnitude(oiDivergence(bd("10"), bd("60")), bd("20"), bd("50"), CE);
    assertThat(scalarBlock.pass()).isFalse();
    assertThat(scalarBlock.operand().subtract(bd("20")).signum()).isNegative();
    assertThat(legBlock.marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
    assertThat(scalarBlock.marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
  }

  @Test
  void aGateThatDeclaresNothingDefaultsToUnsignedSoSilenceIsNeverAnAccusation() {
    // Every gate not explicitly signed above (rsi-band, the boolean rails, ...) rides the 3-arg
    // GateOutcome form and therefore asserts nothing.
    assertThat(ScalperGates.rsiBand(bd("50"), CE).marginSign()).isEqualTo(RailMarginSign.UNSIGNED);
    assertThat(GateOutcome.fail(bd("1"), "no comparison declared").marginSign())
        .isEqualTo(RailMarginSign.UNSIGNED);
    assertThat(RailMarginSign.contradicts(RailMarginSign.UNSIGNED, bd("7"))).isFalse();
    assertThat(RailMarginSign.contradicts(null, bd("7"))).isFalse();
  }

  // ---------------------------------- the contradiction matrix ---------------------------------

  @Test
  void contradictsFlagsOnlyTheStrictlyWrongSideOfEachOperator() {
    // The 2026-07-23 §2.3 id-7794 shape: composite blocked with +0.0373 — the contradiction class.
    assertThat(RailMarginSign.contradicts(RailMarginSign.NEGATIVE_WHEN_BLOCKED, bd("0.0373")))
        .isTrue();
    assertThat(RailMarginSign.contradicts(RailMarginSign.POSITIVE_WHEN_BLOCKED, bd("-0.001")))
        .isTrue();
    // Correct blocks never flag: floor short, ceiling over (the §2.3 correct vwap rows), zero, null.
    assertThat(RailMarginSign.contradicts(RailMarginSign.NEGATIVE_WHEN_BLOCKED, bd("-5"))).isFalse();
    assertThat(RailMarginSign.contradicts(RailMarginSign.POSITIVE_WHEN_BLOCKED, bd("0.0004")))
        .isFalse();
    assertThat(RailMarginSign.contradicts(RailMarginSign.NEGATIVE_WHEN_BLOCKED, BigDecimal.ZERO))
        .isFalse();
    assertThat(RailMarginSign.contradicts(RailMarginSign.NEGATIVE_WHEN_BLOCKED, null)).isFalse();
  }

  /**
   * The name registry is gone, so nothing can drift by NAME — a future gate wired without a sign is
   * safe by construction (UNSIGNED asserts nothing). This records the current fleet state: no
   * shipped YAML arms the two-bound VWAP clause, so the armed path above is a guard, not live
   * behaviour. If one ever does, the armed case is already handled — update this expectation.
   */
  @Test
  void noShippedStrategyArmsTheTwoBoundVwapClause() throws IOException {
    assertThat(ScalperGates.VWAP_DISTANCE_MIN_FRAC)
        .as("the default min fraction keeps the VWAP-pin clause OFF")
        .isEqualByComparingTo(BigDecimal.ZERO);
    try (Stream<Path> yamls =
        Files.list(findModuleRoot().resolve("src/main/resources/scalper-strategies"))) {
      for (Path yaml : yamls.filter(p -> p.toString().endsWith(".yaml")).toList()) {
        assertThat(Files.readString(yaml))
            .withFailMessage(
                "%s arms vwap_distance_min_frac — the gate now derives UNSIGNED for that case, so"
                    + " this is no longer a defect; update this expectation",
                yaml.getFileName())
            .doesNotContain("vwap_distance_min_frac");
      }
    }
  }

  // ------------------------------------------------------------------------------- fixtures

  private static Oi oiImbalance(BigDecimal pct) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
        null, null, pct, false, false, null, null, null);
  }

  private static Oi oiDivergence(BigDecimal divergencePct, BigDecimal spurtPricePct) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"), null, null,
        null, false, false, null, null, spurtPricePct, divergencePct);
  }

  private static Macro macroBreadth(int adv, int dec) {
    return new Macro(bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, adv, dec, bd("50"), null, null);
  }

  private static Macro macroIv(BigDecimal ceIvAvg6, BigDecimal peIvAvg6) {
    return new Macro(
        bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, 40, 10, bd("50"), ceIvAvg6, peIvAvg6);
  }

  private static Macro macroFii(BigDecimal fiiLongPct) {
    return new Macro(bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, 40, 10, fiiLongPct, null, null);
  }

  private static final String GATE =
      "src/main/java/in/arthayantra/strategysignal/scalper/ScalperConfluenceGate.java";

  /** The module dir — surefire's cwd is the module, but tolerate a repo-root run too. */
  private static Path findModuleRoot() {
    Path cwd = Path.of("").toAbsolutePath();
    if (Files.exists(cwd.resolve(GATE))) {
      return cwd;
    }
    Path fromRepoRoot = cwd.resolve("services/strategy-signal-service");
    assertThat(fromRepoRoot.resolve(GATE)).as("gate source locatable").exists();
    return fromRepoRoot;
  }
}
