package in.arthayantra.strategysignal.signals;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.Confluence;
import in.arthayantra.strategysignal.scalper.FiredDiagnosticJson;
import in.arthayantra.strategysignal.scalper.OiQuadrant;
import in.arthayantra.strategysignal.scalper.ScalperConfig;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.scalper.ScalperGateContext;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import in.arthayantra.strategysignal.scalper.ScalperOiProps;
import in.arthayantra.strategysignal.scalper.StrikePicker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * F5 U4a / dead-dot decision A6: ABSENTNESS IS A RECORDED FACT, not an arithmetic inference.
 *
 * <p>{@link ConnectTheDotsScorer.DotScore#absent()} has existed since the P3 freeze — a dot whose
 * INPUT is missing is withheld from BOTH the numerator and the denominator, so a data gap is never
 * scored as evidence against the side. But none of the three hand-rolled per-dot serializers wrote
 * it, so in the persisted forensics an ABSENT dot and a PRESENT-but-unsupporting dot were the same
 * three keys: {@code dot}/{@code weight}/{@code supports}, both with {@code supports:false}.
 *
 * <p><b>The cost this removes.</b> The G13 iv-bloc counterfactual had to REVERSE-ENGINEER absentness
 * from arithmetic — noticing the effective weight sum was 18.80 rather than 19.60 and working
 * backwards to "the 0.80-weight {@code iv_rank} dot must have been withheld". That inference is
 * exactly what {@link #anAbsentDotAndAnUnsupportingDotAreDistinguishableInEveryPersistedShape()}
 * makes unnecessary, and the two aggregates it pins (1.0 vs 0.9592) are the very numbers that had to
 * be decoded by hand.
 *
 * <p>All three serializers are LIVE-ONLY JSONB ({@code signals.scalper_detail} V009, {@code
 * signals.fired_diagnostic} V035, {@code strategy.signal_rejections.diagnostic} V015). The frozen
 * golden writer is {@code GoldenSignalsJson.write} in {@code libs/strategy-engine}, a module with no
 * reference to {@code ConnectTheDotsScorer} at all — so nothing here can move a golden byte.
 */
class DotAbsentSerializationTest {

  private static final ObjectMapper OM = new ObjectMapper();
  private static final BigDecimal T = new BigDecimal("0.6");
  private static final ScalperOiProps P = ScalperOiProps.defaults();

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // The all-CE-aligned fixture from ConnectTheDotsScorerTest: every chart/OI/macro dot points CE, so
  // the ONLY dot that can withhold support is the one whose input we vary.
  private static final Chart BULL_CHART =
      new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000"));
  private static final Oi BULL_OI =
      new Oi(
          OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"),
          bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"));

  private static final ScalperConfig CFG =
      new ScalperConfig(
          "NSE", "NIFTY 50", "NIFTY 50", "NIFTY 50", 2,
          new StrikePicker.Params(0.6, 0.7, bd("100"), bd("400"), 0.065), bd("0.6"),
          false, ScalperConfig.StructuralStop.NONE, false, false, false, false, false, false, false,
          false, false);

  /**
   * {@code ivRank} null exercises the absent/withheld path; a value exercises the present path.
   *
   * <p>Scored with the A3 {@code iv-rank-dot} tag ARMED (the trailing {@code true}). The dot is
   * default-OFF — unarmed it is withheld whatever the input says, so a present rank could not reach
   * the present path at all and this fixture would lose its discriminating pair. Arming here keeps
   * this test's subject exactly what it was: how {@code absent} SERIALIZES, not when it is set.
   */
  private static Confluence confluence(BigDecimal ivRank) {
    Macro macro =
        new Macro(bd("14"), ivRank, bd("12"), Boolean.FALSE, 40, 10, bd("50"), bd("0.20"), bd("0.05"));
    ScalperGateContext ctx =
        new ScalperGateContext("NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART, BULL_OI, macro);
    return ConnectTheDotsScorer.score(ctx, CE, 1, T, P, true, false, false, false, null, true);
  }

  private static ScalperGateContext context(BigDecimal ivRank) {
    Macro macro =
        new Macro(bd("14"), ivRank, bd("12"), Boolean.FALSE, 40, 10, bd("50"), bd("0.20"), bd("0.05"));
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART, BULL_OI, macro);
  }

  private static StrikePicker.Pick pick() {
    return new StrikePicker.Pick(
        new StrikePicker.Candidate("NFO", "NIFTY26AUG25000CE", bd("25000"), CE, bd("152.65"), bd("14")),
        bd("0.65"));
  }

  /** The three live-only serializers, each keyed by the JSONB column it feeds. */
  private static String scalperDetail(BigDecimal ivRank) {
    Confluence conf = confluence(ivRank);
    ScalperConfluenceGate.Decision d =
        new ScalperConfluenceGate.Decision(
            CE, List.of(new ScalperConfluenceGate.Leg(CE, pick())), conf,
            LocalDate.of(2026, 8, 27), null, null, null, null);
    return SignalEngine.scalperDetailJson(OM, d, CFG);
  }

  private static String rejectionDiagnostic(BigDecimal ivRank) {
    ScalperConfluenceGate.RejectionDiagnostic d =
        new ScalperConfluenceGate.RejectionDiagnostic(
            "confluence-composite", CE, bd("0.50"), T, bd("-0.10"), "below threshold",
            bd("0.50"), T, List.of(), confluence(ivRank), context(ivRank),
            "NIFTY 50", LocalDate.of(2026, 8, 27), pick(), null);
    return SignalEngine.rejectionDiagnosticJson(OM, d);
  }

  private static String firedDiagnostic(BigDecimal ivRank) {
    ScalperConfluenceGate.FiredDiagnostic d =
        new ScalperConfluenceGate.FiredDiagnostic(
            CE, bd("1.0"), T, List.of(), confluence(ivRank), context(ivRank),
            "NIFTY 50", LocalDate.of(2026, 8, 27), pick(), null);
    return FiredDiagnosticJson.write(OM, d);
  }

  /** The {@code dots[]} array wherever each serializer nests it. */
  private static JsonNode dots(String json, boolean nestedUnderConfluence) {
    try {
      JsonNode root = OM.readTree(json);
      return nestedUnderConfluence ? root.get("confluence").get("dots") : root.get("dots");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static JsonNode dot(JsonNode dots, String name) {
    for (JsonNode n : dots) {
      if (name.equals(n.get("dot").asText())) {
        return n;
      }
    }
    throw new AssertionError("no dot named " + name);
  }

  private static Set<String> keys(JsonNode node) {
    List<String> out = new ArrayList<>();
    node.fieldNames().forEachRemaining(out::add);
    return Set.copyOf(out);
  }

  /** One persisted JSONB column, its serializer, and whether {@code dots[]} nests under confluence. */
  private record Shape(String column, Function<BigDecimal, String> writer, boolean nested) {}

  /**
   * VERIFY GOAL 1 — the red-proof. In EVERY persisted shape, the withheld {@code iv_rank} (null
   * input) reads {@code absent:true} while the present-but-unsupporting one (IV rank 80, above the
   * "low = cheap premium" cut of 50) reads {@code absent:false}. Both carry {@code supports:false},
   * which is precisely why the flag is needed: without it these two rows are byte-identical.
   *
   * <p>Against the pre-change serializers every {@code has("absent")} assertion below fails — the key
   * did not exist in any of the three.
   */
  @Test
  void anAbsentDotAndAnUnsupportingDotAreDistinguishableInEveryPersistedShape() {
    List<Shape> shapes =
        List.of(
            new Shape("signals.scalper_detail", DotAbsentSerializationTest::scalperDetail, false),
            new Shape(
                "strategy.signal_rejections.diagnostic",
                DotAbsentSerializationTest::rejectionDiagnostic, true),
            new Shape("signals.fired_diagnostic", DotAbsentSerializationTest::firedDiagnostic, true));

    for (Shape shape : shapes) {
      JsonNode withheld = dot(dots(shape.writer().apply(null), shape.nested()), "iv_rank");
      JsonNode present = dot(dots(shape.writer().apply(bd("80")), shape.nested()), "iv_rank");

      assertThat(withheld.has("absent"))
          .as("%s: a missing-input dot must SAY it was withheld, not leave it to arithmetic",
              shape.column())
          .isTrue();
      assertThat(withheld.get("absent").asBoolean()).as(shape.column()).isTrue();
      assertThat(present.has("absent")).as(shape.column()).isTrue();
      assertThat(present.get("absent").asBoolean())
          .as("%s: IV rank 80 is PRESENT — it was scored and lost, not withheld", shape.column())
          .isFalse();
      // The discriminating pair: identical on every pre-existing key, so `absent` is the ONLY thing
      // that tells them apart. This is the G13 reverse-engineering step, deleted.
      assertThat(withheld.get("supports").asBoolean()).as(shape.column()).isFalse();
      assertThat(present.get("supports").asBoolean()).as(shape.column()).isFalse();
      assertThat(withheld.get("weight").decimalValue())
          .as(shape.column())
          .isEqualByComparingTo(present.get("weight").decimalValue());
    }
  }

  /**
   * VERIFY GOAL 2 — the numbers do not move. {@code absent} is a RECORD of the existing withholding,
   * never a change to it: the aggregate stays 1.0 when {@code iv_rank} is withheld (18.80/18.80) and
   * 0.9592 when it is present and scored against (18.80/19.60). Those two values are the G13
   * arithmetic, now pinned rather than inferred.
   *
   * <p>The stronger half is the reconstruction: the SERIALIZED dot list must itself reproduce the
   * aggregate under the withholding rule (Σw·s ÷ Σw, absent dots skipped). If a future change ever
   * flipped the flag without flipping the arithmetic — or vice versa — this fails.
   */
  @Test
  void theFlagRecordsTheWithholdingItDoesNotChangeIt() {
    assertThat(confluence(null).aggregate())
        .as("withheld: 18.80/18.80 — the iv_rank weight leaves BOTH sides")
        .isEqualByComparingTo("1.0");
    assertThat(confluence(bd("80")).aggregate())
        .as("present + unsupporting: 18.80/19.60 — it stays in the denominator")
        .isEqualByComparingTo("0.9592");

    for (BigDecimal ivRank : new BigDecimal[] {null, bd("80")}) {
      JsonNode dots = dots(rejectionDiagnostic(ivRank), true);
      double num = 0;
      double den = 0;
      int supporting = 0;
      for (JsonNode n : dots) {
        if (n.get("absent").asBoolean()) {
          continue;
        }
        den += n.get("weight").asDouble();
        if (n.get("supports").asBoolean()) {
          num += n.get("weight").asDouble();
          supporting++;
        }
      }
      assertThat(BigDecimal.valueOf(num / den).setScale(4, RoundingMode.HALF_UP))
          .as("the serialized dots reconstruct the aggregate the scorer published (ivRank=%s)", ivRank)
          .isEqualByComparingTo(confluence(ivRank).aggregate());
      // 18 dots, every one CE-aligned except iv_rank — withheld or scored-against, it never supports.
      assertThat(supporting)
          .as("supporting dots among those actually counted (ivRank=%s)", ivRank)
          .isEqualTo(17);
      // THE G13 NUMBER, now asserted instead of decoded: 18.80 when iv_rank is withheld, 19.60 when
      // it is counted. Compared at scale 2 — the accumulation is a double sum, so ULP drift is noise.
      assertThat(BigDecimal.valueOf(den).setScale(2, RoundingMode.HALF_UP))
          .as("the effective weight sum (ivRank=%s)", ivRank)
          .isEqualByComparingTo(ivRank == null ? bd("18.80") : bd("19.60"));
    }
  }

  /**
   * The shape contract stays exactly as documented: {@code signals.scalper_detail} carries the LIGHT
   * dot ({@code reason} was never part of it), while the two diagnostic columns stay in field-for-field
   * lockstep so the §4.2 Stage-2 fired-vs-rejected contrast still joins cleanly. {@code absent} is the
   * ONE key added to each — an exact key-set assertion, so an accidental extra or dropped key fails.
   */
  @Test
  void onlyTheAbsentKeyIsAddedAndTheTwoDiagnosticShapesStayInLockstep() {
    JsonNode light = dots(scalperDetail(null), false).get(0);
    assertThat(keys(light)).isEqualTo(Set.of("dot", "weight", "supports", "absent"));

    Set<String> rejection = keys(dots(rejectionDiagnostic(null), true).get(0));
    Set<String> fired = keys(dots(firedDiagnostic(null), true).get(0));
    assertThat(rejection).isEqualTo(Set.of("dot", "weight", "supports", "reason", "absent"));
    assertThat(fired).as("FiredDiagnosticJson mirrors the rejection dot field-for-field")
        .isEqualTo(rejection);
  }
}
