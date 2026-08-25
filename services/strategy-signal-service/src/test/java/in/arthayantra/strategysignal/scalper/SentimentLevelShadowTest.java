package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The counterfactual arithmetic itself: what the two live sentiment SIGN tests would have said on the
 * LEVEL operand. Every case here is a pure function of one {@link Oi} — no gate, no engine.
 */
class SentimentLevelShadowTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  /** An OI snapshot with only the three sentiment-relevant operands varied. */
  private static Oi oi(BigDecimal flow, BigDecimal slope, BigDecimal level) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, flow, bd("5"), bd("12"), bd("-60000"),
        bd("70000"), bd("80"), true, false, slope, bd("60"), bd("60"), bd("25"), level);
  }

  /**
   * VERIFY GOAL — the exact live pathology this measurement exists for. The FLOW operand is
   * 0.00 (the no-dissemination tick: 34% of measured SENSEX buckets), which {@code sideSigned} fails
   * for BOTH sides at once, and which {@code oi-slope-agree} fails too because {@code signum()==0} is
   * neither {@code >0} nor {@code <0}. The LEVEL operand on the same bar is a decisive +30, so both
   * shadow verdicts say the level read WOULD have confirmed CE. The recorded pair is the finding.
   */
  @Test
  void aZeroFlowBarThatTheLevelOperandWouldHavePassed() {
    Oi bar = oi(bd("0.00"), bd("5"), bd("30"));

    // The LIVE reads on this bar: both fail, on the operand being exactly zero.
    assertThat(ConnectTheDotsScorer.sideSigned(bar.sentimentPct(), true)).isFalse();
    assertThat(ScalperGates.oiSlopeAgree(bar, CE).pass()).isFalse();

    SentimentLevelShadow shadow = SentimentLevelShadow.of(bar, CE);
    assertThat(shadow.flowPct()).isEqualByComparingTo("0.00");
    assertThat(shadow.levelPct()).isEqualByComparingTo("30");
    assertThat(shadow.sentimentDotWouldSupport()).isTrue();
    assertThat(shadow.oiSlopeAgreeWouldPass()).isTrue();
  }

  /**
   * The mirror, so neither verdict is a constant: a level that opposes the side reads false on both,
   * on the SAME bar whose flow operand would have supported it. The two operands genuinely disagree
   * in both directions — a shadow that only ever loosened would be measuring nothing.
   */
  @Test
  void aLevelThatOpposesTheSideReadsFalseOnBothVerdicts() {
    Oi bar = oi(bd("12"), bd("5"), bd("-30"));

    assertThat(ConnectTheDotsScorer.sideSigned(bar.sentimentPct(), true)).isTrue();
    assertThat(ScalperGates.oiSlopeAgree(bar, CE).pass()).isTrue();

    SentimentLevelShadow shadow = SentimentLevelShadow.of(bar, CE);
    assertThat(shadow.sentimentDotWouldSupport()).isFalse();
    assertThat(shadow.oiSlopeAgreeWouldPass()).isFalse();
  }

  /** PE is the sign-mirror of CE on the same numbers — the verdicts are side-dependent, not global. */
  @Test
  void theVerdictsFollowTheSide() {
    Oi bar = oi(bd("0.00"), bd("-5"), bd("-30"));

    assertThat(SentimentLevelShadow.of(bar, PE).sentimentDotWouldSupport()).isTrue();
    assertThat(SentimentLevelShadow.of(bar, PE).oiSlopeAgreeWouldPass()).isTrue();
    assertThat(SentimentLevelShadow.of(bar, CE).sentimentDotWouldSupport()).isFalse();
    assertThat(SentimentLevelShadow.of(bar, CE).oiSlopeAgreeWouldPass()).isFalse();
  }

  /**
   * The safe degrade the brief requires: a market-data payload without {@code sentimentLevelPct} (an
   * older deploy, or the S24 monthly-expiry OI suppression) yields NO verdict — null, not the live
   * rule's fail-closed false. Collapsing the two would silently score "could not evaluate" as
   * "the level agrees with the incumbent" on exactly the thin-data bars.
   */
  @Test
  void aMissingLevelYieldsNoVerdictRatherThanFalse() {
    SentimentLevelShadow shadow = SentimentLevelShadow.of(oi(bd("12"), bd("5"), null), CE);

    assertThat(shadow.flowPct()).isEqualByComparingTo("12");
    assertThat(shadow.levelPct()).isNull();
    assertThat(shadow.sentimentDotWouldSupport()).isNull();
    assertThat(shadow.oiSlopeAgreeWouldPass()).isNull();
  }

  /** No OI context at all (the gate blocked before {@code context()} resolved): everything unknown. */
  @Test
  void aMissingContextYieldsTheEmptyShadow() {
    assertThat(SentimentLevelShadow.of(null, CE)).isEqualTo(SentimentLevelShadow.EMPTY);
  }

  /** The #11 neutral straddle resolves no side, so the side-dependent verdicts are unknowable. */
  @Test
  void aNullSideYieldsNoVerdictButStillRecordsBothOperands() {
    SentimentLevelShadow shadow = SentimentLevelShadow.of(oi(bd("12"), bd("5"), bd("30")), null);

    assertThat(shadow.flowPct()).isEqualByComparingTo("12");
    assertThat(shadow.levelPct()).isEqualByComparingTo("30");
    assertThat(shadow.sentimentDotWouldSupport()).isNull();
    assertThat(shadow.oiSlopeAgreeWouldPass()).isNull();
  }

  /**
   * The two verdicts are INDEPENDENT facts, not one fact written twice. {@code oi-slope-agree} is a
   * conjunction of level AND slope, so a null slope fails it (the live rule's fail-closed branch,
   * reached through the substituted operand) while the dot — which reads the level alone — still
   * supports. A shadow that mirrored one verdict onto the other would fail here.
   */
  @Test
  void theSlopeGateVerdictStillRequiresTheSlopeItAlwaysRequired() {
    SentimentLevelShadow shadow = SentimentLevelShadow.of(oi(bd("0.00"), null, bd("30")), CE);

    assertThat(shadow.sentimentDotWouldSupport()).isTrue();
    assertThat(shadow.oiSlopeAgreeWouldPass()).isFalse();
  }

  /**
   * ⚠️ THE WEAKENING PROOF for the reason code. A reason field that always reported the same value
   * would satisfy "a reason code exists" and every single-case assertion elsewhere in this file, yet
   * carry exactly as much information as the four nulls it replaced — i.e. none. This asserts the
   * property that a constant CANNOT have: the five distinguishable states map to five DISTINCT
   * codes. It fails on any collapse, whether to one constant or to a merge of two causes.
   */
  @Test
  void everyDistinguishableCauseGetsItsOwnReasonCode() {
    Oi suppressed = Oi.monthlyExpirySuppressed(bd("12"));
    // The historical ambiguity, reproduced: identical in every operand the shadow reads, differing
    // ONLY in the provenance flag. Before the reason code these two rows were indistinguishable.
    Oi unavailable = oi(null, null, null);
    assertThat(suppressed.sentimentPct()).isEqualTo(unavailable.sentimentPct());
    assertThat(suppressed.sentimentLevelPct()).isEqualTo(unavailable.sentimentLevelPct());

    Map<String, SentimentLevelShadow.Reason> byCause = new LinkedHashMap<>();
    byCause.put("no OI context", SentimentLevelShadow.of(null, CE).reason());
    byCause.put("monthly-expiry suppression", SentimentLevelShadow.of(suppressed, CE).reason());
    byCause.put("level unavailable", SentimentLevelShadow.of(unavailable, CE).reason());
    byCause.put("no side resolved", SentimentLevelShadow.of(oi(bd("12"), bd("5"), bd("30")), null).reason());
    byCause.put("computed", SentimentLevelShadow.of(oi(bd("12"), bd("5"), bd("30")), CE).reason());

    // The property a constant fails: five causes, five codes, no two alike.
    assertThat(byCause.values()).doesNotHaveDuplicates().hasSize(5);
    // …and each is the RIGHT one, so a permutation is caught too.
    assertThat(byCause)
        .containsExactly(
            entry("no OI context", SentimentLevelShadow.Reason.NO_OI_CONTEXT),
            entry("monthly-expiry suppression", SentimentLevelShadow.Reason.MONTHLY_EXPIRY_SUPPRESSED),
            entry("level unavailable", SentimentLevelShadow.Reason.LEVEL_UNAVAILABLE),
            entry("no side resolved", SentimentLevelShadow.Reason.SIDE_UNRESOLVED),
            entry("computed", SentimentLevelShadow.Reason.COMPUTED));
    // Every constant the enum declares is reachable — a code nothing can produce is a lie in a
    // dashboard legend, and a code produced by two causes is the ambiguity all over again.
    assertThat(byCause.values())
        .containsExactlyInAnyOrder(SentimentLevelShadow.Reason.values());
  }

  /**
   * The reason NEVER substitutes for a verdict. On the suppressed bar the four operand/verdict
   * fields stay null — the point of the S24 branch is that the chain OI is corrupt, so any number
   * derived from it would be measurement garbage. A "fix" that filled them in would pass a naive
   * reason-code test and fail this one.
   */
  @Test
  void theSuppressedReasonDoesNotConjureAVerdict() {
    SentimentLevelShadow shadow = SentimentLevelShadow.of(Oi.monthlyExpirySuppressed(bd("12")), CE);

    assertThat(shadow.reason()).isEqualTo(SentimentLevelShadow.Reason.MONTHLY_EXPIRY_SUPPRESSED);
    assertThat(shadow.flowPct()).isNull();
    assertThat(shadow.levelPct()).isNull();
    assertThat(shadow.sentimentDotWouldSupport()).isNull();
    assertThat(shadow.oiSlopeAgreeWouldPass()).isNull();
  }

  /**
   * Root cause beats proximate cause. A suppressed snapshot ALSO has a null level, so an
   * implementation that tested {@code level == null} before the provenance flag would report
   * LEVEL_UNAVAILABLE and restore the very ambiguity this field removes — a failure mode invisible
   * to any test that only ever checks one snapshot at a time.
   */
  @Test
  void suppressionOutranksTheMissingLevelItCauses() {
    assertThat(Oi.monthlyExpirySuppressed(bd("12")).sentimentLevelPct()).isNull();

    assertThat(SentimentLevelShadow.of(Oi.monthlyExpirySuppressed(bd("12")), CE).reason())
        .isNotEqualTo(SentimentLevelShadow.Reason.LEVEL_UNAVAILABLE)
        .isEqualTo(SentimentLevelShadow.Reason.MONTHLY_EXPIRY_SUPPRESSED);
  }

  /**
   * The substitution swaps the OPERAND and nothing else: every other field the substituted
   * {@link Oi} carries — the slope especially, which {@code oi-slope-agree} also reads — must be the
   * bar's own. Asserted by construction: a shadow computed with the slope pointing the wrong way
   * cannot pass, however decisive the level is.
   */
  @Test
  void theSubstitutionCarriesEveryOtherOperandThrough() {
    for (OptionType side : new OptionType[] {CE, PE}) {
      // level favours the side, slope opposes it → the conjunction must still fail.
      BigDecimal level = side == CE ? bd("30") : bd("-30");
      BigDecimal slope = side == CE ? bd("-5") : bd("5");
      assertThat(SentimentLevelShadow.of(oi(bd("0.00"), slope, level), side).oiSlopeAgreeWouldPass())
          .as("%s: an opposing slope must survive the operand substitution", side)
          .isFalse();
    }
  }
}
