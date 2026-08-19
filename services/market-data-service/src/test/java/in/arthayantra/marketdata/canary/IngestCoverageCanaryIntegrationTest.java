package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.bhavcopy.BhavcopyStartupCatchup;
import in.arthayantra.marketdata.canary.IngestCoverageCanary.IngestCoverageReport;
import in.arthayantra.marketdata.canary.IngestCoverageCanary.SourceCoverage;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;

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

  /**
   * Same pin for the morning-canary BOOT catch-up (task_e2e01c). {@link MorningCanaryCatchUp} fires
   * on {@code ApplicationReadyEvent} and dispatches onto {@code monitorTaskScheduler}, so a CACHED
   * context would sweep and INSERT into {@code canary_runs} inside whatever unrelated test is
   * running at the time — the identical hazard the bhavcopy pin above exists for. This class never
   * sets the property; the bean being ABSENT proves the substrate default reaches contexts that
   * never asked for it. The catch-up path itself is covered by calling {@code catchUpIfMissed()}
   * directly on a hand-constructed canary with a fixed clock.
   */
  @Test
  void morningCanaryBootCatchUpIsAbsentViaTheSharedSubstrateDefault() {
    org.assertj.core.api.Assertions.assertThat(context.getBeansOfType(MorningCanaryCatchUp.class))
        .as("the substrate default must disable the boot catch-up bean in EVERY base-extending context")
        .isEmpty();
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
        .hasSize(9) // +EQUITY_BREADTH, registered 2026-08-19 (chip task_1e319725)
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
    // minervini ran but wrote 0 rows — a data-starved skip, not a healthy screen. Writing 0 rows
    // means no OUTPUT landed either, which is the shape the artifact-first policy must still see as
    // the SAME data-starved YELLOW, not as a missing day.
    deleteSource(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    deleteScreenRows(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    seedBatch(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "SUCCESS", 0L, true);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status()).isEqualTo("YELLOW");
    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    assertThat(cov.status()).isEqualTo("YELLOW");
    assertThat(cov.detail()).contains("data-starved");
  }

  /**
   * The FALSE RED this policy change closes, in its exact production shape (measured 2026-08-13).
   * Neither screen has its own cron — both are {@code @EventListener(BhavcopyBackfillCompleted)} off
   * the 19:30 IST bhavcopy job, and the owner's machine shuts down at 19:00, so the whole chain
   * routinely runs on the NEXT MORNING's boot. On 2026-08-12 that produced 1785 Minervini + 2270
   * Manas rows for the trade date, complete, written by runs that STARTED 2026-08-13 08:04 IST —
   * {@code ingest_runs} held no screen row stamped 08-12 at all, and the board reported RED with
   * "no ingest run recorded for the trading day". So: NO run rows in the trade day's window, and
   * output that exists but was computed the next morning.
   */
  /**
   * Ledger/chip task_1e319725, and the reason EQUITY_BREADTH is NOT on {@code REQUIRE_SUCCESS}.
   *
   * <p>{@code EquityBreadthEodJob}'s dedup skip returns before {@code ledger.start}, so a no-op run
   * records NOTHING, and a boot catch-up that materializes the missed day is stamped the FOLLOWING
   * day. Measured on live data: 2026-08-12 has no EQUITY_BREADTH run row at all while 2026-08-13
   * carries two. Under a run-row policy that day reds with its data sitting in the table.
   */
  @Test
  void breadthIsGreenWhenTheRowLandedWithoutARunRowInTheDay() {
    LocalDate target = LocalDate.of(2026, 1, 13);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    // the run row is gone — dedup-skipped, or written by the next day's catch-up...
    deleteSource(target, IngestRunLedger.SOURCE_EQUITY_BREADTH);
    // ...but seedBatchesHealthy already materialized the day, which is what actually matters.

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_EQUITY_BREADTH);
    assertThat(cov.status())
        .as("breadth for this day EXISTS — a missing run row must not red it")
        .isEqualTo("GREEN");
    assertThat(cov.detail()).contains("materialized for this trading day");
    assertThat(report.status()).isEqualTo("GREEN");
  }

  /**
   * The other direction: a SUCCESS run that covered a DIFFERENT day. The job computes
   * {@code [latest..watermark]}, so a run stamped this day proves nothing about this day — if the
   * watermark had not advanced it restated an older one. That must not green.
   */
  @Test
  void breadthIsRedWhenARunSucceededButNothingLandedForTheDay() {
    LocalDate target = LocalDate.of(2026, 1, 14);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteBreadthRow(target); // the run wrote rows, but for another trade_date

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_EQUITY_BREADTH);
    assertThat(cov.status())
        .as("a SUCCESS run that covered another day is not evidence about this one")
        .isEqualTo("RED");
    assertThat(cov.detail()).contains("nothing materialized for");
  }

  @Test
  void aScreenerIsGreenWhenTheNextMorningCatchUpDidTheWork() {
    LocalDate target = LocalDate.of(2026, 1, 6);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    // the 19:30 chain never fired: no screen run row lands inside the trade day at all...
    deleteSource(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    deleteSource(target, IngestRunLedger.SOURCE_MANAS_SCREEN);
    // ...but the next morning's boot catch-up screened the day and stored its output.
    seedScreenRows(target, target.plusDays(1).atTime(8, 4));

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status())
        .as("a screen that produced its rows must not read as a missing day just because catch-up did it")
        .isEqualTo("GREEN");
    for (String source :
        List.of(IngestRunLedger.SOURCE_MINERVINI_SCREEN, IngestRunLedger.SOURCE_MANAS_SCREEN)) {
      SourceCoverage cov = find(report, source);
      assertThat(cov.status()).as(source).isEqualTo("GREEN");
      assertThat(cov.detail()).contains("screen stored 1 rows for this trading day");
      // Late-but-done must stay distinguishable from never-done, or the false RED has simply been
      // traded for a blind GREEN. The lateness rides the detail, with its T+n.
      assertThat(cov.detail()).contains("LATE, T+1 catch-up");
      assertThat(cov.detail()).contains("computed " + target.plusDays(1) + " 08:04 IST");
    }
  }

  /** The mirror: a screen computed on its own evening says so, and must NOT be labelled late. */
  @Test
  void aScreenerComputedTheSameEveningIsGreenAndNotLabelledLate() {
    LocalDate target = LocalDate.of(2026, 1, 13);
    clearWindow(target);
    seedBatchesHealthy(target); // seeds the screen output at 19:01 on the trade date itself
    seedCapture(target, 5200L);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    assertThat(cov.status()).isEqualTo("GREEN");
    assertThat(cov.detail()).contains("computed " + target + " 19:01 IST (same day)");
    assertThat(cov.detail()).doesNotContain("LATE");
  }

  /**
   * The guard must still be able to fire (catalogue trap #14: a check whose operand can no longer be
   * non-empty reports success forever). Genuinely-missing screen output for the trade date — no run
   * row AND no stored rows — stays RED, and the detail now names the ABSENCE THAT MATTERS: the
   * output's, not the run row's.
   */
  @Test
  void aScreenerWithNoStoredOutputAndNoRunIsStillRed() {
    LocalDate target = LocalDate.of(2026, 1, 20);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteSource(target, IngestRunLedger.SOURCE_MANAS_SCREEN);
    deleteScreenRows(target, IngestRunLedger.SOURCE_MANAS_SCREEN);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status()).isEqualTo("RED");
    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_MANAS_SCREEN);
    assertThat(cov.status()).isEqualTo("RED");
    assertThat(cov.detail()).contains("no screen output stored for the trading day");
    // the sibling screen kept its output and must be unaffected — a real gap, not a blanket RED
    assertThat(find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN).status()).isEqualTo("GREEN");
  }

  /**
   * Newly reachable, and previously a SILENT GREEN: the run reports SUCCESS with a positive
   * {@code rows_written}, but every row it wrote carries a DIFFERENT {@code screen_date} — the
   * bhavcopy watermark never advanced, so the screen restated an older day. The old policy read the
   * counter, and the counter was never about this date. Same family as the bhavcopy aggregate that
   * greened over a dead NSE side.
   */
  @Test
  void aScreenerThatRestatedAnotherDayIsRedDespiteAPositiveRowCount() {
    LocalDate target = LocalDate.of(2026, 4, 14);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    // the run ran on the trade date and reports 1783 rows written, yet no row in the output table
    // carries THIS screen_date — the screen restated whatever day the stalled watermark still named
    deleteScreenRows(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    deleteSource(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    seedBatch(target, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "SUCCESS", 1783L, true);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    assertThat(cov.status())
        .as("a positive rows_written must NOT rescue a trade date the screen never labelled")
        .isEqualTo("RED");
    assertThat(cov.detail()).contains("NONE carry screen_date=" + target);
    assertThat(cov.detail()).contains("stale watermark");
  }

  /**
   * The hole this closes (review of #1327): the mis-dated-payload guard REFUSES a payload NSE dated
   * wrongly and returns an empty list, but {@code BhavcopyBackfillService} still records a SUCCESS
   * run. Under the old {@code REQUIRE_SUCCESS} policy that was GREEN — so a SYSTEMATIC false positive
   * (NSE changing {@code DATE1} semantics) would discard every payload, stall {@code
   * nse_eod_bhavcopy} indefinitely, and never alarm. The only signal was a WARN line.
   *
   * <p>A bare row floor is safe here BECAUSE the sweep only ever evaluates the previous TRADING day —
   * a holiday is never assessed, so it cannot alarm on a legitimately empty non-trading day.
   */
  @Test
  void aBhavcopyRunIsYellowWhenONEExchangeStoredNothing() {
    LocalDate target = LocalDate.of(2026, 3, 10);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    // The PRODUCTION shape, and the reason this test was rewritten (review of #1329): NSE refused
    // every payload as mis-dated while BSE stayed healthy. rows_written is nse+bse summed over the
    // whole catch-up window, so it is POSITIVE here — the first version of this policy read that
    // column, greened, and could not see the very failure it was built for. The earlier fixture set
    // rows_written to an aggregate 0, a stricter stand-in that never reproduced this shape.
    deleteSource(target, IngestRunLedger.SOURCE_BHAVCOPY);
    seedBatch(target, IngestRunLedger.SOURCE_BHAVCOPY, "SUCCESS", 3102L, true);
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE trade_date = ?", target);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    assertThat(report.status()).isEqualTo("YELLOW");
    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_BHAVCOPY);
    assertThat(cov.status())
        .as("a positive aggregate rows_written must NOT rescue a dead NSE side")
        .isEqualTo("YELLOW");
    assertThat(cov.detail()).contains("NSE stored NO rows");
    assertThat(cov.detail()).contains("NSE 0 / BSE 1 rows");
    // The detail must name the metric, so the reader can tell a refusing guard from a dry fetch.
    assertThat(cov.detail()).contains("ay_bhavcopy_misdated_payload_total");
  }

  /** The mirror: a dead BSE side with a healthy NSE must alarm too, and must name BSE. */
  @Test
  void aBhavcopyRunIsYellowWhenTheOtherExchangeStoredNothing() {
    LocalDate target = LocalDate.of(2026, 3, 11);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    jdbc.update("DELETE FROM bse_eod_bhavcopy WHERE trade_date = ?", target);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_BHAVCOPY);
    assertThat(cov.status()).isEqualTo("YELLOW");
    assertThat(cov.detail()).contains("BSE stored NO rows");
    assertThat(cov.detail()).contains("NSE 1 / BSE 0 rows");
  }

  /** The floor must not redden the NORMAL path: a bhavcopy run that wrote rows stays GREEN. */
  @Test
  void aBhavcopySuccessWithRowsStaysGreen() {
    LocalDate target = LocalDate.of(2026, 3, 17);
    clearWindow(target);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteSource(target, IngestRunLedger.SOURCE_BHAVCOPY);
    seedBatch(target, IngestRunLedger.SOURCE_BHAVCOPY, "SUCCESS", 3102L, true);

    IngestCoverageReport report = canary(morningAfter(target), true, mock(NtfyClient.class)).evaluate(target);

    SourceCoverage cov = find(report, IngestRunLedger.SOURCE_BHAVCOPY);
    assertThat(cov.status()).isEqualTo("GREEN");
    // Counts come from the destination tables now, not from the run row's aggregate.
    assertThat(cov.detail()).contains("NSE 1 / BSE 1 rows");
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
    clearCanaryRun(today); // the sweep now claims before publishing — a leftover marker would mute it
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
    clearCanaryRun(today); // else a rerun's leftover marker makes the silence vacuous, not earned
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

  // ---- catchUpIfMissed(): the boot-time replay of a cron that ticked while the stack was down ---
  // task_e2e01c / E2E audit 2026-07-31 §2.1: the stack was down 02:29-08:56 IST, the 08:45 cron
  // ticked into the void, and Spring never replays a missed fire — so the canary that exists to
  // catch a broken overnight batch is exactly what does not run on a late-boot morning.

  @Test
  void catchUpSweepsWhenTodaysCronFireWasMissed() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 4, 6));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target);
    clearCanaryRun(today);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteSource(target, IngestRunLedger.SOURCE_NSE_FII_DII);

    NtfyClient ntfy = mock(NtfyClient.class);
    // the owner's machine came up at 08:56 — a quarter of an hour after the 08:45 fire
    boolean swept = canary(fixedAt(today, 8, 56), true, ntfy).catchUpIfMissed();

    assertThat(swept).isTrue();
    verify(ntfy).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
    assertThat(canaryRunSource(today)).isEqualTo("BOOT_CATCHUP");
    assertThat(canaryRunState(today)).as("a completed publication must be confirmed DONE").isEqualTo("DONE");
  }

  @Test
  void catchUpSkipsWhenTodaysRunIsAlreadyRecorded() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 4, 13));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target);
    clearCanaryRun(today);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteSource(target, IngestRunLedger.SOURCE_NSE_FII_DII); // a real gap: silence must be the claim
    seedPublishedRun(today, "SCHEDULED");

    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept = canary(fixedAt(today, 8, 56), true, ntfy).catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(ntfy);
    assertThat(canaryRunSource(today)).isEqualTo("SCHEDULED"); // the claim did not overwrite it
    assertThat(canaryRunState(today)).isEqualTo("DONE");
  }

  @Test
  void catchUpSkipsWhenBootIsBeforeTodaysFireTime() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 4, 20));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target);
    clearCanaryRun(today);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteSource(target, IngestRunLedger.SOURCE_NSE_FII_DII);

    NtfyClient ntfy = mock(NtfyClient.class);
    // booted 08:20 — the 08:45 cron is still ahead, so nothing has been missed and the marker must
    // stay unwritten or the cron's own fire would find the day already claimed.
    boolean swept = canary(fixedAt(today, 8, 20), true, ntfy).catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(ntfy);
    assertThat(canaryRunSource(today)).isNull();
  }

  @Test
  void scheduledSweepMarksTheDaySoTheNextBootDoesNotRepeatIt() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 4, 27));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target);
    clearCanaryRun(today);
    seedBatchesHealthy(target);
    seedCapture(target, 5200L);
    deleteSource(target, IngestRunLedger.SOURCE_NSE_FII_DII);

    NtfyClient scheduled = mock(NtfyClient.class);
    canary(fixedMorning(today), true, scheduled).sweep();
    verify(scheduled).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
    assertThat(canaryRunSource(today)).isEqualTo("SCHEDULED");
    assertThat(canaryRunState(today)).isEqualTo("DONE");

    // restart at 09:10 — past the lease, so only the CONFIRMED state can hold the boot door off
    NtfyClient afterRestart = mock(NtfyClient.class);
    boolean swept = canary(fixedAt(today, 9, 10), true, afterRestart).catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(afterRestart);
  }

  /**
   * Observable holiday behaviour: no alert, nothing claimed. ⚠️ This pins the BEHAVIOUR, not the
   * {@code targetFor} null-check — since the evaluate-first reordering that check is defence in
   * depth, because {@code evaluate(null)} NPEs before {@code runOnce} can claim or publish. Deleting
   * it leaves this test green (mutation M6, review round 1); see {@code targetFor}'s javadoc.
   */
  @Test
  void catchUpSkipsOnWeekdayHolidayAndClaimsNothing() {
    LocalDate holiday = LocalDate.of(2026, 1, 26); // Republic Day, a Monday — the cron fires, we must not
    assertThat(CAL.isTradingDay(holiday)).isFalse();
    clearCanaryRun(holiday);

    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept = canary(fixedAt(holiday, 8, 56), true, ntfy).catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(ntfy);
    assertThat(canaryRunSource(holiday)).isNull();
  }

  @Test
  void catchUpSkipsWhenNotLive() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 5, 4));
    clearWindow(CAL.previousTradingDay(today)); // a real gap exists; a mock stack must still not alert
    clearCanaryRun(today);

    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept = canary(fixedAt(today, 8, 56), false, ntfy).catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(ntfy);
    assertThat(canaryRunSource(today)).isNull();
  }

  // ---- the evaluate -> claim -> publish protocol (review round 1, Majors 1 + 2) -----------------

  /**
   * Major 1: {@code evaluate()} is side-effect-free, so it must run BEFORE the claim — a transient
   * ledger read failure has to leave the day retryable rather than burning its marker on a check
   * that never happened. The spy fails the read the same way a dropped connection would; the marker
   * table is then read back through the REAL template, so a claim-first ordering would show up as a
   * row that should not exist.
   */
  @Test
  void anEvaluationFailureClaimsNothingAndLeavesTheDayRetryable() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 5, 11));
    clearCanaryRun(today);

    JdbcTemplate failingReads = spy(jdbc);
    doThrow(new DataAccessResourceFailureException("ledger read failed"))
        .when(failingReads)
        .query(anyString(), any(RowMapper.class), any(), any(), any(), any());

    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept =
        canary(failingReads, fixedAt(today, 8, 56), true, ntfy, new SimpleMeterRegistry())
            .catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(ntfy);
    assertThat(canaryRunSource(today))
        .as("a failed evaluation must not claim the day — the morning stays retryable")
        .isNull();
  }

  /**
   * Major 2, protocol half: the SCHEDULED door honours the same claim the boot door does. Without
   * this, the cron could publish while a straddling boot catch-up published too, and the marker
   * conflict observed afterwards would prevent nothing. Pinned deterministically here; the race
   * itself is exercised by the concurrent test below.
   */
  @Test
  void scheduledSweepHonoursTheSameClaimSoBothDoorsCannotPublish() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 5, 18));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target); // an empty window is an all-RED report: silence can only come from the claim
    clearCanaryRun(today);
    seedPublishedRun(today, "BOOT_CATCHUP"); // the boot door got there first, and finished

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedMorning(today), true, ntfy).sweep();

    verifyNoInteractions(ntfy);
    assertThat(canaryRunSource(today)).isEqualTo("BOOT_CATCHUP");
  }

  /**
   * Major 2, race half: the 08:45 cron and a boot catch-up genuinely run on different threads and
   * can overlap. Both are released from one barrier against the same day and the same alert channel;
   * exactly one may publish. The outcome is decided by the atomic claim, not by how the two
   * interleave.
   *
   * <p>The two clocks are one minute apart — a boot landing just after the cron fired, which is the
   * actual "{@code ApplicationReadyEvent} straddling 08:45" scenario. That is INSIDE {@code
   * CLAIM_LEASE}, so the loser is looking at a live claim and must stand down rather than steal it;
   * an eleven-minute gap would be a lease-expired steal, which is a different (and correct)
   * behaviour tested by {@link #claimThatNeverCompletedIsRetriedByTheNextDoor}.
   */
  @Test
  void anOverlappingScheduledSweepAndBootCatchUpPublishExactlyOnce() throws Exception {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 5, 25));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target); // all-RED, so whoever wins the claim definitely alerts
    clearCanaryRun(today);

    AtomicInteger pushes = new AtomicInteger();
    NtfyClient shared = mock(NtfyClient.class);
    doAnswer(invocation -> pushes.incrementAndGet()).when(shared).send(any(), any(), any());

    CyclicBarrier startTogether = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      pool.submit(
          () -> {
            startTogether.await();
            canary(fixedMorning(today), true, shared).sweep();
            return null;
          });
      pool.submit(
          () -> {
            startTogether.await();
            return canary(fixedAt(today, 8, 46), true, shared).catchUpIfMissed();
          });
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(pushes.get()).as("the two doors must publish exactly once between them").isEqualTo(1);
    assertThat(canaryRunSource(today)).isIn("SCHEDULED", "BOOT_CATCHUP");
    assertThat(canaryRunState(today)).isEqualTo("DONE");
  }

  /**
   * Review round 2, Critical: <b>a claim proves "claimed", not "published"</b>. The row commits
   * before the alert goes out, so a door that died — or whose publish threw — between the two leaves
   * a CLAIMED row for an alert nobody ever received. Under a single-state marker that silences the
   * day forever, from the one component whose job is to break silence.
   *
   * <p>Here a SCHEDULED door claimed at 08:45 and never confirmed; the boot at 09:10 is past the
   * lease, so it must steal the claim and alert. The invariant: suppress on confirmed completion,
   * never on mere intent.
   */
  @Test
  void claimThatNeverCompletedIsRetriedByTheNextDoor() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 6, 8));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target); // all-RED, so a retry is unmistakable
    clearCanaryRun(today);
    seedUnconfirmedRun(today, "SCHEDULED", today.atTime(8, 45).atZone(Ist.ZONE).toInstant());

    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept = canary(fixedAt(today, 9, 10), true, ntfy).catchUpIfMissed();

    assertThat(swept).as("an unconfirmed claim must never suppress the alert it never sent").isTrue();
    verify(ntfy).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
    assertThat(canaryRunSource(today)).isEqualTo("BOOT_CATCHUP");
    assertThat(canaryRunState(today)).isEqualTo("DONE");
  }

  /**
   * The same Critical caught at the moment it happens rather than from its wreckage: a publish that
   * THROWS must leave the claim unconfirmed. A test cannot kill the JVM mid-publish, but a throwing
   * alert channel reaches the same state through the same code path — and unlike the seeded-row test
   * above, this one also pins the ORDER of confirm and publish, because confirming first would mark
   * DONE an alert that then never went out.
   */
  @Test
  void publishFailureLeavesTheClaimUnconfirmedForTheNextDoor() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 6, 22));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target); // all-RED, so the alert is attempted
    clearCanaryRun(today);

    NtfyClient dyingChannel = mock(NtfyClient.class);
    doThrow(new IllegalStateException("alert channel died mid-push"))
        .when(dyingChannel)
        .send(any(), any(), any());

    assertThatThrownBy(() -> canary(fixedAt(today, 8, 56), true, dyingChannel).catchUpIfMissed())
        .isInstanceOf(IllegalStateException.class);
    assertThat(canaryRunState(today))
        .as("an alert that never went out must not be recorded as published")
        .isEqualTo("CLAIMED");

    // ...and the day is genuinely recoverable: a later door, past the lease, still alerts.
    NtfyClient healthy = mock(NtfyClient.class);
    boolean swept = canary(fixedAt(today, 9, 10), true, healthy).catchUpIfMissed();

    assertThat(swept).isTrue();
    verify(healthy).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
    assertThat(canaryRunState(today)).isEqualTo("DONE");
  }

  /**
   * Review round 3: the lease made a dead claim RECLAIMABLE, but nothing came back to RECLAIM it.
   * {@code deploy/docker-compose.yml} sets {@code restart: unless-stopped}, so a crash mid-publish
   * brings the service back INSIDE the five-minute lease — the likely case, not an edge one. That
   * boot sees a fresh {@code CLAIMED} row and correctly stands down, but {@link MorningCanaryCatchUp}
   * fires once on {@code ApplicationReadyEvent} and has had its only turn, and the 08:45 cron is long
   * past. When the lease expired, nothing was left to notice: a silent day.
   *
   * <p>The whole sequence, end to end — crash at 08:56, restart at 08:58 (inside the lease, must
   * suppress AND schedule), then the scheduled reclaim runs past expiry and must actually alert.
   */
  @Test
  void crashInsideTheLeaseIsReclaimedByTheScheduledRetry() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 6, 29));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target); // all-RED: any publish is unmistakable
    clearCanaryRun(today);

    // 1. a boot claims and dies mid-publish
    NtfyClient dyingChannel = mock(NtfyClient.class);
    doThrow(new IllegalStateException("crashed mid-push")).when(dyingChannel).send(any(), any(), any());
    assertThatThrownBy(() -> canary(fixedAt(today, 8, 56), true, dyingChannel).catchUpIfMissed())
        .isInstanceOf(IllegalStateException.class);
    assertThat(canaryRunState(today)).isEqualTo("CLAIMED");

    // 2. restart:unless-stopped brings it back at 08:58 — INSIDE the lease, so it must stand down...
    MutableClock restartClock = new MutableClock(today.atTime(8, 58).atZone(Ist.ZONE).toInstant());
    TaskScheduler scheduler = mock(TaskScheduler.class);
    NtfyClient afterRestart = mock(NtfyClient.class);
    boolean sweptOnRestart =
        canary(jdbc, restartClock, true, afterRestart, new SimpleMeterRegistry(), scheduler)
            .catchUpIfMissed();

    assertThat(sweptOnRestart).isFalse();
    verifyNoInteractions(afterRestart);

    // ...but it must ALSO leave something behind to come back at expiry (08:56 + 5m + slack)
    ArgumentCaptor<Runnable> reclaim = ArgumentCaptor.forClass(Runnable.class);
    ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
    verify(scheduler).schedule(reclaim.capture(), when.capture());
    assertThat(when.getValue())
        .isEqualTo(today.atTime(8, 56).atZone(Ist.ZONE).toInstant().plusSeconds(5 * 60 + 5));

    // 3. the lease expires and the reclaim fires
    restartClock.set(today.atTime(9, 2).atZone(Ist.ZONE).toInstant());
    reclaim.getValue().run();

    verify(afterRestart).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
    assertThat(canaryRunState(today)).as("the reclaimed day must end up published").isEqualTo("DONE");
  }

  /**
   * ⚠️ Deferred work must CARRY its day, never recompute it — the house's most recurrent hazard
   * (same family as in-container {@code now()}/{@code ::date} being UTC and {@code AT TIME ZONE
   * '+05:30'} inverting), and the sibling notifier canary shipped exactly this bug: its retry dropped
   * the day from the lambda and re-derived it, so a day-D retry firing after IST midnight worked on
   * D+1 — D was never resolved, and D+1 was marked DONE by a run that never evaluated it, silencing
   * D+1's own canary.
   *
   * <p>This canary threads {@code today} and {@code target} explicitly through {@code
   * scheduleReclaim} → {@code reclaim} → {@code runOnce}, so it is immune by construction. Nothing
   * stops a future "simplification" from making {@code runOnce} derive its own day, and the failure
   * would be silent in precisely the way this whole feature exists to prevent — hence the pin.
   */
  @Test
  void reclaimCrossingIstMidnightStillResolvesItsOwnDay() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 7, 5));
    LocalDate target = CAL.previousTradingDay(today);
    LocalDate tomorrow = today.plusDays(1);
    clearWindow(target);
    clearCanaryRun(today);
    clearCanaryRun(tomorrow);
    seedUnconfirmedRun(today, "SCHEDULED", today.atTime(8, 56).atZone(Ist.ZONE).toInstant());

    MutableClock clock = new MutableClock(today.atTime(8, 58).atZone(Ist.ZONE).toInstant());
    TaskScheduler scheduler = mock(TaskScheduler.class);
    NtfyClient ntfy = mock(NtfyClient.class);
    assertThat(canary(jdbc, clock, true, ntfy, new SimpleMeterRegistry(), scheduler).catchUpIfMissed())
        .isFalse();

    ArgumentCaptor<Runnable> reclaim = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).schedule(reclaim.capture(), any(Instant.class));

    // the reclaim fires long after it was queued, on the far side of IST midnight
    clock.set(tomorrow.atTime(0, 30).atZone(Ist.ZONE).toInstant());
    reclaim.getValue().run();

    verify(ntfy).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
    assertThat(canaryRunState(today)).as("deferred work must resolve ITS OWN day").isEqualTo("DONE");
    assertThat(canaryRunSource(today)).isEqualTo("BOOT_CATCHUP");
    assertThat(canaryRunState(tomorrow))
        .as("and must never mark a day it did not evaluate — that would silence D+1's own canary")
        .isNull();
  }

  /**
   * The same property across the WHOLE chain, not just its first hop: with {@code
   * MAX_RECLAIM_ATTEMPTS = 3} and a five-minute lease, the last hop can be a long way from the
   * original fire. Here hop 1 crosses midnight, loses to a claim another door refreshed, and hands
   * hop 2 the same {@code today} it started with — two clock-days back by then.
   */
  @Test
  void reclaimChainCarriesItsDayAcrossEveryHop() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 7, 12));
    LocalDate target = CAL.previousTradingDay(today);
    LocalDate tomorrow = today.plusDays(1);
    clearWindow(target);
    clearCanaryRun(today);
    clearCanaryRun(tomorrow);
    seedUnconfirmedRun(today, "SCHEDULED", today.atTime(8, 56).atZone(Ist.ZONE).toInstant());

    MutableClock clock = new MutableClock(today.atTime(8, 58).atZone(Ist.ZONE).toInstant());
    TaskScheduler scheduler = mock(TaskScheduler.class);
    NtfyClient ntfy = mock(NtfyClient.class);
    canary(jdbc, clock, true, ntfy, new SimpleMeterRegistry(), scheduler).catchUpIfMissed();

    // hop 1 fires after midnight, but another door has refreshed the claim — it must lose and requeue
    clock.set(tomorrow.atTime(0, 30).atZone(Ist.ZONE).toInstant());
    restampClaim(today, tomorrow.atTime(0, 29).atZone(Ist.ZONE).toInstant());
    ArgumentCaptor<Runnable> firstHop = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).schedule(firstHop.capture(), any(Instant.class));
    firstHop.getValue().run();

    // hop 2 exists and, when the refreshed claim goes stale, still resolves the ORIGINAL day
    ArgumentCaptor<Runnable> hops = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(2)).schedule(hops.capture(), any(Instant.class));
    clock.set(tomorrow.atTime(0, 40).atZone(Ist.ZONE).toInstant());
    hops.getAllValues().get(1).run();

    verify(ntfy).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
    assertThat(canaryRunState(today)).isEqualTo("DONE");
    assertThat(canaryRunState(tomorrow)).isNull();
  }

  /**
   * The other side of the lease: a claim that is still FRESH is another door publishing right now,
   * not a corpse. Stealing it would re-introduce the double alert. Deterministic companion to the
   * concurrent test — it is what stops "make it retryable" from degenerating into "always retry".
   */
  @Test
  void catchUpDoesNotStealAnotherDoorsLiveClaim() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 6, 15));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target);
    clearCanaryRun(today);
    seedUnconfirmedRun(today, "SCHEDULED", today.atTime(8, 45).atZone(Ist.ZONE).toInstant());

    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept = canary(fixedAt(today, 8, 46), true, ntfy).catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(ntfy);
    assertThat(canaryRunSource(today)).as("the live claim keeps its owner").isEqualTo("SCHEDULED");
  }

  /**
   * Review round 4, Major: a door that LOST the claim must not publish because something else then
   * went wrong. The claim-failure catch used to span the loss handling as well, so a rejected
   * scheduler task landed in it, and the loser fell through and published alongside the live holder —
   * the exact double alert the shared claim exists to prevent.
   *
   * <p>The two failures are opposites wearing the same clothes: a claim ERROR is absence of evidence
   * (publish anyway, pinned by {@link #brokenMarkerLedgerStillPublishesTheAlert}); a claim LOSS is
   * positive evidence someone else holds the day. Losing the reclaim costs the self-heal, not the
   * de-duplication.
   */
  @Test
  void schedulingFailureNeverPromotesLostClaimToPublish() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 7, 19));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target); // all-RED, so any publish would be unmistakable
    clearCanaryRun(today);
    seedUnconfirmedRun(today, "SCHEDULED", today.atTime(8, 56).atZone(Ist.ZONE).toInstant());

    TaskScheduler refusing = mock(TaskScheduler.class);
    doThrow(new TaskRejectedException("reclaim pool exhausted"))
        .when(refusing)
        .schedule(any(Runnable.class), any(Instant.class));

    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept =
        canary(
                jdbc,
                new MutableClock(today.atTime(8, 58).atZone(Ist.ZONE).toInstant()),
                true,
                ntfy,
                new SimpleMeterRegistry(),
                refusing)
            .catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(ntfy);
    assertThat(canaryRunSource(today)).as("the live holder keeps its claim").isEqualTo("SCHEDULED");
    assertThat(canaryRunState(today)).isEqualTo("CLAIMED");
  }

  /**
   * Review round 5, Major: the loss-classification read used to sit INSIDE the fail-open zone. The
   * upsert returning zero rows has already PROVEN a DONE or live-CLAIMED row exists — the read only
   * asks which — so a failure there is not absence of evidence, and letting it propagate into the
   * claim-error catch published a duplicate alongside the proven holder. The invariant: fail-open
   * wraps ONLY the atomic upsert; everything after a zero-row result resolves toward stand-down.
   *
   * <p>The seeded claim is FRESH, so the upsert loses against the real DB; the spy breaks only the
   * classification read. Required outcome: no publish, holder untouched, and the self-heal kept —
   * a reclaim scheduled at the conservative instant (now + lease + slack, the freshest lease the
   * unreadable holder could possibly have).
   */
  @Test
  void classificationFailureAfterLostUpsertStandsDownAndKeepsTheSelfHeal() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 7, 26));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target); // all-RED, so any publish would be unmistakable
    clearCanaryRun(today);
    seedUnconfirmedRun(today, "SCHEDULED", today.atTime(8, 55).atZone(Ist.ZONE).toInstant());

    JdbcTemplate failingClassification = spy(jdbc);
    doThrow(new DataAccessResourceFailureException("connection died between the two statements"))
        .when(failingClassification)
        .query(anyString(), any(ResultSetExtractor.class), any(), any());

    Instant boot = today.atTime(8, 58).atZone(Ist.ZONE).toInstant(); // holder's lease is fresh
    TaskScheduler scheduler = mock(TaskScheduler.class);
    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept =
        canary(failingClassification, new MutableClock(boot), true, ntfy, new SimpleMeterRegistry(), scheduler)
            .catchUpIfMissed();

    assertThat(swept).isFalse();
    verifyNoInteractions(ntfy);
    assertThat(canaryRunState(today)).isEqualTo("CLAIMED");
    assertThat(canaryRunSource(today)).as("the proven holder keeps its claim").isEqualTo("SCHEDULED");
    ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
    verify(scheduler).schedule(any(Runnable.class), when.capture());
    assertThat(when.getValue())
        .as("the self-heal survives, at the freshest lease the unreadable holder could have")
        .isEqualTo(
            boot.plus(IngestCoverageCanary.CLAIM_LEASE).plus(IngestCoverageCanary.RECLAIM_SLACK));
  }

  /**
   * The deliberate asymmetry in {@code runOnce}: a claim EXCEPTION (the marker table itself broken —
   * realistically {@code canary_runs} missing because the migration was not deployed) publishes
   * anyway. A conflict LOSS de-duplicates; an unusable ledger must not silently suppress every
   * morning alert.
   */
  @Test
  void brokenMarkerLedgerStillPublishesTheAlert() {
    LocalDate today = CAL.nextTradingDay(LocalDate.of(2026, 6, 1));
    LocalDate target = CAL.previousTradingDay(today);
    clearWindow(target);
    clearCanaryRun(today);

    JdbcTemplate failingWrites = spy(jdbc);
    // the seven-argument overload is the CLAIM specifically (confirmPublished takes four), so this
    // breaks exactly the statement under test and nothing else
    doThrow(new DataAccessResourceFailureException("relation \"canary_runs\" does not exist"))
        .when(failingWrites)
        .update(anyString(), any(), any(), any(), any(), any(), any());

    NtfyClient ntfy = mock(NtfyClient.class);
    boolean swept =
        canary(failingWrites, fixedAt(today, 8, 56), true, ntfy, new SimpleMeterRegistry())
            .catchUpIfMissed();

    assertThat(swept).isTrue();
    verify(ntfy).send(contains("ingest coverage"), eq("urgent"), contains(IngestRunLedger.SOURCE_NSE_FII_DII));
  }

  // ---- fixtures -------------------------------------------------------------------------------

  private IngestCoverageCanary canary(Clock clock, boolean live, NtfyClient ntfy) {
    return canary(clock, live, ntfy, new SimpleMeterRegistry());
  }

  private IngestCoverageCanary canary(Clock clock, boolean live, NtfyClient ntfy, SimpleMeterRegistry meters) {
    return canary(jdbc, clock, live, ntfy, meters, mock(TaskScheduler.class));
  }

  /** Same, over an explicit template — the protocol tests hand in a spy that fails reads or writes. */
  private IngestCoverageCanary canary(
      JdbcTemplate template, Clock clock, boolean live, NtfyClient ntfy, SimpleMeterRegistry meters) {
    return canary(template, clock, live, ntfy, meters, mock(TaskScheduler.class));
  }

  /**
   * Full form. The scheduler is a MOCK in every test: the reclaim is scheduled at an instant on the
   * canary's own (fixed or mutable) clock, which bears no relation to real time, so a live pool would
   * fire it immediately and non-deterministically. Tests capture the task and run it themselves.
   */
  private IngestCoverageCanary canary(
      JdbcTemplate template,
      Clock clock,
      boolean live,
      NtfyClient ntfy,
      SimpleMeterRegistry meters,
      TaskScheduler scheduler) {
    MockEnvironment env = new MockEnvironment();
    if (live) {
      env.setActiveProfiles("live");
    }
    return new IngestCoverageCanary(
        template, ntfy, CAL, clock, meters, env, scheduler, true, 120, "0 45 8 * * MON-FRI");
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

  /** A fixed clock at 08:45 IST on {@code day}. */
  private static Clock fixedMorning(LocalDate day) {
    return Clock.fixed(day.atTime(8, 45).atZone(Ist.ZONE).toInstant(), ZoneOffset.UTC);
  }

  /** A fixed clock at an arbitrary IST wall-clock time on {@code day}. */
  private static Clock fixedAt(LocalDate day, int hour, int minute) {
    return Clock.fixed(day.atTime(hour, minute).atZone(Ist.ZONE).toInstant(), ZoneOffset.UTC);
  }

  private void clearCanaryRun(LocalDate day) {
    jdbc.update("DELETE FROM canary_runs WHERE canary = ? AND run_day = ?", IngestCoverageCanary.CANARY_KEY, day);
  }

  /** A completed run: the only state that suppresses a later door. */
  private void seedPublishedRun(LocalDate day, String source) {
    seedRun(day, "DONE", source, day.atTime(8, 45).atZone(Ist.ZONE).toInstant());
  }

  /**
   * A run that CLAIMED and never confirmed — a door that died (or threw) mid-publish. Stealable once
   * {@code claimedAt} is older than the lease, suppressing until then.
   */
  private void seedUnconfirmedRun(LocalDate day, String source, Instant claimedAt) {
    seedRun(day, "CLAIMED", source, claimedAt);
  }

  /** Moves an existing claim's lease clock — stands in for another door refreshing it. */
  private void restampClaim(LocalDate day, Instant claimedAt) {
    jdbc.update(
        "UPDATE canary_runs SET claimed_at = ? WHERE canary = ? AND run_day = ?",
        Timestamp.from(claimedAt),
        IngestCoverageCanary.CANARY_KEY,
        day);
  }

  private void seedRun(LocalDate day, String state, String source, Instant claimedAt) {
    jdbc.update(
        "INSERT INTO canary_runs (canary, run_day, state, source, claimed_at) VALUES (?,?,?,?,?)",
        IngestCoverageCanary.CANARY_KEY,
        day,
        state,
        source,
        Timestamp.from(claimedAt));
  }

  /** The recorded run marker for {@code day}, or null when the canary never ran that IST day. */
  private String canaryRunSource(LocalDate day) {
    return canaryRunColumn(day, "source");
  }

  /** {@code CLAIMED} (publishing, or died mid-publish) / {@code DONE} (confirmed) / null. */
  private String canaryRunState(LocalDate day) {
    return canaryRunColumn(day, "state");
  }

  private String canaryRunColumn(LocalDate day, String column) {
    List<String> values =
        jdbc.queryForList(
            "SELECT " + column + " FROM canary_runs WHERE canary = ? AND run_day = ?",
            String.class,
            IngestCoverageCanary.CANARY_KEY,
            day);
    return values.isEmpty() ? null : values.get(0);
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
    seedBhavRows(day, true, true);
    seedBatch(day, IngestRunLedger.SOURCE_INSTRUMENT_SYNC, "SUCCESS", 90000L, true);
    seedBatch(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "SUCCESS", 96L, true);
    seedBatch(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "SUCCESS", 40L, true);
    // EQUITY_BREADTH (chip task_1e319725). MATERIALIZED_DAY reads the ARTIFACT, so a healthy day
    // must seed the breadth row as well as the run — same pairing as seedBhavRows and
    // seedScreenRows above, and for the same reason: the run row does not identify the day it
    // covered.
    seedBatch(day, IngestRunLedger.SOURCE_EQUITY_BREADTH, "SUCCESS", 2L, true);
    seedBreadthRow(day);
    // The SCREENER policy reads the OUTPUT tables, not the run rows — a healthy day must seed both
    // (same reason seedBhavRows exists for the bhavcopy policy). Computed the same evening.
    seedScreenRows(day, day.atTime(19, 1));
  }

  /**
   * Seeds the DESTINATION tables the screener policy actually reads, at an explicit
   * {@code computedAt} so a test can choose the same-evening or the next-morning catch-up shape.
   * Minimal columns only — the policy counts rows and reads {@code max(computed_at)}, nothing else.
   */
  private void seedScreenRows(LocalDate day, java.time.LocalDateTime computedAtIst) {
    OffsetDateTime computedAt = computedAtIst.atZone(Ist.ZONE).toOffsetDateTime();
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

  /** The materialized breadth row MATERIALIZED_DAY actually reads. Minimal columns only. */
  private void seedBreadthRow(LocalDate day) {
    jdbc.update(
        "INSERT INTO equity_breadth_daily (trade_date, advances, declines, unchanged, total)"
            + " VALUES (?, 1, 1, 0, 2) ON CONFLICT (trade_date) DO NOTHING",
        day);
  }

  /** Drops the materialized breadth row for {@code day} — the "breadth never landed" shape. */
  private void deleteBreadthRow(LocalDate day) {
    jdbc.update("DELETE FROM equity_breadth_daily WHERE trade_date = ?", day);
  }

  /** Drops one screener's stored output for {@code day} — the "the screen never ran" shape. */
  private void deleteScreenRows(LocalDate day, String source) {
    String table =
        IngestRunLedger.SOURCE_MINERVINI_SCREEN.equals(source)
            ? "minervini_screen_results"
            : "manas_arora_screen_results";
    jdbc.update("DELETE FROM " + table + " WHERE screen_date = ?", day);
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
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE trade_date = ?", day);
    jdbc.update("DELETE FROM bse_eod_bhavcopy WHERE trade_date = ?", day);
    jdbc.update("DELETE FROM minervini_screen_results WHERE screen_date = ?", day);
    jdbc.update("DELETE FROM manas_arora_screen_results WHERE screen_date = ?", day);
  }

  /**
   * Seeds the DESTINATION tables the bhavcopy policy actually reads. Minimal columns only —
   * the policy counts rows, it does not look at prices.
   */
  private void seedBhavRows(LocalDate day, boolean nse, boolean bse) {
    if (nse) {
      jdbc.update(
          "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series) VALUES (?, ?, 'EQ')"
              + " ON CONFLICT DO NOTHING",
          day,
          "RELIANCE");
    }
    if (bse) {
      jdbc.update(
          "INSERT INTO bse_eod_bhavcopy (trade_date, scrip_code, ticker) VALUES (?, ?, ?)"
              + " ON CONFLICT DO NOTHING",
          day,
          "500325",
          "RELIANCE");
    }
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
