package in.arthayantra.strategysignal.minervini;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The 16:00 IST settle: EXITS ONLY, entries deferred to the 08:35 catch-up (owner decision
 * 2026-08-10). Three properties here are load-bearing and each is easy to destroy by "simplifying"
 * the scheduler:
 *
 * <ol>
 *   <li><b>{@code entriesEnabled} is false, always.</b> The funnel silently serves the latest
 *       persisted screen, and at 16:00 today's has certainly not landed — NSE publishes the bhavcopy
 *       between 17:52 and 19:30+. A true here enters off the PREVIOUS session's names with no error
 *       anywhere.
 *   <li><b>The run goes through {@code runScheduled}</b>, which turns a thrown batch into a FAILED
 *       ops alert. A settle that dies as a lone log line means every held stop went unevaluated with
 *       nobody told.
 *   <li><b>Intent is recorded even when the write fails is survivable, but the batch is not.</b>
 *       The intent row is the detector's population AND the catch-up's precondition — the only two
 *       rows ever written to {@code swing_catchup_runs} are 2026-07-17, both ABANDONED for
 *       NO_SCHEDULE_INTENT — but losing it must never cost the settle.
 * </ol>
 */
class MinerviniSwingSchedulerTest {

  private static final LocalDate SESSION = LocalDate.of(2026, 7, 20);

  private final SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
  private final MinerviniDoctrine doctrine = mock(MinerviniDoctrine.class);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);

  // 2026-07-20T10:30Z is 16:00 IST — the settle's own wall clock.
  private final Clock settleTime = Clock.fixed(Instant.parse("2026-07-20T10:30:00Z"), ZoneOffset.UTC);

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
    new MinerviniSwingScheduler(recorder, doctrine, intents, settleTime).run();
  }

  @Test
  void recordsTheEffectiveArmingForTheIstSessionThenSettlesExitsOnly() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(true);

    run();

    verify(intents).recordSettled("minervini", SESSION, true);
    verify(recorder).runScheduled(doctrine, false);
  }

  /**
   * The entry pass must NEVER run from this scheduler. Pinned as its own assertion rather than left
   * implicit in the call above, because the argument is a bare boolean: a future edit that flips it
   * would read as a one-character change and would silently enter off yesterday's screen.
   */
  @Test
  void neverRunsWithEntriesEnabled() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(true);

    run();

    verify(recorder, never()).runScheduled(any(), eq(true));
    verify(recorder, never()).runScheduled(any());
  }

  /**
   * A DISARMED evening must still record intent — that row is what later tells the detector this
   * session was deliberately skipped rather than missed. Execution itself stays inert inside the
   * recorder, which gates on {@code doctrine.enabled()}.
   */
  @Test
  void aDisarmedSessionStillRecordsIntentSoItIsNotLaterReadAsAMiss() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(false);

    run();

    verify(intents).recordSettled("minervini", SESSION, false);
  }

  /**
   * The detector's bookkeeping must never cost a real settle. If the intent ledger is unreachable
   * the scheduler warns and carries on — the exit pass is the load-bearing work, the intent row is
   * only how we notice later that it did not happen.
   */
  @Test
  void aFailedIntentWriteStillSettlesTheBook() {
    when(doctrine.batchName()).thenReturn("minervini");
    when(doctrine.enabled()).thenReturn(true);
    doThrow(new IllegalStateException("intent ledger unreachable"))
        .when(intents)
        .recordSettled(eq("minervini"), any(), anyBoolean());

    run();

    verify(recorder).runScheduled(doctrine, false);
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
    verify(recorder, never()).runScheduled(any(), anyBoolean());
    verify(recorder, never()).runScheduled(any());
  }

}
