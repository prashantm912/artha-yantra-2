package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RailCheck;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RejectionDiagnostic;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Roadmap F1: variant config parsing + the re-scoring semantics against the all-eval checks. */
class ShadowVariantsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ShadowVariants variants(String json) {
    return new ShadowVariants(MAPPER, json);
  }

  private static RailCheck rail(String name, boolean pass, String operand, String threshold) {
    BigDecimal o = operand == null ? null : new BigDecimal(operand);
    BigDecimal t = threshold == null ? null : new BigDecimal(threshold);
    return new RailCheck(
        name, pass, o, t, o != null && t != null ? o.subtract(t) : null, "t", null);
  }

  private static RejectionDiagnostic diag(List<RailCheck> checks, String composite, String threshold) {
    return new RejectionDiagnostic(
        "volume-floor", OptionType.CE, null, null, null, "t",
        composite == null ? null : new BigDecimal(composite),
        threshold == null ? null : new BigDecimal(threshold),
        checks, null, null, "NIFTY 50", null, null, null);
  }

  @Test
  void thresholdOverrideFlipsAFailedRail() {
    ShadowVariants.Variant v =
        variants("[{\"name\":\"vol-12k5\",\"rails\":[{\"rail\":\"volume-floor\",\"threshold\":12500,\"passWhen\":\"GTE\"}]}]")
            .all().get(0);
    // observed 14000 fails the champion 125k floor but clears the challenger 12.5k floor
    RejectionDiagnostic d =
        diag(List.of(rail("volume-floor", false, "14000", "125000"), rail("rsi-band", true, "62", "60")),
            "0.70", "0.60");
    assertThat(ShadowVariants.accepts(d, v)).isTrue();
    // 9000 fails both floors
    RejectionDiagnostic low =
        diag(List.of(rail("volume-floor", false, "9000", "125000"), rail("rsi-band", true, "62", "60")),
            "0.70", "0.60");
    assertThat(ShadowVariants.accepts(low, v)).isFalse();
  }

  @Test
  void disableIgnoresTheRailEntirelyButOtherFailuresStillVeto() {
    ShadowVariants.Variant v =
        variants("[{\"name\":\"vol-off\",\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true}]}]")
            .all().get(0);
    RejectionDiagnostic volOnly =
        diag(List.of(rail("volume-floor", false, "9000", "125000"), rail("rsi-band", true, "62", "60")),
            "0.70", "0.60");
    assertThat(ShadowVariants.accepts(volOnly, v)).isTrue();
    RejectionDiagnostic alsoRsi =
        diag(List.of(rail("volume-floor", false, "9000", "125000"), rail("rsi-band", false, "48", "60")),
            "0.70", "0.60");
    assertThat(ShadowVariants.accepts(alsoRsi, v)).isFalse();
  }

  @Test
  void compositeFloorOverrideAcceptsBelowChampionThreshold() {
    ShadowVariants.Variant v =
        variants("[{\"name\":\"composite-050\",\"compositeThreshold\":0.50}]").all().get(0);
    RejectionDiagnostic d = diag(List.of(rail("rsi-band", true, "62", "60")), "0.55", "0.60");
    assertThat(ShadowVariants.accepts(d, v)).isTrue();
    RejectionDiagnostic tooLow = diag(List.of(rail("rsi-band", true, "62", "60")), "0.45", "0.60");
    assertThat(ShadowVariants.accepts(tooLow, v)).isFalse();
    // without an override the champion threshold rules
    ShadowVariants.Variant plain =
        variants("[{\"name\":\"noop\",\"rails\":[]}]").all().get(0);
    assertThat(ShadowVariants.accepts(d, plain)).isFalse();
  }

  @Test
  void compositeRailInChecksIsFloorRuledNotPassRuled() {
    // the confluence-composite RailCheck recorded as FAILED must not veto a variant whose
    // composite floor accepts the aggregate
    ShadowVariants.Variant v =
        variants("[{\"name\":\"composite-050\",\"compositeThreshold\":0.50}]").all().get(0);
    RejectionDiagnostic d =
        diag(List.of(
                rail("rsi-band", true, "62", "60"),
                rail("confluence-composite", false, "0.55", "0.60")),
            "0.55", "0.60");
    assertThat(ShadowVariants.accepts(d, v)).isTrue();
  }

  @Test
  void nullOperandKeepsTheOriginalVerdict() {
    ShadowVariants.Variant v =
        variants("[{\"name\":\"vol-12k5\",\"rails\":[{\"rail\":\"volume-floor\",\"threshold\":12500}]}]")
            .all().get(0);
    // an override cannot conjure data: null operand + original FAIL stays a veto
    RejectionDiagnostic d =
        diag(List.of(rail("volume-floor", false, null, "125000")), "0.70", "0.60");
    assertThat(ShadowVariants.accepts(d, v)).isFalse();
  }

  @Test
  void lteOverrideSupportsCapStyleRails() {
    ShadowVariants.Variant v =
        variants("[{\"name\":\"rsi-cap-80\",\"rails\":[{\"rail\":\"rsi-5m-cap\",\"threshold\":80,\"passWhen\":\"LTE\"}]}]")
            .all().get(0);
    RejectionDiagnostic d =
        diag(List.of(rail("rsi-5m-cap", false, "75", "70")), "0.70", "0.60");
    assertThat(ShadowVariants.accepts(d, v)).isTrue();
  }

  @Test
  void badConfigDegradesToNoChallengers() {
    assertThat(variants("not json").all()).isEmpty();
    assertThat(variants("[{\"name\":\"champion\"}]").all()).isEmpty(); // reserved name
    assertThat(variants("[{\"name\":\"Bad Name!\"}]").all()).isEmpty();
    assertThat(variants("[{\"name\":\"x\",\"rails\":[{\"rail\":\"volume-floor\"}]}]").all())
        .as("override without disable or threshold is invalid")
        .isEmpty();
    assertThat(variants("[]").all()).isEmpty();
  }
}
