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

  @Test
  void runScheduledPublishesAFailedAlertWhenTheBatchThrows() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(doctrine, null, true)).thenThrow(new IllegalStateException("funnel unreachable"));

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
    when(engine.runDaily(doctrine, null, true)).thenThrow(new IllegalStateException("boom"));
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
    when(engine.runDaily(doctrine, null, true))
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
    when(engine.runDaily(doctrine, null, true))
        .thenReturn(new SwingBatchEngine.SwingRun(0, 0, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty()));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(
            engine, mock(SwingBatchRunRepository.class), mock(SwingSellDecisionService.class),
            mock(FlagSnapshotService.class), new SwingRunMutex(), mock(ApplicationEventPublisher.class),
            Clock.systemUTC());

    recorder.runScheduled(doctrine);

    verify(doctrine, never()).candidateSnapshot();
    verify(engine).runDaily(doctrine, null, true);
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
    when(engine.runDaily(doctrine, null, true))
        .thenReturn(new SwingBatchEngine.SwingRun(3, 12, 6, 1, 0, probe));

    new SwingBatchRecorder(
            engine, runs, sellDecisions, mock(FlagSnapshotService.class), new SwingRunMutex(), events, clock)
        .runAndRecord(doctrine);

    verify(runs)
        .record(
            "manas-arora", LocalDate.of(2026, 7, 12), 3, 12, 6, 1, 0, 5, 8, 6, 2, true, dropped);
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
    when(engine.runDaily(doctrine, null, true))
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
            anyInt(), anyInt(), anyInt(), anyBoolean(), any());
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
            anyInt(), anyInt(), anyBoolean(), any()))
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
        anyInt(), anyBoolean(), any());
  }
}
