package in.arthayantra.marketdata.screener.minervini;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The screen/bhavcopy race regression (audit H1 2026-07-05): the screen's PRIMARY trigger is the
 * {@code BhavcopyBackfillCompleted} event, so it runs against the fresh watermark instead of
 * racing an identical 19:30 cron and screening yesterday.
 */
class MinerviniSchedulerTest {

  private final TrendTemplateService screener = mock(TrendTemplateService.class);
  private final MinerviniScreenRepository repo = mock(MinerviniScreenRepository.class);
  private final MinerviniGeometryService geometry = mock(MinerviniGeometryService.class);
  private final in.arthayantra.marketdata.alerts.NtfyClient ntfy =
      mock(in.arthayantra.marketdata.alerts.NtfyClient.class);
  private final in.arthayantra.marketdata.ingest.IngestRunLedger ledger =
      mock(in.arthayantra.marketdata.ingest.IngestRunLedger.class);
  private final PlaneDivergenceProbe planeDivergence = mock(PlaneDivergenceProbe.class);

  private MinerviniScheduler scheduler(boolean enabled) {
    return new MinerviniScheduler(
        screener, repo, geometry, planeDivergence, ntfy, ledger, enabled, true);
  }

  @Test
  void bhavcopyCompletedEventRunsAndPersistsTheScreen() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));

    scheduler(true).onBhavcopyBackfillCompleted();

    verify(repo).replaceAll(eq(day), any());
    verify(geometry).persistForPassers(eq(day), any());
  }

  @Test
  void disabledFlagKeepsTheEventPathInert() {
    scheduler(false).onBhavcopyBackfillCompleted();

    verifyNoInteractions(screener, repo, geometry);
  }

  @Test
  void alreadyCurrentScreenIsSkippedOnTheEventAndCronPaths() {
    // The double-run guard: event at ~19:31 already screened today → the 19:50 fallback cron (and
    // a holiday's no-op-backfill event) must not re-run the screen + geometry fan-out.
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(day);
    when(screener.latestScreenDate()).thenReturn(day);

    scheduler(true).onBhavcopyBackfillCompleted();

    org.mockito.Mockito.verify(screener, org.mockito.Mockito.never()).screen(any());
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).replaceAll(any(), any());
  }

  /**
   * The probe NEVER pages. A page keyed on the divergent symbol was built, measured and removed:
   * it fires on 20 of 22 evenings, and it is structurally blind to the one measured casualty
   * (ICEMAKE 2026-07-28, which was displaced across the rs>=70 cut by ANOTHER symbol's error).
   * Re-adding a notification here must fail this test rather than ship quietly.
   */
  @Test
  void theProbeNeverPagesEvenOnADivergentServedCandidate() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));
    when(planeDivergence.probe(day))
        .thenReturn(report(day, name("INDOBORAX", "9.3", day, true)));

    scheduler(true).onBhavcopyBackfillCompleted();

    org.mockito.Mockito.verify(ntfy, org.mockito.Mockito.never()).send(any(), any(), any());
    verify(planeDivergence).markReported(day);
  }

  /**
   * The probe is a SEPARATE, durable observation from the screen.
   *
   * <p>Screen persistence and the ingest ledger both succeed BEFORE the probe runs, and every later
   * door hits the "already current" dedup skip. So without a marker of its own, a crash or probe
   * exception in between lost that evening's reading permanently. Both halves are asserted here and
   * they answer OPPOSITELY on the same skip path: no marker → the probe re-runs; marker → it does
   * not, and the screen is not re-run either way.
   */
  @Test
  void anOutstandingProbeIsRetriedOnTheDedupSkipPathAndOnlyOnce() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(day);
    when(screener.latestScreenDate()).thenReturn(day);
    when(planeDivergence.probe(day)).thenReturn(report(day));

    // no completion marker yet → the skip path still runs the probe
    when(planeDivergence.alreadyReported(day)).thenReturn(false);
    scheduler(true).onBhavcopyBackfillCompleted();
    verify(planeDivergence).probe(day);
    verify(planeDivergence).markReported(day);
    // ...and the SCREEN itself is still skipped — the retry is probe-only
    org.mockito.Mockito.verify(screener, org.mockito.Mockito.never()).screen(any());

    // marker present → no second run
    when(planeDivergence.alreadyReported(day)).thenReturn(true);
    scheduler(true).onBhavcopyBackfillCompleted();
    org.mockito.Mockito.verify(planeDivergence, org.mockito.Mockito.times(1)).probe(day);
  }

  /** A probe failure must not fail the screen, and must leave the date unmarked so it retries. */
  @Test
  void aProbeFailureIsFailSoftAndLeavesTheDateUnmarked() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));
    when(planeDivergence.probe(day)).thenThrow(new IllegalStateException("boom"));

    scheduler(true).onBhavcopyBackfillCompleted();

    verify(repo).replaceAll(eq(day), any());
    verify(ledger).succeed(any(), org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(planeDivergence, org.mockito.Mockito.never()).markReported(any());
    org.mockito.Mockito.verify(ntfy, org.mockito.Mockito.never()).send(any(), any(), any());
  }

  /**
   * The forced-recompute path must NOT be suppressed by the date's existing completion marker.
   *
   * <p>A recompute rewrites {@code computed_at} and can change the candidate set, so the marker
   * describes a screen that no longer exists. The two halves answer OPPOSITELY on the same marker
   * state: the scheduled door stands down, the forced door does not.
   */
  @Test
  void aForcedRecomputeProbesEvenWhenTheDateIsAlreadyMarked() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(day)).thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));
    when(planeDivergence.probe(day)).thenReturn(report(day));
    when(planeDivergence.alreadyReported(day)).thenReturn(true); // an earlier evening reported it

    scheduler(true).runOnce(day);

    verify(planeDivergence).probe(day);
    verify(planeDivergence).markReported(day);
  }

  /** ...while a SCHEDULED door on the same already-marked date still stands down. */
  @Test
  void aScheduledDoorOnAnAlreadyMarkedDateStandsDown() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(day);
    when(screener.latestScreenDate()).thenReturn(day);
    when(planeDivergence.alreadyReported(day)).thenReturn(true);

    scheduler(true).onBhavcopyBackfillCompleted();

    org.mockito.Mockito.verify(planeDivergence, org.mockito.Mockito.never()).probe(any());
  }

  /**
   * The reported round-3 hole, end to end: a FORCED probe that FAILS must leave the date retryable
   * by the next SCHEDULED door. Previously {@code force} only bypassed the read — the old marker
   * survived the failure and suppressed every later door, contradicting the "will retry" log.
   */
  @Test
  void aFailedForcedProbeLeavesTheDateRetryableForTheNextScheduledDoor() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(day)).thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));
    when(planeDivergence.probe(day)).thenThrow(new IllegalStateException("probe died"));

    scheduler(true).runOnce(day);

    // nothing was marked, so the date's completion still describes the PRE-recompute screen
    org.mockito.Mockito.verify(planeDivergence, org.mockito.Mockito.never()).markReported(any());

    // ...and the next scheduled door does observe it (derived completion re-opened the date)
    org.mockito.Mockito.reset(planeDivergence);
    when(repo.latestScreenDate()).thenReturn(day);
    when(screener.latestScreenDate()).thenReturn(day);
    when(planeDivergence.alreadyReported(day)).thenReturn(false); // stale marker < computed_at
    when(planeDivergence.probe(day)).thenReturn(report(day));

    scheduler(true).onBhavcopyBackfillCompleted();

    verify(planeDivergence).probe(day);
    verify(planeDivergence).markReported(day);
  }

  /** A DISABLED probe must write no marker, so enabling it later still yields an observation. */
  @Test
  void aDisabledProbeWritesNoMarker() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(day)).thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));

    new MinerviniScheduler(screener, repo, geometry, planeDivergence, ntfy, ledger, true, false)
        .runOnce(day);

    org.mockito.Mockito.verify(planeDivergence, org.mockito.Mockito.never()).probe(any());
    org.mockito.Mockito.verify(planeDivergence, org.mockito.Mockito.never()).markReported(any());
  }

  /** A marker WRITE failure must not fail the screen; the date simply stays retryable. */
  @Test
  void aMarkerWriteFailureIsFailSoft() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));
    when(planeDivergence.probe(day)).thenReturn(report(day));
    org.mockito.Mockito.doThrow(new IllegalStateException("canary_runs gone"))
        .when(planeDivergence).markReported(day);

    scheduler(true).onBhavcopyBackfillCompleted();

    verify(repo).replaceAll(eq(day), any());
    verify(ledger).succeed(any(), org.mockito.ArgumentMatchers.anyLong());
  }

  /** A marker READ failure must fail OPEN — observe anyway, never inherit silence. */
  @Test
  void aMarkerReadFailureStillObserves() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(day);
    when(screener.latestScreenDate()).thenReturn(day);
    when(planeDivergence.alreadyReported(day))
        .thenThrow(new IllegalStateException("canary_runs unreadable"));
    when(planeDivergence.probe(day)).thenReturn(report(day));

    scheduler(true).onBhavcopyBackfillCompleted();

    verify(planeDivergence).probe(day);
  }

  private static PlaneDivergenceProbe.DivergentName name(
      String symbol, String pct, LocalDate worst, boolean candidate) {
    return new PlaneDivergenceProbe.DivergentName(
        symbol, new java.math.BigDecimal(pct), worst, 270, 0, candidate);
  }

  private static PlaneDivergenceProbe.Report report(
      LocalDate day, PlaneDivergenceProbe.DivergentName... names) {
    List<PlaneDivergenceProbe.DivergentName> list = List.of(names);
    return new PlaneDivergenceProbe.Report(
        day,
        day.atTime(19, 55).atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30)),
        250,
        67_500,
        0,
        0,
        list.size(),
        (int) list.stream().filter(PlaneDivergenceProbe.DivergentName::candidate).count(),
        new java.math.BigDecimal("0.5"),
        420,
        list);
  }

  @Test
  void eventListenerWiringFiresTheScreenOnBhavcopyCompletion() {
    // Pins the CONSUMER half of the audit-H1 fix: the @EventListener wiring itself. Deleting the
    // annotation (or changing the event type) must fail THIS test, not just leave the primary
    // trigger silently dead with fallback-crons-only degradation.
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));

    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withBean(
            MinerviniScheduler.class,
            () -> scheduler(true))
        .run(
            ctx -> {
              ctx.getSourceApplicationContext()
                  .publishEvent(
                      new in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted("job"));
              verify(repo).replaceAll(eq(day), any());
            });
  }
}
