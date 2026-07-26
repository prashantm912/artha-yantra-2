package in.arthayantra.strategysignal.minervini;

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
 * The Minervini swing scheduler delegates to the shared {@link SwingBatchRecorder#runScheduled} and,
 * on the way, freezes its effective arming for the missed-batch detector.
 */
class MinerviniSwingSchedulerTest {

  private final SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
  private final MinerviniDoctrine doctrine = mock(MinerviniDoctrine.class);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);

  // 2026-07-20T15:00Z is 20:30 IST — after the 20:00 cron, on the same IST session.
  private final Clock evening =
      Clock.fixed(Instant.parse("2026-07-20T15:00:00Z"), ZoneOffset.UTC);

  private void run() {
    new MinerviniSwingScheduler(recorder, doctrine, intents, evening).run();
  }

  @Test
  void recordsTheEffectiveArmingForTheIstSessionThenDelegatesToTheRecorder() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(true);

    run();

    verify(intents).recordScheduled("minervini", LocalDate.of(2026, 7, 20), true);
    verify(recorder).runScheduled(doctrine);
  }

  /**
   * A DISARMED evening must still record intent — that row is what later tells the detector this
   * session was deliberately skipped rather than missed.
   */
  @Test
  void aDisarmedEveningStillRecordsIntentSoTheSessionIsNotLaterReadAsAMiss() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(false);

    run();

    verify(intents).recordScheduled("minervini", LocalDate.of(2026, 7, 20), false);
    verify(recorder).runScheduled(doctrine);
  }

  /**
   * The detector's bookkeeping must never cost a real batch run. If the intent ledger is unreachable
   * the scheduler warns and carries on — the batch is the load-bearing work, the intent row is only
   * how we notice later that it did not happen.
   */
  @Test
  void aFailedIntentWriteStillRunsTheBatch() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(true);
    doThrow(new IllegalStateException("intent ledger unreachable"))
        .when(intents)
        .recordScheduled(eq("minervini"), any(), anyBoolean());

    run();

    verify(recorder).runScheduled(doctrine);
  }
}
