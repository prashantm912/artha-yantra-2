package in.arthayantra.strategysignal.signals;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.scalper.RailMarginSign;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RailCheck;
import in.arthayantra.strategysignal.scalper.StrikeNearMiss;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * H34: the near-miss is DURABLE. A counter dies on restart and cannot be joined to a session, so the
 * number the relaxation decision gets made on has to reach {@code strategy.signal_rejections
 * .diagnostic} — which is JSONB (V022), so this is an additive key with NO migration and no
 * springdoc surface to drift ({@code SignalRejectionsController} exposes the column as an opaque
 * {@code JsonNode}, and the golden writer {@code GoldenSignalsJson.write} in
 * {@code libs/strategy-engine} references none of these classes).
 */
class StrikeNearMissSerializationTest {

  private static final ObjectMapper OM = new ObjectMapper();

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  /** The strike-pick rail as the gate really records it: a boolean verdict, all three scalars NULL. */
  private static RailCheck strikePickCheck() {
    return new RailCheck(
        "strike-pick", false, null, null, null, "no strike met the delta/premium band",
        in.arthayantra.strategysignal.scalper.FailPolicy.FAIL_CLOSED);
  }

  private static StrikeNearMiss.NearMiss nearMiss() {
    return new StrikeNearMiss.NearMiss(
        9, false, "BFO", "SENSEX2682077200CE", bd("77200"), bd("289"), bd("0.7314"),
        StrikeNearMiss.Band.PREMIUM, BigDecimal.ZERO, bd("-11"),
        bd("0.7"), bd("0.8"), bd("300"), bd("800"));
  }

  private static JsonNode diagnostic(StrikeNearMiss.NearMiss nm) {
    ScalperConfluenceGate.RejectionDiagnostic d =
        new ScalperConfluenceGate.RejectionDiagnostic(
            "strike-pick", CE, null, null, null, "no strike met the delta/premium band",
            null, null, List.of(strikePickCheck()), null, null, "SENSEX", null, null, null,
            RailMarginSign.UNSIGNED, nm);
    try {
      return OM.readTree(SignalEngine.rejectionDiagnosticJson(OM, d));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void theNearMissRidesTheRejectionDiagnosticAtTheRoot() {
    JsonNode root = diagnostic(nearMiss());

    // At the ROOT, not buried in checks[]: `diagnostic -> 'strikePickNearMiss'` is a query anyone
    // can write; pulling a named element out of a JSON array is not.
    JsonNode nm = root.get("strikePickNearMiss");
    assertThat(nm).isNotNull();
    assertThat(nm.get("failedBand").asText()).isEqualTo("PREMIUM");
    assertThat(nm.get("tradingsymbol").asText()).isEqualTo("SENSEX2682077200CE");
    assertThat(nm.get("exchange").asText()).isEqualTo("BFO");
    assertThat(nm.get("strike").decimalValue()).isEqualByComparingTo("77200");
    assertThat(nm.get("premium").decimalValue()).isEqualByComparingTo("289");
    assertThat(nm.get("delta").decimalValue()).isEqualByComparingTo("0.7314");
    assertThat(nm.get("premiumGap").decimalValue()).isEqualByComparingTo("-11");
    assertThat(nm.get("deltaGap").decimalValue()).isEqualByComparingTo("0");
    assertThat(nm.get("sideCandidates").asInt()).isEqualTo(9);
    assertThat(nm.get("pastExpiryCutoff").asBoolean()).isFalse();
  }

  @Test
  void theRowCarriesTheBandsItWasJudgedAgainst() {
    // The bands are TAG-selected at config load, so a row read later cannot infer which floor was
    // armed. H34's own first write-up did the arithmetic against 0.6-0.7 while the live slugs ran
    // 0.7-0.8 (delta-s24-floor) — a row that carries its own bands cannot be misread that way.
    JsonNode nm = diagnostic(nearMiss()).get("strikePickNearMiss");

    assertThat(nm).isNotNull();
    assertThat(nm.get("deltaLo").decimalValue()).isEqualByComparingTo("0.7");
    assertThat(nm.get("deltaHi").decimalValue()).isEqualByComparingTo("0.8");
    assertThat(nm.get("premiumLo").decimalValue()).isEqualByComparingTo("300");
    assertThat(nm.get("premiumHi").decimalValue()).isEqualByComparingTo("800");
  }

  @Test
  void theKeyIsAbsentEntirelyWhenNoNearMissWasRecorded() {
    // Every other rail's block, and every FIRED bar, must serialize byte-identically to before —
    // an always-present null key would change the shape of ~all rejection rows for no reason.
    assertThat(diagnostic(null).has("strikePickNearMiss")).isFalse();
  }

  @Test
  void theStrikePickCheckEntryStillCarriesItsThreeNullScalars() {
    // Deliberately NOT filled. `strike-pick` is a CONJUNCTION of two bands in different units, so a
    // single operand/threshold/margin triple can only describe one of them — and on a both-bands
    // miss it would describe neither. That is the T14 defect class (a scalar column asserting
    // something the numbers contradict), which the gate's own compositeMargin() exists to avoid.
    // The near-miss carries deltaGap and premiumGap SEPARATELY instead.
    JsonNode check = diagnostic(nearMiss()).get("checks").get(0);

    assertThat(check.get("rail").asText()).isEqualTo("strike-pick");
    assertThat(check.get("operand").isNull()).isTrue();
    assertThat(check.get("threshold").isNull()).isTrue();
    assertThat(check.get("margin").isNull()).isTrue();
    assertThat(check.get("failPolicy").asText()).isEqualTo("FAIL_CLOSED");
  }
}
