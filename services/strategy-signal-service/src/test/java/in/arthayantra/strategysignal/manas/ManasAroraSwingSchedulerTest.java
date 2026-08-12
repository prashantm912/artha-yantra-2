package in.arthayantra.strategysignal.manas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * The Manas settle runs 16:02 IST on the same contract as its Minervini twin — exits only, intent
 * recorded every tick, the FAILED-alert envelope kept. See {@code MinerviniSwingSchedulerTest} for
 * why each half is load-bearing.
 */
class ManasAroraSwingSchedulerTest {

  private static final LocalDate SESSION = LocalDate.of(2026, 7, 20);

  private final SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
  private final ManasDoctrine doctrine = mock(ManasDoctrine.class);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);

  // 2026-07-20T10:32Z is 16:02 IST.
  private final Clock settleTime = Clock.fixed(Instant.parse("2026-07-20T10:32:00Z"), ZoneOffset.UTC);

  private void run() {
    new ManasAroraSwingScheduler(recorder, doctrine, intents, settleTime).run();
  }

  @Test
  void recordsIntentThenSettlesExitsOnly() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);

    run();

    verify(intents).recordSettled("manas-arora", SESSION, true);
    verify(recorder).runScheduled(doctrine, false);
  }

  @Test
  void neverRunsWithEntriesEnabled() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);

    run();

    verify(recorder, never()).runScheduled(any(), eq(true));
    verify(recorder, never()).runScheduled(any());
  }

  @Test
  void aDisarmedSessionStillRecordsIntent() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(false);

    run();

    verify(intents).recordSettled("manas-arora", SESSION, false);
  }

  @Test
  void aFailedIntentWriteStillSettlesTheBook() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);
    doThrow(new IllegalStateException("intent ledger unreachable"))
        .when(intents)
        .recordSettled(eq("manas-arora"), any(), anyBoolean());

    run();

    verify(recorder).runScheduled(doctrine, false);
  }
}
