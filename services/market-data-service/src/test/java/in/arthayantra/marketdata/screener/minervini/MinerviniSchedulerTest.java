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

  @Test
  void bhavcopyCompletedEventRunsAndPersistsTheScreen() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 0, List.of()));

    new MinerviniScheduler(screener, repo, geometry, true).onBhavcopyBackfillCompleted();

    verify(repo).upsertAll(eq(day), any());
    verify(geometry).persistForPassers(eq(day), any());
  }

  @Test
  void disabledFlagKeepsTheEventPathInert() {
    new MinerviniScheduler(screener, repo, geometry, false).onBhavcopyBackfillCompleted();

    verifyNoInteractions(screener, repo, geometry);
  }

  @Test
  void alreadyCurrentScreenIsSkippedOnTheEventAndCronPaths() {
    // The double-run guard: event at ~19:31 already screened today → the 19:50 fallback cron (and
    // a holiday's no-op-backfill event) must not re-run the screen + geometry fan-out.
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(day);
    when(screener.latestScreenDate()).thenReturn(day);

    new MinerviniScheduler(screener, repo, geometry, true).onBhavcopyBackfillCompleted();

    org.mockito.Mockito.verify(screener, org.mockito.Mockito.never()).screen(any());
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).upsertAll(any(), any());
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
            () -> new MinerviniScheduler(screener, repo, geometry, true))
        .run(
            ctx -> {
              ctx.getSourceApplicationContext()
                  .publishEvent(
                      new in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted("job"));
              verify(repo).upsertAll(eq(day), any());
            });
  }
}
