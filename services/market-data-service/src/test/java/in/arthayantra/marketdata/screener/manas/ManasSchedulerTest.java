package in.arthayantra.marketdata.screener.manas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The screen/bhavcopy race regression (audit H1 2026-07-05), Manas twin: the screen's PRIMARY
 * trigger is the {@code BhavcopyBackfillCompleted} event (the 19:40 cron had only accidental
 * headroom over the 19:30 backfill).
 */
class ManasSchedulerTest {

  private final ManasScreenService screener = mock(ManasScreenService.class);
  private final ManasScreenRepository repo = mock(ManasScreenRepository.class);
  private final ManasGeometryService geometry = mock(ManasGeometryService.class);
  private final in.arthayantra.marketdata.alerts.NtfyClient ntfy =
      mock(in.arthayantra.marketdata.alerts.NtfyClient.class);
  private final in.arthayantra.marketdata.ingest.IngestRunLedger ledger =
      mock(in.arthayantra.marketdata.ingest.IngestRunLedger.class);

  @Test
  void bhavcopyCompletedEventRunsAndPersistsTheScreen() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new ManasScreenService.ScreenResult(day, 0, List.of()));

    new ManasScheduler(screener, repo, geometry, ntfy, ledger).onBhavcopyBackfillCompleted();

    verify(repo).replaceAll(eq(day), any());
    verify(geometry).persistForPassers(eq(day), any());
  }

  @Test
  void alreadyCurrentScreenIsSkippedOnTheEventAndCronPaths() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(day);
    when(screener.latestScreenDate()).thenReturn(day);

    new ManasScheduler(screener, repo, geometry, ntfy, ledger).onBhavcopyBackfillCompleted();

    org.mockito.Mockito.verify(screener, org.mockito.Mockito.never()).screen(any());
    org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).replaceAll(any(), any());
  }

  @Test
  void eventListenerWiringFiresTheScreenOnBhavcopyCompletion() {
    // Pins the @EventListener wiring itself (mirror of the Minervini twin's wiring test).
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new ManasScreenService.ScreenResult(day, 0, List.of()));

    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        // the class-level @ConditionalOnProperty is evaluated even for withBean registrations —
        // without the flag the bean (and its listener) is silently skipped
        .withPropertyValues("artha.manas-arora.screen.enabled=true")
        .withBean(ManasScheduler.class, () -> new ManasScheduler(screener, repo, geometry, ntfy, ledger))
        .run(
            ctx -> {
              ctx.getSourceApplicationContext()
                  .publishEvent(
                      new in.arthayantra.marketdata.bhavcopy.BhavcopyBackfillCompleted("job"));
              verify(repo).replaceAll(eq(day), any());
            });
  }
}
