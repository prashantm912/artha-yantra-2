package in.arthayantra.marketcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** Phase-4 boundary table: holiday, weekend, 09:14/09:15/15:30/15:31, Tuesday expiry (A.5). */
class MarketCalendarTest {

  private final MarketCalendar calendar = MarketCalendar.nse();

  // 2026-06-10 is a regular Wednesday trading day
  private static Instant ist(String date, String time) {
    return ZonedDateTime.parse(date + "T" + time + "+05:30[Asia/Kolkata]").toInstant();
  }

  @Test
  void sessionBoundaryTable() {
    assertThat(calendar.isOpen(ist("2026-06-10", "09:14:59"))).isFalse();
    assertThat(calendar.isOpen(ist("2026-06-10", "09:15:00"))).isTrue();
    assertThat(calendar.isOpen(ist("2026-06-10", "12:00:00"))).isTrue();
    assertThat(calendar.isOpen(ist("2026-06-10", "15:29:59"))).isTrue();
    assertThat(calendar.isOpen(ist("2026-06-10", "15:30:00"))).isFalse();
    assertThat(calendar.isOpen(ist("2026-06-10", "15:31:00"))).isFalse();
  }

  @Test
  void holidayListCarriesNamesDateAscending() {
    var list = calendar.holidayList();
    assertThat(list).isNotEmpty();
    // date-ascending
    assertThat(list).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));
    // every modeled holiday date has a non-blank published name
    assertThat(list).allSatisfy(h -> assertThat(h.name()).isNotBlank());
    // a known entry resolves to its published name
    assertThat(list)
        .anySatisfy(
            h -> {
              assertThat(h.date()).isEqualTo(LocalDate.parse("2026-01-26"));
              assertThat(h.name()).isEqualTo("Republic Day");
            });
    // the named list and the trading-day holiday set agree
    assertThat(list).allSatisfy(h -> assertThat(calendar.isTradingDay(h.date())).isFalse());
  }

  @Test
  void bareDateCalendarHasNoNamedList() {
    assertThat(MarketCalendar.of(java.util.List.of(LocalDate.parse("2026-01-26"))).holidayList())
        .isEmpty();
  }

  @Test
  void weekendsAndHolidaysAreClosed() {
    assertThat(calendar.isTradingDay(LocalDate.parse("2026-06-13"))).isFalse(); // Saturday
    assertThat(calendar.isTradingDay(LocalDate.parse("2026-06-14"))).isFalse(); // Sunday
    assertThat(calendar.isTradingDay(LocalDate.parse("2026-01-26"))).isFalse(); // Republic Day
    assertThat(calendar.isOpen(ist("2026-01-26", "10:00:00"))).isFalse();
    assertThat(calendar.isTradingDay(LocalDate.parse("2026-06-10"))).isTrue();
  }

  @Test
  void sessionBoundsAreIstInstants() {
    MarketCalendar.SessionBounds bounds =
        calendar.sessionBounds(LocalDate.parse("2026-06-10")).orElseThrow();

    // 09:15 IST == 03:45 UTC
    assertThat(bounds.open()).isEqualTo(Instant.parse("2026-06-10T03:45:00Z"));
    assertThat(bounds.close()).isEqualTo(Instant.parse("2026-06-10T10:00:00Z"));
    assertThat(calendar.sessionBounds(LocalDate.parse("2026-06-13"))).isEmpty();
  }

  @Test
  void nextTradingDaySkipsWeekendsAndHolidays() {
    // Friday -> Monday
    assertThat(calendar.nextTradingDay(LocalDate.parse("2026-06-12")))
        .isEqualTo(LocalDate.parse("2026-06-15"));
    // Friday before Republic Day Monday -> Tuesday
    assertThat(calendar.nextTradingDay(LocalDate.parse("2026-01-23")))
        .isEqualTo(LocalDate.parse("2026-01-27"));
  }

  @Test
  void previousTradingDaySkipsWeekendsAndHolidays() {
    // Monday -> previous Friday
    assertThat(calendar.previousTradingDay(LocalDate.parse("2026-06-15")))
        .isEqualTo(LocalDate.parse("2026-06-12"));
    // Tuesday after Republic Day Monday -> previous Friday
    assertThat(calendar.previousTradingDay(LocalDate.parse("2026-01-27")))
        .isEqualTo(LocalDate.parse("2026-01-23"));
  }

  @Test
  void expectedMinuteBucketsCountsSessionBuckets() {
    // full trading day = 375 one-minute buckets (09:15 .. 15:29)
    assertThat(
            calendar.expectedMinuteBuckets(
                ist("2026-06-10", "00:00:00"), ist("2026-06-10", "23:59:59")))
        .isEqualTo(375);
    // half-open range: [09:15, 09:20) = 5 buckets
    assertThat(
            calendar.expectedMinuteBuckets(ist("2026-06-10", "09:15:00"), ist("2026-06-10", "09:20:00")))
        .isEqualTo(5);
    // weekend range -> 0
    assertThat(
            calendar.expectedMinuteBuckets(
                ist("2026-06-13", "00:00:00"), ist("2026-06-14", "23:59:59")))
        .isEqualTo(0);
    // two full days (Wed+Thu) = 750
    assertThat(
            calendar.expectedMinuteBuckets(
                ist("2026-06-10", "00:00:00"), ist("2026-06-11", "23:59:59")))
        .isEqualTo(750);
  }

  @Test
  void weeklyIndexExpiryIsTuesday() {
    // Wednesday -> next Tuesday
    assertThat(calendar.nextWeeklyIndexExpiry(LocalDate.parse("2026-06-10")))
        .isEqualTo(LocalDate.parse("2026-06-16"));
    // a Tuesday is its own expiry
    assertThat(calendar.nextWeeklyIndexExpiry(LocalDate.parse("2026-06-16")))
        .isEqualTo(LocalDate.parse("2026-06-16"));
    assertThat(calendar.isWeeklyIndexExpiryDay(LocalDate.parse("2026-06-16"))).isTrue();
    assertThat(calendar.isWeeklyIndexExpiryDay(LocalDate.parse("2026-06-10"))).isFalse();
  }

  @Test
  void monthlyIndexExpiryIsTheLastWeeklyExpiryOfTheMonth() {
    // June 2026 weekly expiries: Tue 2/9/16/23/30 (all trading days). The LAST, 2026-06-30, is the
    // monthly — the next weekly expiry after it (2026-07-07) is in July.
    assertThat(calendar.isMonthlyIndexExpiryDay(LocalDate.parse("2026-06-30"))).isTrue();
    // an earlier June weekly expiry is weekly but NOT monthly (the next, 2026-06-30, is same month)
    assertThat(calendar.isWeeklyIndexExpiryDay(LocalDate.parse("2026-06-23"))).isTrue();
    assertThat(calendar.isMonthlyIndexExpiryDay(LocalDate.parse("2026-06-23"))).isFalse();
    // a non-expiry weekday is neither weekly nor monthly
    assertThat(calendar.isMonthlyIndexExpiryDay(LocalDate.parse("2026-06-24"))).isFalse();
  }

  @Test
  void holidayTuesdayExpiryPreponesToMonday() {
    // 2026-10-20 (Tue) is Dussehra -> expiry prepones to Mon 2026-10-19
    assertThat(calendar.nextWeeklyIndexExpiry(LocalDate.parse("2026-10-14")))
        .isEqualTo(LocalDate.parse("2026-10-19"));
    assertThat(calendar.isWeeklyIndexExpiryDay(LocalDate.parse("2026-10-19"))).isTrue();
    // asking ON the holiday Tuesday rolls to next week's expiry (the prepone is in the past)
    assertThat(calendar.nextWeeklyIndexExpiry(LocalDate.parse("2026-10-20")))
        .isEqualTo(LocalDate.parse("2026-10-27"));
  }

  @Test
  void uncoveredYearFailsLoudly() {
    assertThatThrownBy(() -> calendar.isTradingDay(LocalDate.parse("2027-01-04")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2027");
  }

  @Test
  void holidayResourceCoversBackfillYears() {
    // 2024 + 2025 added for the Phase-1 backfill window + scalp backtests (plan §17.5)
    assertThat(calendar.coveredYears()).contains(2024, 2025, 2026);
  }

  @Test
  void multiYearHolidaysAndTradingDays() {
    // 2024 — Republic Day + Diwali Laxmi Pujan (regular session closed) are holidays; an
    // ordinary weekday trades
    assertThat(calendar.isTradingDay(LocalDate.parse("2024-01-26"))).isFalse();
    assertThat(calendar.isTradingDay(LocalDate.parse("2024-11-01"))).isFalse();
    assertThat(calendar.isTradingDay(LocalDate.parse("2024-01-02"))).isTrue();
    // 2025 — Independence Day + Diwali Laxmi Pujan are holidays; an ordinary weekday trades
    assertThat(calendar.isTradingDay(LocalDate.parse("2025-08-15"))).isFalse();
    assertThat(calendar.isTradingDay(LocalDate.parse("2025-10-21"))).isFalse();
    assertThat(calendar.isTradingDay(LocalDate.parse("2025-01-02"))).isTrue();
  }
}
