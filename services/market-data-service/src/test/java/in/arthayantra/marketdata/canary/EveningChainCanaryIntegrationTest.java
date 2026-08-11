package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.canary.EveningChainCanary.ChainReport;
import in.arthayantra.marketdata.canary.EveningChainCanary.SourceProgress;
import in.arthayantra.marketdata.canary.EveningChainCanary.SourceState;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * "Can I shut down yet?" IT. Runs the REAL {@code ingest_runs} SQL against the Timescale container +
 * the real Redis container (both from {@link MarketDataIntegrationTestBase}), with a FIXED clock and
 * a mocked {@link NtfyClient} so the push decision is deterministic. Mirrors the hand-construction
 * pattern in {@code IngestCoverageCanaryIntegrationTest} (never the Spring-wired bean, so {@code
 * live}/{@code enabled} can be flipped per test via {@link MockEnvironment}).
 *
 * <p>Isolation on the shared singleton DB + Redis (NO per-method cleanup): every method uses a
 * DISTINCT synthetic day for its {@code ingest_runs} window, and explicitly clears its own Redis
 * push marker first — a surefire rerun-on-failure would otherwise find yesterday's OWN marker still
 * set and read "already pushed" from the first line.
 *
 * <p>⚠️ Dates are drawn from {@link #day(int)} — an August-2026 window, deliberately EARLIER than
 * {@code IngestHealthBoardIntegrationTest}'s September 2026 fixtures. That sibling test relies on
 * {@code IngestHealthBoard.lastRunBySource()}'s un-day-scoped {@code DISTINCT ON (source) ORDER BY
 * started_at DESC} to make ITS rows the global newest per source (its own doc comment says so); this
 * class's own queries are day-scoped and immune either way, but seeding a LATER date for a shared
 * source (BHAVCOPY, MINERVINI_SCREEN, ...) would silently hijack that sibling's "last run" and break
 * it — caught exactly this way once already while writing this file.
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
  @Autowired StringRedisTemplate redis;

  // ---- report(): classification --------------------------------------------------------------

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
   * row with no reaper. Without the aged-vs-fresh distinction it would read PENDING forever and the
   * chain would never be reported complete.
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

  /** A stuck source does not block completion — nothing more is coming from it either. */
  @Test
  void aStuckSourceDoesNotBlockCompletion() {
    LocalDate day = day(4);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_MANAS_SCREEN);
    seedRun(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "RUNNING", 18, 0);

    ChainReport report = canary(fixedAt(day, 21, 0), true).report();

    assertThat(find(report, IngestRunLedger.SOURCE_MANAS_SCREEN).state()).isEqualTo(SourceState.STUCK);
    assertThat(report.complete()).as("STUCK is a resting state too — nothing left to wait on").isTrue();
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

  // ---- poll(): push decision + once-per-day guard ---------------------------------------------

  @Test
  void pollPushesChainCompleteOnceAndNeverTwice() {
    LocalDate day = day(6);
    clearWindow(day);
    clearPushMarker(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    EveningChainCanary c = canary(fixedAt(day, 19, 35), true, ntfy);
    c.poll();
    c.poll(); // a later tick the same evening must not push again

    verify(ntfy, times(1))
        .send(
            contains("evening chain complete"),
            eq("default"),
            contains("chain complete " + EveningChainCanary.EXPECTED.size() + "/" + EveningChainCanary.EXPECTED.size()));
  }

  @Test
  void pollStaysSilentBeforeTheFinalCheckTimeWhileSourcesAreStillPending() {
    LocalDate day = day(7);
    clearWindow(day);
    clearPushMarker(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_EQUITY_BREADTH); // never ran yet

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedAt(day, 19, 40), true, ntfy).poll(); // well before the 21:55 default final check

    verifyNoInteractions(ntfy);
  }

  @Test
  void pollPushesStillPendingAtTheFinalCheckTimeNamingTheOutstandingSources() {
    LocalDate day = day(8);
    clearWindow(day);
    clearPushMarker(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_EQUITY_BREADTH);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedAt(day, 21, 55), true, ntfy).poll();

    verify(ntfy)
        .send(
            contains("evening chain still pending"),
            eq("default"),
            contains("still pending: " + IngestRunLedger.SOURCE_EQUITY_BREADTH));
  }

  @Test
  void pollSkipsWhenNotLive() {
    LocalDate day = day(9);
    clearWindow(day);
    clearPushMarker(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedAt(day, 21, 55), false, ntfy).poll();

    verifyNoInteractions(ntfy);
  }

  @Test
  void pollSkipsWhenDisabled() {
    LocalDate day = day(10);
    clearWindow(day);
    clearPushMarker(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(jdbc, redis, fixedAt(day, 21, 55), true, false, true, ntfy).poll();

    verifyNoInteractions(ntfy);
  }

  @Test
  void pollSkipsEntirelyOnANonTradingDay() {
    LocalDate sunday = LocalDate.of(2026, 10, 11);
    assertThat(CAL.isTradingDay(sunday)).isFalse();
    clearPushMarker(sunday);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedAt(sunday, 21, 55), true, ntfy).poll();

    verifyNoInteractions(ntfy);
  }

  @Test
  void pollCanBeSilencedWithAlertsDisabledWithoutBreakingThePushMarker() {
    LocalDate day = day(11);
    clearWindow(day);
    clearPushMarker(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    // alertsEnabled=false: the decision still fires (and claims the marker) but the ntfy send is skipped
    canary(jdbc, redis, fixedAt(day, 19, 35), true, true, false, ntfy).poll();

    verifyNoInteractions(ntfy);
  }

  // ---- fixtures ---------------------------------------------------------------------------------

  private EveningChainCanary canary(Clock clock, boolean live) {
    return canary(clock, live, mock(NtfyClient.class));
  }

  private EveningChainCanary canary(Clock clock, boolean live, NtfyClient ntfy) {
    return canary(jdbc, redis, clock, live, true, true, ntfy);
  }

  private EveningChainCanary canary(
      JdbcTemplate template,
      StringRedisTemplate redisTemplate,
      Clock clock,
      boolean live,
      boolean enabled,
      boolean alertsEnabled,
      NtfyClient ntfy) {
    MockEnvironment env = new MockEnvironment();
    if (live) {
      env.setActiveProfiles("live");
    }
    return new EveningChainCanary(
        template, ntfy, redisTemplate, CAL, clock, env, enabled, alertsEnabled, 120, "21:55");
  }

  private static Clock fixedAt(LocalDate day, int hour, int minute) {
    return Clock.fixed(day.atTime(hour, minute).atZone(Ist.ZONE).toInstant(), ZoneOffset.UTC);
  }

  private void clearPushMarker(LocalDate day) {
    redis.delete("evening-chain:pushed:" + day);
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
}
