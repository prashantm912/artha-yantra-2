package in.arthayantra.strategysignal.signals;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.Confluence;
import in.arthayantra.strategysignal.scalper.FiredDiagnosticJson;
import in.arthayantra.strategysignal.scalper.OiQuadrant;
import in.arthayantra.strategysignal.scalper.RailMarginSign;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.scalper.ScalperGateContext;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import in.arthayantra.strategysignal.scalper.ScalperOiProps;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shadow verdict is RECORDED, on both diagnostic surfaces, in lockstep.
 *
 * <p>{@code signals.fired_diagnostic} and {@code signal_rejections.diagnostic} are JSONB (V035 /
 * V022) so these are additive keys with NO migration; neither column has a springdoc surface (zero
 * occurrences of {@code fired_diagnostic} in any committed spec), so nothing here can drift the
 * contract. The golden writer is {@code GoldenSignalsJson.write} in {@code libs/strategy-engine},
 * which references none of these classes — {@code libs/} and {@code backtest-service} have zero
 * references to {@code ScalperGateContext} / {@code ConnectTheDotsScorer} / {@code MarketOiClient}.
 *
 * <p><b>Population — ENTRY path only.</b> These two writers cover every bar on which the operand is
 * read while DECIDING AN ENTRY: a bar that fires, and a bar the confluence gate blocks. A chart-stage
 * block never reaches the gate and never consults sentiment, so it correctly has no row.
 *
 * <p>⚠️ <b>They do NOT cover the EXIT path, and an earlier version of this note wrongly claimed they
 * covered every read.</b> The E9 D4 confluence-flip exit oracle ({@code SignalEngine.confluenceFlipExit},
 * tag {@code oi-confluence-exit}, armed on 12 shipped YAMLs) RE-RUNS the same confluence gate on every
 * bar a position is held, and that call produces neither diagnostic. Left uncovered, a future
 * flow→level swap could move EXIT TIMING and realized P&L with no counterfactual evidence at all —
 * biasing the eventual legs→P&L comparison in an unknown direction rather than merely thinning it.
 * That half is captured separately in {@code exit_oracle_shadow} (V056, {@link ExitOracleShadowWriter});
 * the two populations together are the complete set of sentiment reads. Found by cross-vendor review.
 */
class SentimentLevelShadowSerializationTest {

  private static final ObjectMapper OM = new ObjectMapper();
  private static final BigDecimal T = new BigDecimal("0.6");
  private static final ScalperOiProps P = ScalperOiProps.defaults();

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static final Chart BULL_CHART =
      new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000"));
  private static final Macro MACRO =
      new Macro(bd("14"), null, bd("12"), null, 40, 10, bd("50"), bd("0.20"), bd("0.05"));

  /** Flow exactly 0.00 (the coarse-OI tick) with a decisive +30 level — the divergence bar. */
  private static Oi oi(BigDecimal level) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("0.00"), bd("5"), bd("12"),
        bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"), bd("25"),
        level);
  }

  private static ScalperGateContext context(BigDecimal level) {
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART, oi(level), MACRO);
  }

  private static Confluence confluence(BigDecimal level) {
    return ConnectTheDotsScorer.score(context(level), CE, 1, T, P, true);
  }

  private static StrikePicker.Pick pick() {
    return new StrikePicker.Pick(
        new StrikePicker.Candidate("NFO", "NIFTY26AUG25000CE", bd("25000"), CE, bd("152.65"), bd("14")),
        bd("0.65"));
  }

  private static JsonNode fired(BigDecimal level, ScalperGateContext ctx) {
    ScalperConfluenceGate.FiredDiagnostic d =
        new ScalperConfluenceGate.FiredDiagnostic(
            CE, confluence(level).aggregate(), T, List.of(), confluence(level), ctx, "NIFTY 50",
            LocalDate.of(2026, 8, 27), pick(), null);
    return read(FiredDiagnosticJson.write(OM, d));
  }

  private static JsonNode rejected(BigDecimal level, ScalperGateContext ctx) {
    ScalperConfluenceGate.RejectionDiagnostic d =
        new ScalperConfluenceGate.RejectionDiagnostic(
            "oi-slope-agree", CE, bd("5"), null, null, "level/slope disagree",
            confluence(level).aggregate(), T, List.of(), confluence(level), ctx, "NIFTY 50",
            LocalDate.of(2026, 8, 27), pick(), null, RailMarginSign.NEGATIVE_WHEN_BLOCKED);
    return read(SignalEngine.rejectionDiagnosticJson(OM, d));
  }

  private static JsonNode read(String json) {
    try {
      return OM.readTree(json);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static Set<String> keys(JsonNode node) {
    List<String> out = new ArrayList<>();
    node.fieldNames().forEachRemaining(out::add);
    return Set.copyOf(out);
  }

  /**
   * VERIFY GOAL — asserted on VALUES, not on key presence. On the divergence bar the recorded block
   * carries both operands and both counterfactual verdicts: flow 0.00 (which the live reads fail),
   * level 30, and TRUE/TRUE for what the level read would have said. Dropping either {@code put}
   * fails at {@code get(...)}; emitting the flow verdict by mistake fails on the boolean.
   */
  @Test
  void theFiredRowRecordsBothOperandsAndBothCounterfactualVerdicts() {
    JsonNode shadow = fired(bd("30"), context(bd("30"))).get("sentimentLevelShadow");

    assertThat(keys(shadow))
        .isEqualTo(Set.of("flowPct", "levelPct", "dotWouldSupport", "slopeGateWouldPass", "reason"));
    assertThat(shadow.get("flowPct").decimalValue()).isEqualByComparingTo("0.00");
    assertThat(shadow.get("levelPct").decimalValue()).isEqualByComparingTo("30");
    assertThat(shadow.get("dotWouldSupport").asBoolean()).isTrue();
    assertThat(shadow.get("slopeGateWouldPass").asBoolean()).isTrue();
    assertThat(shadow.path("reason").asText()).isEqualTo("COMPUTED");
  }

  /**
   * Lockstep: the rejected side records the SAME block, byte-for-byte, on the same bar. The sentiment
   * operand is read on every bar that reaches the gate, so — unlike the U4b counterfactual keys — this
   * one has a real counterpart on both sides and must never become fired-only.
   */
  @Test
  void theRejectedRowRecordsTheIdenticalBlock() {
    JsonNode firedShadow = fired(bd("30"), context(bd("30"))).get("sentimentLevelShadow");
    JsonNode rejectedShadow = rejected(bd("30"), context(bd("30"))).get("sentimentLevelShadow");

    assertThat(rejectedShadow).isEqualTo(firedShadow);
  }

  /**
   * The safe degrade, end to end: an absent {@code sentimentLevelPct} still records the flow operand
   * and serializes both verdicts as JSON null — key present, value null. A reader can then tell "no
   * level available" apart from "the level said no", which is the whole reason the verdicts are
   * nullable rather than fail-closed booleans.
   */
  @Test
  void aMissingLevelSerializesNullVerdictsWithoutFailing() {
    for (JsonNode root : List.of(fired(null, context(null)), rejected(null, context(null)))) {
      JsonNode shadow = root.get("sentimentLevelShadow");
      assertThat(shadow.get("flowPct").decimalValue()).isEqualByComparingTo("0.00");
      assertThat(shadow.get("levelPct").isNull()).isTrue();
      assertThat(shadow.get("dotWouldSupport").isNull()).isTrue();
      assertThat(shadow.get("slopeGateWouldPass").isNull()).isTrue();
      // …and it says WHY: this fixture is an ordinary bar whose level operand is simply absent, so
      // the row must NOT claim the monthly-expiry suppression that produces identical nulls.
      assertThat(shadow.path("reason").asText()).isEqualTo("LEVEL_UNAVAILABLE");
    }
  }

  /**
   * The discriminator is REAL on the wire, not merely on the record: the two rows whose confusion
   * prompted this change — a suppressed monthly expiry and a plain missing level — are identical in
   * all four operand/verdict fields and differ ONLY in {@code reason}. An implementation that always
   * emitted the same code would serialize two indistinguishable blocks and fail the last assertion.
   */
  @Test
  void twoRowsWithIdenticalNullsAreToldApartByTheReasonAlone() {
    ScalperGateContext suppressed =
        new ScalperGateContext(
            "NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART,
            Oi.monthlyExpirySuppressed(bd("12")), MACRO);
    // Same shape, NOT suppressed: quadrants NEUTRAL and every OI soft-numeric null — what four
    // failed reads produce. Byte-identical to the above everywhere except the provenance flag.
    ScalperGateContext unavailable =
        new ScalperGateContext(
            "NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART,
            new Oi(
                OiQuadrant.NEUTRAL, OiQuadrant.NEUTRAL, null, null, bd("12"), null, null, null,
                false, false, null, null, null, null, null),
            MACRO);

    JsonNode a = fired(null, suppressed).get("sentimentLevelShadow");
    JsonNode b = fired(null, unavailable).get("sentimentLevelShadow");

    for (String operand : List.of("flowPct", "levelPct", "dotWouldSupport", "slopeGateWouldPass")) {
      assertThat(a.get(operand)).as("%s must be null on BOTH rows", operand).isEqualTo(b.get(operand));
      assertThat(a.get(operand).isNull()).isTrue();
    }
    assertThat(a.path("reason").asText()).isEqualTo("MONTHLY_EXPIRY_SUPPRESSED");
    assertThat(b.path("reason").asText()).isEqualTo("LEVEL_UNAVAILABLE");
  }

  /**
   * A diagnostic with no OI context at all (the gate blocked before {@code context()} resolved) must
   * still serialize the key with four nulls rather than omitting it or throwing — the shape stays
   * stable across every row in the table, which is what makes a straight SQL projection safe.
   */
  @Test
  void aContextlessDiagnosticStillCarriesTheKeyWithNulls() {
    JsonNode shadow = fired(bd("30"), null).get("sentimentLevelShadow");

    assertThat(keys(shadow))
        .isEqualTo(Set.of("flowPct", "levelPct", "dotWouldSupport", "slopeGateWouldPass", "reason"));
    assertThat(shadow.get("flowPct").isNull()).isTrue();
    assertThat(shadow.get("dotWouldSupport").isNull()).isTrue();
    // The four nulls stay four nulls; only the reason distinguishes this row from a suppressed one.
    assertThat(shadow.path("reason").asText()).isEqualTo("NO_OI_CONTEXT");
  }

  /**
   * Nothing ELSE moved. The pre-existing top-level key set is intact plus exactly one new key, and
   * {@code context.oi} — deliberately left alone, so existing queries and the fired/rejected contrast
   * keep their shape — is byte-identical between a bar with a level and the same bar without one.
   */
  @Test
  void exactlyOneRootKeyIsAddedAndTheContextBlockDoesNotMove() {
    JsonNode withLevel = fired(bd("30"), context(bd("30")));
    JsonNode without = fired(null, context(null));

    assertThat(keys(withLevel))
        .isEqualTo(
            Set.of(
                "blockingRail", "side", "operand", "threshold", "margin", "reason", "compositeScore",
                "compositeThreshold", "firedLeg", "checks", "confluence", "sentimentLevelShadow",
                "context"));
    assertThat(withLevel.get("context")).isEqualTo(without.get("context"));
    assertThat(withLevel.get("confluence")).isEqualTo(without.get("confluence"));
  }
}
