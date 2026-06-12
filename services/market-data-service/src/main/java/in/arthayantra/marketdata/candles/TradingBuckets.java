package in.arthayantra.marketdata.candles;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates expected bucket starts inside a range (Phase 11 gap math + the mock generator).
 * Trading sessions come from the shared {@link MarketCalendar}; years outside its holiday
 * coverage degrade to a weekday-only approximation (documented — holidays there may cause
 * harmless empty re-fetch attempts, never wrong data).
 */
public final class TradingBuckets {

  private final MarketCalendar calendar;

  /** Wires the shared calendar. */
  public TradingBuckets(MarketCalendar calendar) {
    this.calendar = calendar;
  }

  /** Expected 1m bucket starts in {@code [from, to)} within sessions. */
  public List<OffsetDateTime> minuteBuckets(OffsetDateTime from, OffsetDateTime to) {
    List<OffsetDateTime> buckets = new ArrayList<>();
    OffsetDateTime fromIst = from.withOffsetSameInstant(Ist.OFFSET);
    OffsetDateTime toIst = to.withOffsetSameInstant(Ist.OFFSET);
    LocalDate day = fromIst.toLocalDate();
    while (!day.isAfter(toIst.toLocalDate())) {
      if (isTradingDay(day)) {
        OffsetDateTime open = day.atTime(MarketCalendar.SESSION_OPEN).atOffset(Ist.OFFSET);
        OffsetDateTime close = day.atTime(MarketCalendar.SESSION_CLOSE).atOffset(Ist.OFFSET);
        for (OffsetDateTime b = open; b.isBefore(close); b = b.plusMinutes(1)) {
          if (!b.isBefore(fromIst) && b.isBefore(toIst)) {
            buckets.add(b);
          }
        }
      }
      day = day.plusDays(1);
    }
    return buckets;
  }

  /** Expected 1d bucket starts (IST midnight of trading days) in {@code [from, to)}. */
  public List<OffsetDateTime> dayBuckets(OffsetDateTime from, OffsetDateTime to) {
    List<OffsetDateTime> buckets = new ArrayList<>();
    OffsetDateTime fromIst = from.withOffsetSameInstant(Ist.OFFSET);
    OffsetDateTime toIst = to.withOffsetSameInstant(Ist.OFFSET);
    LocalDate day = fromIst.toLocalDate();
    while (!day.isAfter(toIst.toLocalDate())) {
      if (isTradingDay(day)) {
        OffsetDateTime bucket = day.atTime(LocalTime.MIDNIGHT).atOffset(Ist.OFFSET);
        if (!bucket.isBefore(fromIst) && bucket.isBefore(toIst)) {
          buckets.add(bucket);
        }
      }
      day = day.plusDays(1);
    }
    return buckets;
  }

  /** Trading-day check with the weekday fallback for uncovered years. */
  public boolean isTradingDay(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      DayOfWeek dow = day.getDayOfWeek();
      return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }
  }
}
