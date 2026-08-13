package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * {@link EquityMarkCache} — the in-memory daily-close mark that lets book equity value a CASH EQUITY
 * position, which never appears in the Redis {@code ticks:last} hash the paper ledger otherwise marks
 * against.
 */
class EquityMarkCacheTest {

  private static final Instant T0 = Instant.parse("2026-08-13T10:00:00Z");
  private static final LocalDate SESSION = LocalDate.of(2026, 8, 12);

  private static Clock at(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  @Test
  void aCapturedCloseIsServedBackForTheSameSymbol() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 96);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), SESSION);

    assertThat(cache.price("NSE", "AUTOIND")).contains(new BigDecimal("99.77"));
    assertThat(cache.mark("NSE", "AUTOIND")).get().extracting(EquityMarkCache.Mark::session)
        .isEqualTo(SESSION);
  }

  @Test
  void anUncapturedSymbolHasNoMark() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 96);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), SESSION);

    assertThat(cache.price("NSE", "HFCL")).as("a different symbol never reads AUTOIND's mark").isEmpty();
    assertThat(cache.price("BSE", "AUTOIND")).as("the exchange is part of the key").isEmpty();
  }

  /**
   * The deliberate difference from {@link ManasGoverningStopCache}, which is a tighten-only ratchet: a
   * MARK is a market fact and must be free to fall. A ratchet here would pin each position to its
   * high-water close and make book equity permanently overstate the book.
   */
  @Test
  void aLaterLowerCloseReplacesAnEarlierHigherOneRatherThanBeingIgnored() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 96);

    cache.put("NSE", "PRECOT", new BigDecimal("850.00"), SESSION);
    cache.put("NSE", "PRECOT", new BigDecimal("799.75"), SESSION.plusDays(1));

    assertThat(cache.price("NSE", "PRECOT"))
        .as("last write wins — a mark is not a ratchet")
        .contains(new BigDecimal("799.75"));
  }

  @Test
  void aNonPositiveOrNullCloseIsNotStored() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 96);

    cache.put("NSE", "AUTOIND", null, SESSION);
    cache.put("NSE", "HFCL", BigDecimal.ZERO, SESSION);
    cache.put("NSE", "SOTL", new BigDecimal("-5"), SESSION);

    assertThat(cache.size()).as("a zero/negative/absent close is not a mark").isZero();
  }

  /**
   * The staleness bound. A dead swing batch must not keep serving a week-old close into book equity —
   * a missing mark is visible (the unmarked count) and conservative; a silently stale one is neither.
   */
  @Test
  void aMarkOlderThanTheMaxAgeIsTreatedAsAbsent() {
    // ShiftingClock reports T0 to put() and T0+97h to price(), so the entry ages past the 96h bound.
    EquityMarkCache aged = new EquityMarkCache(new ShiftingClock(T0, Duration.ofHours(97)), 96);
    aged.put("NSE", "AUTOIND", new BigDecimal("99.77"), SESSION);

    assertThat(aged.price("NSE", "AUTOIND")).as("97h old against a 96h bound").isEmpty();
    assertThat(aged.mark("NSE", "AUTOIND"))
        .as("the entry is still held — it is the READ that refuses it, so it can be diagnosed")
        .isPresent();
  }

  @Test
  void aMarkInsideTheMaxAgeIsStillServed() {
    EquityMarkCache fresh = new EquityMarkCache(new ShiftingClock(T0, Duration.ofHours(95)), 96);
    fresh.put("NSE", "AUTOIND", new BigDecimal("99.77"), SESSION);

    assertThat(fresh.price("NSE", "AUTOIND")).as("95h old against a 96h bound").isPresent();
  }

  @Test
  void evictionDropsTheMark() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 96);
    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), SESSION);

    cache.evict("NSE", "AUTOIND");

    assertThat(cache.price("NSE", "AUTOIND")).isEmpty();
  }

  /** A clock that reports {@code base} on the first read and {@code base + shift} on every later one. */
  private static final class ShiftingClock extends Clock {
    private final Instant base;
    private final Duration shift;
    private boolean shifted;

    ShiftingClock(Instant base, Duration shift) {
      this.base = base;
      this.shift = shift;
    }

    @Override
    public Instant instant() {
      if (!shifted) {
        shifted = true;
        return base;
      }
      return base.plus(shift);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
