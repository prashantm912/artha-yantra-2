package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
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
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), SESSION);

    assertThat(cache.price("NSE", "AUTOIND")).contains(new BigDecimal("99.77"));
    assertThat(cache.mark("NSE", "AUTOIND")).get().extracting(EquityMarkCache.Mark::session)
        .isEqualTo(SESSION);
  }

  @Test
  void anUncapturedSymbolHasNoMark() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), SESSION);

    assertThat(cache.price("NSE", "HFCL")).as("a different symbol never reads AUTOIND's mark").isEmpty();
    assertThat(cache.price("BSE", "AUTOIND")).as("the exchange is part of the key").isEmpty();
  }

  @Test
  void aNonPositiveOrNullCloseIsNotStored() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AUTOIND", null, SESSION);
    cache.put("NSE", "HFCL", BigDecimal.ZERO, SESSION);
    cache.put("NSE", "SOTL", new BigDecimal("-5"), SESSION);

    assertThat(cache.size()).as("a zero/negative/absent close is not a mark").isZero();
  }

  /**
   * Freshness is judged on the SESSION, not on capture time (cross-vendor review, 2026-08-13). A
   * catch-up run pins a PAST session and legitimately evaluates that session's bar, and
   * MarketDataCandlesClient fail-softs a STALE endpoint response "unchanged" — both would carry a
   * brand-new capture instant on an old price, so a capture-time bound would call them fresh forever.
   */
  @Test
  void aMarkFromASessionOlderThanTheBoundIsTreatedAsAbsent() {
    // Clock reads 2026-08-13 IST; a session 6 days back is outside the 5-day bound.
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), LocalDate.of(2026, 8, 7));

    assertThat(cache.price("NSE", "AUTOIND")).as("6 sessions back against a 5-day bound").isEmpty();
    assertThat(cache.mark("NSE", "AUTOIND"))
        .as("the entry is still held — it is the READ that refuses it, so it can be diagnosed")
        .isPresent();
  }

  @Test
  void aMarkInsideTheSessionBoundIsStillServed() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), LocalDate.of(2026, 8, 8));

    assertThat(cache.price("NSE", "AUTOIND")).as("5 days back, exactly on the bound").isPresent();
  }

  /**
   * A FRESH capture instant must not rescue an OLD session — this is the exact defect the review
   * found. Both marks below are written "now"; only the one whose BAR is recent may be served.
   */
  @Test
  void aFreshCaptureOfAnOldBarIsStillRefused() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), LocalDate.of(2026, 7, 10)); // pinned catch-up
    cache.put("NSE", "HFCL", new BigDecimal("221.05"), LocalDate.of(2026, 8, 12)); // yesterday

    assertThat(cache.price("NSE", "AUTOIND"))
        .as("captured this instant, but the BAR is a month old")
        .isEmpty();
    assertThat(cache.price("NSE", "HFCL")).isPresent();
  }

  /** A future-dated session is evidence something is wrong, not evidence of freshness. */
  @Test
  void aFutureDatedSessionIsRefused() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), LocalDate.of(2026, 8, 20));

    assertThat(cache.price("NSE", "AUTOIND")).isEmpty();
  }

  @Test
  void aMarkWithNoSessionIsRefused() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), null);

    assertThat(cache.price("NSE", "AUTOIND")).isEmpty();
  }

  @Test
  void evictionDropsTheMark() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);
    cache.put("NSE", "AUTOIND", new BigDecimal("99.77"), SESSION);

    cache.evict("NSE", "AUTOIND");

    assertThat(cache.price("NSE", "AUTOIND")).isEmpty();
  }


  // ---- session-monotonic writes (cross-vendor review Critical 1) --------------------------------

  /**
   * The key is shared across books (a close is a property of the SYMBOL, and AVALON / PRECOT /
   * KANORICHEM are held by BOTH swing books today). Two doctrines write this cache in the same batch
   * cycle, and a CATCH-UP run legitimately evaluates a PAST session's bar — so unconditional
   * last-write-wins let one book's historical replay clobber the other book's current mark, with a
   * fresh capture instant on it. The read-side session bound alone does not save this: it would
   * refuse the clobbered value, silently dropping a mark that WAS available.
   */
  @Test
  void anOlderSessionDoesNotClobberANewerMark() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "AVALON", new BigDecimal("1973.70"), LocalDate.of(2026, 8, 12));
    cache.put("NSE", "AVALON", new BigDecimal("1800.00"), LocalDate.of(2026, 8, 10)); // catch-up replay

    assertThat(cache.price("NSE", "AVALON"))
        .as("the current session's close survives a historical replay writing the same key")
        .contains(new BigDecimal("1973.70"));
  }

  @Test
  void aNewerSessionReplacesAnOlderMarkEvenWhenThePriceFell() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "PRECOT", new BigDecimal("850.00"), LocalDate.of(2026, 8, 11));
    cache.put("NSE", "PRECOT", new BigDecimal("799.75"), LocalDate.of(2026, 8, 12));

    assertThat(cache.price("NSE", "PRECOT"))
        .as("session-monotonic is NOT a price ratchet — a newer, LOWER close must win")
        .contains(new BigDecimal("799.75"));
  }

  @Test
  void aSameSessionRewriteStillCorrectsThePrice() {
    EquityMarkCache cache = new EquityMarkCache(at(T0), 5);

    cache.put("NSE", "SCPL", new BigDecimal("640.00"), SESSION);
    cache.put("NSE", "SCPL", new BigDecimal("650.00"), SESSION);

    assertThat(cache.price("NSE", "SCPL"))
        .as("an in-session correction (a re-fetched, revised bar) must land")
        .contains(new BigDecimal("650.00"));
  }
}
