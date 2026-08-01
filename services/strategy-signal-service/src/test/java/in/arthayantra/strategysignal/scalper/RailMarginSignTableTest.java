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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * G17 / T14: the {@link RailMarginSigns} registry must be DERIVED from the gate's own operators,
 * never a hand-maintained list that drifts. Three mechanisms enforce that here:
 *
 * <ol>
 *   <li><b>Completeness</b>: a source scan of {@code ScalperConfluenceGate} (the same regex as
 *       {@code RailPolicyTableTest}) — every recorded rail must be declared, no phantoms.
 *   <li><b>Derivation</b>: every NEGATIVE/POSITIVE declaration is re-derived by probing the REAL
 *       {@link ScalperGates} operator on both sides of its threshold — a floor must fail below and
 *       pass above, a ceiling the mirror. If the operator's direction ever changes, the probe (not
 *       an eyeball) fails the build.
 *   <li><b>UNSIGNED justification</b>: each scalar rail declared UNSIGNED is proven to block with
 *       BOTH margin signs (side-dependent or conjunction operators), so the declaration is a
 *       measured impossibility, not laziness.
 * </ol>
 */
class RailMarginSignTableTest {

  private static final Pattern RAIL_LITERAL =
      Pattern.compile("(?:fails|failsBool|failsScore)\\(\\s*\"([a-z0-9-]+)\"");

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // ---------------------------------------------------------------- completeness (source scan)

  @Test
  void everyRailInTheGateHasADeclaredSignAndViceVersa() throws IOException {
    Set<String> inSource = railsDeclaredInGateSource();
    Set<String> inRegistry = new TreeSet<>(RailMarginSigns.all().keySet());

    assertThat(inSource).as("rails found in ScalperConfluenceGate source").isNotEmpty();

    Set<String> missing = new TreeSet<>(inSource);
    missing.removeAll(inRegistry);
    assertThat(missing)
        .withFailMessage(
            "these gate rails have NO declared RailMarginSign — add each to RailMarginSigns with"
                + " the sign its operator implies on a block (or UNSIGNED with a probe below): %s",
            missing)
        .isEmpty();

    Set<String> phantom = new TreeSet<>(inRegistry);
    phantom.removeAll(inSource);
    assertThat(phantom)
        .withFailMessage(
            "RailMarginSigns declares rails the gate no longer records (remove): %s", phantom)
        .isEmpty();
  }

  // ------------------------------------------- derivation probes: floors (blocked margin < 0)

  @Test
  void volumeFloorOperatorIsAFloor() {
    BigDecimal floor = bd("100");
    assertThat(ScalperGates.volume("NIFTY 50", bd("99"), floor).pass()).isFalse(); // below blocks
    assertThat(ScalperGates.volume("NIFTY 50", bd("101"), floor).pass()).isTrue(); // above passes
    assertThat(RailMarginSigns.of("volume-floor"))
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
  }

  @Test
  void psarDurabilityOperatorIsAFloor() {
    // distPct = |close-psar|/close*100 vs PSAR_DISTANCE_MIN_PCT (0.05%): 0.01% blocks, 1% passes.
    assertThat(ScalperGates.psarDurable(bd("100"), bd("100.01")).pass()).isFalse();
    assertThat(ScalperGates.psarDurable(bd("100"), bd("99")).pass()).isTrue();
    assertThat(RailMarginSigns.of("psar-durability"))
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
  }

  @Test
  void rsiRecoveryOperatorIsAFloor() {
    // Recent oversold trough (25 <= 30) arms the gate; current RSI below the 40 recovery level
    // blocks, at/above passes. (PE is inert by construction — never blocked, so no PE sign.)
    assertThat(ScalperGates.rsiRecovery(bd("25"), bd("35"), CE, bd("30"), bd("40")).pass())
        .isFalse();
    assertThat(ScalperGates.rsiRecovery(bd("25"), bd("45"), CE, bd("30"), bd("40")).pass())
        .isTrue();
    assertThat(ScalperGates.rsiRecovery(bd("25"), bd("35"), PE, bd("30"), bd("40")).pass())
        .as("PE mirror is inert — rsi-recovery can only ever block a CE, so CE's floor is THE sign")
        .isTrue();
    assertThat(RailMarginSigns.of("rsi-recovery")).isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
  }

  @Test
  void callPutDeltaFilterOperatorIsAFloor() {
    assertThat(ScalperGates.callPutDeltaFilter(oiImbalance(bd("40")), bd("50")).pass()).isFalse();
    assertThat(ScalperGates.callPutDeltaFilter(oiImbalance(bd("60")), bd("50")).pass()).isTrue();
    assertThat(RailMarginSigns.of("call-put-delta-filter"))
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
  }

  @Test
  void breadthOperatorIsAStrictFloorSoAZeroMarginBlockIsLegitimate() {
    // count > 32 (strict): 32 blocks WITH margin exactly 0 — which is why the seam check flags only
    // a STRICTLY positive margin on a floor rail, never zero (G16 read exactly 32 all session).
    assertThat(ScalperGates.breadth(macroBreadth(32, 10), CE).pass()).isFalse();
    assertThat(ScalperGates.breadth(macroBreadth(33, 10), CE).pass()).isTrue();
    assertThat(ScalperGates.breadth(macroBreadth(10, 33), PE).pass())
        .as("PE reads DECLINES against the same 32 floor — same direction, not side-inverted")
        .isTrue();
    assertThat(RailMarginSigns.of("breadth-gate")).isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
    assertThat(RailMarginSigns.contradicts("breadth-gate", BigDecimal.ZERO))
        .as("a margin of exactly zero is never a contradiction (strict operators block AT it)")
        .isFalse();
  }

  @Test
  void confluenceCompositeMarginIsNullOrNegativeOnABlockNeverPositive() {
    // The B5/#985 pin: a scalar shortfall keeps the negative margin; a decisive-leg block (aggregate
    // cleared the threshold but a leg failed) records NULL — so any NON-NULL blocked margin is the
    // scalar shortfall and must be negative. A positive one is the 07-20 §6.3 contradiction class.
    assertThat(ScalperConfluenceGate.compositeMargin(false, bd("0.5"), bd("0.6")))
        .isEqualByComparingTo("-0.1");
    assertThat(ScalperConfluenceGate.compositeMargin(false, bd("0.7"), bd("0.6")))
        .as("decisive-leg block records no scalar margin — a positive 'margin' would lie")
        .isNull();
    assertThat(RailMarginSigns.of("confluence-composite"))
        .isEqualTo(RailMarginSign.NEGATIVE_WHEN_BLOCKED);
  }

  // ---------------------------------------- derivation probes: ceilings (blocked margin > 0)

  @Test
  void vwapDistanceOperatorIsACeilingWithTheMinClauseOff() {
    // frac = |close-vwap|/close vs maxFrac: 0.01 > 0.004 blocks (margin POSITIVE — the 2026-07-23
    // §2.3 rows were CORRECT), 0.001 passes. Min clause off (null), the shipped configuration.
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("99"), null, bd("0.004")).pass()).isFalse();
    assertThat(ScalperGates.vwapDistance(bd("100"), bd("99.9"), null, bd("0.004")).pass()).isTrue();
    assertThat(RailMarginSigns.of("vwap-distance"))
        .isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
  }

  @Test
  void indicatorDistanceVetoOperatorIsACeiling() {
    Chart far = new Chart(bd("100"), bd("99"), null, null, 1, bd("65"), bd("60000"));
    Chart near = new Chart(bd("100"), bd("99.9"), null, null, 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(far, bd("0.004")).pass()).isFalse(); // 0.01 > max
    assertThat(ScalperGates.indicatorDistance(near, bd("0.004")).pass()).isTrue(); // 0.001 <= max
    assertThat(RailMarginSigns.of("indicator-distance-veto"))
        .isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
  }

  @Test
  void ivBuyerCapOperatorIsACeiling() {
    assertThat(ScalperGates.ivBuyerCap(macroIv(bd("0.45"), null), CE, bd("0.40")).pass()).isFalse();
    assertThat(ScalperGates.ivBuyerCap(macroIv(bd("0.35"), null), CE, bd("0.40")).pass()).isTrue();
    assertThat(RailMarginSigns.of("iv-buyer-cap")).isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
  }

  @Test
  void gapSizeSideGateBlocksOnlyAtOrAboveTheSuppressionPoints() {
    GapState.Gap bigDown = new GapState.Gap(true, false, bd("400"), bd("25000"), false);
    GapState.Gap smallDown = new GapState.Gap(true, false, bd("200"), bd("25000"), false);
    assertThat(ScalperGates.gapSizeSide(bigDown, PE, bd("300")).pass()).isFalse(); // 400 >= 300
    assertThat(ScalperGates.gapSizeSide(smallDown, PE, bd("300")).pass()).isTrue(); // 200 < 300
    assertThat(ScalperGates.gapSizeSide(bigDown, CE, bd("300")).pass())
        .as("CE never blocks here — only the PE side is suppressed, so the PE ceiling is THE sign")
        .isTrue();
    assertThat(RailMarginSigns.of("gap-size-side-gate"))
        .isEqualTo(RailMarginSign.POSITIVE_WHEN_BLOCKED);
  }

  // ------------------------------- UNSIGNED justifications: blocked with BOTH margin signs

  @Test
  void pctPriceMoveBlocksWithBothMarginSignsSoNoSingleSignExists() {
    // CE fail: move +0.5% < +1% floor -> margin vs the recorded threshold (+1) is NEGATIVE.
    var ceBlock = ScalperGates.pctPriceMove(bd("100.5"), bd("100"), bd("1"), CE);
    assertThat(ceBlock.pass()).isFalse();
    assertThat(ceBlock.operand().subtract(bd("1")).signum()).isNegative();
    // PE fail: move +2% (needs <= -1%) -> margin vs the SAME recorded threshold is POSITIVE.
    var peBlock = ScalperGates.pctPriceMove(bd("102"), bd("100"), bd("1"), PE);
    assertThat(peBlock.pass()).isFalse();
    assertThat(peBlock.operand().subtract(bd("1")).signum()).isPositive();
    assertThat(RailMarginSigns.of("pct-price-move")).isEqualTo(RailMarginSign.UNSIGNED);
  }

  @Test
  void fiiBiasBlocksWithBothMarginSignsSoNoSingleSignExists() {
    // CE fail: longPct 40 < 50 -> margin NEGATIVE; PE fail: longPct 60 > 50 -> margin POSITIVE.
    var ceBlock = ScalperGates.fiiBias(macroFii(bd("40")), CE);
    assertThat(ceBlock.pass()).isFalse();
    assertThat(ceBlock.operand().subtract(ScalperGates.FII_NEUTRAL_PCT).signum()).isNegative();
    var peBlock = ScalperGates.fiiBias(macroFii(bd("60")), PE);
    assertThat(peBlock.pass()).isFalse();
    assertThat(peBlock.operand().subtract(ScalperGates.FII_NEUTRAL_PCT).signum()).isPositive();
    assertThat(RailMarginSigns.of("fii-bias")).isEqualTo(RailMarginSign.UNSIGNED);
  }

  @Test
  void oiDivergenceMagnitudeIsAConjunctionSoTheOperandCanClearWhileTheRailBlocks() {
    // CE: divergence +30 clears the +20 floor but the price-impulse leg (10 < 50) blocks -> the
    // recorded div-margin is POSITIVE on a legitimate block (the pre-B5 composite class, by design).
    var legBlock =
        ScalperGates.oiDivergenceMagnitude(oiDivergence(bd("30"), bd("10")), bd("20"), bd("50"), CE);
    assertThat(legBlock.pass()).isFalse();
    assertThat(legBlock.operand().subtract(bd("20")).signum()).isPositive();
    // CE: divergence 10 short of the 20 floor -> margin NEGATIVE. Both signs block => UNSIGNED.
    var scalarBlock =
        ScalperGates.oiDivergenceMagnitude(oiDivergence(bd("10"), bd("60")), bd("20"), bd("50"), CE);
    assertThat(scalarBlock.pass()).isFalse();
    assertThat(scalarBlock.operand().subtract(bd("20")).signum()).isNegative();
    assertThat(RailMarginSigns.of("oi-divergence-magnitude")).isEqualTo(RailMarginSign.UNSIGNED);
  }

  // ------------------------------------------------- the vwap-distance min-clause guard rails

  @Test
  void vwapDistancePositiveDeclarationHoldsOnlyWhileTheMinClauseStaysUnarmed() throws IOException {
    // The operator has a SECOND clause: with minFrac > 0 a too-CLOSE entry blocks with a NEGATIVE
    // margin against the recorded max threshold — which the POSITIVE declaration would mis-flag.
    var tooClose = ScalperGates.vwapDistance(bd("100"), bd("99.9"), bd("0.002"), bd("0.004"));
    assertThat(tooClose.pass()).as("armed min clause blocks a too-close entry").isFalse();
    assertThat(tooClose.operand().subtract(bd("0.004")).signum()).isNegative();

    // So the declaration is guarded twice: the shipped DEFAULT keeps the clause off...
    assertThat(ScalperGates.VWAP_DISTANCE_MIN_FRAC)
        .as("the default min fraction keeps the VWAP-pin clause OFF")
        .isEqualByComparingTo(BigDecimal.ZERO);

    // ...and no bundled YAML arms it. If either moves, flip vwap-distance to UNSIGNED in
    // RailMarginSigns (this test is the tripwire; a DB-tuned param would bypass it — accepted for
    // a WARN-only diagnostic, documented on the registry entry).
    try (Stream<Path> yamls =
        Files.list(findModuleRoot().resolve("src/main/resources/scalper-strategies"))) {
      for (Path yaml : yamls.filter(p -> p.toString().endsWith(".yaml")).toList()) {
        assertThat(Files.readString(yaml))
            .withFailMessage(
                "%s arms vwap_distance_min_frac — vwap-distance can now block with EITHER margin"
                    + " sign, so its RailMarginSigns entry must become UNSIGNED",
                yaml.getFileName())
            .doesNotContain("vwap_distance_min_frac");
      }
    }
  }

  // --------------------------------------------------------------- contradicts() sign matrix

  @Test
  void contradictsFlagsOnlyTheStrictlyWrongSideOfEachOperator() {
    // The 2026-07-23 §2.3 id-7794 shape: composite blocked with +0.0373 — the contradiction class.
    assertThat(RailMarginSigns.contradicts("confluence-composite", bd("0.0373"))).isTrue();
    assertThat(RailMarginSigns.contradicts("volume-floor", bd("1"))).isTrue();
    assertThat(RailMarginSigns.contradicts("vwap-distance", bd("-0.001"))).isTrue();
    // Correct blocks never flag: floor short, ceiling over (the §2.3 correct vwap rows), zero, null.
    assertThat(RailMarginSigns.contradicts("volume-floor", bd("-5"))).isFalse();
    assertThat(RailMarginSigns.contradicts("vwap-distance", bd("0.0004"))).isFalse();
    assertThat(RailMarginSigns.contradicts("volume-floor", BigDecimal.ZERO)).isFalse();
    assertThat(RailMarginSigns.contradicts("volume-floor", null)).isFalse();
    // UNSIGNED and unknown rails are never flagged (and never throw — this sits on a live seam).
    assertThat(RailMarginSigns.contradicts("pct-price-move", bd("7"))).isFalse();
    assertThat(RailMarginSigns.contradicts("some-future-rail", bd("7"))).isFalse();
    assertThat(RailMarginSigns.of("some-future-rail")).isEqualTo(RailMarginSign.UNSIGNED);
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
    return new Macro(bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, 40, 10, bd("50"), ceIvAvg6, peIvAvg6);
  }

  private static Macro macroFii(BigDecimal fiiLongPct) {
    return new Macro(bd("14"), bd("30"), bd("12.5"), Boolean.FALSE, 40, 10, fiiLongPct, null, null);
  }

  private static Set<String> railsDeclaredInGateSource() throws IOException {
    Path src =
        findModuleRoot()
            .resolve(
                "src/main/java/in/arthayantra/strategysignal/scalper/ScalperConfluenceGate.java");
    Matcher m = RAIL_LITERAL.matcher(Files.readString(src));
    Set<String> rails = new TreeSet<>();
    while (m.find()) {
      rails.add(m.group(1));
    }
    return rails;
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
