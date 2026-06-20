package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.scalper.ConnectTheDotsScorer.Confluence;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** Connect-the-Dots confluence scorer (§12.3) — VWAP decisive, 60m bias must agree. */
class ConnectTheDotsScorerTest {

  private static final BigDecimal T = new BigDecimal("0.6");

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static ScalperGateContext ctx(Chart c, Oi oi, Macro m) {
    return new ScalperGateContext("NIFTY 50", LocalTime.of(10, 30), c, oi, m);
  }

  // fully-bullish dots for a CE signal
  private static final Chart BULL_CHART = new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("130000"));
  private static final Oi BULL_OI = new Oi(OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"));
  private static final Macro BULL_MACRO = new Macro(bd("14"), bd("30"), bd("12"), Boolean.FALSE, 40, 10, bd("50"));

  @Test
  void allDotsAlignedFiresBullishCe() {
    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, BULL_MACRO), CE, 1, T);

    assertThat(r.bullish()).isTrue();
    assertThat(r.bearish()).isFalse();
    assertThat(r.vwapAligned()).isTrue();
    assertThat(r.biasAligned()).isTrue();
    assertThat(r.aggregate()).isEqualByComparingTo("1.0");
    assertThat(r.dots()).hasSize(14);
  }

  @Test
  void wrongVwapSideBlocksEvenWithStrongRest() {
    // price below VWAP but still above VWMA/PSAR so only the decisive VWAP dot flips
    Chart c = new Chart(bd("98"), bd("99"), bd("96"), bd("95"), 1, bd("65"), bd("130000"));

    Confluence r = ConnectTheDotsScorer.score(ctx(c, BULL_OI, BULL_MACRO), CE, 1, T);

    assertThat(r.vwapAligned()).isFalse();
    assertThat(r.bullish()).isFalse();
  }

  @Test
  void opposing60mBiasBlocks() {
    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, BULL_OI, BULL_MACRO), CE, -1, T);

    assertThat(r.biasAligned()).isFalse();
    assertThat(r.bullish()).isFalse();
  }

  @Test
  void belowThresholdBlocksThoughVwapAligned() {
    // VWAP still aligned, but the OI/macro dots all oppose -> aggregate under threshold
    Oi oi = new Oi(OiQuadrant.SHORT_BUILDUP, OiQuadrant.SHORT_BUILDUP, bd("-10"), bd("-5"), bd("-5"));
    Macro m = new Macro(bd("14"), bd("80"), bd("12"), Boolean.TRUE, 10, 40, bd("50"));

    Confluence r = ConnectTheDotsScorer.score(ctx(BULL_CHART, oi, m), CE, 1, T);

    assertThat(r.vwapAligned()).isTrue();
    assertThat(r.aggregate()).isLessThan(T);
    assertThat(r.bullish()).isFalse();
  }

  @Test
  void allDotsAlignedFiresBearishPe() {
    Chart c = new Chart(bd("98"), bd("99"), bd("100"), bd("101"), -1, bd("35"), bd("130000"));
    Oi oi = new Oi(OiQuadrant.SHORT_BUILDUP, OiQuadrant.SHORT_BUILDUP, bd("-10"), bd("-5"), bd("-5"));
    Macro m = new Macro(bd("14"), bd("30"), bd("12"), Boolean.TRUE, 10, 40, bd("50"));

    Confluence r = ConnectTheDotsScorer.score(ctx(c, oi, m), PE, -1, T);

    assertThat(r.bearish()).isTrue();
    assertThat(r.bullish()).isFalse();
    assertThat(r.aggregate()).isEqualByComparingTo("1.0");
  }
}
