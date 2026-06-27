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
  void openingTickWindowOverloadBoundsOnFromInclusiveToExclusive() {
    // #9 Morning Trade: the opening-tick overload passes from `from` (inclusive) up to `to` (exclusive).
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 16), LocalTime.of(9, 16), LocalTime.of(9, 30)).pass())
        .isTrue(); // at `from` => pass
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 15), LocalTime.of(9, 16), LocalTime.of(9, 30)).pass())
        .isFalse(); // before `from` => fail
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 29), LocalTime.of(9, 16), LocalTime.of(9, 30)).pass())
        .isTrue();
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 30), LocalTime.of(9, 16), LocalTime.of(9, 30)).pass())
        .isFalse(); // at `to` => fail (exclusive)
    // the default no-arg window still rejects 09:16 (its "after 09:45" floor) — the overload is the
    // only path that admits the opening tick.
    assertThat(ScalperGates.timeWindow(LocalTime.of(9, 16)).pass()).isFalse();
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
  void rsiS24BandShiftsBuyTo50To75AndSellTo25To40() {
    // S24 ratified band (tag rsi-s24-bands, owner U1/U2/U3): CE buy 50-75, PE sell 25-40, 40-50 no-trade.
    assertThat(ScalperGates.rsiS24Band(bd("50"), CE).pass()).isFalse(); // floor exclusive
    assertThat(ScalperGates.rsiS24Band(bd("51"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiS24Band(bd("74"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiS24Band(bd("75"), CE).pass()).isFalse();
    assertThat(ScalperGates.rsiS24Band(bd("44"), CE).pass()).isFalse(); // 40-50 no-trade
    // PE sell 25-40
    assertThat(ScalperGates.rsiS24Band(bd("40"), PE).pass()).isFalse();
    assertThat(ScalperGates.rsiS24Band(bd("39"), PE).pass()).isTrue();
    assertThat(ScalperGates.rsiS24Band(bd("26"), PE).pass()).isTrue();
    assertThat(ScalperGates.rsiS24Band(bd("25"), PE).pass()).isFalse();
    // the S24/legacy divergence: RSI 55 CE trades under S24 but is in the legacy 40-60 no-trade gap.
    assertThat(ScalperGates.rsiS24Band(bd("55"), CE).pass()).isTrue();
    assertThat(ScalperGates.rsiBand(bd("55"), CE).pass()).isFalse();
    assertThat(ScalperGates.rsiS24Band(null, CE).pass()).isFalse(); // null rsi -> fail
  }

  @Test
  void rsiAboveIsTheRelaxedOpenHighFloor() {
    // #2 (open-high-low) gates on the source's "RSI >50" floor, not the 60-80 band.
    assertThat(ScalperGates.rsiAbove(bd("50"), bd("50")).pass()).isFalse(); // exactly 50 -> not >
    assertThat(ScalperGates.rsiAbove(bd("50.1"), bd("50")).pass()).isTrue();
    assertThat(ScalperGates.rsiAbove(bd("85"), bd("50")).pass()).isTrue(); // no upper cap for #2
    assertThat(ScalperGates.rsiAbove(null, bd("50")).pass()).isFalse(); // null rsi -> fail
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

  @Test
  void callPutDeltaFilterPassesAtFloorFailsBelowAndDegradesOnNull() {
    BigDecimal floor = bd("50");
    assertThat(ScalperGates.callPutDeltaFilter(imbalance(bd("50")), floor).pass()).isTrue(); // == floor
    assertThat(ScalperGates.callPutDeltaFilter(imbalance(bd("75")), floor).pass()).isTrue();
    assertThat(ScalperGates.callPutDeltaFilter(imbalance(bd("49.9")), floor).pass()).isFalse();
    // null imbalance (data unavailable / flat-OI caveat) DEGRADES to pass — never blocks
    assertThat(ScalperGates.callPutDeltaFilter(imbalance(null), floor).pass()).isTrue();
  }

  @Test
  void indicatorDistanceBlocksWhenPriceRanFarFromTheWholeCluster() {
    BigDecimal max = bd("0.015"); // 1.5%
    // the nearest indicator (vwap, 0.5% away) is within the band -> not overextended -> pass.
    Chart near = new Chart(bd("100"), bd("99.5"), bd("130"), bd("70"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(near, max).pass()).isTrue();
    // every indicator is > 1.5% away (nearest is vwap at 3%) -> overextended -> block.
    Chart farFromAll = new Chart(bd("100"), bd("97"), bd("130"), bd("70"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(farFromAll, max).pass()).isFalse();
    // null close degrades to pass (never blocks on missing data).
    Chart noClose = new Chart(null, bd("97"), bd("130"), bd("70"), 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(noClose, max).pass()).isTrue();
    // a fully-absent cluster degrades to pass.
    Chart noCluster = new Chart(bd("100"), null, null, null, 1, bd("65"), bd("60000"));
    assertThat(ScalperGates.indicatorDistance(noCluster, max).pass()).isTrue();
  }

  @Test
  void divergenceVolumeNeedsTheHeavyweightBarRegardlessOfIndex() {
    assertThat(ScalperGates.divergenceVolume(bd("125000")).pass()).isTrue();
    assertThat(ScalperGates.divergenceVolume(bd("124999")).pass()).isFalse();
    assertThat(ScalperGates.divergenceVolume(null).pass()).isFalse(); // confirm required -> null fails
  }

  private static Oi imbalance(BigDecimal pct) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("0"), bd("5"),
        null, null, pct, false, false, null, null, null);
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
