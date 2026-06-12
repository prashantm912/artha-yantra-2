package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.ContBackAdjuster;
import in.arthayantra.marketdata.candles.RollEventsRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** B-19 unit math: roll-date selection across holidays + two-roll back-adjustment fixture. */
class ContRollMathTest {

  private static final ContinuousFuturesRoller ROLLER =
      new ContinuousFuturesRoller(
          null, null, null, MarketCalendar.nse(), Clock.systemUTC(), List.of(), 1);

  @Test
  void rollDateIsOneTradingDayBeforeExpiry() {
    // plain mid-week: expiry Thu 2026-06-25 → roll Wed 2026-06-24
    assertThat(ROLLER.rollDate(LocalDate.parse("2026-06-25")))
        .isEqualTo(LocalDate.parse("2026-06-24"));
  }

  @Test
  void rollDateSkipsHolidayAdjacentToExpiry() {
    // expiry Mon 2026-10-05; Fri 2026-10-02 is Gandhi Jayanti → roll Thu 2026-10-01
    assertThat(ROLLER.rollDate(LocalDate.parse("2026-10-05")))
        .isEqualTo(LocalDate.parse("2026-10-01"));
  }

  @Test
  void backAdjustmentOffsetsPreRollBarsByCumulativeLaterGaps() {
    RollEventsRepository fakeRolls =
        new RollEventsRepository(null) {
          @Override
          public List<RollEvent> rollsFor(String underlying) {
            return List.of(
                new RollEvent("NIFTY 50", LocalDate.parse("2026-06-24"), "A", "B", new BigDecimal("10.0000")),
                new RollEvent("NIFTY 50", LocalDate.parse("2026-07-27"), "B", "C", new BigDecimal("-4.0000")));
          }
        };
    ContBackAdjuster adjuster = new ContBackAdjuster(fakeRolls);
    List<Candle> raw =
        List.of(
            bar("2026-06-23", "100"), // before both rolls → +10 − 4 = +6
            bar("2026-07-01", "200"), // after roll 1, before roll 2 → −4
            bar("2026-08-01", "300")); // after both → unchanged

    List<Candle> adjusted = adjuster.backAdjust("NIFTY 50", raw);

    assertThat(adjusted.get(0).close()).isEqualByComparingTo("106.0000");
    assertThat(adjusted.get(1).close()).isEqualByComparingTo("196.0000");
    assertThat(adjusted.get(2).close()).isEqualByComparingTo("300");
    assertThat(adjusted.get(0).volume()).isEqualTo(raw.get(0).volume());
  }

  private static Candle bar(String date, String close) {
    BigDecimal c = new BigDecimal(close);
    return new Candle(
        "NFO", "NIFTY-FUT-CONT", "1d",
        OffsetDateTime.parse(date + "T00:00:00+05:30"),
        c, c, c, c, 10, null, "MOCK");
  }
}
