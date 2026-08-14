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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * "Can I shut down yet?" IT. Runs the REAL {@code ingest_runs}/{@code canary_runs}/screen-output SQL
 * against the Timescale container (from {@link MarketDataIntegrationTestBase}), with a FIXED clock
 * and a mocked {@link NtfyClient} so the push decision is deterministic. Mirrors the
 * hand-construction pattern in {@code IngestCoverageCanaryIntegrationTest} (never the Spring-wired
 * bean, so {@code live}/{@code enabled} can be flipped per test).
 *
 * <p>Isolation on the shared singleton DB (NO per-method cleanup): every method uses a DISTINCT
 * synthetic day for its {@code ingest_runs} window, and explicitly clears its own {@code canary_runs}
 * marker first — a surefire rerun-on-failure would otherwise find a PRIOR run's own {@code
 * DONE}/{@code CLAIMED} row still there and read "already published" from the first line.
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
 * (key {@code MINERVINI_PLANE_DIVERGENCE}) regardless of date. The band is {@code day(0..17)} =
 * 2026-08-12 → 2026-09-04 (2026-09-14 Ganesh Chaturthi is the only NSE holiday in the span and falls
 * after it); the sibling's earliest fixture is 2026-09-11, so the two do not meet.
 *
 * <p>⚠️ <b>AND THAT REASONING WAS INCOMPLETE — the cleanup below is why this class now deletes what
 * it writes (2026-08-13).</b> "Distinct synthetic day" isolates this class from other FIXTURES, but
 * not from readers whose window is derived from the SYSTEM clock. {@code IngestHealthBoard.board(n)}
 * evaluates the last {@code n} settled trading days strictly before {@code LocalDate.now(clock)}, and
 * {@code FiiDigestService} gates {@code trustReasons} on that board — so the moment real time reached
 * {@code day(0)}, these SUCCESS rows became the newest settled trading day's verdict for {@code
 * NSE_FII_DII}, flipping it GREEN and silently deleting a reason {@code MarketContextI2IntegrationTest}
 * asserts on. Measured: green while today was ≤ 2026-08-11, red from 2026-08-13 onward with no code
 * change in between — a calendar time bomb, not a regression, and it fails in a DIFFERENT class from
 * the one that caused it.
 *
 * <p>Moving the anchor cannot fix this: a future date is safe only until today reaches it, and a past
 * date passes back through the same window on its way out. The only date-independent property is that
 * the class leaves every shared table as it found it, which is what {@link #removeSeededRows()}
 * enforces.
 *
 * <p>⚠️ <b>AND THAT WAS STILL NOT ENOUGH (2026-08-14, review Major 7).</b> The cleanup covered the
 * rows this class INSERTS; it did not cover the rows it DELETED on the way in. {@link
 * #clearWindow(LocalDate)} was an unfiltered {@code DELETE FROM ingest_runs WHERE started_at >= ? AND
 * started_at < ?} with no {@code source} predicate, called by most methods here — and {@code
 * day(0..17)} spans 2026-08-12 → 2026-09-04, which CONTAINS the real current date. So on any run
 * after 2026-08-12 it destroyed rows other Spring contexts had written TODAY: {@code
 * MinerviniScheduler.onStartup}/{@code ManasScheduler.onStartup} fire on every {@code @SpringBootTest}
 * {@code ApplicationReadyEvent}, and {@code IngestRunReaperIntegrationTest} inserts at {@code now()}.
 * The javadoc on the cleanup asserted "never a blanket day sweep" while the pre-clean was exactly
 * that. It is now source-scoped, and {@code canary_runs} is cleaned as well as {@code ingest_runs}.
 *
 * <p><b>Screen-output fixtures are a different hazard again</b>, because the rule under test compares
 * two GLOBAL maxima ({@code max(screen_date)} vs {@code max(trade_date)}), not day-scoped rows. A
 * fixture that controls those necessarily becomes the newest row in a table half this service reads.
 * {@link #beyondEveryWatermark()} derives its date from the CURRENT maxima rather than hardcoding
 * one, so the tests are deterministic whatever else is in the DB, and every such row is tracked and
 * removed in {@link #removeSeededRows()}.
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

  private static final String MINERVINI_TABLE = "minervini_screen_results";
  private static final String MANAS_TABLE = "manas_arora_screen_results";

  /** The {@code n}-th trading day at-or-after {@link #ANCHOR} (0-indexed); each test gets a distinct one. */
  private static LocalDate day(int n) {
    LocalDate d = ANCHOR.minusDays(1);
    for (int i = 0; i <= n; i++) {
      d = CAL.nextTradingDay(d);
    }
    return d;
  }

  @Autowired JdbcTemplate jdbc;

  /** One {@code ingest_runs} row this class wrote, so the cleanup can take it back. */
  private record SeededRun(LocalDate day, String source) {}

  /** One screen-output row this class wrote: which table, and the {@code screen_date} it carries. */
  private record SeededScreen(String table, LocalDate screenDate) {}

  private final Set<SeededRun> seededRuns = new LinkedHashSet<>();
  private final Set<SeededScreen> seededScreens = new LinkedHashSet<>();
  private final Set<LocalDate> seededBhavcopyDays = new LinkedHashSet<>();
  private final Set<LocalDate> touchedCanaryDays = new LinkedHashSet<>();

  /**
   * Hand every row this class wrote back — see the class javadoc for why a distinct synthetic day is
   * NOT enough on its own, and why the pre-clean had to become source-scoped too. Deletes exactly the
   * keys written, never a blanket sweep of anything, so it cannot take a neighbouring fixture's rows
   * with it. Runs after EVERY attempt, so a surefire rerun-on-failure leaves nothing behind either.
   *
   * <p>{@code canary_runs} is in here deliberately: those rows were previously written by the {@code
   * check()} tests and by {@code seedDoneCanaryRun} and never removed, so they outlived the run on a
   * key ({@code EVENING_CHAIN}) that only collides with THIS class — invisible until a later change
   * makes a stale marker meaningful.
   */
  @AfterEach
  void removeSeededRows() {
    seededRuns.forEach(run -> deleteSource(run.day(), run.source()));
    seededScreens.forEach(
        screen ->
            jdbc.update(
                "DELETE FROM " + screen.table() + " WHERE screen_date = ?", screen.screenDate()));
    seededBhavcopyDays.forEach(
        d -> jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE trade_date = ?", d));
    touchedCanaryDays.forEach(this::deleteCanaryRun);
    seededRuns.clear();
    seededScreens.clear();
    seededBhavcopyDays.clear();
    touchedCanaryDays.clear();
  }

  // ---- report(): classification ----------------------------------------------------------------

  @Test
  void reportOnAHealthyEveningIsCompleteWithEverySourceDone() {
    LocalDate day = day(0);
    clearWindow(day);
    seedAllHealthy(day);

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

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

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

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
    // Started 18:52 and checked at 18:59 — seven minutes, inside the 10-minute default.
    seedRun(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "RUNNING", 18, 52);

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

    SourceProgress s = find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    assertThat(s.state()).isEqualTo(SourceState.PENDING);
    assertThat(s.status()).isEqualTo("RUNNING");
  }

  /**
   * The MANAS_SCREEN finding from the brief: a container recreate mid-job leaves an orphaned RUNNING
   * row with no reaper. Without the aged-vs-fresh distinction it would read PENDING forever.
   *
   * <p>⚠️ MAJOR 3 (review, 2026-08-14). This fixture is the PRODUCTION shape — a row started at
   * 18:45, the earliest of the nine expected writers, judged at the 18:59 check — and under the old
   * shared {@code artha.ingest-canary.running-stale-minutes} default of 120 it was PENDING, because
   * 14 minutes is the OLDEST age this canary can ever observe. STUCK was unreachable in production
   * and its tests only reached it with fixtures production cannot produce (a RUNNING row from 16:00
   * against a 21:00 clock). It has its own threshold now; this test is the proof it can fire.
   */
  @Test
  void anAgedRunningRowIsStuckNotPending() {
    LocalDate day = day(3);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_MANAS_SCREEN);
    seedRun(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "RUNNING", 18, 45);

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

    SourceProgress s = find(report, IngestRunLedger.SOURCE_MANAS_SCREEN);
    assertThat(s.state())
        .as("14 minutes is the OLDEST a RUNNING row can be at the 18:59 check — a threshold this"
            + " cannot reach makes STUCK dead code")
        .isEqualTo(SourceState.STUCK);
  }

  /**
   * MAJOR 3, stated as a ratchet rather than only as a fixture: the earliest of the nine expected
   * writers fires at 18:45 (compose {@code ARTHA_BHAVCOPY_EOD_CRON}) and the check at 18:59, so any
   * threshold at or above 14 minutes can NEVER classify anything as STUCK. A future edit that
   * "harmonises" this back with the 120-minute ingest-canary knob reds here, with the reason.
   */
  @Test
  void theStaleThresholdIsReachableInsideThePreShutdownWindow() {
    assertThat(EveningChainCanary.DEFAULT_RUNNING_STALE_MINUTES)
        .as(
            "the whole evening chain runs 18:45-18:51 and the check fires 18:59, so a RUNNING row"
                + " here is at most 14 minutes old; a larger threshold makes SourceState.STUCK"
                + " unreachable in production while its tests stay green on impossible fixtures")
        .isLessThan(14);
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
    seedRun(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "RUNNING", 18, 45);

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

    assertThat(find(report, IngestRunLedger.SOURCE_MANAS_SCREEN).state()).isEqualTo(SourceState.STUCK);
    assertThat(report.done()).isEqualTo(EveningChainCanary.EXPECTED.size() - 1);
    assertThat(report.complete())
        .as("a crashed/orphaned job must never read as safe to shut down")
        .isFalse();
  }

  /**
   * A FAILURE row is TERMINAL, so it is DONE — nothing more will run for that source tonight, which
   * is the only question {@code complete} answers.
   *
   * <p>⚠️ MAJOR 4 (review, 2026-08-14) is about what is done WITH that fact, not about this flag.
   * The earlier version of this test asserted only {@code complete()).isTrue()} and stopped, which
   * left the reader with "a failed evening is complete" and nothing saying that complete ≠ safe —
   * and the code agreed with the reader, titling the push "evening chain complete". The flag is
   * unchanged and correct; the verdict is made at the message and the panel, and {@link
   * #aFailedSourceNeverPublishesACompleteTitle} is the half that was missing.
   */
  @Test
  void aFailedRunIsDoneNotPending() {
    LocalDate day = day(5);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_DATA_QUALITY);
    seedRunRange(day, IngestRunLedger.SOURCE_DATA_QUALITY, "FAILURE", 18, 50, 18, 51);

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

    SourceProgress s = find(report, IngestRunLedger.SOURCE_DATA_QUALITY);
    assertThat(s.state()).isEqualTo(SourceState.DONE);
    assertThat(s.status()).isEqualTo("FAILURE");
    assertThat(report.complete())
        .as(
            "complete means 'nothing more will run', which is true here — the SAFE-vs-not"
                + " distinction is made by the message and the panel, never by this flag alone")
        .isTrue();
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

  // ---- report(): the screener artifact carve-out (Critical 1) ----------------------------------

  /**
   * CRITICAL 1 (review, 2026-08-14). Both screen schedulers RETURN on their dedup skip BEFORE {@code
   * ledger.start} ({@code MinerviniScheduler:116-125}, {@code ManasScheduler:79-83}), so a screener
   * with nothing left to do writes NO {@code ingest_runs} row at all — indistinguishable, to a
   * run-row-only check, from one that has not run. The push then said {@code still pending:
   * MINERVINI_SCREEN, MANAS_SCREEN} on a chain where nothing was coming, every single evening the
   * watermark did not move. Measured: {@code ingest_runs} carried no screen row for 2026-08-12 at all.
   *
   * <p>The fixture is that exact shape: no run row, and both screens already current with the
   * bhavcopy watermark.
   */
  @Test
  void aScreenerThatSkippedBecauseItIsAlreadyCurrentIsDoneNotPending() {
    LocalDate day = day(6);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    deleteSource(day, IngestRunLedger.SOURCE_MANAS_SCREEN);
    LocalDate watermark = beyondEveryWatermark();
    seedBhavcopyDay(watermark);
    seedScreenOutput(MINERVINI_TABLE, watermark);
    seedScreenOutput(MANAS_TABLE, watermark);

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

    SourceProgress minervini = find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    assertThat(minervini.state())
        .as("the screen has consumed the watermark — no further screen work will happen tonight")
        .isEqualTo(SourceState.DONE);
    assertThat(minervini.status())
        .as("and it must not claim a run SUCCEEDED, because no run happened")
        .isEqualTo(EveningChainCanary.STATUS_UP_TO_DATE);
    assertThat(find(report, IngestRunLedger.SOURCE_MANAS_SCREEN).state()).isEqualTo(SourceState.DONE);
    assertThat(report.complete()).isTrue();
  }

  /**
   * The other half, and the reason the carve-out cannot simply green the screeners: when the bhavcopy
   * watermark HAS moved and the screen has not followed it, work really is outstanding and must stay
   * PENDING. Without this the fix would be a blind GREEN traded for a false RED.
   */
  @Test
  void aScreenerWhoseWatermarkMovedWithoutItIsStillPending() {
    LocalDate day = day(7);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    LocalDate watermark = beyondEveryWatermark();
    seedBhavcopyDay(watermark); // equity data advanced; no screen output carries that date

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

    assertThat(find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN).state())
        .isEqualTo(SourceState.PENDING);
    assertThat(report.complete()).isFalse();
  }

  /**
   * The carve-out may only ever promote PENDING→DONE, never mask a crash. A process killed after
   * {@code repo.replaceAll} but before {@code ledger.succeed} leaves a CURRENT artifact AND a
   * stranded RUNNING row — the plane-divergence probe runs between those two points, so the leg is
   * not done, and a stranded row an hour before shutdown is exactly what the owner must hear.
   */
  @Test
  void aCurrentScreenArtifactDoesNotRescueAStrandedRunningRow() {
    LocalDate day = day(8);
    clearWindow(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN);
    seedRun(day, IngestRunLedger.SOURCE_MINERVINI_SCREEN, "RUNNING", 18, 45);
    LocalDate watermark = beyondEveryWatermark();
    seedBhavcopyDay(watermark);
    seedScreenOutput(MINERVINI_TABLE, watermark);

    ChainReport report = canary(fixedAt(day, 18, 59), true).report();

    assertThat(find(report, IngestRunLedger.SOURCE_MINERVINI_SCREEN).state())
        .as("the screen output landed, but the run died mid-leg — that is not 'safe to shut down'")
        .isEqualTo(SourceState.STUCK);
    assertThat(report.complete()).isFalse();
  }

  // ---- the fixture harness itself (Major 7) -----------------------------------------------------

  /**
   * MAJOR 7 (review, 2026-08-14): the per-method pre-clean must not destroy rows this class did not
   * write. It used to be an unfiltered {@code DELETE FROM ingest_runs} over the whole synthetic day,
   * and {@code day(0..17)} contains the real current date — so on any run from 2026-08-12 onward it
   * deleted rows other live Spring contexts had written TODAY.
   *
   * <p>{@code INSTRUMENT_SYNC} at 08:29 is that exact shape: a real source this class never seeds,
   * on a day it does. It must survive the sweep.
   */
  @Test
  void theWindowPreCleanNeverTouchesAForeignSourcesRowOnTheSameDay() {
    LocalDate day = day(17);
    clearWindow(day);
    seedRunRange(day, IngestRunLedger.SOURCE_INSTRUMENT_SYNC, "SUCCESS", 8, 29, 8, 30);

    clearWindow(day); // the pre-clean, exactly as every method above runs it

    assertThat(rowsFor(day, IngestRunLedger.SOURCE_INSTRUMENT_SYNC))
        .as(
            "a day-wide DELETE takes rows this class never wrote with it — MinerviniScheduler and"
                + " ManasScheduler onStartup, and IngestRunReaperIntegrationTest, all write real"
                + " rows at today's date while another context is alive")
        .isEqualTo(1);
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

  /**
   * The annotation carries the cron as a LITERAL and this pins it to {@link
   * EveningChainCanary#DEFAULT_CHECK_CRON}, so the two copies cannot drift.
   *
   * <p>⚠️ Why a LITERAL rather than the constant concatenated into the placeholder, which is a
   * compile-time constant expression and therefore reflectively IDENTICAL: strategy-signal's {@code
   * OperatingWindowTest#everyScheduledJobIsInsideTheWindowOrExplicitlyExcused} walks the SOURCE TEXT
   * of both services to prove no scheduled job is stranded outside the owner's 08:00-19:00 machine
   * window, and a concatenation leaves it reading the cron as a bare placeholder prefix truncated at
   * the closing quote — a schedule it cannot enumerate, which it correctly refuses. Neither file was
   * wrong on its own; the combination was.
   *
   * <p>⚠️ So read what THIS test does and does not prove, because the two are easy to conflate.
   * Measured by re-running it against the concatenated form: it PASSES either way, because constant
   * folding erases the difference before reflection ever sees it. It is a guard on the two copies of
   * the VALUE, never on the source FORM. The form is guarded only by {@code OperatingWindowTest} and
   * {@code CronPassthroughParityTest} (the latter also requires {@code cron} and {@code zone} on ONE
   * source line), both of which read text, which is why the annotation also carries comments.
   */
  @Test
  void defaultCheckCronMatchesTheScheduledAnnotation() throws NoSuchMethodException {
    Scheduled scheduled =
        EveningChainCanary.class.getDeclaredMethod("check").getAnnotation(Scheduled.class);

    assertThat(scheduled).isNotNull();
    assertThat(scheduled.cron())
        .as(
            "the @Scheduled default and DEFAULT_CHECK_CRON are two copies of one schedule — a drift"
                + " reschedules the live check while every test that reads the constant stays green")
        .isEqualTo("${artha.evening-chain.check-cron:" + EveningChainCanary.DEFAULT_CHECK_CRON + "}");
    assertThat(scheduled.zone())
        .as("without the zone Spring schedules in the container's UTC and 18:59 becomes 00:29 IST")
        .isEqualTo("Asia/Kolkata");
  }

  // ---- check(): the evaluate/claim/publish protocol --------------------------------------------

  @Test
  void checkPushesChainCompleteOnceAndConfirmsDone() {
    LocalDate day = day(9);
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
    LocalDate day = day(10);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_EQUITY_BREADTH); // never ran
    deleteSource(day, IngestRunLedger.SOURCE_MANAS_SCREEN);
    // 18:45 is the earliest of the nine expected writers; still RUNNING at the 18:59 check it is 14
    // minutes old, past the 10-minute default. This is the production shape — see
    // anAgedRunningRowIsStuckNotPending for why the fixture used to have to be an impossible one.
    seedRun(day, IngestRunLedger.SOURCE_MANAS_SCREEN, "RUNNING", 18, 45); // aged -> stuck

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
   * MAJOR 4 (review, 2026-08-14): a FAILED source must never yield a "complete" / "safe to shut down"
   * title. It used to — {@code outstanding} was computed from PENDING/STUCK alone, so a FAILURE row
   * (terminal, therefore DONE) produced the title "ArthaYantra evening chain complete" with the
   * failure demoted to a body suffix. A phone shows the title first, and the Data-Ops panel renders
   * the same shape into an {@code aria-live} region, so the reader who gets ONLY the announcement was
   * told the opposite of the truth.
   *
   * <p>Asserted on the LITERAL strings rather than a helper, because the defect was entirely in what
   * the words said.
   */
  @Test
  void aFailedSourceNeverPublishesACompleteTitle() {
    LocalDate day = day(11);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);
    deleteSource(day, IngestRunLedger.SOURCE_DATA_QUALITY);
    seedRunRange(day, IngestRunLedger.SOURCE_DATA_QUALITY, "FAILURE", 18, 50, 18, 51);

    NtfyClient ntfy = mock(NtfyClient.class);
    when(ntfy.trySend(any(), any(), any())).thenReturn(true);
    canary(fixedAt(day, 18, 59), true, ntfy).check();

    ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(ntfy).trySend(title.capture(), eq("default"), body.capture());
    assertThat(title.getValue())
        .as("the title is the whole notification on a phone lock screen")
        .doesNotContain("complete")
        .contains("WITH FAILURES");
    assertThat(body.getValue())
        .doesNotContain("chain complete")
        .contains("NOT clean")
        .contains("failed: " + IngestRunLedger.SOURCE_DATA_QUALITY);
    assertThat(canaryRunState(day)).isEqualTo("DONE");
  }

  /**
   * MAJOR 2 (review, 2026-08-14): a delivery failure leaves the claim CLAIMED — an honest record that
   * nothing was confirmed — and NOTHING is scheduled to come back for it.
   *
   * <p>⚠️ This replaces a test that drove a reclaim {@code Runnable} by hand on a mutable clock and
   * "proved" a retry that could never run in production: the check fires 18:59:00 against a hard
   * 19:00 shutdown, and the reclaim was scheduled at {@code CLAIM_LEASE + RECLAIM_SLACK} = 19:04:05.
   * All three attempts were dead. Shrinking the lease does not rescue it — one ntfy POST is bounded
   * by 3 s connect + 60 s read, so a single publish can consume the whole 60 s of runway, and a lease
   * shorter than that is not a retry but a concurrent second POST. The retry is gone; this pins that
   * the loss is recorded rather than silently papered over.
   */
  @Test
  void aSendFailureLeavesTheClaimUnconfirmedAndDoesNotRetry() {
    LocalDate day = day(12);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    when(ntfy.trySend(any(), any(), any())).thenReturn(false);
    EveningChainCanary c = canary(fixedAt(day, 18, 59), true, ntfy);

    c.check();

    verify(ntfy, times(1)).trySend(any(), any(), any());
    assertThat(canaryRunState(day))
        .as("a send that did not actually deliver must not confirm the claim")
        .isEqualTo("CLAIMED");

    // And a second fire inside the same lease stands down rather than double-pushing — the claim is
    // still the de-duplication, it just no longer promises a retry.
    c.check();
    verify(ntfy, times(1)).trySend(any(), any(), any());
  }

  @Test
  void checkStandsDownWhenAlreadyPublishedToday() {
    LocalDate day = day(13);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);
    seedDoneCanaryRun(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(fixedAt(day, 18, 59), true, ntfy).check();

    verifyNoInteractions(ntfy);
  }

  /**
   * alertsEnabled=false must still resolve the claim to DONE (nothing was ever owed a delivery), not
   * leave it dangling CLAIMED forever — the gap the vacuous predecessor test never checked.
   */
  @Test
  void alertsDisabledStillConfirmsDoneWithoutSendingAnything() {
    LocalDate day = day(14);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(jdbc, fixedAt(day, 18, 59), true, true, false, ntfy).check();

    verifyNoInteractions(ntfy);
    assertThat(canaryRunState(day)).isEqualTo("DONE");
  }

  @Test
  void checkSkipsWhenNotLive() {
    LocalDate day = day(15);
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
    LocalDate day = day(16);
    clearWindow(day);
    clearCanaryRun(day);
    seedAllHealthy(day);

    NtfyClient ntfy = mock(NtfyClient.class);
    canary(jdbc, fixedAt(day, 18, 59), true, false, true, ntfy).check();

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
    return canary(jdbc, clock, live, true, true, ntfy);
  }

  private EveningChainCanary canary(
      JdbcTemplate template,
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
        template,
        ntfy,
        CAL,
        clock,
        env,
        enabled,
        alertsEnabled,
        EveningChainCanary.DEFAULT_RUNNING_STALE_MINUTES);
  }

  private static Clock fixedAt(LocalDate day, int hour, int minute) {
    return Clock.fixed(day.atTime(hour, minute).atZone(Ist.ZONE).toInstant(), ZoneOffset.UTC);
  }

  /** Clears this canary's marker for {@code day} AND registers the day for the after-each sweep. */
  private void clearCanaryRun(LocalDate day) {
    touchedCanaryDays.add(day);
    deleteCanaryRun(day);
  }

  private void deleteCanaryRun(LocalDate day) {
    jdbc.update("DELETE FROM canary_runs WHERE canary = ? AND run_day = ?", EveningChainCanary.CANARY_KEY, day);
  }

  /** Simulates a prior door having already confirmed today's push. */
  private void seedDoneCanaryRun(LocalDate day) {
    touchedCanaryDays.add(day);
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
      seedRunRange(day, source, "SUCCESS", 18, 45, 18, 46);
    }
  }

  /** {@code finishedAt} null means still RUNNING; pass e.g. {@code 18, 45} for 18:45 IST. */
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
    // Recorded at the ONE place rows are written, so a new fixture cannot be added without its
    // cleanup: every seeder in this class funnels through here.
    seededRuns.add(new SeededRun(day, source));
  }

  /** How many {@code ingest_runs} rows one source has inside {@code day}'s IST bounds. */
  private int rowsFor(LocalDate day, String source) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM ingest_runs WHERE source = ? AND started_at >= ? AND started_at < ?",
            Integer.class,
            source,
            day.atStartOfDay(Ist.ZONE).toOffsetDateTime(),
            day.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime());
    return n == null ? 0 : n;
  }

  private void deleteSource(LocalDate day, String source) {
    OffsetDateTime start = day.atStartOfDay(Ist.ZONE).toOffsetDateTime();
    OffsetDateTime end = day.plusDays(1).atStartOfDay(Ist.ZONE).toOffsetDateTime();
    jdbc.update(
        "DELETE FROM ingest_runs WHERE source = ? AND started_at >= ? AND started_at < ?", source, start, end);
  }

  /**
   * Pre-clean for one synthetic day, so a crashed prior attempt cannot leave a row behind.
   *
   * <p>⚠️ SOURCE-SCOPED, never a day sweep (review Major 7). This used to be an unfiltered {@code
   * DELETE FROM ingest_runs} over the whole day, and {@code day(0..17)} contains the real current
   * date from 2026-08-12 onward — so it deleted rows OTHER Spring contexts had written today ({@code
   * MinerviniScheduler}/{@code ManasScheduler} {@code onStartup} fire on every {@code @SpringBootTest}
   * ready event; {@code IngestRunReaperIntegrationTest} inserts at {@code now()}). Scoping it to the
   * sources this class actually seeds cannot touch a foreign row, and the canary ignores every source
   * outside {@code EXPECTED} anyway.
   */
  private void clearWindow(LocalDate day) {
    EveningChainCanary.EXPECTED.forEach(source -> deleteSource(day, source));
  }

  /**
   * A date strictly newer than every screen/bhavcopy watermark currently in the shared DB.
   *
   * <p>The rule under test compares two GLOBAL maxima, so a fixture can only control it by BEING the
   * newest row — there is no day-scoped way to ask "has the screen caught up". Deriving the date from
   * the current maxima rather than hardcoding one makes these tests deterministic whatever else the
   * singleton DB holds, and whatever order the classes ran in. Every row seeded at it is tracked and
   * removed in {@link #removeSeededRows()}, because while it exists it IS the watermark every
   * screener read in this service sees.
   *
   * <p>Deliberately UNFILTERED by {@code series}: the production watermark reads {@code series IN
   * ('EQ','BE')}, so dominating only that subset would leave a later non-EQ row able to change the
   * answer. Dominating every row is the property that makes the fixture sound.
   */
  private LocalDate beyondEveryWatermark() {
    LocalDate max = ANCHOR;
    for (String sql :
        List.of(
            "SELECT max(trade_date) FROM nse_eod_bhavcopy",
            "SELECT max(screen_date) FROM " + MINERVINI_TABLE,
            "SELECT max(screen_date) FROM " + MANAS_TABLE)) {
      LocalDate found = jdbc.queryForObject(sql, LocalDate.class);
      if (found != null && found.isAfter(max)) {
        max = found;
      }
    }
    return max.plusDays(1);
  }

  /** One EQ bhavcopy row, which is all the watermark query needs. */
  private void seedBhavcopyDay(LocalDate tradeDate) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series) VALUES (?, 'RELIANCE', 'EQ')"
            + " ON CONFLICT DO NOTHING",
        tradeDate);
    seededBhavcopyDays.add(tradeDate);
  }

  /** One stored screen row for {@code screenDate} — enough to move {@code max(screen_date)}. */
  private void seedScreenOutput(String table, LocalDate screenDate) {
    if (MINERVINI_TABLE.equals(table)) {
      jdbc.update(
          "INSERT INTO " + MINERVINI_TABLE + " (screen_date, symbol, close_price, gate1, gate2,"
              + " gate3, gate4, gate5, gate6, gate7, gate8, gates_passed, passes_all)"
              + " VALUES (?, 'RELIANCE', 100, true, true, true, true, true, true, true, true, 8, true)"
              + " ON CONFLICT (screen_date, symbol) DO NOTHING",
          screenDate);
    } else {
      jdbc.update(
          "INSERT INTO " + MANAS_TABLE + " (screen_date, symbol, close_price, within_high,"
              + " above_sma50, liquid_volume, liquid_depth, low_cap, gate1, gate2, gate3, gate4,"
              + " gate5, gate6, gates_passed, passes_all)"
              + " VALUES (?, 'RELIANCE', 100, true, true, true, true, true, true, true, true, true,"
              + " true, true, 6, true)"
              + " ON CONFLICT (screen_date, symbol) DO NOTHING",
          screenDate);
    }
    seededScreens.add(new SeededScreen(table, screenDate));
  }

  private static SourceProgress find(ChainReport report, String source) {
    return report.sources().stream()
        .filter(s -> s.source().equals(source))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no chain row for " + source));
  }
}
