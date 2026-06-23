package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure breach logic for the paper SL/TP auto-exit (long = SL floor / TP ceiling; short inverts). */
class PaperBracketEvaluatorTest {

  private static PositionRow pos(String side, String sl, String tp) {
    return new PositionRow(
        1L, "NFO", "X", side, 50, new BigDecimal("100"), BigDecimal.ZERO, "OPEN", null, null, null,
        sl == null ? null : new BigDecimal(sl),
        tp == null ? null : new BigDecimal(tp));
  }

  @Test
  void longStopLossHitWhenLtpAtOrBelowStop() {
    assertThat(PaperBracketEvaluator.breach(pos("BUY", "90", "120"), new BigDecimal("90"))).isEqualTo("STOP_LOSS");
    assertThat(PaperBracketEvaluator.breach(pos("BUY", "90", "120"), new BigDecimal("89"))).isEqualTo("STOP_LOSS");
  }

  @Test
  void longTakeProfitHitWhenLtpAtOrAboveTarget() {
    assertThat(PaperBracketEvaluator.breach(pos("BUY", "90", "120"), new BigDecimal("121"))).isEqualTo("TAKE_PROFIT");
  }

  @Test
  void longInsideBandIsNull() {
    assertThat(PaperBracketEvaluator.breach(pos("BUY", "90", "120"), new BigDecimal("105"))).isNull();
  }

  @Test
  void shortStopLossHitWhenLtpAtOrAboveStop() {
    assertThat(PaperBracketEvaluator.breach(pos("SELL", "110", "80"), new BigDecimal("111"))).isEqualTo("STOP_LOSS");
  }

  @Test
  void shortTakeProfitHitWhenLtpAtOrBelowTarget() {
    assertThat(PaperBracketEvaluator.breach(pos("SELL", "110", "80"), new BigDecimal("79"))).isEqualTo("TAKE_PROFIT");
  }

  @Test
  void noBracketsIsAlwaysNull() {
    assertThat(PaperBracketEvaluator.breach(pos("BUY", null, null), new BigDecimal("1"))).isNull();
  }
}
