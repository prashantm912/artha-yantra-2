package in.arthayantra.marketcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * CD-2 tripwire (audit P1-9): the bundled NSE holiday CSV is refreshed yearly BY HAND, and every
 * calendar consumer (live scalper gate, OI capture, backtest pre-flight) breaks when a query
 * crosses the coverage edge. This test goes RED ~45 days before the max covered year ends —
 * turning a guaranteed New-Year outage into a routine CI reminder. When it fires: append the next
 * year's NSE trading holidays to nse-trading-holidays.csv (suffix rows, never edit applied ones)
 * and rebuild all services.
 */
class CalendarHorizonCanaryTest {

  @Test
  void holidayCoverageExtendsAtLeast45DaysBeyondToday() {
    int maxYear = MarketCalendar.nse().coveredYears().last();
    LocalDate coverageEnd = LocalDate.of(maxYear, 12, 31);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    assertThat(today.plusDays(45))
        .withFailMessage(
            "nse-trading-holidays.csv covers only through %s and today is %s — refresh the CSV for"
                + " %d NOW (CD-2): live expiry math starts degrading in the final December week and"
                + " OI capture halts on Jan 1.",
            coverageEnd, today, maxYear + 1)
        .isBeforeOrEqualTo(coverageEnd);
  }
}
