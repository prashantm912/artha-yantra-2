package in.arthayantra.marketcalendar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Shared IST market calendar (A.5 / ADR D12): NSE regular session 09:15–15:30 IST, Mon–Fri, minus
 * the exchange holiday list shipped as a versioned in-library resource (CD-2 — yearly refresh by
 * PR, never a DB seed).
 *
 * <p>Queries outside the covered holiday years fail loudly rather than silently treating an
 * unknown year as holiday-free.
 *
 * <p>Weekly index expiries are <b>Tuesdays</b> — NSE index weekly expiries moved to Tuesday
 * effective September 2025 under SEBI's single-expiry-day rule (supersedes the plan's older
 * "Expiry Thursday" wording). An expiry falling on a holiday prepones to the previous trading day.
 */
public final class MarketCalendar {

  /** Session open, inclusive. */
  public static final LocalTime SESSION_OPEN = LocalTime.of(9, 15);

  /** Session close, exclusive — the last 1m bucket starts 15:29. */
  public static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);

  /** IST — India has no DST. */
  public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static final String HOLIDAY_RESOURCE = "/nse-trading-holidays.csv";

  private final NavigableSet<LocalDate> holidays;
  private final NavigableSet<Integer> coveredYears;

  private MarketCalendar(Collection<LocalDate> holidayDates) {
    this.holidays = new TreeSet<>(holidayDates);
    this.coveredYears =
        holidayDates.stream().map(LocalDate::getYear).collect(Collectors.toCollection(TreeSet::new));
    if (coveredYears.isEmpty()) {
      throw new IllegalStateException("holiday calendar resource is empty");
    }
  }

  /** The NSE calendar from the bundled holiday resource. */
  public static MarketCalendar nse() {
    try (InputStream in = MarketCalendar.class.getResourceAsStream(HOLIDAY_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + HOLIDAY_RESOURCE);
      }
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return new MarketCalendar(
            reader
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> LocalDate.parse(line.substring(0, line.indexOf(','))))
                .collect(Collectors.toList()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading " + HOLIDAY_RESOURCE, e);
    }
  }

  /** Calendar over an explicit holiday set — for tests and future multi-exchange use. */
  public static MarketCalendar of(Collection<LocalDate> holidayDates) {
    return new MarketCalendar(holidayDates);
  }

  /** True when {@code date} is a weekday and not an exchange holiday. */
  public boolean isTradingDay(LocalDate date) {
    requireCovered(date.getYear());
    DayOfWeek day = date.getDayOfWeek();
    if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
      return false;
    }
    return !holidays.contains(date);
  }

  /** True when the instant falls inside a regular session: [09:15, 15:30) IST on a trading day. */
  public boolean isOpen(Instant instant) {
    ZonedDateTime ist = instant.atZone(IST);
    if (!isTradingDay(ist.toLocalDate())) {
      return false;
    }
    LocalTime time = ist.toLocalTime();
    return !time.isBefore(SESSION_OPEN) && time.isBefore(SESSION_CLOSE);
  }

  /** The session open/close instants of a trading day. */
  public record SessionBounds(Instant open, Instant close) {}

  /** Session bounds for {@code date}, or empty when the market is closed that day. */
  public Optional<SessionBounds> sessionBounds(LocalDate date) {
    if (!isTradingDay(date)) {
      return Optional.empty();
    }
    return Optional.of(
        new SessionBounds(
            date.atTime(SESSION_OPEN).atZone(IST).toInstant(),
            date.atTime(SESSION_CLOSE).atZone(IST).toInstant()));
  }

  /** The first trading day strictly after {@code date}. */
  public LocalDate nextTradingDay(LocalDate date) {
    LocalDate candidate = date.plusDays(1);
    while (!isTradingDay(candidate)) {
      candidate = candidate.plusDays(1);
    }
    return candidate;
  }

  /** The first trading day strictly before {@code date}. */
  public LocalDate previousTradingDay(LocalDate date) {
    LocalDate candidate = date.minusDays(1);
    while (!isTradingDay(candidate)) {
      candidate = candidate.minusDays(1);
    }
    return candidate;
  }

  /**
   * The number of 1-minute candle buckets whose bucket-start lies in {@code [from, to)} and inside
   * a regular session — the denominator for gap detection (a full day is 375).
   */
  public long expectedMinuteBuckets(Instant from, Instant to) {
    if (!to.isAfter(from)) {
      return 0;
    }
    long total = 0;
    LocalDate day = from.atZone(IST).toLocalDate();
    LocalDate lastDay = to.atZone(IST).toLocalDate();
    while (!day.isAfter(lastDay)) {
      Optional<SessionBounds> bounds = sessionBounds(day);
      if (bounds.isPresent()) {
        Instant lo = max(bounds.get().open(), from);
        Instant hi = min(bounds.get().close(), to);
        if (hi.isAfter(lo)) {
          Instant firstBucket = lo.truncatedTo(ChronoUnit.MINUTES);
          if (firstBucket.isBefore(lo)) {
            firstBucket = firstBucket.plus(Duration.ofMinutes(1));
          }
          if (hi.isAfter(firstBucket)) {
            total += Duration.between(firstBucket, hi).toNanos() / Duration.ofMinutes(1).toNanos();
            if (Duration.between(firstBucket, hi).toNanos() % Duration.ofMinutes(1).toNanos() > 0) {
              total += 1;
            }
          }
        }
      }
      day = day.plusDays(1);
    }
    return total;
  }

  /**
   * The next weekly index expiry on or after {@code date}: the coming Tuesday, preponed to the
   * previous trading day when that Tuesday is a holiday (never returning a date before
   * {@code date} — a preponed expiry already in the past rolls to the following week).
   */
  public LocalDate nextWeeklyIndexExpiry(LocalDate date) {
    LocalDate tuesday = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
    while (true) {
      LocalDate expiry = tuesday;
      while (!isTradingDay(expiry)) {
        expiry = expiry.minusDays(1);
      }
      if (!expiry.isBefore(date)) {
        return expiry;
      }
      tuesday = tuesday.plusWeeks(1);
    }
  }

  /** True when {@code date} is a weekly index expiry day (Tuesday or its holiday prepone). */
  public boolean isWeeklyIndexExpiryDay(LocalDate date) {
    return isTradingDay(date) && nextWeeklyIndexExpiry(date).equals(date);
  }

  /** True when {@code date} is the MONTHLY index expiry (the last weekly index expiry of its calendar month). */
  public boolean isMonthlyIndexExpiryDay(LocalDate date) {
    return isWeeklyIndexExpiryDay(date)
        && !java.time.YearMonth.from(nextWeeklyIndexExpiry(date.plusDays(1)))
            .equals(java.time.YearMonth.from(date));
  }

  /** The years the bundled holiday resource covers. */
  public NavigableSet<Integer> coveredYears() {
    return coveredYears;
  }

  private void requireCovered(int year) {
    if (!coveredYears.contains(year)) {
      throw new IllegalArgumentException(
          "NSE holiday calendar covers years "
              + coveredYears
              + " but year "
              + year
              + " was queried — refresh libs/market-calendar (CD-2)");
    }
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
