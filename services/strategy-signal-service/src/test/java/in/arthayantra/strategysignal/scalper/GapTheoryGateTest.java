package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
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
 * section 3.4 Gap-Theory pre-gate: block a fresh entry while a significant 3-min gap is still open, pass
 * once it has filled (with-trend), and stay inert when no significant gap exists. The structural
 * stop is anchored on the pre-gap candle's extreme (the matrix SL alternative; see {@link
 * GapTheoryGate}).
 */
class GapTheoryGateTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 6, 20, 9, 45, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final String BN = "NIFTY BANK";

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static EngineCandle bar(int i, String open, String high, String low, String close) {
    return new EngineCandle(T0.plusMinutes(3L * i), bd(open), bd(high), bd(low), bd(close), 60_000);
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "BANKNIFTY-FUT", "3m"), List.of(candles));
  }

  @Test
  void blocksWhileASignificantBullishGapIsStillUnfilled() {
    // bar0 close 42530; bar1 4.30 pt gap up, unfilled -> a CE entry must wait.
    EngineSeries s =
        series(
            bar(0, "42525", "42531", "42520", "42530"),
            bar(1, "42534.30", "42540", "42533", "42538"));
    GapTheoryGate.Verdict v = GapTheoryGate.evaluate(s, 1, CE, BN);
    assertThat(v.pass()).isFalse();
    assertThat(v.stopLevel()).isNull();
  }

  @Test
  void passesWithTrendOnceTheBullishGapHasFilledAndAnchorsTheStopOnThePreGapLow() {
    // 4.30 pt gap (bar0->bar1), bar2 low retraces below the origin (42530) -> filled -> CE may enter.
    EngineSeries s =
        series(
            bar(0, "42525", "42531", "42515", "42530"),
            bar(1, "42534.30", "42540", "42533", "42538"),
            bar(2, "42536", "42537", "42465.50", "42470"));
    GapTheoryGate.Verdict v = GapTheoryGate.evaluate(s, 2, CE, BN);
    assertThat(v.pass()).isTrue();
    // pre-gap candle is bar0 (the candle before the gap candle bar1); its low anchors the long SL.
    assertThat(v.stopLevel()).isEqualByComparingTo("42515");
  }

  @Test
  void passesAndAnchorsThePreGapHighForAFilledBearishGap() {
    // bar0 close 41000; bar1 10 pt gap down; bar2 high 41005 fills it -> PE may enter.
    EngineSeries s =
        series(
            bar(0, "41002", "41010", "40998", "41000"),
            bar(1, "40990", "40992", "40980", "40985"),
            bar(2, "40988", "41005", "40986", "41002"));
    GapTheoryGate.Verdict v = GapTheoryGate.evaluate(s, 2, PE, BN);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isEqualByComparingTo("41010"); // pre-gap candle (bar0) high
  }

  @Test
  void isInertWhenNoSignificantGapExists() {
    // a flat session with no >3 pt jump -> the gate never fires (passes, no stop anchor).
    EngineSeries s =
        series(
            bar(0, "42525", "42531", "42520", "42530"),
            bar(1, "42531", "42535", "42529", "42533"),
            bar(2, "42533", "42537", "42531", "42535"));
    GapTheoryGate.Verdict v = GapTheoryGate.evaluate(s, 2, CE, BN);
    assertThat(v.pass()).isTrue();
    assertThat(v.stopLevel()).isNull();
  }

  @Test
  void degradesToNoEntryWithInsufficientBars() {
    // a lone bar cannot carry a gap, but the gate must not blow up - it blocks (no entry).
    EngineSeries s = series(bar(0, "42525", "42531", "42520", "42530"));
    assertThat(GapTheoryGate.evaluate(s, 0, CE, BN).pass()).isFalse();
    assertThat(GapTheoryGate.evaluate(null, 5, CE, BN).pass()).isFalse();
  }
}
