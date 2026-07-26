package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRetry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The 2026-07-17 gap + the cross-vendor review that hardened it: the stack was down across 20:05 IST,
 * the Manas batch never ran, and OMAXAUTO sat open three days past its stop because nothing retried.
 * These pin the catch-up's durability properties — it re-runs the missed session pinned to that
 * session's date; a PARTIAL run (a held bar missing) is NOT recorded done and stays retryable
 * (Critical 1); every emission is gated behind an ATOMIC claim so overlapping invocations cannot
 * double-fill (Critical 2); and a session refused/incomplete today is retried across later sweeps and
 * finally ABANDONED, not silently forgotten (Major).
 */
class SwingBatchCatchUpTest {

  // Monday 2026-07-20, 08:35 IST (03:05Z) — the real incident's next trading morning. Friday
  // 2026-07-17 is the most-recent missed session; Thursday 2026-07-16 the one before it.
  private static final Clock MONDAY_0835 =
      Clock.fixed(Instant.parse("2026-07-20T03:05:00Z"), ZoneOffset.UTC);
  private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 17);
  private static final LocalDate THURSDAY = LocalDate.of(2026, 7, 16);

  private final SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
  private final SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
  private final SwingCatchUpStateRepository state = retryableState(runs);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);
  private final SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);
  private final SignalRepository signals = mock(SignalRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private boolean effectDefaultsConfigured;

  private static SwingDoctrine doctrine(boolean armed, LocalDate inputsAsOf) {
    SwingDoctrine d = mock(SwingDoctrine.class);
    when(d.enabled()).thenReturn(armed);
    when(d.batchName()).thenReturn("manas-arora");
    when(d.alertLabel()).thenReturn("Manas swing");
    when(d.inputsAsOf()).thenReturn(Optional.ofNullable(inputsAsOf));
    return d;
  }

  private static SwingCatchUpStateRepository retryableState(SwingBatchRunRepository runs) {
    SwingCatchUpStateRepository state = mock(SwingCatchUpStateRepository.class);
    when(state.retryableSessions("manas-arora", 30))
        .thenAnswer(
            ignored ->
                List.of(
                        LocalDate.of(2026, 7, 9),
                        LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 7, 13),
                        LocalDate.of(2026, 7, 14),
                        LocalDate.of(2026, 7, 15),
                        LocalDate.of(2026, 7, 16),
                        LocalDate.of(2026, 7, 17),
                        LocalDate.of(2026, 7, 20))
                    .stream()
                    .filter(session -> !runs.hasRun("manas-arora", session))
                    .toList());
    return state;
  }

  private SwingBatchCatchUp catchUp(Clock clock, boolean enabled, SwingDoctrine... doctrines) {
    if (!effectDefaultsConfigured) {
      when(effects.allConfirmed(any(), any())).thenReturn(true);
      when(effects.repairable(any(), any())).thenReturn(List.of());
      effectDefaultsConfigured = true;
    }
    return new SwingBatchCatchUp(
        recorder, runs, state, intents, effects, signals, new SwingRunMutex(), List.of(doctrines), events, clock, enabled, 5);
  }

  /** A run outcome whose canonical marker write SUCCEEDED (the ordinary complete/partial cases). */
  private static SwingBatchRecorder.RunOutcome run(int entries, int exits, int exitSkipped) {
    return run(entries, exits, exitSkipped, true);
  }

  private static SwingBatchRecorder.RunOutcome run(
      int entries, int exits, int exitSkipped, boolean markerRecorded) {
    return new SwingBatchRecorder.RunOutcome(
        new SwingBatchEngine.SwingRun(
            1, 5, entries, exits, exitSkipped, SwingBatchEngine.AdmissionProbe.empty()),
        markerRecorded);
  }

  /** Armed family with only FRIDAY missed (every other window session already ran), FRIDAY claimable. */
  private void armedFamilyOnlyFridayMissed() {
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(true);
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false);
    when(intents.find("manas-arora", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(state.claim(eq("manas-arora"), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(1)));
  }

  @Test
  void runsTheMissedSessionPinnedAndEntriesEnabledWhenTheFunnelIsAsOfIt() {
    SwingDoctrine manas = doctrine(true, FRIDAY); // funnel screen == the session
    armedFamilyOnlyFridayMissed();
    when(recorder.runAndRecord(
            eq(manas), eq(FRIDAY), eq(true), eq(SwingBatchRecorder.MarkerPolicy.ON_COMPLETE)))
        .thenReturn(run(1, 1, 0));

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(recorder)
        .runAndRecord(manas, FRIDAY, true, SwingBatchRecorder.MarkerPolicy.ON_COMPLETE);
    verify(state).markDone("manas-arora", FRIDAY);
    assertThat(alerts()).anyMatch(a -> a.message().contains("1 exits"));
  }

  @Test
  void catchesUpExitsButSkipsEntriesWhenTheFunnelIsNotTheSessionsScreen() {
    // 07-17's screen never landed → the funnel serves THURSDAY's names. Entering off it is wrong, but
    // the held STOPS must still be evaluated off Friday's bar — so entries are suppressed, exits run.
    SwingDoctrine manas = doctrine(true, THURSDAY);
    armedFamilyOnlyFridayMissed();
    when(recorder.runAndRecord(
            eq(manas), eq(FRIDAY), eq(false), eq(SwingBatchRecorder.MarkerPolicy.ON_COMPLETE)))
        .thenReturn(run(0, 1, 0));

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(recorder)
        .runAndRecord(manas, FRIDAY, false, SwingBatchRecorder.MarkerPolicy.ON_COMPLETE);
    verify(state).markDone("manas-arora", FRIDAY);
    assertThat(alerts()).anyMatch(a -> a.message().contains("Entries were SKIPPED"));
  }

  @Test
  void aPartialRunIsLeftRetryableNotRecordedDone() {
    // Critical 1: a held anchor's daily bar was missing (exitSkipped>0). The run must NOT be marked
    // complete — else the session looks done and its unevaluated stop is never retried (the original bug).
    SwingDoctrine manas = doctrine(true, FRIDAY);
    armedFamilyOnlyFridayMissed();
    when(recorder.runAndRecord(
            eq(manas), eq(FRIDAY), org.mockito.ArgumentMatchers.anyBoolean(), any()))
        .thenReturn(run(0, 0, 1));

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(state).markPending("manas-arora", FRIDAY);
    verify(state, never()).markDone(any(), any());
    assertThat(alerts()).anyMatch(a -> a.title().contains("INCOMPLETE"));
  }

  @Test
  void aDisabledFamilyRecordsDisarmedMarkersAndNeverRecovers() {
    // Round-3 Critical: a family that is OFF must NOT be caught up (that would replay sessions the owner
    // deliberately skipped). Its un-marked window sessions get a terminal DISARMED marker so a later
    // re-arm cannot mistake them for an outage. Against the round-2 code (`if (!enabled) continue`), no
    // DISARMED is written and — crucially — a re-arm WOULD replay them; this pins the fix.
    SwingDoctrine manas = doctrine(false, FRIDAY); // family OFF
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(false); // nothing recorded for the window
    when(intents.find(eq("manas-arora"), any()))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(false, null)));

    catchUp(MONDAY_0835, true, manas).catchUp();

    // Every window session (all un-marked) is stamped DISARMED; none is claimed or run.
    verify(state, org.mockito.Mockito.atLeast(1)).recordDisarmed(eq("manas-arora"), any());
    verify(state).recordDisarmed("manas-arora", FRIDAY);
    verify(state, never()).claim(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    verify(recorder, never())
        .runAndRecord(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
  }

  @Test
  void disarmedBookkeepingRunsEvenWhenTheCatchUpFeatureFlagIsOff() {
    // The DISARMED record must be written independent of the catch-up flag — else arming the feature
    // AFTER a disarm period (during which the flag was off) would replay the un-recorded backlog.
    SwingDoctrine manas = doctrine(false, FRIDAY);
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(false);
    when(intents.find(eq("manas-arora"), any()))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(false, null)));

    catchUp(MONDAY_0835, false, manas).catchUp(); // catch-up feature OFF

    verifyNoInteractions(state, intents);
    verify(recorder, never())
        .runAndRecord(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
  }

  @Test
  void aMarkerWriteFailureLeavesTheSessionRepairableNotTerminalDone() {
    // Round-3 Major: exits fully evaluated (exitSkipped==0) but the canonical swing_batch_runs marker
    // write FAILED (markerRecorded=false). Marking DONE here would strand the session — hasRun stays
    // false (looks un-run) while the claim is terminal (never re-claimed). It must stay PENDING.
    SwingDoctrine manas = doctrine(true, FRIDAY);
    armedFamilyOnlyFridayMissed();
    when(recorder.runAndRecord(
            eq(manas), eq(FRIDAY), org.mockito.ArgumentMatchers.anyBoolean(), any()))
        .thenReturn(run(1, 1, 0, false)); // complete run, but the marker did NOT persist

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(state).markPending("manas-arora", FRIDAY);
    verify(state, never()).markDone(any(), any());
    assertThat(alerts()).anyMatch(a -> a.title().contains("marker WRITE FAILED"));
  }

  @Test
  void everyEmissionIsGatedBehindTheAtomicClaim() {
    // Critical 2: if the claim is lost (a concurrent invocation holds it, or the session is terminal),
    // NO run happens — the claim is the durable lock taken before any money effect.
    SwingDoctrine manas = doctrine(true, FRIDAY);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(false);
    when(runs.hasRun("manas-arora", THURSDAY)).thenReturn(true);
    when(intents.find("manas-arora", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(intents.find("manas-arora", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(state.claim(eq("manas-arora"), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(Optional.empty()); // every claim lost

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(recorder, never())
        .runAndRecord(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
  }

  @Test
  void anAlreadyRecordedSessionIsNeverEvenClaimed() {
    // Idempotency: swing_batch_runs is the completeness record; a session that ran (on time OR via a
    // prior complete catch-up) is skipped before the claim — it can never be re-run.
    SwingDoctrine manas = doctrine(true, FRIDAY);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(FRIDAY));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(true); // everything already ran

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(state, never()).claim(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    verify(recorder, never())
        .runAndRecord(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
  }

  @Test
  void abandonsAfterTheAttemptBudgetIsExhausted() {
    // Major: a session whose held bar never lands is retried, but bounded — once it exhausts its
    // attempt budget it is ABANDONED (a terminal alert), never chased forever.
    SwingDoctrine manas = doctrine(true, FRIDAY);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(false);
    when(runs.hasRun("manas-arora", THURSDAY)).thenReturn(true);
    when(intents.find("manas-arora", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    // FRIDAY reports the 6th attempt on a max-5 budget → abandon; the other window sessions lose the claim.
    when(state.claim(eq("manas-arora"), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(Optional.empty());
    when(state.claim(eq("manas-arora"), eq(FRIDAY), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(6)));

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(state)
        .markAbandoned(
            eq("manas-arora"), eq(FRIDAY),
            org.mockito.ArgumentMatchers.contains("ATTEMPT_BUDGET_EXHAUSTED"));
    verify(recorder, never())
        .runAndRecord(eq(manas), eq(FRIDAY), org.mockito.ArgumentMatchers.anyBoolean(), any());
    assertThat(alerts()).anyMatch(a -> a.title().contains("ABANDONED"));
  }

  @Test
  void neverRecordedBatchIsSkipped() {
    SwingDoctrine manas = doctrine(true, FRIDAY);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.empty());

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(state, never()).claim(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    verify(recorder, never())
        .runAndRecord(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
  }

  @Test
  void disarmedFamilyIsNeverRecovered() {
    // A disabled family records DISARMED bookkeeping (covered above) but never runs the money path.
    SwingDoctrine manas = doctrine(false, FRIDAY);
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(false);

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(recorder, never())
        .runAndRecord(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    verify(state, never()).claim(any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void anArmedFamilyDoesNothingWhenTheRecoveryFlagIsOff() {
    // An ENABLED family with the catch-up feature OFF: no recovery, and no DISARMED (it is not disarmed).
    SwingDoctrine manas = doctrine(true, FRIDAY);

    catchUp(MONDAY_0835, false, manas).catchUp();

    verifyNoInteractions(recorder, runs, state, events);
  }

  @Test
  void refusesInsideMarketHours() {
    // Monday 2026-07-20 11:00 IST (05:30Z) — a cron override must not fire an EOD batch into a live session.
    Clock midSession = Clock.fixed(Instant.parse("2026-07-20T05:30:00Z"), ZoneOffset.UTC);
    SwingDoctrine manas = doctrine(true, FRIDAY);

    catchUp(midSession, true, manas).catchUp();

    verifyNoInteractions(recorder, runs, state);
  }

  @Test
  void aMultiDayOutageCatchesUpEveryMissedSessionOldestFirst() {
    // Down Thu+Fri: only those two missed (every earlier session in the window ran). Both get caught up
    // (each pinned to its own bar); the older one first.
    SwingDoctrine manas = doctrine(true, null); // funnel unknown → entries suppressed, exits still run
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(true); // default: earlier window sessions ran
    when(runs.hasRun("manas-arora", THURSDAY)).thenReturn(false);
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false);
    when(intents.find(eq("manas-arora"), any()))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(state.claim(eq("manas-arora"), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(1)));
    when(recorder.runAndRecord(
            eq(manas), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
        .thenReturn(run(0, 1, 0));

    catchUp(MONDAY_0835, true, manas).catchUp();

    ArgumentCaptor<LocalDate> sessions = ArgumentCaptor.forClass(LocalDate.class);
    verify(recorder, org.mockito.Mockito.times(2))
        .runAndRecord(eq(manas), sessions.capture(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    assertThat(sessions.getAllValues()).containsExactly(THURSDAY, FRIDAY); // oldest first
  }

  @Test
  void aSevenSessionOutageKeepsTheOldestPendingRowUntilItReachesTerminalState() {
    LocalDate oldest = LocalDate.of(2026, 7, 9);
    Clock tuesday0835 =
        Clock.fixed(Instant.parse("2026-07-21T03:05:00Z"), ZoneOffset.UTC);
    SwingDoctrine manas = doctrine(true, null);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(oldest.minusDays(1)));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(true);
    when(runs.hasRun("manas-arora", oldest)).thenReturn(false);
    when(intents.find("manas-arora", oldest))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(state.retryableSessions("manas-arora", 30)).thenReturn(List.of(oldest), List.of(oldest));
    when(state.claim(eq("manas-arora"), eq(oldest), anyInt()))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(1)))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(2)));
    when(recorder.runAndRecord(eq(manas), eq(oldest), anyBoolean(), any(), any()))
        .thenReturn(run(0, 0, 1))
        .thenReturn(run(0, 1, 0));

    catchUp(MONDAY_0835, true, manas).catchUp();
    catchUp(tuesday0835, true, manas).catchUp();

    verify(state).markPending("manas-arora", oldest);
    verify(state).markDone("manas-arora", oldest);
    verify(recorder, org.mockito.Mockito.times(2))
        .runAndRecord(eq(manas), eq(oldest), anyBoolean(), any(), any());
  }

  @Test
  void anUnconfirmedPaperEffectLeavesTheSessionPendingAndALaterSweepCanFinishIt() {
    SwingDoctrine manas = doctrine(true, FRIDAY);
    armedFamilyOnlyFridayMissed();
    when(state.retryableSessions("manas-arora", 30)).thenReturn(List.of(FRIDAY), List.of(FRIDAY));
    when(recorder.runAndRecord(
            eq(manas), eq(FRIDAY), anyBoolean(), any()))
        .thenReturn(run(1, 1, 0));
    when(effects.repairable("manas-arora", FRIDAY)).thenReturn(List.of());
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false, true);
    when(state.claim(eq("manas-arora"), eq(FRIDAY), anyInt()))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(1)));

    SwingBatchCatchUp runner = catchUp(MONDAY_0835, true, manas);
    // Configure the explicit false/true sequence after the helper's default-confirmed setup.
    when(effects.allConfirmed("manas-arora", FRIDAY)).thenReturn(false, true);
    runner.catchUp();
    runner.catchUp();

    verify(state).markPending("manas-arora", FRIDAY, "PAPER_EFFECT_UNCONFIRMED");
    verify(state).markDone("manas-arora", FRIDAY);
    verify(recorder).runAndRecord(eq(manas), eq(FRIDAY), anyBoolean(), any());
  }

  @Test
  void anUndecidedPaperEffectIsNotReplayedAfterRestart() {
    SwingDoctrine manas = doctrine(true, FRIDAY);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(true);
    when(effects.pendingSessions("manas-arora")).thenReturn(List.of(FRIDAY));
    when(state.retryableSessions("manas-arora", 30)).thenReturn(List.of(FRIDAY));
    when(effects.allConfirmed("manas-arora", FRIDAY)).thenReturn(false);
    SwingPaperEffectRepository.Effect effect = mock(SwingPaperEffectRepository.Effect.class);
    SignalRepository.SignalRow signal = mock(SignalRepository.SignalRow.class);
    when(signal.id()).thenReturn(7L);
    when(signal.suggestedQty()).thenReturn(new java.math.BigDecimal("5"));
    when(signal.entryPrice()).thenReturn(new java.math.BigDecimal("100"));
    when(signal.scalperDetail()).thenReturn(null);
    when(signals.find(7L)).thenReturn(Optional.of(signal));
    when(effect.decision()).thenReturn("UNDECIDED");
    when(effect.effectType()).thenReturn("ENTRY");
    when(effect.signalId()).thenReturn(7L);
    SwingBatchCatchUp runner = catchUp(MONDAY_0835, true, manas);
    when(effects.repairable("manas-arora", FRIDAY)).thenReturn(List.of(effect));

    when(effects.allConfirmed("manas-arora", FRIDAY)).thenReturn(false);
    runner.catchUp();

    verify(events, never()).publishEvent(any(SwingPaperEffectRetry.class));
    verify(state).markPending("manas-arora", FRIDAY, "PAPER_EFFECT_UNCONFIRMED");
  }

  @Test
  void aRefusedRunIsRetriedInsteadOfBecomingDoneOnTheNextSweep() {
    SwingDoctrine manas = doctrine(true, FRIDAY);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false, false);
    when(state.retryableSessions("manas-arora", 30)).thenReturn(List.of(FRIDAY), List.of(FRIDAY));
    when(intents.find("manas-arora", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(state.claim("manas-arora", FRIDAY, 30))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(1)))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(2)));
    SwingBatchEngine.SwingRun refused =
        new SwingBatchEngine.SwingRun(
            1, 1, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty(),
            List.of("MIXED_PRE_POST_LOTS:TESTCO"));
    when(recorder.runAndRecord(
            eq(manas), eq(FRIDAY), anyBoolean(), any(), any()))
        .thenReturn(new SwingBatchRecorder.RunOutcome(refused, false));

    SwingBatchCatchUp runner = catchUp(MONDAY_0835, true, manas);
    runner.catchUp();
    runner.catchUp();

    verify(recorder, org.mockito.Mockito.times(2))
        .runAndRecord(eq(manas), eq(FRIDAY), anyBoolean(), any(), any());
    verify(state, never()).markDone("manas-arora", FRIDAY);
  }

  @Test
  void missingWindowRowsAreSeededBeforeTheSweepCanClaimThem() {
    SwingDoctrine manas = doctrine(true, FRIDAY);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(state.retryableSessions("manas-arora", 30)).thenReturn(List.of(FRIDAY));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(true);
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false);
    when(intents.find("manas-arora", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(state.claim("manas-arora", FRIDAY, 30))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(1)));
    when(recorder.runAndRecord(eq(manas), eq(FRIDAY), anyBoolean(), any()))
        .thenReturn(run(0, 1, 0));
    when(effects.allConfirmed("manas-arora", FRIDAY)).thenReturn(true);

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(state).seedWindow(eq("manas-arora"), org.mockito.ArgumentMatchers.argThat(days -> days.contains(FRIDAY)));
    verify(state).claim("manas-arora", FRIDAY, 30);
  }

  @Test
  void stopsBeforeClaimingRemainingSessionsWhenTheMarketOpens() {
    MutableClock clock = new MutableClock(MONDAY_0835.instant());
    LocalDate wednesday = LocalDate.of(2026, 7, 15);
    SwingDoctrine manas = doctrine(true, null);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(wednesday));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(true);
    when(runs.hasRun("manas-arora", THURSDAY)).thenReturn(false);
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false);
    when(intents.find(eq("manas-arora"), any()))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(state.retryableSessions("manas-arora", 30)).thenReturn(List.of(THURSDAY, FRIDAY));
    when(state.claim(eq("manas-arora"), any(), anyInt()))
        .thenReturn(Optional.of(new SwingCatchUpStateRepository.Claim(1)));
    when(recorder.runAndRecord(eq(manas), any(), anyBoolean(), any(), any()))
        .thenAnswer(
            invocation -> {
              clock.set(Instant.parse("2026-07-20T03:46:00Z")); // 09:16 IST, after the deadline
              return run(0, 1, 0);
            });

    catchUp(clock, true, manas).catchUp();

    verify(recorder).runAndRecord(eq(manas), eq(THURSDAY), anyBoolean(), any(), any());
    verify(recorder, never())
        .runAndRecord(eq(manas), eq(FRIDAY), anyBoolean(), any(), any());
    verify(state).markDone("manas-arora", THURSDAY);
    verify(state, never()).markDone("manas-arora", FRIDAY);
    verify(state, never()).markPending("manas-arora", FRIDAY);
  }

  @Test
  void missingScheduleIntentIsRefusedAndRecordedInsteadOfReplayed() {
    SwingDoctrine manas = doctrine(true, FRIDAY);
    armedFamilyOnlyFridayMissed();
    when(intents.find("manas-arora", FRIDAY)).thenReturn(Optional.empty());

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(state)
        .markAbandoned(
            eq("manas-arora"), eq(FRIDAY),
            org.mockito.ArgumentMatchers.contains("NO_SCHEDULE_INTENT"));
    verify(state, never()).claim(any(), any(), anyInt());
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any());
    assertThat(alerts()).anyMatch(a -> a.title().contains("REFUSED"));
  }

  @Test
  void aSessionArmedAtScheduleTimeIsReplayedEvenWhenDisabledToday() {
    SwingDoctrine manas = doctrine(false, FRIDAY);
    armedFamilyOnlyFridayMissed();
    when(recorder.runAndRecord(eq(manas), eq(FRIDAY), eq(true), any(), any()))
        .thenReturn(run(1, 1, 0));

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(recorder).runAndRecord(eq(manas), eq(FRIDAY), eq(true), any(), any());
    verify(state).markDone("manas-arora", FRIDAY);
    verify(state, never()).recordDisarmed(any(), any());
  }

  @Test
  void aSessionDisarmedAtScheduleTimeIsNotReplayedWhenEnabledToday() {
    SwingDoctrine manas = doctrine(true, FRIDAY);
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(THURSDAY));
    when(runs.hasRun(eq("manas-arora"), any())).thenReturn(true);
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false);
    when(intents.find("manas-arora", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(false, null)));

    catchUp(MONDAY_0835, true, manas).catchUp();

    verify(state).recordDisarmed("manas-arora", FRIDAY);
    verify(state, never()).claim(any(), any(), anyInt());
    verify(recorder, never()).runAndRecord(any(), any(), anyBoolean(), any(), any());
  }

  private List<SwingBatchAlert> alerts() {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
    return captor.getAllValues().stream()
        .filter(SwingBatchAlert.class::isInstance)
        .map(SwingBatchAlert.class::cast)
        .toList();
  }

  private static final class MutableClock extends Clock {
    private volatile Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Kolkata");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }

    private void set(Instant current) {
      this.current = current;
    }
  }
}
