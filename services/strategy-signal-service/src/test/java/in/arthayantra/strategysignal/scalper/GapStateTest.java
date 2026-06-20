package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * section 3.4 Gap detection on the 3-min Bank Nifty future: a significant (&gt;3 pt) jump between the prior
 * candle's close and the current candle's open, with the fill flag set once a later bar retraces to
 * the gap origin (the prior close). Pure over fixed closed bars (replay-safe).
 */
class GapStateTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 6, 20, 9, 45, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // open, high, low, close - a plain candle (volume immaterial to gap geometry).
  private static EngineCandle bar(int i, String open, String high, String low, String close) {
    return new EngineCandle(T0.plusMinutes(3L * i), bd(open), bd(high), bd(low), bd(close), 60_000);
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "BANKNIFTY-FUT", "3m"), List.of(candles));
  }

  @Test
  void detectsSignificantBullishGapUpAndIsUnfilledWhilePriceStaysAbove() {
    // bar0 close 42530; bar1 opens 42534.30 (4.30 pt gap up, > 3) and stays above the origin.
    EngineSeries s =
        series(
            bar(0, "42525", "42531", "42520", "42530"),
            bar(1, "42534.30", "42540", "42533", "42538"));
    GapState.Gap g = GapState.detect(s, 1);
    assertThat(g.present()).isTrue();
    assertThat(g.bullish()).isTrue();
    assertThat(g.sizePoints()).isEqualByComparingTo("4.30");
    assertThat(g.origin()).isEqualByComparingTo("42530"); // prior close = gap origin
    assertThat(g.filled()).isFalse();
  }

  @Test
  void marksTheBullishGapFilledOnceALaterBarLowRetracesToTheOrigin() {
    // same 4.30 pt gap, then bar2 low (42465.50) prints below the origin (42530) -> filled.
    EngineSeries s =
        series(
            bar(0, "42525", "42531", "42520", "42530"),
            bar(1, "42534.30", "42540", "42533", "42538"),
            bar(2, "42536", "42537", "42465.50", "42470"));
    GapState.Gap g = GapState.detect(s, 2);
    assertThat(g.present()).isTrue();
    assertThat(g.bullish()).isTrue();
    assertThat(g.filled()).isTrue();
  }

  @Test
  void detectsSignificantBearishGapDownAndFillsWhenAHighRetracesUp() {
    // bar0 close 41000; bar1 opens 40990 (10 pt gap down) and stays below; bar2 high 41005 fills it.
    EngineSeries unfilled =
        series(
            bar(0, "41002", "41006", "40998", "41000"),
            bar(1, "40990", "40992", "40980", "40985"));
    GapState.Gap before = GapState.detect(unfilled, 1);
    assertThat(before.present()).isTrue();
    assertThat(before.bullish()).isFalse();
    assertThat(before.sizePoints()).isEqualByComparingTo("10");
    assertThat(before.origin()).isEqualByComparingTo("41000");
    assertThat(before.filled()).isFalse();

    EngineSeries filled =
        series(
            bar(0, "41002", "41006", "40998", "41000"),
            bar(1, "40990", "40992", "40980", "40985"),
            bar(2, "40988", "41005", "40986", "41002"));
    assertThat(GapState.detect(filled, 2).filled()).isTrue();
  }

  @Test
  void ignoresAnInsignificantGapBelowThreePoints() {
    // a 2 pt jump is below the 3 pt floor -> not a gap.
    EngineSeries s =
        series(
            bar(0, "42525", "42531", "42520", "42530"),
            bar(1, "42532", "42536", "42531", "42535"));
    assertThat(GapState.detect(s, 1).present()).isFalse();
  }

  @Test
  void ignoresAGapTheCreatingCandleAlreadyFilledByItsOwnWick() {
    // a 5 pt gap up, but the gap candle's own low (42528) already prints at/below the origin
    // (42530) -> the gap is filled on creation, so it is not an actionable unfilled gap.
    EngineSeries s =
        series(
            bar(0, "42525", "42531", "42520", "42530"),
            bar(1, "42535", "42542", "42528", "42540"));
    assertThat(GapState.detect(s, 1).present()).isFalse();
  }

  @Test
  void takesTheMostRecentSignificantGapWhenSeveralExistInTheSession() {
    // an early filled gap then a later unfilled one - detect reports the later, still-open gap.
    EngineSeries s =
        series(
            bar(0, "42525", "42531", "42520", "42530"),
            bar(1, "42536", "42541", "42528", "42539"), // 6 pt gap, own wick (42528) fills it
            bar(2, "42539", "42545", "42537", "42543"),
            bar(3, "42548", "42552", "42547", "42550")); // 5 pt gap, unfilled
    GapState.Gap g = GapState.detect(s, 3);
    assertThat(g.present()).isTrue();
    assertThat(g.origin()).isEqualByComparingTo("42543"); // bar2 close = the later gap origin
    assertThat(g.filled()).isFalse();
  }

  @Test
  void degradesToAbsentWithoutAPriorCandle() {
    EngineSeries s = series(bar(0, "42525", "42531", "42520", "42530"));
    assertThat(GapState.detect(s, 0).present()).isFalse();
    assertThat(GapState.detect(null, 5).present()).isFalse();
  }
}
