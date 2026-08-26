package in.arthayantra.marketdata.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.canary.IngestHealthBoard;
import in.arthayantra.marketdata.context.DayContextService.DayContext;
import in.arthayantra.marketdata.kite.VixQuoteCache;
import in.arthayantra.marketdata.options.OptionsDigestService;
import in.arthayantra.marketdata.upstox.UpstoxGlobalInstrumentsClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Pins the H31 intraday snapshot: the EXPENSIVE half of a day context is precomputed on its own
 * schedule, while every NOW-dependent value is still recomputed per request.
 *
 * <p>⚠️ <b>Why this exists.</b> {@code strategy-signal}'s {@code InsightSweeper} reads
 * {@code GET /context/day-context} every 15 minutes through a {@code ContextClient} whose read
 * timeout is <b>2000 ms</b>, and the assembly cost ~1.9 s server-side. Measured 2026-08-26: the
 * 09:00 and 09:15 sweeps refreshed 4 insights each, 09:30 refreshed <b>0</b>, and 09:45:02 logged
 * <i>"Read timed out"</i> — the sweep discarded work the server had already completed. Widening the
 * client budget is REFUSED (it hides the queue), and a VIX-cache TTL raise halves the misses at
 * best; the fix is to stop paying the upstream reads inline.
 *
 * <p>⚠️ <b>The load-bearing test here is {@link #thePhaseIsRecomputedAcrossTheOpeningBell()}.</b>
 * The sweep fires at exactly 09:15:01, so a snapshot that also cached {@code sessionPhase} would
 * hand every downstream insight a stale {@code PRE_OPEN} across the opening bell — trading a
 * timeout for a wrong regime label, which is strictly worse. Caching the cheap half is the obvious
 * "simplification" of this design and it is the one thing that must never happen.
 */
class DayContextSnapshotTest {

  /** 2026-08-20 is a Thursday inside the bundled 2024–2026 calendar and is not an NSE holiday. */
  private static final Instant AT_0914 = Instant.parse("2026-08-20T03:44:00Z"); // 09:14:00 IST

  @Test
  @DisplayName("a refreshed snapshot is REUSED — two requests make no further upstream calls")
  void theSnapshotIsReusedAcrossRequests() {
    Fixture f = new Fixture(AT_0914, 300L);

    f.service.refreshSnapshot();
    f.clock.advance(Duration.ofSeconds(30));
    f.service.dayContext();
    f.clock.advance(Duration.ofSeconds(30));
    f.service.dayContext();

    // ONE call in total — made by the refresher, not by either request. Without the snapshot this
    // is 3 (one per refresh + one per request), which is exactly the inline cost H31 removes.
    verify(f.digest, times(1)).digest(any(), any(), any());
    verify(f.board, times(1)).board(anyInt());
    verify(f.vixQuotes, times(1)).quote(any());
    verify(f.candles, times(1)).read(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("the session phase is recomputed FRESH across 09:15 while the heavy half is cached")
  void thePhaseIsRecomputedAcrossTheOpeningBell() {
    Fixture f = new Fixture(AT_0914, 300L);
    f.service.refreshSnapshot();

    f.clock.advance(Duration.ofSeconds(30)); // 09:14:30 IST — before the bell
    DayContext before = f.service.dayContext();
    f.clock.advance(Duration.ofSeconds(60)); // 09:15:30 IST — after the bell, 90 s of snapshot age
    DayContext after = f.service.dayContext();

    assertThat(before.sessionPhase())
        .as("09:14:30 IST on a trading day is PRE_OPEN")
        .isEqualTo("PRE_OPEN");
    assertThat(after.sessionPhase())
        .as(
            "09:15:30 IST is OPEN — a cached phase would still say PRE_OPEN, and the sweep that"
                + " reads this fires at 09:15:01")
        .isEqualTo("OPEN");
    assertThat(after.asOf())
        .as("asOf is the request instant, never the snapshot's")
        .isAfter(before.asOf());
    assertThat(before.tradeDate()).isEqualTo(after.tradeDate());

    // ...and the heavy half really WAS served from the snapshot across that boundary, so the
    // phase change above cannot be explained by a full recompute.
    verify(f.digest, times(1)).digest(any(), any(), any());
    verify(f.board, times(1)).board(anyInt());
  }

  @Test
  @DisplayName("a snapshot older than the max age is recomputed INLINE")
  void anExpiredSnapshotIsRecomputedInline() {
    Fixture f = new Fixture(AT_0914, 300L);
    f.service.refreshSnapshot();

    f.clock.advance(Duration.ofSeconds(300)); // exactly at the bound — still served
    f.service.dayContext();
    verify(f.digest, times(1)).digest(any(), any(), any());

    f.clock.advance(Duration.ofSeconds(1)); // 301 s — past the bound
    f.service.dayContext();

    // ⚠️ This is what keeps MarketContextEodJob correct: at 18:49 the newest possible snapshot is
    // the 15:58 one (~171 min old), so the EOD row is built from a fresh inline compute.
    verify(f.digest, times(2)).digest(any(), any(), any());
  }

  @Test
  @DisplayName("a throwing refresh keeps the PREVIOUS snapshot and never propagates")
  void aFailedRefreshKeepsThePreviousSnapshot() {
    Fixture f = new Fixture(AT_0914, 300L);
    f.service.refreshSnapshot();

    // ingestTrust() dereferences board.sources() unguarded, so this is a genuinely propagating
    // failure inside computeHeavy() — not one of the fail-softed blocks that only add a note.
    when(f.board.board(anyInt())).thenThrow(new IllegalStateException("board down"));
    f.clock.advance(Duration.ofSeconds(30)); // 09:14:30 IST — still inside the max age

    assertThatCode(f.service::refreshSnapshot).doesNotThrowAnyException();

    DayContext dc = f.service.dayContext();
    assertThat(dc.ingestTrust())
        .as(
            "the previous snapshot must still serve — a dropped snapshot would recompute inline"
                + " and the throwing board would take the endpoint down with it")
        .isNotNull();
    assertThat(dc.sessionPhase()).isEqualTo("PRE_OPEN");

    // The premise of the assertion above, asserted rather than trusted: with the snapshot expired
    // there IS no cached value to fall back on and the same call really does blow up.
    f.clock.advance(Duration.ofSeconds(301)); // snapshot now 331 s old — past the 300 s bound
    assertThatThrownBy(f.service::dayContext).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("freshDayContext() computes inline even when the snapshot is BRAND NEW")
  void freshDayContextIgnoresTheNewestPossibleSnapshot() {
    Fixture f = new Fixture(AT_0914, 300L);
    f.service.refreshSnapshot(); // age 0 — the most cache-eligible a snapshot can possibly be

    f.service.freshDayContext();

    // ⚠️ Age 0 is the point. A max-age test cannot distinguish "the EOD path is structurally
    // uncached" from "the snapshot happened to be stale when the EOD job ran" — and the latter is
    // exactly the config-gated coincidence this entry point exists to stop depending on.
    verify(f.digest, times(2)).digest(any(), any(), any());
    verify(f.board, times(2)).board(anyInt());
    verify(f.vixQuotes, times(2)).quote(any());

    // ...while the CACHED entry point on the same service, same instant, still serves the snapshot.
    f.service.dayContext();
    verify(f.digest, times(2)).digest(any(), any(), any());
  }

  @Test
  @DisplayName("a cache hit declares the heavy half's AGE in notes, because asOf cannot")
  void aCacheHitCarriesItsAgeInTheNotes() {
    Fixture f = new Fixture(AT_0914, 300L);
    f.service.refreshSnapshot();
    f.clock.advance(Duration.ofSeconds(45));

    DayContext cached = f.service.dayContext();

    // compose() stamps asOf = the REQUEST instant, so on this path asOf describes neither the VIX
    // band nor the index range beside it — and MarketStructureGenerator:57,64 cites s.asOf() as the
    // evidence timestamp of a PERSISTED insight. The note is the only thing that keeps that honest.
    assertThat(cached.notes()).contains("heavy half computed 45s ago");

    // ...and the uncached path must NOT carry it: there is nothing stale to declare.
    assertThat(f.service.freshDayContext().notes())
        .noneMatch(note -> note.startsWith("heavy half computed"));
  }

  @Test
  @DisplayName("the refresher skips a weekday NSE holiday instead of burning upstream passes")
  void theRefresherSkipsHolidaysFallingOnWeekdays() {
    // 2026-09-14 (Ganesh Chaturthi) is a MONDAY in nse-trading-holidays.csv — the MON-FRI cron
    // fires, so without the calendar check the day costs 32 pointless upstream passes.
    Fixture f = new Fixture(Instant.parse("2026-09-14T03:44:00Z"), 300L);

    f.service.refreshSnapshot();

    verify(f.digest, never()).digest(any(), any(), any());
    verify(f.vixQuotes, never()).quote(any());
    verify(f.board, never()).board(anyInt());
  }

  /** A real {@link DayContextService} over mock collaborators and a hand-advanced clock. */
  private static final class Fixture {
    private final MutableClock clock;
    private final OptionsDigestService digest = mock(OptionsDigestService.class);
    private final IngestHealthBoard board = mock(IngestHealthBoard.class);
    private final VixQuoteCache vixQuotes = mock(VixQuoteCache.class);
    private final CandleQueryService candles = mock(CandleQueryService.class);
    private final DayContextService service;

    private Fixture(Instant start, long maxAgeSeconds) {
      this.clock = new MutableClock(start);
      when(board.board(anyInt()))
          .thenReturn(new IngestHealthBoard.BoardReport(start, null, null, 0, List.of()));
      this.service =
          new DayContextService(
              digest,
              vixQuotes,
              new StaticListableBeanFactory().getBeanProvider(UpstoxGlobalInstrumentsClient.class),
              candles,
              board,
              MarketCalendar.nse(),
              clock,
              new SimpleMeterRegistry(),
              "NIFTY 50",
              "NSE",
              "NIFTY 50",
              "INDIA VIX",
              5,
              5,
              new BigDecimal("13"),
              new BigDecimal("17"),
              new BigDecimal("22"),
              maxAgeSeconds);
    }
  }

  /**
   * A {@link Clock} whose instant is moved by hand. The whole point of this suite is that two
   * requests at DIFFERENT wall-clock times see the same cached heavy half, which a
   * {@code Clock.fixed} cannot express.
   */
  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration by) {
      this.now = this.now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException("fixture clock is UTC-only");
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
