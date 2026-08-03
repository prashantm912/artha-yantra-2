package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer;
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

  @Test
  void mixedRelaxTightenSpecClampsTheTightenedRailToChampion() {
    // §3.3.3 clamp: volume-floor disable RELAXES; rsi-band 70 GTE TIGHTENS vs the champion 60.
    // The tightened rail must behave as champion — never veto an entry the champion's rsi passed.
    ShadowVariants.Variant v =
        variants(
                "[{\"name\":\"mixed\",\"rails\":[{\"rail\":\"volume-floor\",\"disable\":true},"
                    + "{\"rail\":\"rsi-band\",\"threshold\":70,\"passWhen\":\"GTE\"}]}]")
            .all().get(0);
    // rsi 62 passes champion 60 but would fail the 70 override — the clamp keeps 60, so ACCEPT
    RejectionDiagnostic d =
        diag(List.of(rail("volume-floor", false, "9000", "125000"), rail("rsi-band", true, "62", "60")),
            "0.70", "0.60");
    assertThat(ShadowVariants.accepts(d, v)).isTrue();
    // rsi 55 fails champion 60 too — the clamp does not LOOSEN either (still a veto)
    RejectionDiagnostic worse =
        diag(List.of(rail("volume-floor", false, "9000", "125000"), rail("rsi-band", false, "55", "60")),
            "0.70", "0.60");
    assertThat(ShadowVariants.accepts(worse, v)).isFalse();
  }

  @Test
  void lteClampNeverTightensACapRail() {
    // cap rail: champion 70; override 60 LTE is TIGHTER → clamped to max(60,70)=70: operand 65 passes
    ShadowVariants.Variant v =
        variants("[{\"name\":\"cap-60\",\"rails\":[{\"rail\":\"rsi-5m-cap\",\"threshold\":60,\"passWhen\":\"LTE\"}]}]")
            .all().get(0);
    RejectionDiagnostic d =
        diag(List.of(rail("rsi-5m-cap", true, "65", "70")), "0.70", "0.60");
    assertThat(ShadowVariants.accepts(d, v)).isTrue();
  }

  @Test
  void pureLooseningOverrideIsUnaffectedByTheClamp() {
    // champion floor 125000, override 12500 (looser): min(12500,125000)=12500 — loosening intact
    ShadowVariants.Variant v =
        variants("[{\"name\":\"vol-12k5b\",\"rails\":[{\"rail\":\"volume-floor\",\"threshold\":12500}]}]")
            .all().get(0);
    RejectionDiagnostic d =
        diag(List.of(rail("volume-floor", false, "14000", "125000")), "0.70", "0.60");
    assertThat(ShadowVariants.accepts(d, v)).isTrue();
  }

  // ------------------------------------------------ F5 U4b: the `dot-null-withheld` evidence lane

  private static final ShadowVariants.Variant NULL_WITHHELD =
      variants("[{\"name\":\"dot-null-withheld\",\"nullPolicy\":\"withheld\"}]").all().get(0);

  /** A rejection whose confluence recorded the given champion aggregate + U4b shadow aggregate. */
  private static RejectionDiagnostic diagWithShadow(String composite, String withheld, String threshold) {
    return diagWithShadow(composite, withheld, threshold, true);
  }

  /** As above, with the three decisive legs (hard VWAP / 60m bias / stand-aside) held or not. */
  private static RejectionDiagnostic diagWithShadow(
      String composite, String withheld, String threshold, boolean decisiveLegsHeld) {
    ConnectTheDotsScorer.Confluence conf =
        new ConnectTheDotsScorer.Confluence(
            new BigDecimal(composite), OptionType.CE, false, false, true, true, false, List.of(),
            withheld == null ? null : new BigDecimal(withheld), decisiveLegsHeld);
    return new RejectionDiagnostic(
        "confluence-composite", OptionType.CE, null, null, null, "t",
        new BigDecimal(composite), new BigDecimal(threshold),
        List.of(rail("rsi-band", true, "62", "60")), conf, null, "NIFTY 50", null, null, null);
  }

  @Test
  void nullWithheldVariantAcceptsWhatTheUnifiedRulePromoted() {
    // The measurement: the champion composite misses its floor, but withholding the input-missing
    // dots instead of scoring them against the side would have cleared it. Only this variant opens a
    // book on that bar, so the proposal earns a real PnL label without touching live scoring.
    assertThat(ShadowVariants.accepts(diagWithShadow("0.55", "0.72", "0.60"), NULL_WITHHELD)).isTrue();
    // Neither number reaches the floor -> no evidence to record.
    assertThat(ShadowVariants.accepts(diagWithShadow("0.55", "0.58", "0.60"), NULL_WITHHELD)).isFalse();
    // The SAME bar without the knob stays a champion-only decision (0.55 < 0.60).
    ShadowVariants.Variant plain = variants("[{\"name\":\"noop2\",\"rails\":[]}]").all().get(0);
    assertThat(ShadowVariants.accepts(diagWithShadow("0.55", "0.72", "0.60"), plain)).isFalse();
  }

  @Test
  void withholdingThatLowersTheCompositeIsRecordedAsARefusal() {
    // ⚠️ REPLACES `nullWithheldVariantNeverDropsARowTheChampionAccepted` (2026-08-03), which asserted
    // the OPPOSITE — `accepts(champion 0.70, withheld 0.41, floor 0.60)` is TRUE — to pin the
    // `max(champion, withheld)` clamp. Two things were wrong with it.
    //
    // 1. THE FIXTURE COULD NOT OCCUR. `blockingRail = confluence-composite` with the composite ABOVE
    //    its threshold and the decisive legs held is a contradiction: `valid = decisiveLegsHeld &&
    //    aggregate >= threshold`, so that bar FIRED and is not in `signal_rejections` at all. The
    //    clamp was pinned by a bar the writer can never produce.
    // 2. IT NAMED THE WRONG MECHANISM. What stops this book shedding champion-accepted rows is the
    //    WRITER — it only ever sees REJECTED entries, so a row the unified rule would demote was
    //    never in the book to lose. The clamp added nothing to that, and on live data (where the
    //    dominant missing input is `vix`, which LOWERS the composite) it made the lane blind in the
    //    only direction the data actually moves.
    //
    // The honest behaviour on the REACHABLE shape — champion below its floor, withholding drags it
    // further down — is a refusal, and refusals are what the null result is made of.
    assertThat(ShadowVariants.accepts(diagWithShadow("0.55", "0.41", "0.60"), NULL_WITHHELD))
        .as("withholding lowered it further below the floor — no entry to attribute")
        .isFalse();
  }

  @Test
  void clampRemovalIsInertForAPureNullPolicyVariant() {
    // The claim that made removing the clamp safe, pinned. On any row that REACHES the composite
    // comparison, `accepts` has already required every non-composite rail to pass and
    // `armedPolicyCouldHaveFired` has already required the decisive legs to hold — so the block WAS
    // the composite rail with legs held, i.e. champion < championThreshold, and the floor IS that
    // threshold. `max(champion, withheld) >= floor` is then exactly `withheld >= floor`.
    //
    // ⚠️ Honest label: this is a CHARACTERIZATION test, not a discriminator — the clamped
    // implementation gives these same answers, which is the whole point of the claim. The
    // discriminating case is the next test.
    assertThat(ShadowVariants.accepts(diagWithShadow("0.59", "0.61", "0.60"), NULL_WITHHELD)).isTrue();
    assertThat(ShadowVariants.accepts(diagWithShadow("0.59", "0.59", "0.60"), NULL_WITHHELD)).isFalse();
    assertThat(ShadowVariants.accepts(diagWithShadow("0.10", "0.99", "0.60"), NULL_WITHHELD)).isTrue();
    assertThat(ShadowVariants.accepts(diagWithShadow("0.59", "0.01", "0.60"), NULL_WITHHELD)).isFalse();
  }

  @Test
  void clampWouldFalselyAcceptWhenWithholdingLowersBelowALowerFloor() {
    // THE discriminating case, and a reachable one: the vocabulary allows `nullPolicy` and
    // `compositeThreshold` on the SAME variant, and `ShadowVariantRegistry.enforceRelaxingOrNeutral`
    // rejects only a floor ABOVE the champion's — a LOWER one registers fine. Once the floor is no
    // longer the champion's own threshold, the inertness argument above evaporates.
    //
    //   floor 0.55 · champion 0.60 · withheld 0.50
    //     clamped   -> max(0.60, 0.50) = 0.60 >= 0.55 -> ACCEPT  (a position booked against a
    //                                                             proposal that scores 0.50 and
    //                                                             would NOT have taken this bar)
    //     unclamped ->             0.50           < 0.55 -> REFUSE (correct)
    //
    // The two implementations give OPPOSITE answers here. Reverting `compositeFor` to
    // `champion.max(withheld)` turns this assertion red.
    ShadowVariants.Variant lowFloor =
        variants("[{\"name\":\"dnw-055\",\"nullPolicy\":\"withheld\",\"compositeThreshold\":0.55}]")
            .all().get(0);
    assertThat(ShadowVariants.accepts(diagWithShadow("0.60", "0.50", "0.65"), lowFloor))
        .as("the armed policy scores 0.50 and rejects — the book must not credit it an entry")
        .isFalse();
    // …and the same variant still books the bars the unified rule genuinely promoted.
    assertThat(ShadowVariants.accepts(diagWithShadow("0.50", "0.60", "0.65"), lowFloor)).isTrue();
  }

  @Test
  void unclampedAcceptsAreASubsetOfClampedAccepts() {
    // The §3.3.3 direction check: removing the clamp can only REMOVE accepts, never add one, because
    // `withheld <= max(champion, withheld)` always. So the rejection-only writer still cannot be
    // biased upward — the change is conservative, which is the safe direction for an evidence lane.
    String[][] rows = {
      {"0.55", "0.72"}, {"0.55", "0.58"}, {"0.59", "0.61"}, {"0.59", "0.01"}, {"0.10", "0.99"},
    };
    for (String[] r : rows) {
      RejectionDiagnostic d = diagWithShadow(r[0], r[1], "0.60");
      boolean unclamped = ShadowVariants.accepts(d, NULL_WITHHELD);
      boolean clamped =
          new BigDecimal(r[0]).max(new BigDecimal(r[1])).compareTo(new BigDecimal("0.60")) >= 0;
      assertThat(!unclamped || clamped)
          .as("champion=%s withheld=%s: unclamped accepted where clamped did not", r[0], r[1])
          .isTrue();
    }
  }

  @Test
  void nullWithheldDeclinesWhenTheDecisiveLegsDidNotHold() {
    // Review Critical. `valid` is decisive-legs AND scalar, and the null policy changes only the
    // scalar. A bar whose recalculated composite clears the floor while hard-VWAP / 60m-bias /
    // stand-aside blocked is a bar the ARMED policy still REJECTS — booking it would make the
    // challenger's PnL wrong, not merely noisy. (Live-observed shape: ScalperConfluenceGate
    // .compositeMargin records 4 rows with the aggregate clearing while a decisive leg blocked.)
    assertThat(ShadowVariants.accepts(diagWithShadow("0.55", "0.72", "0.60", false), NULL_WITHHELD))
        .as("the scalar alone must never open a challenger position")
        .isFalse();
    // The identical bar with the legs held is the one the experiment is entitled to book.
    assertThat(ShadowVariants.accepts(diagWithShadow("0.55", "0.72", "0.60", true), NULL_WITHHELD))
        .isTrue();
  }

  @Test
  void nullWithheldDeclinesWhenNoCounterfactualWasRecorded() {
    // Fail-closed rather than degrade-to-champion: a bar with no recorded shadow has nothing for
    // this experiment to measure, and booking it under the variant's name would attribute a
    // champion-duplicate row to the proposal.
    assertThat(ShadowVariants.accepts(diagWithShadow("0.70", null, "0.60"), NULL_WITHHELD)).isFalse();
    assertThat(ShadowVariants.accepts(diagWithShadow("0.55", null, "0.60"), NULL_WITHHELD)).isFalse();
    // …and the direction-neutral straddle stand-in, which carries no confluence at all.
    RejectionDiagnostic noConfluence =
        diag(List.of(rail("rsi-band", true, "62", "60")), "0.70", "0.60");
    assertThat(ShadowVariants.accepts(noConfluence, NULL_WITHHELD)).isFalse();
    // A NON-nullPolicy variant is untouched by the guard — the champion book and the rail-override
    // variants keep their existing floor-ruled semantics on exactly the same row.
    ShadowVariants.Variant plain = variants("[{\"name\":\"noop3\",\"rails\":[]}]").all().get(0);
    assertThat(ShadowVariants.accepts(noConfluence, plain)).isTrue();
  }

  @Test
  void onlyWithheldIsARecognisedNullPolicy() {
    assertThat(variants("[{\"name\":\"x\",\"nullPolicy\":\"legacy\"}]").all()).isEmpty();
    assertThat(variants("[{\"name\":\"x\",\"nullPolicy\":\"\"}]").all()).isEmpty();
    // absent ⇒ the champion composite rules, which is the pre-U4b behaviour of every other variant
    assertThat(variants("[{\"name\":\"x\",\"rails\":[]}]").all().get(0).nullWithheld()).isFalse();
    assertThat(NULL_WITHHELD.nullWithheld()).isTrue();
  }
}
