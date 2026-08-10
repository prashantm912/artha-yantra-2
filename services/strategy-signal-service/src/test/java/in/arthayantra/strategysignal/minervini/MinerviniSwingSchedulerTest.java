package in.arthayantra.strategysignal.minervini;

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
 * The Minervini swing scheduler polls the evening window, gates ENTRIES on the funnel serving THIS
 * session's screen, and freezes its effective arming for the missed-batch detector on the way.
 *
 * <p>Two properties here are load-bearing and easy to break by "fixing" a test:
 *
 * <ol>
 *   <li><b>Intent is recorded on EVERY tick, including one that declines to run.</b> The detector's
 *       population is "armed intent with no {@code swing_batch_runs} row", so an evening where the
 *       screen never landed must still leave an intent row or the detector goes blind to exactly the
 *       miss it exists for. The run guard is the RUN row, never the intent row.
 *   <li><b>The deadline runs the batch even with a stale screen — with entries suppressed.</b> A
 *       held stop must be evaluated; you can decline to ENTER, never to LEAVE.
 * </ol>
 */
class MinerviniSwingSchedulerTest {

  private static final LocalDate SESSION = LocalDate.of(2026, 7, 20);

  private final SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
  private final MinerviniDoctrine doctrine = mock(MinerviniDoctrine.class);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);

  // 2026-07-20T15:00Z is 20:30 IST — PAST the 18:45 entry deadline, on the same IST session.
  private final Clock pastDeadline =
      Clock.fixed(Instant.parse("2026-07-20T15:00:00Z"), ZoneOffset.UTC);

  // 2026-07-20T12:45Z is 18:15 IST — inside the poll window, BEFORE the deadline.
  private final Clock beforeDeadline =
      Clock.fixed(Instant.parse("2026-07-20T12:45:00Z"), ZoneOffset.UTC);

  private void run(Clock clock) {
    new MinerviniSwingScheduler(recorder, doctrine, intents, clock).run();
  }

  private void screenFor(LocalDate screenDate) {
    when(doctrine.candidateSnapshot())
        .thenReturn(
            new SwingDoctrine.CandidateSnapshotRead(
                screenDate == null
                    ? Optional.empty()
                    : Optional.of(new SwingDoctrine.CandidateSnapshot(screenDate, List.of()))));
  }

  @Test
  void recordsTheEffectiveArmingForTheIstSessionThenRunsWithEntriesEnabled() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(true);
    screenFor(SESSION); // this session's screen has landed

    run(beforeDeadline);

    verify(intents).recordScheduled("minervini", SESSION, true);
    verify(recorder)
        .runAndRecord(eq(doctrine), isNull(), eq(true), any(), any());
  }

  /**
   * A DISARMED evening must still record intent — that row is what later tells the detector this
   * session was deliberately skipped rather than missed.
   */
  @Test
  void aDisarmedEveningStillRecordsIntentSoTheSessionIsNotLaterReadAsAMiss() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(false);
    screenFor(SESSION);

    run(beforeDeadline);

    verify(intents).recordScheduled("minervini", SESSION, false);
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
    screenFor(SESSION);
    doThrow(new IllegalStateException("intent ledger unreachable"))
        .when(intents)
        .recordScheduled(eq("minervini"), any(), anyBoolean());

    run(beforeDeadline);

    verify(recorder).runAndRecord(eq(doctrine), isNull(), eq(true), any(), any());
  }

  @Test
  void aStaleScreenBeforeTheDeadlineDefersRatherThanEnteringOffYesterdaysNames() {
    // The funnel SILENTLY serves the latest persisted screen, so running here would enter off the
    // PREVIOUS session's names with no error anywhere. A later poll picks it up.
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(true);
    screenFor(SESSION.minusDays(1));

    run(beforeDeadline);

    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any());
    // …but the intent row is still written, or the detector cannot tell this from a dead container.
    verify(intents).recordScheduled("minervini", SESSION, true);
  }

  @Test
  void theDeadlineRunsWithEntriesSUPPRESSEDRatherThanSkippingExits() {
    // The half that makes the gate safe to add: a screen that never lands must NOT mean stops go
    // unevaluated overnight. Exits run; entries do not.
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(true);
    screenFor(SESSION.minusDays(1));

    run(pastDeadline);

    verify(recorder).runAndRecord(eq(doctrine), isNull(), eq(false), any(), any());
  }

  @Test
  void aSessionAlreadyRunIsNotRunAgainByALaterPoll() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(intents.hasRunFor("minervini", SESSION)).thenReturn(true);

    run(pastDeadline);

    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any());
  }
}
