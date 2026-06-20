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
 * section 3.2 Open=High / Open=Low pre-gate: tier &gt;= HIGH for the side + the &lt;=50% spurt reject
 * rules + the 1st-half (~12:00) cutoff; the front-future VWAP anchors the SL. Any failing leg BLOCKS
 * (never a false fire); null reject magnitudes do NOT block; a null OI snapshot degrades to a block.
 */
class OpenHighLowGateTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final LocalTime MORNING = LocalTime.of(9, 50);
  private static final BigDecimal VWAP = new BigDecimal("97");

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static EngineCandle bar(int i, String open, String high, String low, String close) {
    return new EngineCandle(T0.plusMinutes(3L * i), bd(open), bd(high), bd(low), bd(close), 60_000);
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "NIFTY-FUT", "3m"), List.of(candles));
  }

  // An Open=High session (bar0 open 100 is the running session high).
  private static EngineSeries openHighSeries() {
    return series(
        bar(0, "100", "100", "95", "98"),
        bar(1, "98", "99", "94", "96"),
        bar(2, "96", "100", "93", "97"));
  }

  // An Open=Low session (bar0 open 100 is the running session low).
  private static EngineSeries openLowSeries() {
    return series(
        bar(0, "100", "105", "100", "103"),
        bar(1, "103", "107", "101", "105"),
        bar(2, "105", "108", "100", "106"));
  }

  // The Oi snapshot the gate reads: the underlying quadrant + the spurt OI/price reject magnitudes.
  private static Oi oi(OiQuadrant underlying, String spurtOiPct, String spurtPricePct) {
    return new Oi(
        underlying, OiQuadrant.NEUTRAL, bd("10"), bd("5"), bd("5"), null, null, null, false, false,
        null, spurtOiPct == null ? null : bd(spurtOiPct), spurtPricePct == null ? null : bd(spurtPricePct));
  }

  @Test
  void passesABullishOpenHighAtTheHighTierAndAnchorsTheVwapStop() {
    OpenHighLowGate.Verdict v =
        OpenHighLowGate.evaluate(
            openHighSeries(), 2, CE, oi(OiQuadrant.LONG_BUILDUP, "10", "20"), VWAP, MORNING);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isEqualByComparingTo("97"); // the VWAP SL
  }

  @Test
  void passesABearishOpenLowAtTheHighTier() {
    OpenHighLowGate.Verdict v =
        OpenHighLowGate.evaluate(
            openLowSeries(), 2, PE, oi(OiQuadrant.SHORT_BUILDUP, "-10", "-20"), VWAP, MORNING);
    assertThat(v.pass()).isTrue();
  }

  @Test
  void blocksAtTheMildTierWhenTheQuadrantDoesNotConfirm() {
    // OH on the future but a bearish quadrant -> MILD -> block.
    assertThat(
            OpenHighLowGate.evaluate(
                    openHighSeries(), 2, CE, oi(OiQuadrant.SHORT_BUILDUP, "10", "20"), VWAP, MORNING)
                .pass())
        .isFalse();
  }

  @Test
  void blocksAtStandAsideWhenThereIsNoMarkOnTheSide() {
    // an OH session has no OL mark -> a PE request stands aside -> block.
    assertThat(
            OpenHighLowGate.evaluate(
                    openHighSeries(), 2, PE, oi(OiQuadrant.SHORT_BUILDUP, "10", "20"), VWAP, MORNING)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenThePremiumSpurtExceedsFiftyPercent() {
    // the price spurt magnitude is 50.1% -> the move has already exhausted -> reject.
    assertThat(
            OpenHighLowGate.evaluate(
                    openHighSeries(), 2, CE, oi(OiQuadrant.LONG_BUILDUP, "10", "50.1"), VWAP, MORNING)
                .pass())
        .isFalse();
  }

  @Test
  void blocksWhenTheOiSpurtExceedsFiftyPercent() {
    // the OI-change magnitude is -60% (abs 60 > 50) -> a bigger player took the opposite view -> reject.
    assertThat(
            OpenHighLowGate.evaluate(
                    openHighSeries(), 2, CE, oi(OiQuadrant.LONG_BUILDUP, "-60", "20"), VWAP, MORNING)
                .pass())
        .isFalse();
  }

  @Test
  void passesAtExactlyFiftyPercentSpurt() {
    // exactly 50% is within the band (the reject is strictly >50) -> still passes.
    assertThat(
            OpenHighLowGate.evaluate(
                    openHighSeries(), 2, CE, oi(OiQuadrant.LONG_BUILDUP, "50", "50"), VWAP, MORNING)
                .pass())
        .isTrue();
  }

  @Test
  void doesNotBlockOnNullSpurtMagnitudes() {
    // null spurt magnitudes are UNAVAILABLE, not a reject -> the gate still passes on the HIGH tier.
    assertThat(
            OpenHighLowGate.evaluate(
                    openHighSeries(), 2, CE, oi(OiQuadrant.LONG_BUILDUP, null, null), VWAP, MORNING)
                .pass())
        .isTrue();
  }

  @Test
  void blocksAFreshEntryInTheSecondHalf() {
    // 12:00 is past the ~12:00 1st-half cutoff -> no fresh OH/OL entry.
    assertThat(
            OpenHighLowGate.evaluate(
                    openHighSeries(),
                    2,
                    CE,
                    oi(OiQuadrant.LONG_BUILDUP, "10", "20"),
                    VWAP,
                    LocalTime.of(12, 0))
                .pass())
        .isFalse();
    // 11:59 is still in the 1st half -> allowed.
    assertThat(
            OpenHighLowGate.evaluate(
                    openHighSeries(),
                    2,
                    CE,
                    oi(OiQuadrant.LONG_BUILDUP, "10", "20"),
                    VWAP,
                    LocalTime.of(11, 59))
                .pass())
        .isTrue();
  }

  @Test
  void degradesToABlockWhenTheOiSnapshotIsNull() {
    assertThat(
            OpenHighLowGate.evaluate(openHighSeries(), 2, CE, null, VWAP, MORNING).pass())
        .isFalse();
  }

  @Test
  void passesWithANullVwapButYieldsANullStop() {
    // the engine has no VWAP yet -> the gate still passes on the tier, but the SL is null (size off
    // structure, as the other gates do when no anchor is available).
    OpenHighLowGate.Verdict v =
        OpenHighLowGate.evaluate(
            openHighSeries(), 2, CE, oi(OiQuadrant.LONG_BUILDUP, "10", "20"), null, MORNING);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isNull();
  }
}
