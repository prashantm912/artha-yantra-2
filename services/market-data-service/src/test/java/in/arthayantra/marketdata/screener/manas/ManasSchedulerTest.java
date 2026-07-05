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

  @Test
  void bhavcopyCompletedEventRunsAndPersistsTheScreen() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(screener.screen(null))
        .thenReturn(new ManasScreenService.ScreenResult(day, 0, List.of()));

    new ManasScheduler(screener, repo, geometry).onBhavcopyBackfillCompleted();

    verify(repo).upsertAll(eq(day), any());
    verify(geometry).persistForPassers(eq(day), any());
  }
}
