package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategysignal.scalper.OpenHighLow.Tier;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * section 3.2 Open=High / Open=Low primitive: the front-future OH/OL mark detection (open == session
 * extreme within a 1-pt tolerance) and the L489 FNO-structure probability tier (HIGH / MILD /
 * STAND_ASIDE) from (mark x OI quadrant), including the two-sided OH+OL stand-aside.
 */
class OpenHighLowTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static EngineCandle bar(int i, String open, String high, String low, String close) {
    return new EngineCandle(T0.plusMinutes(3L * i), bd(open), bd(high), bd(low), bd(close), 60_000);
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "NIFTY-FUT", "3m"), List.of(candles));
  }

  // Only the underlying quadrant drives the tier; the rest of the Oi record is filler.
  private static Oi oi(OiQuadrant underlying) {
    return new Oi(
        underlying, OiQuadrant.NEUTRAL, bd("10"), bd("5"), bd("5"), null, null, null, false, false,
        null, null, null);
  }

  // An Open=High session: bar0 open 100 is the running session high; later bars never exceed it.
  private static EngineSeries openHighSeries() {
    return series(
        bar(0, "100", "100", "95", "98"),
        bar(1, "98", "99", "94", "96"),
        bar(2, "96", "100", "93", "97")); // high revisits 100 but never above the open
  }

  // An Open=Low session: bar0 open 100 is the running session low; later bars never go below it.
  private static EngineSeries openLowSeries() {
    return series(
        bar(0, "100", "105", "100", "103"),
        bar(1, "103", "107", "101", "105"),
        bar(2, "105", "108", "100", "106")); // low revisits 100 but never below the open
  }

  @Test
  void detectsAnOpenHighMark() {
    OpenHighLow.Marks marks = OpenHighLow.marks(openHighSeries(), 2);
    assertThat(marks.openHigh()).isTrue();
    assertThat(marks.openLow()).isFalse();
  }

  @Test
  void detectsAnOpenLowMark() {
    OpenHighLow.Marks marks = OpenHighLow.marks(openLowSeries(), 2);
    assertThat(marks.openLow()).isTrue();
    assertThat(marks.openHigh()).isFalse();
  }

  @Test
  void detectsTheOpenHighWithinTheOnePointTolerance() {
    // the high runs 1 pt above the open (101 vs 100) - still an OH within the tick tolerance.
    EngineSeries s =
        series(bar(0, "100", "100", "95", "98"), bar(1, "98", "101", "94", "96"));
    assertThat(OpenHighLow.marks(s, 1).openHigh()).isTrue();
  }

  @Test
  void rejectsTheOpenHighOnceTheHighRunsMoreThanATickAbove() {
    // the high runs 2 pts above the open (102 vs 100) - beyond the 1-pt tolerance, not an OH.
    EngineSeries s =
        series(bar(0, "100", "100", "95", "98"), bar(1, "98", "102", "94", "96"));
    assertThat(OpenHighLow.marks(s, 1).openHigh()).isFalse();
  }

  @Test
  void bullishHighTierWhenFutureOpenHighMeetsABullishQuadrant() {
    // OH on the future + a bullish underlying quadrant (LB) -> the bullish HIGH-tier proxy for a CE.
    assertThat(OpenHighLow.tier(openHighSeries(), 2, CE, oi(OiQuadrant.LONG_BUILDUP)))
        .isEqualTo(Tier.HIGH);
    // SHORT_COVERING is also bullish -> still HIGH.
    assertThat(OpenHighLow.tier(openHighSeries(), 2, CE, oi(OiQuadrant.SHORT_COVERING)))
        .isEqualTo(Tier.HIGH);
  }

  @Test
  void bearishHighTierWhenFutureOpenLowMeetsABearishQuadrant() {
    // OL on the future + a bearish underlying quadrant (SB) -> the bearish HIGH-tier proxy for a PE.
    assertThat(OpenHighLow.tier(openLowSeries(), 2, PE, oi(OiQuadrant.SHORT_BUILDUP)))
        .isEqualTo(Tier.HIGH);
    // LONG_UNWINDING is also bearish -> still HIGH.
    assertThat(OpenHighLow.tier(openLowSeries(), 2, PE, oi(OiQuadrant.LONG_UNWINDING)))
        .isEqualTo(Tier.HIGH);
  }

  @Test
  void mildTierWhenTheMarkIsPresentButTheQuadrantDoesNotConfirm() {
    // OH on the future but a bearish (SB) underlying quadrant -> opposite-trend mild, not HIGH.
    assertThat(OpenHighLow.tier(openHighSeries(), 2, CE, oi(OiQuadrant.SHORT_BUILDUP)))
        .isEqualTo(Tier.MILD);
    // OH on the future but a NEUTRAL quadrant (data missing) -> mild (no confirmation).
    assertThat(OpenHighLow.tier(openHighSeries(), 2, CE, oi(OiQuadrant.NEUTRAL)))
        .isEqualTo(Tier.MILD);
  }

  @Test
  void standAsideWhenBothOpenHighAndOpenLowAppearTogether() {
    // a single flat bar: the open is simultaneously the session high and low -> two-sided -> stand aside.
    EngineSeries flat = series(bar(0, "100", "100", "100", "100"));
    assertThat(OpenHighLow.marks(flat, 0).openHigh()).isTrue();
    assertThat(OpenHighLow.marks(flat, 0).openLow()).isTrue();
    assertThat(OpenHighLow.tier(flat, 0, CE, oi(OiQuadrant.LONG_BUILDUP))).isEqualTo(Tier.STAND_ASIDE);
    assertThat(OpenHighLow.tier(flat, 0, PE, oi(OiQuadrant.SHORT_BUILDUP))).isEqualTo(Tier.STAND_ASIDE);
  }

  @Test
  void standAsideWhenNoMarkOnTheRequestedSide() {
    // an OH session has no OL mark -> a PE request stands aside (nothing to trade short).
    assertThat(OpenHighLow.tier(openHighSeries(), 2, PE, oi(OiQuadrant.SHORT_BUILDUP)))
        .isEqualTo(Tier.STAND_ASIDE);
    // an OL session has no OH mark -> a CE request stands aside.
    assertThat(OpenHighLow.tier(openLowSeries(), 2, CE, oi(OiQuadrant.LONG_BUILDUP)))
        .isEqualTo(Tier.STAND_ASIDE);
  }

  @Test
  void standAsideWhenTheOiSnapshotIsNull() {
    // a mark with no OI snapshot can never be confirmed HIGH; the tier is MILD (no quadrant) -> the
    // gate will block. (null oi -> MILD, since the mark exists but the quadrant cannot confirm.)
    assertThat(OpenHighLow.tier(openHighSeries(), 2, CE, null)).isEqualTo(Tier.MILD);
  }

  @Test
  void degradesWithInsufficientBars() {
    assertThat(OpenHighLow.marks(null, 0).openHigh()).isFalse();
    assertThat(OpenHighLow.marks(openHighSeries(), 9).openHigh()).isFalse();
    assertThat(OpenHighLow.tier(null, 0, CE, oi(OiQuadrant.LONG_BUILDUP))).isEqualTo(Tier.STAND_ASIDE);
  }
}
