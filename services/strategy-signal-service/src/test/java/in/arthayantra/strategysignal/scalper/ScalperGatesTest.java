package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** §0B universal pre-flight gates — boundary coverage (master plan §12.1). */
class ScalperGatesTest {

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  @Test
  void timeWindowBlocksOpenNoiseMiddayAndLateEntry() {
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 44)).pass()).isFalse();
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 45)).pass()).isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(10, 30)).pass()).isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(11, 0)).pass()).isFalse(); // midday block start
    assertThat(ScalperGates.timeWindow(LocalTime.of(12, 59)).pass()).isFalse();
    assertThat(ScalperGates.timeWindow(LocalTime.of(13, 0)).pass()).isTrue(); // block ends
    assertThat(ScalperGates.timeWindow(LocalTime.of(15, 29)).pass()).isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(15, 30)).pass()).isFalse(); // no fresh entry
  }

  @Test
  void volumeFloorByUnderlying() {
    assertThat(ScalperGates.volume("NIFTY 50", bd("124999")).pass()).isFalse();
    assertThat(ScalperGates.volume("NIFTY 50", bd("125000")).pass()).isTrue();
    assertThat(ScalperGates.volume("NIFTY BANK", bd("49999")).pass()).isFalse();
    assertThat(ScalperGates.volume("NIFTY BANK", bd("50000")).pass()).isTrue();
    assertThat(ScalperGates.volume("SENSEX", bd("50000")).pass()).isTrue(); // default floor 50k
  }

  @Test
  void rsiNoTradeBandAndDirectionalZones() {
    // 40-60 is no-trade for both sides
    assertThat(ScalperGates.rsiBand(bd("50"), CE).pass()).isFalse();
    assertThat(ScalperGates.rsiBand(bd("50"), PE).pass()).isFalse();
    // CE trades 60-80
    assertThat(ScalperGates.rsiBand(bd("60"), CE).pass()).isFalse();
    assertThat(ScalperGates.rsiBand(bd("61"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("79"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("80"), CE).pass()).isFalse(); // exhaustion
    // PE trades 20-40
    assertThat(ScalperGates.rsiBand(bd("39"), PE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("40"), PE).pass()).isFalse();
    assertThat(ScalperGates.rsiBand(bd("21"), PE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("20"), PE).pass()).isFalse(); // exhaustion
  }

  @Test
  void indicatorAlignmentNeedsAllOnTheCorrectSide() {
    Chart bull = new Chart(bd("100"), bd("99"), bd("98"), bd("97"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorAlignment(bull, CE).pass()).isTrue();
    // one operand on the wrong side (PSAR above price) breaks CE alignment
    Chart psarAbove = new Chart(bd("100"), bd("99"), bd("98"), bd("101"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorAlignment(psarAbove, CE).pass()).isFalse();
    // supertrend pointing the wrong way breaks it too
    Chart stDown = new Chart(bd("100"), bd("99"), bd("98"), bd("97"), -1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorAlignment(stDown, CE).pass()).isFalse();

    Chart bear = new Chart(bd("100"), bd("101"), bd("102"), bd("103"), -1, bd("35"), bd("60000"));
    assertThat(ScalperGates.indicatorAlignment(bear, PE).pass()).isTrue();
  }

  @Test
  void oiQuadrantMatchesSide() {
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.LONG_BUILDUP), CE).pass()).isTrue();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.SHORT_COVERING), CE).pass()).isTrue();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.SHORT_BUILDUP), CE).pass()).isFalse();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.SHORT_BUILDUP), PE).pass()).isTrue();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.LONG_UNWINDING), PE).pass()).isTrue();
    assertThat(ScalperGates.oiQuadrant(oi(OiQuadrant.LONG_BUILDUP), PE).pass()).isFalse();
  }

  @Test
  void breadthThirtyTwoCutoff() {
    assertThat(ScalperGates.breadth(macro(33, 10, null), CE).pass()).isTrue();
    assertThat(ScalperGates.breadth(macro(32, 10, null), CE).pass()).isFalse();
    assertThat(ScalperGates.breadth(macro(10, 33, null), PE).pass()).isTrue();
    assertThat(ScalperGates.breadth(macro(10, 32, null), PE).pass()).isFalse();
  }

  @Test
  void vixDirectionFavoursSideAndUnknownNeverBlocks() {
    assertThat(ScalperGates.vix(macro(0, 0, Boolean.FALSE), CE).pass()).isTrue(); // falling -> CE
    assertThat(ScalperGates.vix(macro(0, 0, Boolean.TRUE), CE).pass()).isFalse();
    assertThat(ScalperGates.vix(macro(0, 0, Boolean.TRUE), PE).pass()).isTrue(); // rising -> PE
    assertThat(ScalperGates.vix(macro(0, 0, null), CE).pass()).isTrue(); // unknown -> pass
  }

  @Test
  void futuresBasisPremiumBullDiscountBear() {
    assertThat(ScalperGates.futuresBasis(basis(bd("5")), CE).pass()).isTrue();
    assertThat(ScalperGates.futuresBasis(basis(bd("-5")), CE).pass()).isFalse();
    assertThat(ScalperGates.futuresBasis(basis(bd("-5")), PE).pass()).isTrue();
    assertThat(ScalperGates.futuresBasis(basis(null), PE).pass()).isTrue(); // unavailable -> pass
  }

  private static Oi oi(OiQuadrant futures) {
    return new Oi(futures, futures, bd("10"), bd("0"), bd("5"), null, null, null, false, false, null, null, null);
  }

  private static Oi basis(BigDecimal b) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), b, null, null, null, false, false, null,
        null, null);
  }

  private static Macro macro(int adv, int dec, Boolean vixRising) {
    return new Macro(bd("14"), bd("30"), bd("12.5"), vixRising, adv, dec, bd("50"), null, null);
  }
}
