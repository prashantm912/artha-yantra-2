package in.arthayantra.strategysignal.manas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SwingBatchRunRepository.Pass;
import in.arthayantra.strategysignal.swing.SwingBatchIntentRepository;
import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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

  /**
   * ⚠️ Required, not decoration. The recorder is a MOCK, so an unstubbed {@code Optional} return
   * defaults to EMPTY — which is the holiday branch. Without this every test in the file would
   * exercise the skip path while appearing to test the settle.
   */
  @BeforeEach
  void theClockIsOnATradingDay() {
    when(recorder.scheduledSettleSession()).thenReturn(Optional.of(SESSION));
  }

  private void run() {
    new ManasAroraSwingScheduler(recorder, doctrine, intents, settleTime).run();
  }

  @Test
  void recordsIntentThenSettlesExitsOnly() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);

    run();

    verify(intents).recordSettled("manas-arora", SESSION, true);
    verify(recorder).runScheduled(doctrine, Pass.SETTLE);
  }

  @Test
  void neverRunsWithEntriesEnabled() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);

    run();

    verify(recorder, never()).runScheduled(any(), eq(Pass.ENTRIES));
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

    verify(recorder).runScheduled(doctrine, Pass.SETTLE);
  }

  /**
   * A weekday NSE holiday must leave NO trace: no intent row, no run, no marker.
   *
   * <p>⚠️ The guard sits BEFORE the intent write, and that ordering is the whole point. An intent
   * row for a day the batch never marks is precisely the shape {@code SwingBatchCanary} pages
   * "DID NOT RUN" for — and its own comment notes that a past session can never acquire a run
   * marker, so the page repeats to its ceiling about a session that never traded. Found in
   * cross-vendor review of this PR (2026-08-20); the first cut resolved the holiday back to the
   * previous trading day instead, which additionally overwrote that session's real counters.
   */
  @Test
  void aNonTradingDayLeavesNoIntentAndRunsNothing() {
    when(recorder.scheduledSettleSession()).thenReturn(Optional.empty());

    run();

    verify(intents, never()).recordSettled(any(), any(), anyBoolean());
    verify(recorder, never()).runScheduled(any(), any());
    verify(recorder, never()).runScheduled(any());
  }

}
