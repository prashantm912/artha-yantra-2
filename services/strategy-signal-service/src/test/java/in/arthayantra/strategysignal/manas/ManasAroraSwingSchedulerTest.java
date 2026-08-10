package in.arthayantra.strategysignal.manas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.swing.SwingBatchIntentRepository;
import in.arthayantra.strategysignal.swing.SwingBatchRecorder;
import in.arthayantra.strategysignal.swing.SwingDoctrine;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The Manas swing scheduler polls the evening window with the same contract as its Minervini twin —
 * intent on every tick, entries gated on this session's screen, exits unconditional at the deadline.
 * See {@code MinerviniSwingSchedulerTest} for why both halves are load-bearing.
 */
class ManasAroraSwingSchedulerTest {

  private static final LocalDate SESSION = LocalDate.of(2026, 7, 20);

  private final SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
  private final ManasDoctrine doctrine = mock(ManasDoctrine.class);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);

  // 18:15 IST — inside the poll window, before the 18:45 entry deadline.
  private final Clock beforeDeadline =
      Clock.fixed(Instant.parse("2026-07-20T12:45:00Z"), ZoneOffset.UTC);

  // 20:30 IST — past the deadline.
  private final Clock pastDeadline =
      Clock.fixed(Instant.parse("2026-07-20T15:00:00Z"), ZoneOffset.UTC);

  private void run(Clock clock) {
    new ManasAroraSwingScheduler(recorder, doctrine, intents, clock).run();
  }

  private void screenFor(LocalDate screenDate) {
    when(doctrine.candidateSnapshot())
        .thenReturn(
            new SwingDoctrine.CandidateSnapshotRead(
                Optional.of(new SwingDoctrine.CandidateSnapshot(screenDate, List.of()))));
  }

  @Test
  void recordsIntentThenRunsWithEntriesEnabledOnceThisSessionsScreenHasLanded() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);
    screenFor(SESSION);

    run(beforeDeadline);

    verify(intents).recordScheduled("manas-arora", SESSION, true);
    verify(recorder).runAndRecord(eq(doctrine), isNull(), eq(true), any(), any());
  }

  /**
   * The detector's bookkeeping must never cost a real batch run. If the intent ledger is unreachable
   * the scheduler warns and carries on — the batch is the load-bearing work, the intent row is only
   * how we notice later that it did not happen.
   */
  @Test
  void aFailedIntentWriteStillRunsTheBatch() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);
    screenFor(SESSION);
    doThrow(new IllegalStateException("intent ledger unreachable"))
        .when(intents)
        .recordScheduled(eq("manas-arora"), any(), anyBoolean());

    run(beforeDeadline);

    verify(recorder).runAndRecord(eq(doctrine), isNull(), eq(true), any(), any());
  }

  @Test
  void aStaleScreenBeforeTheDeadlineDefersButStillRecordsIntent() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);
    screenFor(SESSION.minusDays(1));

    run(beforeDeadline);

    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any());
    verify(intents).recordScheduled("manas-arora", SESSION, true);
  }

  @Test
  void theDeadlineRunsExitsWithEntriesSuppressed() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(doctrine.enabled()).thenReturn(true);
    screenFor(SESSION.minusDays(1));

    run(pastDeadline);

    verify(recorder).runAndRecord(eq(doctrine), isNull(), eq(false), any(), any());
  }

  @Test
  void aSessionAlreadyRunIsNotRunAgainByALaterPoll() {
    when(doctrine.batchName()).thenReturn("manas-arora");
    when(intents.hasRunFor("manas-arora", SESSION)).thenReturn(true);

    run(pastDeadline);

    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any());
  }
}
