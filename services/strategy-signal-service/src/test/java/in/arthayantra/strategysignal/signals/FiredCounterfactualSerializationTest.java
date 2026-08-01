package in.arthayantra.strategysignal.signals;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.Confluence;
import in.arthayantra.strategysignal.scalper.FiredDiagnosticJson;
import in.arthayantra.strategysignal.scalper.OiQuadrant;
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
 * F5 U4b Part 1: the ARMED-policy counterfactual is RECORDED on the fired side.
 *
 * <p><b>Why the fired side specifically.</b> {@code dot-null-withheld} moves the composite BOTH ways
 * — withholding an opposes-in-denominator dot raises it, withholding one that currently reads a null
 * as SUPPORT ({@code vix} / {@code basis}) lowers it. The shadow book measures the LOOSENING half
 * (champion-rejected bars the armed policy would fire) but is structurally blind to the TIGHTENING
 * half, because its writer fires on the rejection path only — it never sees a champion-FIRED entry
 * the unified rule would have removed. Persisting the counterfactual here is what makes that half
 * visible, and it needs no simulation: those trades really happened and already carry realized PnL,
 * so the measurement is a subtraction over {@code signals}, not a second virtual book.
 *
 * <p><b>The verdict is NOT the scalar.</b> A side is valid only when the scalar clears the threshold
 * AND the three decisive legs hold, so both keys are required to answer "would the armed policy have
 * fired?". Recording {@code withheldAggregate} alone would reproduce the exact defect the U4b review
 * caught in {@code ShadowVariants}.
 *
 * <p>{@code signals.fired_diagnostic} is JSONB (V035) so these are additive keys with NO migration,
 * and the column has no read path in Java and no springdoc surface (zero occurrences in the committed
 * spec) — so nothing here can drift the contract. The golden writer is {@code GoldenSignalsJson.write}
 * in {@code libs/strategy-engine}, which has no reference to {@code ConnectTheDotsScorer} at all.
 */
class FiredCounterfactualSerializationTest {

  private static final ObjectMapper OM = new ObjectMapper();
  private static final BigDecimal T = new BigDecimal("0.6");
  private static final ScalperOiProps P = ScalperOiProps.defaults();

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static final Chart BULL_CHART =
      new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000"));

  // The U4b fixture: every present dot aligns CE, and four inputs are genuinely missing —
  //   futures_oi (1.5) NEUTRAL quadrant | vix (1.0) null | basis (1.0) null | iv_rank (0.8) null
  // LEGACY   17.3/18.8 = 0.9202   (futures_oi scored AGAINST; vix + basis scored FOR)
  // WITHHELD 15.3/15.3 = 1.0      (all four leave both sides)
  private static final Oi GAPPY_OI =
      new Oi(
          OiQuadrant.LONG_BUILDUP, OiQuadrant.NEUTRAL, bd("10"), bd("5"), null,
          bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"));
  private static final Macro GAPPY_MACRO =
      new Macro(bd("14"), null, bd("12"), null, 40, 10, bd("50"), bd("0.20"), bd("0.05"));

  private static ScalperGateContext context() {
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART, GAPPY_OI, GAPPY_MACRO);
  }

  /** Scored on the UNARMED (LEGACY) policy — the live default; the counterfactual rides alongside. */
  private static Confluence confluence(int bias60mDir) {
    return ConnectTheDotsScorer.score(context(), CE, bias60mDir, T, P, true);
  }

  private static StrikePicker.Pick pick() {
    return new StrikePicker.Pick(
        new StrikePicker.Candidate("NFO", "NIFTY26AUG25000CE", bd("25000"), CE, bd("152.65"), bd("14")),
        bd("0.65"));
  }

  private static JsonNode firedConfluence(int bias60mDir) {
    ScalperConfluenceGate.FiredDiagnostic d =
        new ScalperConfluenceGate.FiredDiagnostic(
            CE, confluence(bias60mDir).aggregate(), T, List.of(), confluence(bias60mDir), context(),
            "NIFTY 50", LocalDate.of(2026, 8, 27), pick(), null);
    try {
      return OM.readTree(FiredDiagnosticJson.write(OM, d)).get("confluence");
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
   * VERIFY GOAL — the red-proof, asserted on VALUES not on key presence. On a bar with four genuinely
   * withheld inputs the recorded counterfactual is 1.0 against a live aggregate of 0.9202: a real,
   * specific number the armed policy would have produced. Reverting either {@code c.put} drops the key
   * and fails at {@code get(...)}; emitting a constant, or emitting the live aggregate by mistake,
   * fails on the value.
   */
  @Test
  void theFiredRowRecordsWhatTheArmedPolicyWouldHaveScored() {
    JsonNode conf = firedConfluence(1);

    assertThat(conf.get("withheldAggregate").decimalValue())
        .as("what the composite WOULD have been with every input-missing dot withheld (15.3/15.3)")
        .isEqualByComparingTo("1.0");
    assertThat(conf.get("decisiveLegsHeld").asBoolean())
        .as("the scalar is not the verdict — the three decisive legs must be recorded with it")
        .isTrue();

    // …and it is NOT merely a copy of the live aggregate: the two differ by exactly the withholding.
    assertThat(conf.get("aggregate").decimalValue()).isEqualByComparingTo("0.9202");
    assertThat(conf.get("withheldAggregate").decimalValue())
        .isNotEqualByComparingTo(conf.get("aggregate").decimalValue());
  }

  /**
   * The discriminating case a constant {@code true} would pass and must not. Flipping ONLY the 60m
   * bias against the side leaves the scalar untouched — both aggregates are identical to the test
   * above — while the decisive legs no longer hold, so the armed policy would NOT have fired even
   * though its composite still clears the threshold. That is precisely the champion-fired /
   * armed-rejects cell this key exists to identify.
   */
  @Test
  void decisiveLegsHeldTracksTheRealVerdictNotAConstant() {
    JsonNode aligned = firedConfluence(1);
    JsonNode biasAgainst = firedConfluence(-1);

    assertThat(biasAgainst.get("decisiveLegsHeld").asBoolean()).isFalse();
    assertThat(aligned.get("decisiveLegsHeld").asBoolean()).isTrue();
    // The scalar half is untouched by the bias, so the two keys are genuinely independent facts —
    // a reader that looked only at withheldAggregate would misread this bar as "armed would fire".
    assertThat(biasAgainst.get("withheldAggregate").decimalValue())
        .isEqualByComparingTo(aligned.get("withheldAggregate").decimalValue())
        .isEqualByComparingTo("1.0");
    assertThat(biasAgainst.get("aggregate").decimalValue())
        .isEqualByComparingTo(aligned.get("aggregate").decimalValue());
  }

  /**
   * The NUMERIC side is unchanged — this PR records a fact, it does not alter one. Same aggregate,
   * same dot list (names, order, weights, supports, absent). Only the two counterfactual keys appear,
   * asserted as an EXACT key set so an accidental extra or dropped key fails.
   */
  @Test
  void onlyTheTwoCounterfactualKeysAreAddedAndTheNumbersDoNotMove() {
    JsonNode conf = firedConfluence(1);

    assertThat(keys(conf))
        .isEqualTo(
            Set.of(
                "aggregate", "threshold", "bullish", "bearish", "vwapAligned", "biasAligned",
                "standAside", "withheldAggregate", "decisiveLegsHeld", "dots"));

    // The dot array is byte-for-byte the same shape and content it was before.
    Confluence source = confluence(1);
    JsonNode dots = conf.get("dots");
    assertThat(dots).hasSize(source.dots().size()).hasSize(18);
    for (int i = 0; i < source.dots().size(); i++) {
      ConnectTheDotsScorer.DotScore expected = source.dots().get(i);
      JsonNode actual = dots.get(i);
      assertThat(keys(actual)).isEqualTo(Set.of("dot", "weight", "supports", "reason", "absent"));
      assertThat(actual.get("dot").asText()).isEqualTo(expected.dot());
      assertThat(actual.get("weight").asDouble()).isEqualTo(expected.weight());
      assertThat(actual.get("supports").asBoolean()).isEqualTo(expected.supports());
      assertThat(actual.get("absent").asBoolean()).isEqualTo(expected.absent());
      assertThat(actual.get("reason").asText()).isEqualTo(expected.reason());
    }
    assertThat(conf.get("aggregate").decimalValue()).isEqualByComparingTo(source.aggregate());
  }
}
