package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * section 3.12 Trend-Change pre-gate: a structure break in the side's direction + a >=50% Trending-OI
 * momentum shift (CE: put-OI rising AND call-OI falling; PE mirror) + a 2-candle 3rd-candle confirm +
 * the ~14:30 late-reversal cap. Any missing precondition BLOCKS (never a false fire); null OI deltas
 * DEGRADE to a block. The broken swing pivot anchors the structural stop.
 */
class TrendChangeGateTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 6, 20, 9, 45, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final String BN = "NIFTY BANK";
  private static final LocalTime MORNING = LocalTime.of(10, 0);

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static EngineCandle bar(int i, String open, String high, String low, String close, long vol) {
    return new EngineCandle(T0.plusMinutes(3L * i), bd(open), bd(high), bd(low), bd(close), vol);
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "BANKNIFTY-FUT", "3m"), List.of(candles));
  }

  // A bullish up-reversal series: a swing-high pivot at bar2 (high 60), then two strong GREEN bars
  // (bar3, bar4) that the deploy bar5 confirms by closing ABOVE the swing-high (the structure break +
  // the §3.1 2-candle 3rd-candle entry coincide on bar5). Volumes clear the 50k BANKNIFTY floor.
  private static EngineSeries upReversalSeries() {
    return series(
        bar(0, "40", "45", "38", "44", 60_000),
        bar(1, "44", "50", "43", "48", 60_000),
        bar(2, "48", "60", "47", "55", 60_000), // swing-high pivot (high 60)
        bar(3, "53", "56", "52", "55", 60_000), // 1st of the two confirm candles (green)
        bar(4, "55", "59", "54", "58", 60_000), // 2nd confirm candle (green, strong body)
        bar(5, "58", "63", "57", "62", 60_000)); // deploy: closes 62 > swing-high 60 -> break
  }

  // A bearish down-reversal series: a swing-low pivot at bar2 (low 30), two strong RED confirm bars,
  // and the deploy bar5 closes BELOW the swing-low.
  private static EngineSeries downReversalSeries() {
    return series(
        bar(0, "50", "52", "46", "48", 60_000),
        bar(1, "48", "49", "35", "40", 60_000),
        bar(2, "40", "41", "30", "38", 60_000), // swing-low pivot (low 30)
        bar(3, "40", "41", "36", "37", 60_000), // 1st confirm candle (red)
        bar(4, "37", "38", "33", "34", 60_000), // 2nd confirm candle (red, strong body)
        bar(5, "34", "35", "27", "28", 60_000)); // deploy: closes 28 < swing-low 30 -> break
  }

  // The Tier-1 OI snapshot the gate reads: signed CE/PE deltas + the call-put imbalance %. Only those
  // three fields drive the gate; the rest are filler.
  private static Oi oi(String ceDelta, String peDelta, String imbalancePct) {
    return new Oi(
        OiQuadrant.LONG_BUILDUP, OiQuadrant.LONG_BUILDUP, bd("10"), bd("5"), bd("5"),
        ceDelta == null ? null : bd(ceDelta),
        peDelta == null ? null : bd(peDelta),
        imbalancePct == null ? null : bd(imbalancePct),
        false, false, null, null, null);
  }

  // bullish shift: put-OI rising (+), call-OI falling (-), imbalance >= 50.
  private static Oi bullishShift() {
    return oi("-200", "300", "50");
  }

  // bearish shift: call-OI rising (+), put-OI falling (-), imbalance >= 50.
  private static Oi bearishShift() {
    return oi("300", "-200", "60");
  }

  @Test
  void passesAFullBullishTrendChangeAndAnchorsTheStopOnTheBrokenSwingHigh() {
    TrendChangeGate.Verdict v =
        TrendChangeGate.evaluate(upReversalSeries(), 5, CE, BN, bullishShift(), MORNING);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isEqualByComparingTo("60"); // the broken swing-high pivot
  }

  @Test
  void passesAFullBearishTrendChangeAndAnchorsTheStopOnTheBrokenSwingLow() {
    TrendChangeGate.Verdict v =
        TrendChangeGate.evaluate(downReversalSeries(), 5, PE, BN, bearishShift(), MORNING);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isEqualByComparingTo("30"); // the broken swing-low pivot
  }

  @Test
  void blocksWhenThereIsNoStructureBreak() {
    // same OI + confirm candles, but the deploy bar closes 58 (below the swing-high 60) -> no break.
    EngineSeries noBreak =
        series(
            bar(0, "40", "45", "38", "44", 60_000),
            bar(1, "44", "50", "43", "48", 60_000),
            bar(2, "48", "60", "47", "55", 60_000),
            bar(3, "53", "56", "52", "55", 60_000),
            bar(4, "55", "59", "54", "57", 60_000),
            bar(5, "57", "59", "56", "58", 60_000)); // closes below the swing-high -> no break
    assertThat(TrendChangeGate.evaluate(noBreak, 5, CE, BN, bullishShift(), MORNING).pass()).isFalse();
  }

  @Test
  void blocksWhenTheOiImbalanceIsBelowFiftyPercent() {
    Oi weak = oi("-200", "300", "49.9"); // correct direction, but < 50% shift
    assertThat(TrendChangeGate.evaluate(upReversalSeries(), 5, CE, BN, weak, MORNING).pass()).isFalse();
  }

  @Test
  void blocksWhenTheOiShiftIsInTheWrongDirection() {
    // a bearish OI shift (call rising, put falling) for a CE (bullish) side -> wrong way -> block.
    assertThat(TrendChangeGate.evaluate(upReversalSeries(), 5, CE, BN, bearishShift(), MORNING).pass())
        .isFalse();
  }

  @Test
  void degradesToABlockWhenTheOiDeltasAreNull() {
    Oi nullDeltas = oi(null, null, "80"); // a big imbalance but no signed deltas -> cannot confirm
    assertThat(
            TrendChangeGate.evaluate(upReversalSeries(), 5, CE, BN, nullDeltas, MORNING).pass())
        .isFalse();
  }

  @Test
  void degradesToABlockWhenTheImbalancePctIsNull() {
    Oi nullImbalance = oi("-200", "300", null); // flat-OI caveat -> cannot meet the >=50% rule
    assertThat(
            TrendChangeGate.evaluate(upReversalSeries(), 5, CE, BN, nullImbalance, MORNING).pass())
        .isFalse();
  }

  @Test
  void blocksWhenTheTwoCandleConfirmIsAbsent() {
    // a valid break + OI shift, but the two pre-deploy candles are not both green (bar3 is red) ->
    // the §3.1 2-candle 3rd-candle confirmation fails -> block.
    EngineSeries noConfirm =
        series(
            bar(0, "40", "45", "38", "44", 60_000),
            bar(1, "44", "50", "43", "48", 60_000),
            bar(2, "48", "60", "47", "55", 60_000),
            bar(3, "55", "56", "52", "53", 60_000), // RED (close < open) -> not a green confirm
            bar(4, "53", "59", "52", "58", 60_000),
            bar(5, "58", "63", "57", "62", 60_000));
    assertThat(TrendChangeGate.evaluate(noConfirm, 5, CE, BN, bullishShift(), MORNING).pass())
        .isFalse();
  }

  @Test
  void blocksADownReversalAfterTheLateReversalCap() {
    // 14:31 is past the ~14:30 down-reversal cap -> a bearish trend change is blocked.
    assertThat(
            TrendChangeGate.evaluate(
                    downReversalSeries(), 5, PE, BN, bearishShift(), LocalTime.of(14, 31))
                .pass())
        .isFalse();
  }

  @Test
  void allowsAnUpReversalAfterTheLateReversalCap() {
    // the cap is specific to DOWN-reversals ("avoid a fresh down-reversal after ~14:30"); an
    // up-reversal at 14:31 is still allowed.
    assertThat(
            TrendChangeGate.evaluate(
                    upReversalSeries(), 5, CE, BN, bullishShift(), LocalTime.of(14, 31))
                .pass())
        .isTrue();
  }

  @Test
  void degradesToABlockWithInsufficientBars() {
    EngineSeries tooShort = series(bar(0, "40", "45", "38", "44", 60_000));
    assertThat(TrendChangeGate.evaluate(tooShort, 0, CE, BN, bullishShift(), MORNING).pass())
        .isFalse();
    assertThat(TrendChangeGate.evaluate(null, 5, CE, BN, bullishShift(), MORNING).pass()).isFalse();
  }

  @Test
  void blocksWhenTheOiSnapshotIsNull() {
    assertThat(TrendChangeGate.evaluate(upReversalSeries(), 5, CE, BN, (Oi) null, MORNING).pass())
        .isFalse();
  }
}
