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
 * section 3.12 market-structure primitive on the 3-min future: detect 3-bar fractal swing pivots and a
 * structure break (a close beyond the most recent OPPOSING swing pivot) in the reversal direction. Pure
 * over fixed closed bars (replay-safe).
 */
class MarketStructureTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 6, 20, 9, 45, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

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
  void detectsAnUpReversalBreakWhenPriceClosesAboveTheRecentSwingHigh() {
    // bars 1..3 carve a swing-high at bar2 (high 60, strictly above bar1=50 and bar3=55); the deploy
    // bar4 closes 62 (> 60) -> up-structure break; the broken swing-high (60) is the stop anchor.
    EngineSeries s =
        series(
            bar(0, "40", "45", "38", "44"),
            bar(1, "44", "50", "43", "48"),
            bar(2, "48", "60", "47", "55"), // swing-high pivot (high 60)
            bar(3, "55", "55", "50", "52"),
            bar(4, "52", "63", "51", "62")); // closes above the swing-high 60 -> break
    MarketStructure.Break b = MarketStructure.detect(s, 4, CE);
    assertThat(b.broke()).isTrue();
    assertThat(b.pivot()).isEqualByComparingTo("60"); // the broken swing-high
  }

  @Test
  void detectsADownReversalBreakWhenPriceClosesBelowTheRecentSwingLow() {
    // bars carve a swing-low at bar2 (low 30, strictly below bar1=35 and bar3=33); the deploy bar4
    // closes 28 (< 30) -> down-structure break; the broken swing-low (30) is the stop anchor.
    EngineSeries s =
        series(
            bar(0, "50", "52", "46", "48"),
            bar(1, "48", "49", "35", "40"),
            bar(2, "40", "41", "30", "38"), // swing-low pivot (low 30)
            bar(3, "38", "42", "33", "40"),
            bar(4, "40", "41", "27", "28")); // closes below the swing-low 30 -> break
    MarketStructure.Break b = MarketStructure.detect(s, 4, PE);
    assertThat(b.broke()).isTrue();
    assertThat(b.pivot()).isEqualByComparingTo("30"); // the broken swing-low
  }

  @Test
  void doesNotBreakWhenPriceStaysWithinTheRange() {
    // a swing-high at bar2 (60) but bar4 closes 58 (< 60) -> no up-break.
    EngineSeries s =
        series(
            bar(0, "40", "45", "38", "44"),
            bar(1, "44", "50", "43", "48"),
            bar(2, "48", "60", "47", "55"),
            bar(3, "55", "56", "50", "52"),
            bar(4, "52", "59", "51", "58")); // below the swing-high -> no break
    assertThat(MarketStructure.detect(s, 4, CE).broke()).isFalse();
  }

  @Test
  void doesNotReportAnUpBreakForADownsideClose() {
    // the only pivot is a swing-low; a CE (up) break needs a swing-high to clear -> no up-break.
    EngineSeries s =
        series(
            bar(0, "50", "52", "46", "48"),
            bar(1, "48", "49", "35", "40"),
            bar(2, "40", "41", "30", "38"), // swing-low only
            bar(3, "38", "42", "33", "40"),
            bar(4, "40", "55", "39", "54")); // rallies, but there is no swing-high to break
    assertThat(MarketStructure.detect(s, 4, CE).broke()).isFalse();
  }

  @Test
  void breaksTheMostRecentOpposingSwingPivot() {
    // two swing-highs in the session (bar2=60, bar5=58); bar7 closes 59 -> clears the RECENT one (58)
    // but not the older 60. The most-recent confirmed swing-high (58) is the anchor.
    EngineSeries s =
        series(
            bar(0, "40", "45", "38", "44"),
            bar(1, "44", "50", "43", "48"),
            bar(2, "48", "60", "47", "55"), // older swing-high (60)
            bar(3, "55", "56", "50", "52"),
            bar(4, "52", "54", "49", "51"),
            bar(5, "51", "58", "50", "53"), // recent swing-high (58)
            bar(6, "53", "54", "50", "52"),
            bar(7, "52", "60", "51", "59")); // closes 59 > 58 (recent) -> break on the recent pivot
    MarketStructure.Break b = MarketStructure.detect(s, 7, CE);
    assertThat(b.broke()).isTrue();
    assertThat(b.pivot()).isEqualByComparingTo("58");
  }

  @Test
  void degradesToNoBreakWithoutEnoughBarsOrNoConfirmedPivot() {
    EngineSeries tooShort = series(bar(0, "40", "45", "38", "44"), bar(1, "44", "50", "43", "48"));
    assertThat(MarketStructure.detect(tooShort, 1, CE).broke()).isFalse();
    assertThat(MarketStructure.detect(null, 5, CE).broke()).isFalse();
  }
}
