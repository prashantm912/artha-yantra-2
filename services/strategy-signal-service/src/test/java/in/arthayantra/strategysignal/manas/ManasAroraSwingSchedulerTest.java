package in.arthayantra.strategysignal.manas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.swing.SwingBatchIntentRepository;
import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The Manas swing scheduler is now a thin shell — it just delegates to the shared {@link
 * SwingBatchRecorder#runScheduled} with its doctrine (the recorder owns the marker + the FAILED-alert
 * envelope, covered by {@code SwingBatchRecorderTest}).
 */
class ManasAroraSwingSchedulerTest {

  @Test
  void delegatesToTheRecorderWithTheManasDoctrine() {
    SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
    ManasDoctrine doctrine = mock(ManasDoctrine.class);
    SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);

    new ManasAroraSwingScheduler(
            recorder,
            doctrine,
            intents,
            Clock.fixed(Instant.parse("2026-07-20T15:00:00Z"), ZoneOffset.UTC))
        .run();

    verify(intents).recordScheduled("manas-arora", LocalDate.of(2026, 7, 20), true);
    verify(recorder).runScheduled(doctrine);
  }

  /**
   * The detector's bookkeeping must never cost a real batch run. If the intent ledger is unreachable
   * the scheduler warns and carries on — the batch is the load-bearing work, the intent row is only
   * how we notice later that it did not happen.
   */
  @Test
  void aFailedIntentWriteStillRunsTheBatch() {
    SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
    ManasDoctrine doctrine = mock(ManasDoctrine.class);
    SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);
    doThrow(new IllegalStateException("intent ledger unreachable"))
        .when(intents)
        .recordScheduled(eq("manas-arora"), any(), anyBoolean());

    new ManasAroraSwingScheduler(
            recorder,
            doctrine,
            intents,
            Clock.fixed(Instant.parse("2026-07-20T15:00:00Z"), ZoneOffset.UTC))
        .run();

    verify(recorder).runScheduled(doctrine);
  }
}
