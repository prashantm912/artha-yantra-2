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
 * §3.12 {@link Trendline} break: the line through the two most recent same-side fractal pivots, broken
 * by the deploy bar's close. CE breaks a DESCENDING-highs resistance UP; PE breaks an ASCENDING-lows
 * support DOWN; a flat/wrong-slope pair or too-few pivots is not a tradeable trendline.
 */
class TrendlineTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 6, 20, 9, 45, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static EngineCandle bar(int i, String open, String high, String low, String close) {
    return new EngineCandle(T0.plusMinutes(3L * i), bd(open), bd(high), bd(low), bd(close), 60_000);
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "NIFTY-FUT", "3m"), List.of(candles));
  }

  // swing-highs at bar1 (110) and bar3 (108) → a falling resistance; projected level at bar6 = 105.
  private static EngineSeries descendingHighs(String deployClose) {
    return series(
        bar(0, "100", "101", "99", "100"),
        bar(1, "105", "110", "104", "108"), // swing-high 110 (p1)
        bar(2, "104", "105", "103", "104"),
        bar(3, "106", "108", "105", "107"), // swing-high 108 (p2), descending
        bar(4, "103", "103", "101", "102"),
        bar(5, "103", "104", "102", "103"),
        bar(6, "105", "107", "104", deployClose)); // deploy; line projects to 105
  }

  @Test
  void ceBreaksTheDescendingResistanceWhenTheCloseClearsTheProjectedLine() {
    Trendline.Break b = Trendline.detect(descendingHighs("106"), 6, CE); // 106 > 105
    assertThat(b.broke()).isTrue();
    assertThat(b.lineLevel()).isEqualByComparingTo("105");
  }

  @Test
  void ceDoesNotBreakWhenTheCloseStaysBelowTheProjectedLine() {
    assertThat(Trendline.detect(descendingHighs("104"), 6, CE).broke()).isFalse(); // 104 < 105
  }

  @Test
  void ascendingHighsAreNotAFallingResistanceSoNoCeBreak() {
    // both pivots present but ASCENDING (108 then 110) — not a resistance line a CE breaks up.
    EngineSeries s =
        series(
            bar(0, "100", "101", "99", "100"),
            bar(1, "105", "108", "104", "107"), // swing-high 108 (p1)
            bar(2, "104", "105", "103", "104"),
            bar(3, "106", "110", "105", "109"), // swing-high 110 (p2) — ascending
            bar(4, "103", "103", "101", "102"),
            bar(5, "103", "104", "102", "103"),
            bar(6, "111", "112", "108", "111"));
    assertThat(Trendline.detect(s, 6, CE).broke()).isFalse();
  }

  @Test
  void peBreaksTheAscendingSupportWhenTheCloseUndercutsTheProjectedLine() {
    // swing-lows at bar1 (90) and bar3 (92) → a rising support; projected level at bar6 = 95.
    EngineSeries s =
        series(
            bar(0, "101", "102", "100", "101"),
            bar(1, "96", "97", "90", "92"), // swing-low 90 (p1)
            bar(2, "95", "96", "94", "95"),
            bar(3, "94", "95", "92", "93"), // swing-low 92 (p2), ascending
            bar(4, "97", "98", "96", "97"),
            bar(5, "96", "97", "95", "96"),
            bar(6, "95", "96", "93", "94")); // deploy; line projects to 95, close 94 < 95
    Trendline.Break b = Trendline.detect(s, 6, PE);
    assertThat(b.broke()).isTrue();
    assertThat(b.lineLevel()).isEqualByComparingTo("95");
  }

  @Test
  void tooFewPivotsYieldsNoTrendline() {
    // only one swing-high in the session → cannot draw a line.
    EngineSeries s =
        series(
            bar(0, "100", "101", "99", "100"),
            bar(1, "105", "110", "104", "108"), // the only swing-high
            bar(2, "104", "105", "103", "104"),
            bar(3, "105", "107", "104", "106"));
    assertThat(Trendline.detect(s, 3, CE).broke()).isFalse();
    assertThat(Trendline.detect(null, 5, CE).broke()).isFalse();
  }
}
