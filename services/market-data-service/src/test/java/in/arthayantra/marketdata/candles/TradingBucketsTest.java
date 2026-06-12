package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase-11 calendar fixtures: expected-bucket enumeration drives the gap math. */
class TradingBucketsTest {

  private final TradingBuckets buckets = new TradingBuckets(MarketCalendar.nse());

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  @Test
  void fullTradingDayHas375MinuteBuckets() {
    List<OffsetDateTime> minutes =
        buckets.minuteBuckets(ist("2026-02-03T00:00:00"), ist("2026-02-04T00:00:00"));

    assertThat(minutes).hasSize(375); // 09:15 .. 15:29
    assertThat(minutes.get(0)).isEqualTo(ist("2026-02-03T09:15:00"));
    assertThat(minutes.get(374)).isEqualTo(ist("2026-02-03T15:29:00"));
  }

  @Test
  void holidayAndWeekendMinutesAreNotExpected() {
    // Fri 2026-01-23 close → Tue 2026-01-27 open; Mon 26th is Republic Day
    List<OffsetDateTime> minutes =
        buckets.minuteBuckets(ist("2026-01-23T15:25:00"), ist("2026-01-27T09:20:00"));

    assertThat(minutes).hasSize(10); // 5 Friday closing minutes + 5 Tuesday opening minutes
    assertThat(minutes)
        .noneMatch(b -> b.toLocalDate().isAfter(LocalDate.parse("2026-01-23"))
            && b.toLocalDate().isBefore(LocalDate.parse("2026-01-27")));
  }

  @Test
  void januaryHasTwentyDayBuckets() {
    // 22 weekdays minus 2 holidays (Jan 15 election, Jan 26 Republic Day)
    List<OffsetDateTime> days =
        buckets.dayBuckets(ist("2026-01-01T00:00:00"), ist("2026-02-01T00:00:00"));

    assertThat(days).hasSize(20);
    assertThat(days).doesNotContain(ist("2026-01-15T00:00:00"), ist("2026-01-26T00:00:00"));
  }

  @Test
  void uncoveredYearDegradesToWeekdayApproximation() {
    // 2030 has no holiday data — Mon 2030-02-04 still enumerates a full session
    List<OffsetDateTime> minutes =
        buckets.minuteBuckets(ist("2030-02-04T00:00:00"), ist("2030-02-05T00:00:00"));

    assertThat(minutes).hasSize(375);
    // and the weekend stays excluded
    assertThat(buckets.minuteBuckets(ist("2030-02-02T00:00:00"), ist("2030-02-04T00:00:00")))
        .isEmpty();
  }
}
