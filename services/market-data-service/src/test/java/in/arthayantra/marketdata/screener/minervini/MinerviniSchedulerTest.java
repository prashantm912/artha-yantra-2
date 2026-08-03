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

    verify(repo).upsertAll(eq(day), any());
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
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).upsertAll(any(), any());
  }

  /**
   * The plane-divergence alarm pages ONLY when a divergent symbol is a SERVED funnel candidate AND
   * its divergence clears the page floor.
   *
   * <p>Three runs of the SAME screen through the SAME scheduler, differing only in the probe's
   * report. They give opposite answers on both axes by construction: (a) a big divergence that is
   * not a served candidate — an implementation that paged on "a divergence exists" fails here;
   * (b) a served candidate below the page floor — an implementation with one threshold instead of
   * two fails here (measured: 4.7 such candidates EVERY evening, so this is the noise case, not a
   * hypothetical); (c) a served candidate above the floor — the only one that may page.
   */
  @Test
  void pagesOnlyWhenAServedCandidateClearsThePageFloor() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));

    // (a) 9% divergence, but WATCH bucket — not served → silent
    when(planeDivergence.probe(day))
        .thenReturn(report(day, name("WATCHONLY", "9.0", day, false)));
    scheduler(true).onBhavcopyBackfillCompleted();
    org.mockito.Mockito.verify(ntfy, org.mockito.Mockito.never()).send(any(), any(), any());

    // (b) a SERVED candidate, but 2.0% — under the 5% page floor → still silent
    when(planeDivergence.probe(day)).thenReturn(report(day, name("ABSLAMC", "2.0", day, true)));
    scheduler(true).onBhavcopyBackfillCompleted();
    org.mockito.Mockito.verify(ntfy, org.mockito.Mockito.never()).send(any(), any(), any());

    // (c) a SERVED candidate at 9.3% → the owner is paged, and the message names it
    when(planeDivergence.probe(day)).thenReturn(report(day, name("INDOBORAX", "9.3", day, true)));
    scheduler(true).onBhavcopyBackfillCompleted();
    org.mockito.Mockito.verify(ntfy)
        .send(
            org.mockito.ArgumentMatchers.contains("two price planes"),
            eq("high"),
            org.mockito.ArgumentMatchers.contains("INDOBORAX"));
  }

  private static PlaneDivergenceProbe.DivergentName name(
      String symbol, String pct, LocalDate worst, boolean candidate) {
    return new PlaneDivergenceProbe.DivergentName(
        symbol, new java.math.BigDecimal(pct), worst, 270, candidate);
  }

  /** Builds a report the way the probe does — the alerting count is DERIVED, never asserted-in. */
  private static PlaneDivergenceProbe.Report report(
      LocalDate day, PlaneDivergenceProbe.DivergentName... names) {
    java.math.BigDecimal reportFloor = new java.math.BigDecimal("0.5");
    java.math.BigDecimal pageFloor = new java.math.BigDecimal("5.0");
    List<PlaneDivergenceProbe.DivergentName> list = List.of(names);
    PlaneDivergenceProbe.Report shell =
        new PlaneDivergenceProbe.Report(day, 250, 0, 0, 0, reportFloor, pageFloor, 420, List.of());
    return new PlaneDivergenceProbe.Report(
        day,
        250,
        list.size(),
        (int) list.stream().filter(PlaneDivergenceProbe.DivergentName::candidate).count(),
        (int) list.stream().filter(shell::isAlerting).count(),
        reportFloor,
        pageFloor,
        420,
        list);
  }

  /** A probe failure must never fail (or silence) the screen it rides on. */
  @Test
  void aProbeFailureIsFailSoft() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));
    when(planeDivergence.probe(day)).thenThrow(new IllegalStateException("boom"));

    scheduler(true).onBhavcopyBackfillCompleted();

    verify(repo).upsertAll(eq(day), any());
    verify(ledger).succeed(any(), org.mockito.ArgumentMatchers.anyLong());
    org.mockito.Mockito.verify(ntfy, org.mockito.Mockito.never()).send(any(), any(), any());
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
              verify(repo).upsertAll(eq(day), any());
            });
  }
}
