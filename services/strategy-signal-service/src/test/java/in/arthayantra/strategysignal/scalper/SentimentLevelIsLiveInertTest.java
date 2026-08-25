package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.Confluence;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * THE POINT OF THIS PR: {@code sentimentLevelPct} is measurement-only. Adding it must not change
 * which bars pass the live gate.
 *
 * <p>The method is a PAIRED comparison rather than a golden string. Each test builds two bars that
 * are identical in every field except {@code sentimentLevelPct} — absent on one, and on the other a
 * value chosen so that BOTH shadow verdicts flip. Every live output is then asserted equal across
 * the pair, and the shadow is asserted DIFFERENT. If any live rule ever starts reading the new
 * field, the equality assertions fail; if the field were inert everywhere (e.g. never plumbed), the
 * shadow assertion fails. Both halves are required — either alone is passable by a no-op.
 */
class SentimentLevelIsLiveInertTest {

  private static final BigDecimal T = new BigDecimal("0.6");
  private static final ScalperOiProps P = ScalperOiProps.defaults();

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static final Chart BULL_CHART =
      new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000"));
  private static final Macro MACRO =
      new Macro(bd("14"), bd("30"), bd("12"), false, 40, 10, bd("50"), bd("0.20"), bd("0.05"));

  /**
   * The measured live pathology as a fixture: the FLOW operand is exactly 0.00 (the coarse-OI
   * no-dissemination tick), so both live sentiment reads fail; the slope is positive, so the
   * substituted level is the ONLY thing standing between {@code oi-slope-agree} and a pass.
   */
  private static Oi bar(BigDecimal level) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("0.00"), bd("5"), bd("12"),
        bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"), bd("25"),
        level);
  }

  private static ScalperGateContext ctx(BigDecimal level) {
    return new ScalperGateContext(
        "NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART, bar(level), MACRO);
  }

  private static Confluence score(BigDecimal level, OptionType side) {
    return ConnectTheDotsScorer.score(ctx(level), side, 1, T, P, true);
  }

  /**
   * The confluence is scored on EVERY bar that reaches the gate, so full record equality across the
   * pair covers the aggregate, the bullish/bearish verdicts, the decisive legs, the U4b shadow
   * aggregate and the entire dot list (name, weight, supports, absent, reason, order) in one
   * assertion. BigDecimal equality inside a record is scale-sensitive, so this is a byte-level claim
   * about the composite, not merely a numeric one.
   */
  @Test
  void theConfluenceIsRecordIdenticalWithAndWithoutTheLevelOperand() {
    for (OptionType side : new OptionType[] {CE, PE}) {
      // +30 supports CE and opposes PE, so on each pass it is a value that would MOVE the sentiment
      // dot if anything read it — in opposite directions, so no single sign can be a lucky no-op.
      assertThat(score(bd("30"), side))
          .as("%s: the level operand must not reach the composite", side)
          .isEqualTo(score(null, side));
    }
  }

  /**
   * ...and the rail half. {@code oi-slope-agree} is the second consumer; its whole outcome (pass,
   * operand, reason) must be untouched. This is the rail whose shadow verdict FLIPS on this fixture,
   * so it is the one most likely to be miswired.
   */
  @Test
  void theOiSlopeAgreeRailIsOutcomeIdenticalWithAndWithoutTheLevelOperand() {
    for (OptionType side : new OptionType[] {CE, PE}) {
      BigDecimal level = side == CE ? bd("30") : bd("-30");
      assertThat(ScalperGates.oiSlopeAgree(bar(level), side))
          .as("%s: the level operand must not reach the oi-slope-agree rail", side)
          .isEqualTo(ScalperGates.oiSlopeAgree(bar(null), side));
    }
  }

  /**
   * The discriminating half: on this very fixture the shadow verdicts DO differ across the pair. Both
   * flip from "no verdict" to TRUE, i.e. the level operand would have confirmed CE on a bar the flow
   * operand fails at exactly 0.00. Without this, the two equality tests above would pass on a branch
   * that never plumbed the field at all.
   */
  @Test
  void theShadowVerdictsDoDifferAcrossTheSamePair() {
    SentimentLevelShadow absent = SentimentLevelShadow.of(bar(null), CE);
    SentimentLevelShadow present = SentimentLevelShadow.of(bar(bd("30")), CE);

    assertThat(absent.sentimentDotWouldSupport()).isNull();
    assertThat(absent.oiSlopeAgreeWouldPass()).isNull();
    assertThat(present.sentimentDotWouldSupport()).isTrue();
    assertThat(present.oiSlopeAgreeWouldPass()).isTrue();

    // …while the live reads on the SAME bar both say no. That gap is the measurement.
    assertThat(score(bd("30"), CE).dots().stream().filter(d -> "sentiment".equals(d.dot())).findFirst())
        .hasValueSatisfying(d -> assertThat(d.supports()).isFalse());
    assertThat(ScalperGates.oiSlopeAgree(bar(bd("30")), CE).pass()).isFalse();
  }

  /**
   * The compat constructor is the other way the live decision could move: every pre-existing 13- and
   * 14-arg {@code Oi} literal in the codebase must still mean exactly what it meant, with the new
   * field defaulting to null (= no shadow verdict).
   */
  @Test
  void theCompatConstructorsDefaultTheNewFieldToNull() {
    Oi fourteen =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("0.00"), bd("5"), bd("12"),
            bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"), bd("25"));
    Oi thirteen =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("0.00"), bd("5"), bd("12"),
            bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"));

    assertThat(fourteen.sentimentLevelPct()).isNull();
    assertThat(thirteen.sentimentLevelPct()).isNull();
    assertThat(fourteen).isEqualTo(bar(null));
    assertThat(thirteen.oiDivergencePct()).isNull();
    // The reason-code PR adds a 16th component. Same contract: every pre-existing literal keeps its
    // meaning, and none of them may claim the monthly-expiry suppression by default.
    assertThat(fourteen.monthlyExpirySuppressed()).isFalse();
    assertThat(thirteen.monthlyExpirySuppressed()).isFalse();
    assertThat(bar(null).monthlyExpirySuppressed()).isFalse();
  }

  /**
   * The same inertness claim for the reason-code PR's own field. {@code monthlyExpirySuppressed} is
   * PROVENANCE — it must reach the diagnostic and reach nothing else. Paired exactly as above, on
   * the pair that MATTERS: two level-less bars differing only in the flag, i.e. the by-design
   * monthly-expiry snapshot against the ordinary missing-level one. Every live output is asserted
   * equal across the pair; the shadow's reason is asserted DIFFERENT. Either half alone is passable
   * by a no-op — equality alone passes on a field nothing plumbed, difference alone passes on a
   * field a gate also reads.
   */
  @Test
  void theSuppressionFlagIsLiveInertButReachesTheShadowReason() {
    Oi ordinary = bar(null);
    Oi suppressed =
        new Oi(
            OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("0.00"), bd("5"), bd("12"),
            bd("-60000"), bd("70000"), bd("80"), true, false, bd("5"), bd("60"), bd("60"), bd("25"),
            null, true);

    for (OptionType side : new OptionType[] {CE, PE}) {
      ScalperGateContext ordinaryCtx =
          new ScalperGateContext(
              "NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART, ordinary, MACRO);
      ScalperGateContext suppressedCtx =
          new ScalperGateContext(
              "NIFTY 50", "NIFTY 50", LocalTime.of(10, 30), BULL_CHART, suppressed, MACRO);

      assertThat(ConnectTheDotsScorer.score(suppressedCtx, side, 1, T, P, true))
          .as("%s: the suppression flag must not reach the composite", side)
          .isEqualTo(ConnectTheDotsScorer.score(ordinaryCtx, side, 1, T, P, true));
      assertThat(ScalperGates.oiSlopeAgree(suppressed, side))
          .as("%s: the suppression flag must not reach the oi-slope-agree rail", side)
          .isEqualTo(ScalperGates.oiSlopeAgree(ordinary, side));
    }

    // The discriminating half: same four nulls on both, different reason. This is precisely the
    // pair that was indistinguishable on 2026-08-25.
    SentimentLevelShadow ordinaryShadow = SentimentLevelShadow.of(ordinary, CE);
    SentimentLevelShadow suppressedShadow = SentimentLevelShadow.of(suppressed, CE);
    assertThat(suppressedShadow.levelPct()).isNull();
    assertThat(ordinaryShadow.levelPct()).isNull();
    assertThat(suppressedShadow.sentimentDotWouldSupport())
        .isEqualTo(ordinaryShadow.sentimentDotWouldSupport())
        .isNull();
    assertThat(ordinaryShadow.reason()).isEqualTo(SentimentLevelShadow.Reason.LEVEL_UNAVAILABLE);
    assertThat(suppressedShadow.reason())
        .isEqualTo(SentimentLevelShadow.Reason.MONTHLY_EXPIRY_SUPPRESSED);
  }
}
