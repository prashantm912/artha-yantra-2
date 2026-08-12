package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.canary.EveningChainCanary.ChainReport;
import in.arthayantra.marketdata.canary.EveningChainCanary.SourceProgress;
import in.arthayantra.marketdata.canary.EveningChainCanary.SourceState;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;

/**
 * "Can I shut down yet?" IT. Runs the REAL {@code ingest_runs}/{@code canary_runs} SQL against the
 * Timescale container (from {@link MarketDataIntegrationTestBase}), with a FIXED (or mutable, for
 * the lease-retry tests) clock and a mocked {@link NtfyClient} so the push decision is
 * deterministic. Mirrors the hand-construction pattern in {@code IngestCoverageCanaryIntegrationTest}
 * (never the Spring-wired bean, so {@code live}/{@code enabled} can be flipped per test).
 *
 * <p>Isolation on the shared singleton DB (NO per-method cleanup): every method uses a DISTINCT
 * synthetic day for its {@code ingest_runs} window, and explicitly clears its own {@code
 * canary_runs} marker first — a surefire rerun-on-failure would otherwise find a PRIOR run's own
 * {@code DONE}/{@code CLAIMED} row still there and read "already published" from the first line.
 *
 * <p>⚠️ Dates are drawn from {@link #day(int)} — an August-2026 window, deliberately EARLIER than
 * {@code IngestHealthBoardIntegrationTest}'s September 2026 fixtures. That sibling test relies on
 * {@code IngestHealthBoard.lastRunBySource()}'s un-day-scoped {@code DISTINCT ON (source) ORDER BY
 * started_at DESC} to make ITS rows the global newest per source (its own doc comment says so); this
 * class's own {@code ingest_runs} queries are day-scoped and immune either way, but seeding a LATER
 * date for a shared source (BHAVCOPY, MINERVINI_SCREEN, ...) would silently hijack that sibling's
 * "last run" and break it — caught exactly this way once already while writing this file. {@code
 * canary_runs} rows use the {@code EVENING_CHAIN} key, unique to this canary, so they cannot collide
 * with {@code IngestCoverageCanary} (key {@code INGEST_COVERAGE}) or {@code PlaneDivergenceProbe}
 * (key {@code MINERVINI_PLANE_DIVERGENCE}) regardless of date.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class EveningChainCanaryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final MarketCalendar CAL = MarketCalendar.nse();

  // Safely before IngestHealthBoardIntegrationTest's September 2026 fixtures — see the class javadoc.
  private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 12);

  /** The {@code n}-th trading day at-or-after {@link #ANCHOR} (0-indexed); each test gets a distinct one. */
  private static LocalDate day(int n) {
    LocalDate d = ANCHOR.minusDays(1);
    for (int i = 0; i <= n; i++) {
      d = CAL.nextTradingDay(d);
    }
    return d;
  }

  @Autowired JdbcTemplate jdbc;

  // ---- report(): classification ----------------------------------------------------------------

  @Test
  void reportOnAHealthyEveningIsCompleteWithEverySourceDone() {
    LocalDate day = day(0);
    clearWindow(day);
    seedAllHealthy(day);

    ChainReport report = canary(fixedAt(day, 20, 30), true).report();

    assertThat(report.tradingDay()).isTrue();
    assertThat(report.total()).isEqualTo(EveningChainCanary.EXPECTED.size());
    assertThat(report.done()).isEqualTo(EveningChainCanary.EXPECTED.size());
    assertThat(report.complete()).isTrue();
    assertThat(report.sources()).allSatisfy(s -> assertThat(s.state()).isEqualTo(SourceState.DONE));
  }

  @Test
  void aSourceWithNoRowYetTodayIsPending() {
    LocalDate day = day(1);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_EQUITY_BREADTH);

    ChainReport report = canary(fixedAt(day, 19, 40), true).report();

    assertThat(report.complete()).isFalse();
    SourceProgress s = find(report, IngestRunLedger.SOURCE_EQUITY_BREADTH);
    assertThat(s.state()).isEqualTo(SourceState.PENDING);
    assertThat(s.status()).isNull();
  }

  @Test
  void aFreshRunningRowIsPendingNotStuck() {
    LocalDate day = day(2);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    // started 10 minutes ago — well under the 120m default stale threshold
    seedRun(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "RUNNING", 19, 31);

    ChainReport report = canary(fixedAt(day, 19, 41), true).report();

    SourceProgress s = find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    assertThat(s.state()).isEqualTo(SourceState.PENDING);
    assertThat(s.status()).isEqualTo("RUNNING");
  }

  /**
   * The MANAS_SCREEN finding from the brief: a container recreate mid-job leaves an orphaned RUNNING
   * row with no reaper. Without the aged-vs-fresh distinction it would read PENDING forever.
   */
  @Test
  void anAgedRunningRowIsStuckNotPending() {
    LocalDate day = day(3);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_MANAS_SCREEN);
    // started 3 hours before "now" — past the 120m default stale threshold, never finished
    seedRun(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "RUNNING", 18, 0);

    ChainReport report = canary(fixedAt(day, 21, 0), true).report();

    SourceProgress s = find(report, IngestRunLedger.SOURCE_MANAS_SCREEN);
    assertThat(s.state()).isEqualTo(SourceState.STUCK);
  }

  /**
   * CRITICAL 1 (cross-vendor review, 2026-08-11): a STUCK source is OUTSTANDING, exactly like
   * PENDING — it must block completion, not be waved through. ⚠️ This test used to be named
   * {@code aStuckSourceDoesNotBlockCompletion} and asserted {@code report.complete()).isTrue()} —
   * pinning the exact false-safe defect the review caught: an orphaned RUNNING row (a container
   * recreate mid-job, no reaper) would have produced "chain complete 9/9 — safe to shut down" while
   * MANAS_SCREEN never actually finished. Inverted here to pin the CORRECT behaviour.
   */
  @Test
  void aStuckSourceBlocksCompletion() {
    LocalDate day = day(4);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_MANAS_SCREEN);
    seedRun(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "RUNNING", 18, 0);

    ChainReport report = canary(fixedAt(day, 21, 0), true).report();

    assertThat(find(report, IngestRunLedger.SOURCE_MANAS_SCREEN).state()).isEqualTo(SourceState.STUCK);
    assertThat(report.done()).isEqualTo(EveningChainCanary.EXPECTED.size() - 1);
    assertThat(report.complete())
        .as("a crashed/orphaned job must never read as safe to shut down")
        .isFalse();
  }

  @Test
  void aFailedRunIsDoneNotPending() {
    LocalDate day = day(5);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_DATA_QUALITY);
    seedRunRange(day, IngestRunLedger.SOURCE_DATA_QUALITY, "FAILURE", 19, 50, 19, 51);

    ChainReport report = canary(fixedAt(day, 20, 0), true).report();

    SourceProgress s = find(report, IngestRunLedger.SOURCE_DATA_QUALITY);
    assertThat(s.state()).isEqualTo(SourceState.DONE);
    assertThat(s.status()).isEqualTo("FAILURE");
    assertThat(report.complete()).isTrue();
  }

  @Test
  void reportOnANonTradingDayReturnsNoSourcesAndVacuousComplete() {
    LocalDate sunday = LocalDate.of(2026, 9, 27);
    assertThat(CAL.isTradingDay(sunday)).isFalse();

    ChainReport report = canary(fixedAt(sunday, 19, 0), true).report();

    assertThat(report.tradingDay()).isFalse();
    assertThat(report.sources()).isEmpty();
    assertThat(report.total()).isZero();
    assertThat(report.complete()).isTrue();
  }

  /**
   * ⚠️ Guard against a vacuous suite (success-shaped-nothing catalogue #14): every fixture above
   * seeds FROM {@code EXPECTED} itself, so an accidentally emptied (or silently shrunk) list would
   * pass every test above trivially. This pins the exact, non-empty membership independently.
   */
  @Test
  void expectedSourcesAreExactlyTheNineEveningBatchWriters() {
    assertThat(EveningChainCanary.EXPECTED)
        .as(
            "the evening-chain source list — change this ONLY alongside a real evening-job change,"
                + " and never let it silently shrink to empty")
        .containsExactlyInAnyOrder(
            IngestRunLedger.SOURCE_NSE_FII_DII,
            IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI,
            IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE,
            IngestRunLedger.SOURCE_BHAVCOPY,
            IngestRunLedger.SOURCE_MARKET_CONTEXT_DAY,
            IngestRunLedger.SOURCE_DATA_QUALITY,
            IngestRunLedger.SOURCE_MINERVINI_SCREEN,
            IngestRunLedger.SOURCE_MANAS_SCREEN,
            IngestRunLedger.SOURCE_EQUITY_BREADTH);
  }

  /**
   * MAJOR 5 (review, 2026-08-11): owner decision — HARD 19:00 IST shutdown, evening batch chain
   * moved to a compressed 18:20-18:59 single-shot window (no polling). The only slot that can see
   * the jobs finished without either catching some mid-flight (a check inside 18:20-18:59 could
   * fire before a job scheduled for 18:58 has even started) or missing the shutdown deadline is the
   * ~60s gap between the two. Pinned so a future edit cannot silently drift the default back outside
   * that window.
   */
  @Test
  void defaultCheckCronFiresAtTheEndOfThePreShutdownWindow() {
    CronExpression cron = CronExpression.parse(EveningChainCanary.DEFAULT_CHECK_CRON);
    LocalDate aWeekday = LocalDate.of(2026, 8, 17); // a Monday
    ZonedDateTime fire = cron.next(aWeekday.atStartOfDay(Ist.ZONE));

    assertThat(fire).isNotNull();
    assertThat(fire.toLocalDate()).isEqualTo(aWeekday);
    assertThat(fire.toLocalTime())
        .as("after the jobs' 18:20-18:59 window closes, before the 19:00 hard shutdown")
        .isEqualTo(LocalTime.of(18, 59));
  }

  // ---- check(): the evaluate/claim/publish protocol --------------------------------------------

  @Test
  void checkPushesChainCompleteOnceAndConfirmsDone() {
    LocalDate day = day(6);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    when(ntfy.trySend(any(), any(), any())).thenReturn(true);
    EveningChainCanary c = canary(fixedAt(day, 18, 59), true, ntfy);
    c.check();
    c.check(); // a second fire the same evening must stand down, not re-publish

    verify(ntfy, times(1))
        .trySend(
            contains("evening chain complete"),
            eq("default"),
            contains("chain complete " + EveningChainCanary.EXPECTED.size() + "/" + EveningChainCanary.EXPECTED.size()));
    assertThat(canaryRunState(day)).isEqualTo("DONE");
  }

  @Test
  void checkNamesOutstandingSourcesIncludingStuckOnes() {
    LocalDate day = day(7);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_EQUITY_BREADTH); // never ran
    deleteSource(day, IngestRunLedger.SOURCE_MANAS_SCREEN);
    // The single-shot check fires at 18:59, so "aged" here means started well before that by more
    // than the 120m default threshold — 16:00 is 179 minutes prior, comfortably past it. (A start
    // time inside the same evening's 18:20-18:59 job window, like 18:00, is NOT aged at 18:59 — that
    // was the first version of this fixture and it silently exercised PENDING, not STUCK.)
    seedRun(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "RUNNING", 16, 0); // aged -> stuck

    NtfyClient ntfy = mock(NtfyClient.class);
    when(ntfy.trySend(any(), any(), any())).thenReturn(true);
    canary(fixedAt(day, 18, 59), true, ntfy).check();

    verify(ntfy)
        .trySend(
            contains("evening chain still pending"),
            eq("default"),
            contains("still pending: " + IngestRunLedger.SOURCE_EQUITY_BREADTH + ", "
                + IngestRunLedger.SOURCE_MANAS_SCREEN + " (stuck)"));
    assertThat(canaryRunState(day)).isEqualTo("DONE");
  }

  /**
   * CRITICAL 2: a delivery failure must NOT confirm the claim — it stays CLAIMED and stealable, so
   * a later door (here, the scheduled reclaim, fired manually) retries and can still confirm DONE.
   * The vacuous predecessor of this test only asserted "no ntfy interaction"; this one asserts the
   * MARKER state directly and drives the retry to a real confirmed delivery.
   */
  @Test
  void aSendFailureLeavesTheClaimedMarkerForTheScheduledReclaimToRetry() {
    LocalDate day = day(8);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    when(ntfy.trySend(any(), any(), any())).thenReturn(false, true); // fails once, then succeeds
    TaskScheduler scheduler = mock(TaskScheduler.class);
    MutableClock clock = new MutableClock(day.atTime(18, 59).atZone(Ist.ZONE).toInstant());
    EveningChainCanary c = canary(jdbc, clock, true, true, true, ntfy, scheduler);

    c.check();

    assertThat(canaryRunState(day))
        .as("a send that did not actually deliver must not confirm the claim")
        .isEqualTo("CLAIMED");
    ArgumentCaptor<Runnable> reclaim = ArgumentCaptor.forClass(Runnable.class);
    ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
    verify(scheduler).schedule(reclaim.capture(), when.capture());
    Instant claimedAt = day.atTime(18, 59).atZone(Ist.ZONE).toInstant();
    Instant expectedRetry =
        claimedAt.plus(EveningChainCanary.CLAIM_LEASE).plus(EveningChainCanary.RECLAIM_SLACK);
    assertThat(when.getValue()).isEqualTo(expectedRetry);

    // The reclaim fires once the lease has genuinely expired — advance the clock past CLAIM_LEASE
    // first, exactly like production (the scheduler only invokes this Runnable AT retryAt); without
    // the advance, claim()'s own "claimed_at < now - CLAIM_LEASE" re-check would lose against the
    // SAME row this door already holds, since no time would have passed on a fixed clock.
    clock.set(expectedRetry.plusSeconds(1));
    reclaim.getValue().run();

    verify(ntfy, times(2)).trySend(any(), any(), any());
    assertThat(canaryRunState(day)).as("the retried send delivered — now confirmed").isEqualTo("DONE");
  }

  @Test
  void checkStandsDownWhenAlreadyPublishedToday() {
    LocalDate day = day(9);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);
    seedDoneCanaryRun(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedAt(day, 18, 59), true, ntfy).check();

    verifyNoInteractions(ntfy);
  }

  /**
   * alertsEnabled=false must still resolve the claim to DONE (nothing will ever be retried toward),
   * not leave it dangling CLAIMED forever — the gap the vacuous predecessor test never checked.
   */
  @Test
  void alertsDisabledStillConfirmsDoneWithoutSendingAnything() {
    LocalDate day = day(10);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(jdbc, fixedAt(day, 18, 59), true, true, false, ntfy, mock(TaskScheduler.class)).check();

    verifyNoInteractions(ntfy);
    assertThat(canaryRunState(day)).isEqualTo("DONE");
  }

  @Test
  void checkSkipsWhenNotLive() {
    LocalDate day = day(11);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedAt(day, 18, 59), false, ntfy).check();

    verifyNoInteractions(ntfy);
    assertThat(canaryRunState(day)).isNull();
  }

  @Test
  void checkSkipsWhenDisabled() {
    LocalDate day = day(12);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(jdbc, fixedAt(day, 18, 59), true, false, true, ntfy, mock(TaskScheduler.class)).check();

    verifyNoInteractions(ntfy);
    assertThat(canaryRunState(day)).isNull();
  }

  @Test
  void checkSkipsEntirelyOnANonTradingDay() {
    LocalDate sunday = LocalDate.of(2026, 10, 11);
    assertThat(CAL.isTradingDay(sunday)).isFalse();
    clearCanaryRun(sunday);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedAt(sunday, 18, 59), true, ntfy).check();

    verifyNoInteractions(ntfy);
    assertThat(canaryRunState(sunday)).isNull();
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  private EveningChainCanary canary(Clock clock, boolean live) {
    return canary(clock, live, mock(NtfyClient.class));
  }

  private EveningChainCanary canary(Clock clock, boolean live, NtfyClient ntfy) {
    return canary(jdbc, clock, live, true, true, ntfy, mock(TaskScheduler.class));
  }

  private EveningChainCanary canary(
      JdbcTemplate template,
      Clock clock,
      boolean live,
      boolean enabled,
      boolean alertsEnabled,
      NtfyClient ntfy,
      TaskScheduler scheduler) {
    MockEnvironment env = new MockEnvironment();
    if (live) {
      env.setActiveProfiles("live");
    }
    return new EveningChainCanary(template, ntfy, CAL, clock, env, scheduler, enabled, alertsEnabled, 120);
  }

  private static Clock fixedAt(LocalDate day, int hour, int minute) {
    return Clock.fixed(day.atTime(hour, minute).atZone(Ist.ZONE).toInstant(), ZoneOffset.UTC);
  }

  private void clearCanaryRun(LocalDate day) {
    jdbc.update("DELETE FROM canary_runs WHERE canary = ? AND run_day = ?", EveningChainCanary.CANARY_KEY, day);
  }

  /** Simulates a prior door having already confirmed today's push. */
  private void seedDoneCanaryRun(LocalDate day) {
    jdbc.update(
        "INSERT INTO canary_runs (canary, run_day, state, source, claimed_at, completed_at)"
            + " VALUES (?, ?, 'DONE', 'TEST_SEED', now(), now())",
        EveningChainCanary.CANARY_KEY,
        day);
  }

  /** {@code CLAIMED} / {@code DONE} / null (no row this IST day). */
  private String canaryRunState(LocalDate day) {
    List<String> values =
        jdbc.queryForList(
            "SELECT state FROM canary_runs WHERE canary = ? AND run_day = ?",
            String.class,
            EveningChainCanary.CANARY_KEY,
            day);
    return values.isEmpty() ? null : values.get(0);
  }

  private void seedAllHealthy(LocalDate day) {
    for (String source : EveningChainCanary.EXPECTED) {
      seedRunRange(day, source, "SUCCESS", 19, 30, 19, 31);
    }
  }

  /** {@code finishedAt} null means still RUNNING; pass e.g. {@code 19, 31} for 19:31 IST. */
  private void seedRun(LocalDate day, String source, String status, int startedHour, int startedMinute) {
    seedRunRange(day, source, status, startedHour, startedMinute, null, null);
  }

  private void seedRunRange(
      LocalDate day,
      String source,
      String status,
      int startedHour,
      int startedMinute,
      Integer finishedHour,
      Integer finishedMinute) {
    OffsetDateTime started = day.atTime(startedHour, startedMinute).atZone(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime finished =
        finishedHour == null ? null : day.atTime(finishedHour, finishedMinute).atZone(Ist.ZONE).toOffsetDateTime();
    jdbc.update(
        "INSERT INTO ingest_runs (source, status, rows_written, started_at, finished_at) VALUES (?,?,?,?,?)",
        source,
        status,
        status.equals("SUCCESS") ? 10L : null,
        started,
        finished);
  }

  private void deleteSource(LocalDate day, String source) {
    OffsetDateTime start = day.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime end = day.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();
    jdbc.update(
        "DELETE FROM ingest_runs WHERE source = ? AND started_at >= ? AND started_at < ?", source, start, end);
  }

  private void clearWindow(LocalDate day) {
    OffsetDateTime start = day.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime end = day.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();
    jdbc.update("DELETE FROM ingest_runs WHERE started_at >= ? AND started_at < ?", start, end);
  }

  private static SourceProgress find(ChainReport report, String source) {
    return report.sources().stream()
        .filter(s -> s.source().equals(source))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no chain row for " + source));
  }

  /** A clock the test can move — the only way to step a fixed-clock canary past a lease expiry. */
  private static final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void set(Instant next) {
      this.now = next;
    }

    @Override
    public Instant instant() {
      return now;
    }

    /** IST, not UTC: {@code withZone} below is identity, so this is what any date-from-clock uses. */
    @Override
    public ZoneId getZone() {
      return Ist.ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
