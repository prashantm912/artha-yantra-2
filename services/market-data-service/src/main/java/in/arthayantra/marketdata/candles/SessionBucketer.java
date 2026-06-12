package in.arthayantra.marketdata.candles;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * IST minute-bucket flooring with session clamping (Phase 10 minimal-config note): on a trading
 * day, pre-open prints fold into the 09:15 open bucket and at/after-close prints fold into the
 * 15:29 last session bucket — day-boundary aware. Outside calendar knowledge (non-trading days,
 * uncovered years — the mock feed ticks 24/7) plain minute flooring applies.
 */
public final class SessionBucketer {

  private final MarketCalendar calendar;

  /** Wires the shared calendar. */
  public SessionBucketer(MarketCalendar calendar) {
    this.calendar = calendar;
  }

  /** The 1m bucket start (IST) for a tick timestamp. */
  public OffsetDateTime bucketFor(OffsetDateTime tickTime) {
    OffsetDateTime ist = tickTime.withOffsetSameInstant(Ist.OFFSET);
    LocalDate day = ist.toLocalDate();
    boolean tradingDay;
    try {
      tradingDay = calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      tradingDay = false;
    }
    if (tradingDay) {
      LocalTime time = ist.toLocalTime();
      if (time.isBefore(MarketCalendar.SESSION_OPEN)) {
        return day.atTime(MarketCalendar.SESSION_OPEN).atOffset(Ist.OFFSET);
      }
      if (!time.isBefore(MarketCalendar.SESSION_CLOSE)) {
        return day.atTime(MarketCalendar.SESSION_CLOSE.minusMinutes(1)).atOffset(Ist.OFFSET);
      }
    }
    return ist.truncatedTo(ChronoUnit.MINUTES);
  }

  /** Convenience for instants. */
  public OffsetDateTime bucketFor(Instant instant) {
    return bucketFor(instant.atOffset(Ist.OFFSET));
  }
}
