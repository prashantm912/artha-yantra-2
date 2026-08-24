package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.canary.IngestHealthBoard.BoardReport;
import in.arthayantra.marketdata.canary.IngestHealthBoard.SourceHealth;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;

/**
 * A11 ingest-health-board IT (app-platform audit §6.3 / §9.1). Runs the REAL board SQL + the reused
 * A5 {@link IngestCoverageCanary#evaluate} against the Timescale container + the deploy/flyway
 * marketdata lineage (V040 {@code ingest_runs}), with a FIXED clock.
 *
 * <p>Isolation on the shared singleton DB (NO per-method cleanup): the per-day verdicts are scoped
 * by clearing+seeding each synthetic trading day's window. The last-run projection queries the WHOLE
 * table (newest row per source), so the synthetic days sit in the FUTURE (Sept 2026, still inside
 * the bundled 2024–2026 calendar) — later than any real mock-boot row (stamped ~today) or a sibling
 * test's Feb/Mar rows — making this test's rows the global newest per source. The two methods'
 * windows are a week apart so they never overlap.
 *
 * <p>⚠️ <b>That makes "Sept 2026" a claim on the LATEST dates in the whole suite, and it is load
 * bearing</b> (measured 2026-08-13). New canary-IT cases were first parked in Oct 2026 — free of any
 * other fixture, and still wrong: their {@code ingest_runs} rows became the global newest per source,
 * so {@link #lastRunMarksAnAgedRunningRowStale} read the October SUCCESS instead of its own September
 * RUNNING and failed with {@code expected "RUNNING" but was "SUCCESS"}. Any NEW fixture in a sibling
 * class must sit EARLIER than this window, never later; the sibling's own Jan–Jul band is the place
 * for it.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class IngestHealthBoardIntegrationTest extends MarketDataIntegrationTestBase {

  private static final MarketCalendar CAL = MarketCalendar.nse();

  @Autowired JdbcTemplate jdbc;

  @Test
  void boardPivotsWindowVerdictsCountsMissingDaysAndJoinsLastRun() {
    LocalDate today = LocalDate.of(2026, 9, 16);
    LocalDate d1 = CAL.previousTradingDay(today); // newest settled day
    LocalDate d2 = CAL.previousTradingDay(d1);
    LocalDate d3 = CAL.previousTradingDay(d2); // oldest
    for (LocalDate d : List.of(d1, d2, d3)) {
      clearWindow(d);
      seedBatchesHealthy(d);
      seedCapture(d, 5200L);
    }
    // FII/DII silently missed the middle day; the screener wrote 0 rows on the newest day. Writing
    // 0 rows means no OUTPUT landed either, so the artifact-first SCREENER policy needs d1's screen
    // rows gone as well as the run row restated.
    deleteSource(d2, IngestRunLedger.SOURCE_NSE_FII_DII);
    deleteSource(d1, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    jdbc.update("DELETE FROM minervini_screen_results WHERE screen_date = ?", d1);
    seedBatch(d1, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "SUCCESS", 0L, true);

    BoardReport report = board(fixedMorning(today)).board(3);

    assertThat(report.tradingDays()).isEqualTo(3);
    assertThat(report.toDay()).isEqualTo(d1);
    assertThat(report.fromDay()).isEqualTo(d3);
    // 9 since EQUITY_BREADTH was registered (chip task_1e319725). This fixture does not seed it, so
    // it pivots RED across the window — which is the point worth keeping: the board derives its rows
    // from IngestCoverageCanary.EXPECTED, so a newly registered source appears here with no board
    // change at all, and an unseeded one reads RED rather than being silently absent.
    assertThat(report.sources()).hasSize(9);

    SourceHealth fii = find(report, IngestRunLedger.SOURCE_NSE_FII_DII);
    assertThat(fii.status()).isEqualTo("GREEN"); // newest day is healthy
    assertThat(fii.missingDays()).isEqualTo(1); // the middle day
    assertThat(fii.days()).hasSize(3);
    assertThat(fii.days().get(0).day()).isEqualTo(d1); // newest first
    assertThat(fii.days().get(1).status()).isEqualTo("RED"); // d2 hole
    assertThat(fii.lastRun()).isNotNull();
    assertThat(fii.lastRun().status()).isEqualTo("SUCCESS");
    assertThat(fii.lastRun().stale()).isFalse();

    SourceHealth screener = find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    assertThat(screener.policy()).isEqualTo("SCREENER");
    assertThat(screener.status()).isEqualTo("YELLOW"); // reused canary policy: 0-row success is starved
    assertThat(screener.detail()).contains("data-starved");
  }

  @Test
  void lastRunMarksAnAgedRunningRowStale() {
    LocalDate today = LocalDate.of(2026, 9, 23);
    LocalDate d1 = CAL.previousTradingDay(today);
    clearWindow(d1);
    seedBatchesHealthy(d1);
    seedCapture(d1, 5200L);
    // bhavcopy crashed mid-flight the prior evening: only a RUNNING row, never finished.
    deleteSource(d1, IngestRunLedger.SOURCE_BHAVCOPY);
    seedBatch(d1, IngestRunLedger.SOURCE_BHAVCOPY, "RUNNING", null, false);

    BoardReport report = board(fixedMorning(today)).board(1);

    SourceHealth bhav = find(report, IngestRunLedger.SOURCE_BHAVCOPY);
    assertThat(bhav.status()).isEqualTo("RED"); // reused canary policy: aged RUNNING = crashed
    assertThat(bhav.detail()).contains("crashed mid-flight");
    assertThat(bhav.lastRun()).isNotNull();
    assertThat(bhav.lastRun().status()).isEqualTo("RUNNING");
    assertThat(bhav.lastRun().stale()).isTrue();
    assertThat(bhav.lastRun().finishedAt()).isNull();
  }

  // ---- fixtures (mirror the A5 canary IT seeding) --------------------------------------------

  private IngestHealthBoard board(Clock clock) {
    MockEnvironment env = new MockEnvironment();
    IngestCoverageCanary canary =
        new IngestCoverageCanary(
            jdbc,
            mock(NtfyClient.class),
            CAL,
            clock,
            new SimpleMeterRegistry(),
            env,
            mock(TaskScheduler.class),
            true,
            120,
            "0 45 8 * * MON-FRI");
    return new IngestHealthBoard(jdbc, canary, CAL, clock, 120);
  }

  /** A fixed clock at 08:45 IST on {@code day}. */
  private static Clock fixedMorning(LocalDate day) {
    return Clock.fixed(day.atTime(8, 45).atZone(Ist.ZONE).toInstant(), ZoneOffset.UTC);
  }

  private void seedBatchesHealthy(LocalDate day) {
    seedBatch(day, IngestRunLedger.SOURCE_NSE_FII_DII, "SUCCESS", 30L, true);
    seedBatch(day, IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI, "SUCCESS", 40L, true);
    seedBatch(day, IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE, "SUCCESS", 12L, true);
    seedBatch(day, IngestRunLedger.SOURCE_BHAVCOPY, "SUCCESS", 4000L, true);
    seedBatch(day, IngestRunLedger.SOURCE_INSTRUMENT_SYNC, "SUCCESS", 90000L, true);
    seedBatch(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "SUCCESS", 96L, true);
    seedBatch(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "SUCCESS", 40L, true);
    seedScreenRows(day);
  }

  /**
   * Seeds the DESTINATION tables the SCREENER policy reads (it counts {@code screen_date} rows, not
   * run rows — see {@code IngestCoverageCanary.Policy.SCREENER}), computed the same evening.
   */
  private void seedScreenRows(LocalDate day) {
    OffsetDateTime computedAt = day.atTime(19, 1).atZone(Ist.ZONE).toOffsetDateTime();
    jdbc.update(
        "INSERT INTO minervini_screen_results (screen_date, symbol, close_price, gate1, gate2,"
            + " gate3, gate4, gate5, gate6, gate7, gate8, gates_passed, passes_all, computed_at)"
            + " VALUES (?, ?, 100, true, true, true, true, true, true, true, true, 8, true, ?)"
            + " ON CONFLICT (screen_date, symbol) DO UPDATE SET computed_at = EXCLUDED.computed_at",
        day,
        "RELIANCE",
        computedAt);
    jdbc.update(
        "INSERT INTO manas_arora_screen_results (screen_date, symbol, close_price, within_high,"
            + " above_sma50, liquid_volume, liquid_depth, low_cap, gate1, gate2, gate3, gate4,"
            + " gate5, gate6, gates_passed, passes_all, computed_at)"
            + " VALUES (?, ?, 100, true, true, true, true, true, true, true, true, true, true,"
            + " true, 6, true, ?)"
            + " ON CONFLICT (screen_date, symbol) DO UPDATE SET computed_at = EXCLUDED.computed_at",
        day,
        "RELIANCE",
        computedAt);
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
    // The SCREENER policy reads these, so the day's window is not cleared until they are — without
    // this the screener verdicts depend on whether some OTHER test class happened to seed this date.
    jdbc.update("DELETE FROM minervini_screen_results WHERE screen_date = ?", day);
    jdbc.update("DELETE FROM manas_arora_screen_results WHERE screen_date = ?", day);
  }

  private void deleteSource(LocalDate day, String source) {
    OffsetDateTime start = day.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime end = day.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();
    jdbc.update(
        "DELETE FROM ingest_runs WHERE source = ? AND started_at >= ? AND started_at < ?", source, start, end);
  }

  private static SourceHealth find(BoardReport report, String source) {
    return report.sources().stream()
        .filter(s -> s.source().equals(source))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no board row for " + source));
  }
}
