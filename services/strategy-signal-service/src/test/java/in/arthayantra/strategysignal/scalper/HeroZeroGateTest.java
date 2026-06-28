package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * section 7 Hero-Zero pre-gate: expiry-day-only (monthly-expiry blocks), the 14:30-15:20 window, a
 * &gt;50% OI+price "real move" on the side, short-covering on the side (drastic side-OI fall +
 * SHORT_COVERING quadrant), the deploy bar closing toward the matching session extreme, and RSI not
 * overbought (CE) / not oversold (PE). Any failing leg BLOCKS; a null/inert OI snapshot degrades to a
 * block. The OPPOSITE session extreme anchors the structural stop.
 */
class HeroZeroGateTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalTime FIRE_TIME = LocalTime.of(14, 45);
  private static final BigDecimal RSI_OK_CE = new BigDecimal("65");
  private static final BigDecimal RSI_OK_PE = new BigDecimal("35");

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // open, high, low, close - one 3m bar; volume above the floor (immaterial to the gate).
  private static EngineCandle bar(int i, String open, String high, String low, String close) {
    return new EngineCandle(
        LocalDate.of(2026, 6, 23).atTime(14, 30).plusMinutes(3L * i).atOffset(IST),
        bd(open), bd(high), bd(low), bd(close), 130_000);
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "NIFTY-FUT", "3m"), List.of(candles));
  }

  // a session with high 120 / low 100; the deploy bar (index 2) closes 117 -> within 25% of the range
  // (20) of the HIGH -> "closing toward the day high" (bullish CE).
  private static EngineSeries towardHighFuture() {
    return series(
        bar(0, "105", "120", "100", "110"),
        bar(1, "110", "118", "104", "112"),
        bar(2, "112", "117", "108", "117"));
  }

  // the mirror: the deploy bar closes 103 -> within 25% of the range of the LOW -> toward the day low.
  private static EngineSeries towardLowFuture() {
    return series(
        bar(0, "115", "120", "100", "110"),
        bar(1, "110", "116", "102", "108"),
        bar(2, "108", "112", "101", "103"));
  }

  // a future closing in the MIDDLE of the range (110, range 100-120) -> not toward either extreme.
  private static EngineSeries midRangeFuture() {
    return series(
        bar(0, "105", "120", "100", "110"),
        bar(1, "110", "118", "104", "112"),
        bar(2, "112", "115", "108", "110"));
  }

  /**
   * An OI snapshot for the gate. {@code sideDelta} is signed for the gate's side (CE -> ceOiDelta,
   * PE -> peOiDelta); the opposite leg is +100 (rising). {@code quadrant} is set on BOTH the
   * underlying and futures quadrant slots so a single value drives the test.
   */
  private static Oi oi(
      OptionType side,
      String sideDelta,
      String imbalancePct,
      String spurtPricePct,
      OiQuadrant quadrant) {
    BigDecimal ce = side == CE ? bd(sideDelta) : bd("100");
    BigDecimal pe = side == PE ? bd(sideDelta) : bd("100");
    return new Oi(
        quadrant, quadrant, bd("10"), bd("5"), bd("5"), ce, pe,
        imbalancePct == null ? null : bd(imbalancePct), false, false, null, null,
        spurtPricePct == null ? null : bd(spurtPricePct));
  }

  // the canonical bullish (CE) "real move + short-covering" OI: call-OI falling, imbalance 60, price
  // +60%, SHORT_COVERING quadrant.
  private static Oi bullishOi() {
    return oi(CE, "-200", "60", "60", OiQuadrant.SHORT_COVERING);
  }

  // the mirror bearish (PE) OI: put-OI falling, imbalance 60, price -60%, SHORT_COVERING.
  private static Oi bearishOi() {
    return oi(PE, "-200", "60", "-60", OiQuadrant.SHORT_COVERING);
  }

  @Test
  void firesBullishAndAnchorsTheSessionLowStop() {
    HeroZeroGate.Verdict v =
        HeroZeroGate.evaluate(
            towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, true, false);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isEqualByComparingTo("100"); // the opposite extreme (session low)
  }

  @Test
  void firesBearishAndAnchorsTheSessionHighStop() {
    HeroZeroGate.Verdict v =
        HeroZeroGate.evaluate(
            towardLowFuture(), 2, PE, bearishOi(), RSI_OK_PE, FIRE_TIME, true, false);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isEqualByComparingTo("120"); // the opposite extreme (session high)
  }

  @Test
  void e4BothSidesFlatIvBlocksOnlyWhenTheAveragesArePassed() {
    // baseline: a normally-firing bullish setup (no IV passed → 8-arg form, byte-identical).
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isTrue();
    // armed both-flat: CE 0.30 vs PE 0.31 (gap 0.01 ≤ 0.02) → sellers pin both sides → BLOCK.
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, true, false, false,
                    bd("0.30"), bd("0.31"))
                .pass())
        .isFalse();
    // divergent IV (gap 0.10 > 0.02) → the both-flat leg does not block, the gate still fires.
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, true, false, false,
                    bd("0.30"), bd("0.20"))
                .pass())
        .isTrue();
    // null averages (the unarmed pass-through) → the leg is skipped, the gate still fires.
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, true, false, false,
                    null, null)
                .pass())
        .isTrue();
  }

  @Test
  void w4SideOiTagRaisesThePutSideFloorToSeventyButLeavesTheCallSideAtFifty() {
    // W4 #3 (tag herozero-side-oi, S24 Day-17): the PUT side's OI confirm rises to ~70% when armed.
    // unarmed (8-arg): the PE imbalance-60 real-move fires.
    assertThat(
            HeroZeroGate.evaluate(
                    towardLowFuture(), 2, PE, bearishOi(), RSI_OK_PE, FIRE_TIME, true, false)
                .pass())
        .isTrue();
    // armed (9-arg true): the same PE imbalance-60 now BLOCKS (60 < 70 put-side floor).
    assertThat(
            HeroZeroGate.evaluate(
                    towardLowFuture(), 2, PE, bearishOi(), RSI_OK_PE, FIRE_TIME, true, false, true)
                .pass())
        .isFalse();
    // armed: a PE imbalance-70 clears the raised floor.
    Oi strongPe = oi(PE, "-200", "70", "-60", OiQuadrant.SHORT_COVERING);
    assertThat(
            HeroZeroGate.evaluate(
                    towardLowFuture(), 2, PE, strongPe, RSI_OK_PE, FIRE_TIME, true, false, true)
                .pass())
        .isTrue();
    // armed: the CALL side is UNCHANGED — imbalance-60 still fires (the asymmetry is PE-only).
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, true, false, true)
                .pass())
        .isTrue();
  }

  @Test
  void blocksWhenNotAnExpiryDay() {
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, false, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksOnAMonthlyExpiryDay() {
    // expiry day, but the MONTHLY flag is set -> the prior-month OI is corrupt -> block.
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, true, true)
                .pass())
        .isFalse();
  }

  @Test
  void blocksBefore1430() {
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, LocalTime.of(14, 29), true, false)
                .pass())
        .isFalse();
    // exactly 14:30 is admitted (the range-marked floor is inclusive).
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, LocalTime.of(14, 30), true, false)
                .pass())
        .isTrue();
  }

  @Test
  void blocksAtOrAfterThe1520FreshEntryCap() {
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, LocalTime.of(15, 20), true, false)
                .pass())
        .isFalse();
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), RSI_OK_CE, LocalTime.of(15, 19), true, false)
                .pass())
        .isTrue();
  }

  @Test
  void blocksWhenTheOiLegIsBelowFiftyPercent() {
    // imbalance 49 (< 50) -> not a real move -> block, even with everything else aligned.
    Oi weakOi = oi(CE, "-200", "49", "60", OiQuadrant.SHORT_COVERING);
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, weakOi, RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenThePriceLegIsBelowFiftyPercent() {
    // OI imbalance fine (60) but the price spurt is only 40% -> OI moved without price -> block.
    Oi noPrice = oi(CE, "-200", "60", "40", OiQuadrant.SHORT_COVERING);
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, noPrice, RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenThePriceMoveIsTheWrongDirection() {
    // bullish CE but the price spurt is -60% (down) -> the >50% move is on the wrong side -> block.
    Oi wrongWay = oi(CE, "-200", "60", "-60", OiQuadrant.SHORT_COVERING);
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, wrongWay, RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenTheSideOiIsNotFalling() {
    // call-OI RISING (+200) -> no call-writer short-covering -> block.
    Oi rising = oi(CE, "200", "60", "60", OiQuadrant.SHORT_COVERING);
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, rising, RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenTheQuadrantIsNotShortCovering() {
    // call-OI falling but the quadrant is LONG_BUILDUP, not SHORT_COVERING -> block.
    Oi noSc = oi(CE, "-200", "60", "60", OiQuadrant.LONG_BUILDUP);
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, noSc, RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenRsiIsOverboughtForACe() {
    // RSI 80 (>= the 80 overbought cap) -> a bullish buy into an overbought tape is skipped.
    assertThat(
            HeroZeroGate.evaluate(
                    towardHighFuture(), 2, CE, bullishOi(), bd("80"), FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenRsiIsOversoldForAPe() {
    assertThat(
            HeroZeroGate.evaluate(
                    towardLowFuture(), 2, PE, bearishOi(), bd("20"), FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksOnBothSidesLongUnwinding() {
    // both quadrants LONG_UNWINDING is the deck's explicit skip (no SC -> blocks at the quadrant leg
    // too, but the dedicated both-sides-unwind rail makes the intent explicit).
    Oi unwind = oi(CE, "-200", "60", "60", OiQuadrant.LONG_UNWINDING);
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, unwind, RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenNotClosingTowardTheExtreme() {
    // a mid-range close (110 in a 100-120 range) is not "closing toward" either extreme -> block.
    assertThat(
            HeroZeroGate.evaluate(midRangeFuture(), 2, CE, bullishOi(), RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void degradesToABlockWhenTheOiSnapshotIsNull() {
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, null, RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void degradesToABlockWhenTheImbalanceIsNull() {
    // the monthly-suppression / unavailable-read shape: null imbalance + null price -> cannot confirm.
    Oi inert = oi(CE, "-200", null, null, OiQuadrant.NEUTRAL);
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, inert, RSI_OK_CE, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenRsiIsNull() {
    assertThat(
            HeroZeroGate.evaluate(towardHighFuture(), 2, CE, bullishOi(), null, FIRE_TIME, true, false)
                .pass())
        .isFalse();
  }
}
