package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.DroppedCandidate;
import in.arthayantra.strategysignal.signals.FlagSnapshotService;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The shared run path (audit P0-4/H10): {@link SwingBatchRecorder#runScheduled} turns a thrown batch
 * into a FAILED {@link SwingBatchAlert} rather than a lone log line, and swallows a further failure in
 * the alert publish so the cron never propagates. A successful run alerts only via {@code
 * runAndRecord}'s summary path, not a FAILED one.
 */
class SwingBatchRecorderTest {

  private static SwingDoctrine manasDoctrine() {
    SwingDoctrine d = mock(SwingDoctrine.class);
    when(d.enabled()).thenReturn(true);
    when(d.batchName()).thenReturn("manas-arora");
    when(d.book()).thenReturn("manas-arora");
    when(d.alertLabel()).thenReturn("Manas swing");
    when(d.pyramid()).thenReturn(PyramidPolicy.NONE);
    return d;
  }

  /**
   * {@code MarkerPolicy.NEVER} against the REAL recorder: no {@code swing_batch_runs} write at all,
   * and {@code markerRecorded=false}.
   *
   * <p>⚠️ The catch-up suite only proves NEVER was PASSED — it mocks this class, so it cannot prove
   * the suppression happens. Cross-vendor review flagged exactly that gap (2026-08-11), and it
   * matters more than a usual coverage note: NEVER exists so a recovery pass on an UNKNOWN arming
   * cannot write the marker that the next sweep would read as proof of settle-time arming. If the
   * suppression silently stopped working, the catch-up would resume manufacturing its own
   * authorisation and every test above would still pass.
   */
  @Test
  void markerPolicyNeverWritesNoRunMarkerAtAll() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
    SwingSellDecisionService sellDecisions = mock(SwingSellDecisionService.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), anyBoolean(), any(), anyBoolean()))
        .thenReturn(
            new SwingBatchEngine.SwingRun(
                1, 0, 0, 2, 0, SwingBatchEngine.AdmissionProbe.empty()));

    SwingBatchRecorder.RunOutcome outcome =
        new SwingBatchRecorder(
                engine, runs, sellDecisions, mock(FlagSnapshotService.class), new SwingRunMutex(),
                events, Clock.systemUTC())
            .runAndRecord(
                doctrine,
                LocalDate.of(2026, 7, 17),
                false,
                SwingBatchRecorder.MarkerPolicy.NEVER,
                Optional.of(
                    new SwingDoctrine.CandidateSnapshot(LocalDate.of(2026, 7, 17), List.of())));

    assertThat(outcome.markerRecorded())
        .as("NEVER must report the marker as NOT recorded, whatever the run did")
        .isFalse();
    verify(runs, never())
        .record(
            any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyBoolean(), any(), anyBoolean());
  }

  /**
   * ⚠️ An entries-disabled run finished its EXITS, not the batch — and saying otherwise contradicts
   * the coordinator.
   *
   * <p>Cross-vendor review Major. Every successful entries-disabled run published "<family> batch
   * done"; moments later {@code SwingBatchCatchUp} publishes EXITS ONLY and leaves the session
   * retryable, precisely because it is NOT done. Two alerts, opposite claims, same run — and the
   * operator believes the first one they read.
   *
   * <p>It was invisible to the coordinator's own tests because those MOCK this recorder, so the
   * contradiction only existed in production. That is the gap this test closes, and it is why the
   * assertion lives here rather than there.
   */
  @Test
  void anEntriesDisabledRunAnnouncesAnExitsPassNotACompletedBatch() {
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    runOnce(events, false);

    ArgumentCaptor<SwingBatchAlert> captor = ArgumentCaptor.forClass(SwingBatchAlert.class);
    verify(events).publishEvent(captor.capture());
    assertThat(captor.getValue().title())
        .as("the 16:00 settle and a screen-mismatched catch-up both run exits only")
        .contains("exits pass complete")
        .doesNotContain("batch done");
  }

  /** The other half: a real both-passes run must still say what it always said. */
  @Test
  void anEntriesEnabledRunStillAnnouncesTheBatchDone() {
    // ⚠️ Without this the fix could have relabelled EVERY run and the test above would still pass.
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    runOnce(events, true);

    ArgumentCaptor<SwingBatchAlert> captor = ArgumentCaptor.forClass(SwingBatchAlert.class);
    verify(events).publishEvent(captor.capture());
    assertThat(captor.getValue().title()).contains("batch done");
  }

  /** A clean run — no refusals, no skipped exits — with entries on or off. */
  private static void runOnce(ApplicationEventPublisher events, boolean entriesEnabled) {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), anyBoolean(), any(), anyBoolean()))
        .thenReturn(
            new SwingBatchEngine.SwingRun(
                1, 0, entriesEnabled ? 1 : 0, 2, 0, SwingBatchEngine.AdmissionProbe.empty()));
    new SwingBatchRecorder(
            engine,
            mock(SwingBatchRunRepository.class),
            mock(SwingSellDecisionService.class),
            mock(FlagSnapshotService.class),
            new SwingRunMutex(),
            events,
            Clock.systemUTC())
        .runAndRecord(
            doctrine,
            LocalDate.of(2026, 7, 17),
            entriesEnabled,
            SwingBatchRecorder.MarkerPolicy.ON_COMPLETE,
            Optional.of(new SwingDoctrine.CandidateSnapshot(LocalDate.of(2026, 7, 17), List.of())));
  }

  /** 18:52 IST on 2026-08-19, a normal Wednesday session. */
  private static Clock at(int y, int m, int d) {
    return Clock.fixed(
        java.time.OffsetDateTime.of(y, m, d, 18, 52, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))
            .toInstant(),
        ZoneOffset.UTC);
  }

  private static SwingBatchRecorder recorderWith(SwingBatchEngine engine, Clock clock) {
    return new SwingBatchRecorder(
        engine, mock(SwingBatchRunRepository.class), mock(SwingSellDecisionService.class),
        mock(FlagSnapshotService.class), new SwingRunMutex(), mock(ApplicationEventPublisher.class),
        clock);
  }

  private static LocalDate sessionPassedTo(SwingBatchEngine engine, SwingDoctrine doctrine) {
    ArgumentCaptor<LocalDate> session = ArgumentCaptor.forClass(LocalDate.class);
    verify(engine).runDaily(eq(doctrine), session.capture(), anyBoolean());
    return session.getValue();
  }

  /**
   * Ledger H27, and the whole point of the change: the evening settle must PIN the session it is
   * settling, so a series that does not reach that session is dropped rather than evaluated.
   *
   * <p>⚠️ This asserts the pin, NOT its consequence. The consequence — a pinned session with no bar
   * becomes {@code exitSkipped} + a STOP-NOT-EVALUATED error instead of a silent settle off the
   * previous session — is the engine's, and is already pinned by {@code
   * MinerviniSwingEngineTest.catchUpRefusesToSettleOffABarFromTheWrongSession}. What had no test at
   * all, and is what regressed in #1333, is that the SCHEDULED path reaches that guard: it passed a
   * literal {@code null}, which means "settle off whatever the newest bar happens to be", and 69 of
   * 417 persisted minervini rows were computed on an earlier session's bar as a result.
   */
  @Test
  void theEveningSettlePinsTheSessionItIsSettling() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), anyBoolean()))
        .thenReturn(new SwingBatchEngine.SwingRun(1, 0, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty()));

    recorderWith(engine, at(2026, 8, 19)).runScheduled(doctrine, false);

    assertThat(sessionPassedTo(engine, doctrine))
        .as("a null session means 'settle off the newest bar, whatever day it is' — the H27 defect")
        .isEqualTo(LocalDate.of(2026, 8, 19));
  }

  /**
   * The crons are {@code MON-FRI}, which includes NSE holidays. Pinning the holiday itself would
   * find no bar for any holding and turn every settle on ~12 days a year into a full sweep of
   * STOP-NOT-EVALUATED errors — an alert that fires predictably is one nobody reads. So the pin
   * resolves to the latest TRADING day. 2026-05-28 is Bakri Id, a Thursday; 2026-05-27 is the
   * Wednesday before it and is itself a session.
   */
  @Test
  void aWeekdayHolidayPinsTheLastRealSessionRatherThanTheHoliday() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), anyBoolean()))
        .thenReturn(new SwingBatchEngine.SwingRun(1, 0, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty()));

    recorderWith(engine, at(2026, 5, 28)).runScheduled(doctrine, false);

    assertThat(sessionPassedTo(engine, doctrine)).isEqualTo(LocalDate.of(2026, 5, 27));
  }

  @Test
  void runScheduledPublishesAFailedAlertWhenTheBatchThrows() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), eq(true))).thenThrow(new IllegalStateException("funnel unreachable"));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, mock(SwingBatchRunRepository.class), mock(SwingSellDecisionService.class),
            mock(FlagSnapshotService.class), new SwingRunMutex(), events, Clock.systemUTC());
    recorder.runScheduled(doctrine);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(captor.capture());
    assertThat(captor.getValue()).isInstanceOf(SwingBatchAlert.class);
    SwingBatchAlert alert = (SwingBatchAlert) captor.getValue();
    assertThat(alert.batch()).isEqualTo("manas-arora");
    assertThat(alert.title()).contains("FAILED");
    assertThat(alert.message()).contains("funnel unreachable");
  }

  @Test
  void runScheduledSwallowsAFailureInTheAlertPublishSoTheCronNeverPropagates() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), eq(true))).thenThrow(new IllegalStateException("boom"));
    doThrow(new RuntimeException("event bus down")).when(events).publishEvent(any());

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, mock(SwingBatchRunRepository.class), mock(SwingSellDecisionService.class),
            mock(FlagSnapshotService.class), new SwingRunMutex(), events, Clock.systemUTC());

    assertThatCode(() -> recorder.runScheduled(doctrine)).doesNotThrowAnyException();
  }

  @Test
  void runScheduledDoesNotPublishAFailedAlertOnASuccessfulRun() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), eq(true)))
        .thenReturn(
            new SwingBatchEngine.SwingRun(3, 12, 2, 1, 0, SwingBatchEngine.AdmissionProbe.empty()));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, mock(SwingBatchRunRepository.class), mock(SwingSellDecisionService.class),
            mock(FlagSnapshotService.class), new SwingRunMutex(), events, Clock.systemUTC());
    recorder.runScheduled(doctrine);

    // the "done" summary alert may fire, but never a FAILED one
    verify(events, never())
        .publishEvent(
            org.mockito.ArgumentMatchers.argThat(
                (Object e) -> e instanceof SwingBatchAlert s && s.title().contains("FAILED")));
  }

  @Test
  void disarmedScheduledRunRecordsNoMarkerAndDoesNotReadTheFunnel() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(doctrine.enabled()).thenReturn(false);
    when(engine.runDaily(eq(doctrine), any(), eq(true)))
        .thenReturn(new SwingBatchEngine.SwingRun(0, 0, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty()));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, mock(SwingBatchRunRepository.class), mock(SwingSellDecisionService.class),
            mock(FlagSnapshotService.class), new SwingRunMutex(), mock(ApplicationEventPublisher.class),
            Clock.systemUTC());

    recorder.runScheduled(doctrine);

    verify(doctrine, never()).candidateSnapshot();
    verify(engine).runDaily(eq(doctrine), any(), eq(true));
  }

  @Test
  void runAndRecordForwardsTheAdmissionProbeToTheMarkerAndPersistsSellDecisions() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
    SwingSellDecisionService sellDecisions = mock(SwingSellDecisionService.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    // A fixed clock (04:00Z = 09:30 IST) pins the run date the recorder stamps.
    Clock clock = Clock.fixed(Instant.parse("2026-07-12T04:00:00Z"), ZoneOffset.UTC);
    List<DroppedCandidate> dropped = List.of(new DroppedCandidate("ZEEL", 9));
    SwingBatchEngine.AdmissionProbe probe =
        new SwingBatchEngine.AdmissionProbe(5, 8, 6, 2, true, dropped);
    when(engine.runDaily(eq(doctrine), any(), eq(true)))
        .thenReturn(new SwingBatchEngine.SwingRun(3, 12, 6, 1, 0, probe));

    new SwingBatchRecorder(
            engine, runs, sellDecisions, mock(FlagSnapshotService.class), new SwingRunMutex(), events, clock)
        .runAndRecord(doctrine);

    verify(runs)
        .record(
            "manas-arora", LocalDate.of(2026, 7, 12), 3, 12, 6, 1, 0, 5, 8, 6, 2, true, dropped,
            true);
    // The batch also persists the sell-decision snapshot for the family it ran.
    verify(sellDecisions).persist(doctrine);
  }

  @Test
  void runAndRecordSwallowsASellDecisionPersistFailureSoTheBatchNeverPropagates() {
    // The batch is the swing positions' ONLY exit evaluator; a persist defect must not break the run.
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
    SwingSellDecisionService sellDecisions = mock(SwingSellDecisionService.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    Clock clock = Clock.fixed(Instant.parse("2026-07-12T04:00:00Z"), ZoneOffset.UTC);
    when(engine.runDaily(eq(doctrine), any(), eq(true)))
        .thenReturn(
            new SwingBatchEngine.SwingRun(3, 12, 2, 1, 0, SwingBatchEngine.AdmissionProbe.empty()));
    when(sellDecisions.persist(doctrine)).thenThrow(new RuntimeException("sell-decision store down"));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, runs, sellDecisions, mock(FlagSnapshotService.class), new SwingRunMutex(), events, clock);

    assertThatCode(() -> recorder.runAndRecord(doctrine)).doesNotThrowAnyException();
    // The run marker still records despite the persist failure (fail-soft is per-collaborator).
    verify(runs)
        .record(
            eq("manas-arora"), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyInt(), anyBoolean(), any(), anyBoolean());
  }

  @Test
  void failedFunnelSnapshotCannotCompleteButAValidEmptyScreenCan() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
    SwingDoctrine doctrine = manasDoctrine();
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingBatchEngine.SwingRun result =
        new SwingBatchEngine.SwingRun(1, 0, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty());
    when(engine.runDaily(
            eq(doctrine), eq(LocalDate.of(2026, 7, 17)), eq(true),
            org.mockito.ArgumentMatchers.<Optional<SwingDoctrine.CandidateSnapshot>>any(),
            anyBoolean()))
        .thenReturn(result);
    when(runs.record(
            any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt(), anyInt(), anyBoolean(), any(), anyBoolean()))
        .thenReturn(true);

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, runs, mock(SwingSellDecisionService.class), mock(FlagSnapshotService.class),
            new SwingRunMutex(), events, Clock.systemUTC());
    SwingBatchRecorder.RunOutcome failed =
        recorder.runAndRecord(
            doctrine, LocalDate.of(2026, 7, 17), true, SwingBatchRecorder.MarkerPolicy.ON_COMPLETE,
            Optional.empty());
    SwingBatchRecorder.RunOutcome emptyScreen =
        recorder.runAndRecord(
            doctrine, LocalDate.of(2026, 7, 17), true, SwingBatchRecorder.MarkerPolicy.ON_COMPLETE,
            Optional.of(new SwingDoctrine.CandidateSnapshot(LocalDate.of(2026, 7, 17), List.of())));

    assertThat(failed.markerRecorded()).isFalse();
    assertThat(emptyScreen.markerRecorded()).isTrue();
    verify(runs).record(
        any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
        anyInt(), anyBoolean(), any(), anyBoolean());
  }

  @Test
  void aCapBoundRunSpellsOutTheAdmissionProbeInTheSummaryAlert() {
    // "139 candidates, 0 entries" alone reads like a dead batch; the probe numbers are what say the
    // funnel was full and the slot cap took none of it. An unbound run's text stays as it was.
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), eq(true)))
        .thenReturn(
            new SwingBatchEngine.SwingRun(
                4, 139, 0, 0, 0,
                new SwingBatchEngine.AdmissionProbe(15, 17, 0, 17, true, List.of())))
        .thenReturn(
            new SwingBatchEngine.SwingRun(4, 139, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty()));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, mock(SwingBatchRunRepository.class), mock(SwingSellDecisionService.class),
            mock(FlagSnapshotService.class), new SwingRunMutex(), events, Clock.systemUTC());
    recorder.runAndRecord(doctrine);
    recorder.runAndRecord(doctrine);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
    assertThat(((SwingBatchAlert) captor.getAllValues().get(0)).message())
        .isEqualTo(
            "139 candidates, 0 entries, 0 exits, 0 exit-skipped (4 strategies)"
                + " — entry governor bound: 17 would-enter, 0 admitted, 17 dropped");
    assertThat(((SwingBatchAlert) captor.getAllValues().get(1)).message())
        .as("an unbound run's summary is unchanged")
        .isEqualTo("139 candidates, 0 entries, 0 exits, 0 exit-skipped (4 strategies)");
  }

  @Test
  void aRefusedRunDoesNotRecordTheCanonicalCompletionMarker() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(eq(doctrine), any(), eq(true)))
        .thenReturn(
            new SwingBatchEngine.SwingRun(
                1, 2, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty(),
                List.of("MIXED_PRE_POST_LOTS:TESTCO")));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, runs, mock(SwingSellDecisionService.class), mock(FlagSnapshotService.class),
            new SwingRunMutex(), mock(ApplicationEventPublisher.class), Clock.systemUTC());

    SwingBatchRecorder.RunOutcome outcome =
        recorder.runAndRecord(doctrine, null, true, SwingBatchRecorder.MarkerPolicy.ALWAYS);

    assertThat(outcome.markerRecorded()).isFalse();
    org.mockito.Mockito.verifyNoInteractions(runs);
  }
}
