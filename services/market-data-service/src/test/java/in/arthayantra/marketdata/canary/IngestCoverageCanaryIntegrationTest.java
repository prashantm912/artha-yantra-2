package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import in.arthayantra.marketdata.bhavcopy.BhavcopyStartupCatchup;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.canary.IngestCoverageCanary.IngestCoverageReport;
import in.arthayantra.marketdata.canary.IngestCoverageCanary.SourceCoverage;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * V9 T+1 ingest-coverage canary IT (app-platform audit §8 V9). Runs the REAL coverage SQL against
 * the Timescale container + real {@code deploy/flyway} marketdata lineage (V040 {@code ingest_runs}),
 * with a FIXED clock, a mocked {@link NtfyClient} and a {@link SimpleMeterRegistry} so the ntfy push,
 * the gap counter and the aged-RUNNING window are all deterministic.
 *
 * <p>Isolation on the shared singleton DB (NO per-method cleanup): every method scopes itself to a
 * DISTINCT synthetic trading day and deletes that day's window first, so a sibling method, a mock
 * boot pull (which stamps today's real date), or a surefire rerun can never leak in. Dates stay
 * inside the bundled 2024–2026 calendar coverage.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class IngestCoverageCanaryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final MarketCalendar CAL = MarketCalendar.nse();

  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationContext context;

  /**
   * Pins the {@code MarketDataIntegrationTestBase} substrate default
   * {@code artha.bhavcopy.startup-catchup=false} (task_06ad72b6). This class deliberately does NOT
   * set the property itself — that is the point: a cached context booted for ANY test used to fire
   * the catch-up's fire-and-forget write to {@code nse_eod_bhavcopy}, which deadlocked against
   * another test's whole-table DELETE 3-of-3 surefire attempts on the 2-core runner. The bean being
   * ABSENT here proves the substrate default reaches contexts that never asked for it — remove the
   * default and this goes red in any base-extending context.
   */
  @Test
  void bhavcopyStartupCatchupIsAbsentViaTheSharedSubstrateDefault() {
    org.assertj.core.api.Assertions.assertThat(context.getBeansOfType(BhavcopyStartupCatchup.class))
        .as("the substrate default must disable the catch-up bean in EVERY base-extending context")
        .isEmpty();
    // the SCHEDULED writer is the same race through a different door — 19:30 IST is 14:00 UTC,
    // prime CI hours (review round 1). The bean legitimately exists, so the pin is the property.
    org.assertj.core.api.Assertions.assertThat(
            context.getEnvironment().getProperty("artha.bhavcopy.eod-cron"))
        .as("the substrate must disable the eod-cron in EVERY base-extending context")
        .isEqualTo("-");
  }


  // ---- evaluate(): per-source policy verdicts -------------------------------------------------

  @Test
  void healthyTradingDayIsGreenAndAllSourcesGreen() {
    LocalDate target = LocalDate.of(2026, 2, 3);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status()).isEqualTo("GREEN");
    assertThat(report.sources())
        .hasSize(8)
        .allSatisfy(s -> assertThat(s.status()).isEqualTo("GREEN"));
  }

  @Test
  void aMissingSourceIsRed() {
    LocalDate target = LocalDate.of(2026, 2, 10);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteSource(target, IngestRunLedger.SOURCE_NSE_FII_DII);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status()).isEqualTo("RED");
    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_NSE_FII_DII);
    assertThat(cov.status()).isEqualTo("RED");
    assertThat(cov.detail()).contains("no ingest run recorded");
  }

  @Test
  void anAgedRunningRowWithNoSuccessIsRed() {
    LocalDate target = LocalDate.of(2026, 2, 17);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    // bhavcopy crashed mid-flight: only a RUNNING row, no finish. By next morning it is aged.
    deleteSource(target, IngestRunLedger.SOURCE_BHAVCOPY);
    seedBatch(target, IngestRunLedger.SOURCE_BHAVCOPY, "RUNNING", null, false);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status()).isEqualTo("RED");
    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_BHAVCOPY);
    assertThat(cov.status()).isEqualTo("RED");
    assertThat(cov.detail()).contains("crashed mid-flight");
  }

  @Test
  void aScreenerSuccessWithZeroRowsIsYellowNotGreen() {
    LocalDate target = LocalDate.of(2026, 2, 24);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    // minervini ran but wrote 0 rows — a data-starved skip, not a healthy screen.
    deleteSource(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    seedBatch(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "SUCCESS", 0L, true);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status()).isEqualTo("YELLOW");
    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    assertThat(cov.status()).isEqualTo("YELLOW");
    assertThat(cov.detail()).contains("data-starved");
  }

  @Test
  void anOptionsCaptureWithZeroRowsIsRed() {
    LocalDate target = LocalDate.of(2026, 3, 3);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 0L); // capture session landed no option rows on a trading day

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status()).isEqualTo("RED");
    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_OPTIONS_SNAPSHOT_CAPTURE);
    assertThat(cov.status()).isEqualTo("RED");
    assertThat(cov.detail()).contains("0 rows");
  }

  // ---- sweep(): trading-day gating + alerting -------------------------------------------------

  @Test
  void sweepPushesUrgentAndCountsTheGap() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 3, 2));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteSource(target, IngestRunLedger.SOURCE_NSE_FII_DII);

    NtfyClient ntfy = mock(NtfyClient.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    canary(fixedMorning(today), true, ntfy, meters).sweep();

    verify(ntfy).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
    assertThat(meters.counter("ay_ingest_coverage_gap_total").count()).isEqualTo(1.0);
  }

  @Test
  void sweepIsSilentWhenEveryExpectedSourceIsHealthy() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 3, 16));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedMorning(today), true, ntfy).sweep();

    verifyNoInteractions(ntfy);
  }

  @Test
  void sweepSkipsEntirelyOnANonTradingDay() {
    LocalDate sunday = LocalDate.of(2026, 1, 4); // 2026-01-01 is a Thursday ⇒ 01-04 is a Sunday
    assertThat(CAL.isTradingDay(sunday)).isFalse();

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedMorning(sunday), true, ntfy).sweep();

    verifyNoInteractions(ntfy);
  }

  @Test
  void sweepSkipsWhenNotLive() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 3, 23));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target); // a real gap exists, but a non-live profile must never evaluate/alert

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedMorning(today), false, ntfy).sweep();

    verifyNoInteractions(ntfy);
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private IngestCoverageCanary canary(Clock clock, boolean live, NtfyClient ntfy) {
    return canary(clock, live, ntfy, new SimpleMeterRegistry());
  }

  private IngestCoverageCanary canary(Clock clock, boolean live, NtfyClient ntfy, SimpleMeterRegistry meters) {
    MockEnvironment env = new MockEnvironment();
    if (live) {
      env.setActiveProfiles("live");
    }
    return new IngestCoverageCanary(jdbc, ntfy, CAL, clock, meters, env, true, 120);
  }

  /** A fixed clock at 08:45 IST on {@code day}. */
  private static Clock fixedMorning(LocalDate day) {
    return Clock.fixed(day.atTime(8, 45).atZone(Ist.ZONE).toInstant(), ZoneOffset.UTC);
  }

  /** A fixed clock the morning after {@code target} — so a target-evening RUNNING row reads as aged. */
  private static Clock morningAfter(LocalDate target) {
    return fixedMorning(target.plusDays(1));
  }

  private void seedBatchesHealthy(LocalDate day) {
    seedBatch(day, IngestRunLedger.SOURCE_NSE_FII_DII, "SUCCESS", 30L, true);
    seedBatch(day, IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI, "SUCCESS", 40L, true);
    seedBatch(day, IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE, "SUCCESS", 12L, true);
    seedBatch(day, IngestRunLedger.SOURCE_BHAVCOPY, "SUCCESS", 4000L, true);
    seedBatch(day, IngestRunLedger.SOURCE_INSTRUMENT_SYNC, "SUCCESS", 90000L, true);
    seedBatch(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "SUCCESS", 96L, true);
    seedBatch(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "SUCCESS", 40L, true);
  }

  private void seedBatch(LocalDate day, String source, String status, Long rows, boolean finished) {
    OffsetDateTime started = day.atTime(19, 0).atZone(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime finishedAt = finished ? day.atTime(19, 1).atZone(Ist.ZONE).toOffsetDateTime() : null;
    jdbc.update(
        "INSERT INTO ingest_runs (source, status, rows_written, started_at, finished_at) VALUES (?,?,?,?,?)",
        source,
        status,
        rows,
        started,
        finishedAt);
  }

  private void seedCapture(LocalDate day, Long rows) {
    OffsetDateTime windowStart = day.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    jdbc.update(
        "INSERT INTO ingest_runs (source, window_start, status, rows_written, started_at, finished_at) "
            + "VALUES ('OPTIONS_SNAPSHOT_CAPTURE', ?, 'SUCCESS', ?, ?, ?)",
        windowStart,
        rows,
        windowStart,
        windowStart);
  }

  private void clearWindow(LocalDate day) {
    OffsetDateTime start = day.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime end = day.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();
    jdbc.update(
        "DELETE FROM ingest_runs WHERE (source <> 'OPTIONS_SNAPSHOT_CAPTURE' AND started_at >= ? AND started_at < ?) "
            + "OR (source = 'OPTIONS_SNAPSHOT_CAPTURE' AND window_start >= ? AND window_start < ?)",
        start,
        end,
        start,
        end);
  }

  private void deleteSource(LocalDate day, String source) {
    OffsetDateTime start = day.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime end = day.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();
    jdbc.update(
        "DELETE FROM ingest_runs WHERE source = ? AND started_at >= ? AND started_at < ?", source, start, end);
  }

  private static SourceCoverage find(IngestCoverageReport report, String source) {
    return report.sources().stream()
        .filter(s -> s.source().equals(source))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no coverage row for " + source));
  }
}
