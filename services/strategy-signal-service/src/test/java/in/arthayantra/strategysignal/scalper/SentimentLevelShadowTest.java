package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
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
