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
 * day, at/after-close prints fold into the 15:29 last session bucket — day-boundary aware.
 * Pre-open prints are flagged via {@link #isPreOpen} and DROPPED by the builder: the 09:00–09:15
 * order-collection window streams indicative equilibrium values (not trades) that swing hundreds
 * of points, and folding them into the 09:15 bucket corrupted the open bar's high/low (audit
 * 2026-07-02 §5 — Jul-02 NIFTY 50 09:15 low 23670 vs open 24005). {@link #bucketFor} keeps the
 * 09:15 fold as a fallback for callers that skip the pre-open check. Outside calendar knowledge
 * (non-trading days, uncovered years — the mock feed ticks 24/7) plain minute flooring applies.
 */
public final class SessionBucketer {

  private final MarketCalendar calendar;
  private final boolean clampToSession;

  /** Wires the shared calendar with session clamping ON (the live default). */
  public SessionBucketer(MarketCalendar calendar) {
    this(calendar, true);
  }

  /**
   * Wires the calendar. {@code clampToSession=false} (mock 1m feed only) keeps plain minute
   * flooring 24/7: the mock feed ticks round the clock, so clamping would freeze every symbol's
   * bucket at 15:29 all evening — no further closed bars roll, starving the live signal engine.
   */
  public SessionBucketer(MarketCalendar calendar, boolean clampToSession) {
    this.calendar = calendar;
    this.clampToSession = clampToSession;
  }

  /**
   * True when the tick lands in a trading day's pre-open window (before 09:15 IST) and session
   * clamping is on — such prints are indicative order-collection values, not trades, and must not
   * enter OHLC aggregation. Always false with clamping off (mock 24/7 feed).
   */
  public boolean isPreOpen(OffsetDateTime tickTime) {
    if (!clampToSession) {
      return false;
    }
    OffsetDateTime ist = tickTime.withOffsetSameInstant(Ist.OFFSET);
    boolean tradingDay;
    try {
      tradingDay = calendar.isTradingDay(ist.toLocalDate());
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
    return tradingDay && ist.toLocalTime().isBefore(MarketCalendar.SESSION_OPEN);
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
    if (tradingDay && clampToSession) {
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
