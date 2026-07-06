package in.arthayantra.strategysignal.manas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Pins the audit-H11 gap on the Manas swing scheduler: the batch delegates to {@link
 * ManasSwingRunRecorder}, and — the load-bearing part (P0-4/H10) — a thrown batch is turned into a
 * FAILED {@link SwingBatchAlert} rather than dying as a lone log line, while a further failure in
 * the alert publish is swallowed (the cron must never propagate).
 */
class ManasAroraSwingSchedulerTest {

  @Test
  void delegatesToTheRecorderAndDoesNotAlertOnASuccessfulRun() {
    ManasSwingRunRecorder recorder = mock(ManasSwingRunRecorder.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(recorder.runAndRecord())
        .thenReturn(new ManasAroraSwingEngine.ManasSwingRun(3, 12, 2, 1, 0));

    new ManasAroraSwingScheduler(recorder, events).run();

    verify(recorder).runAndRecord();
    verify(events, never()).publishEvent(any());
  }

  @Test
  void publishesAFailedAlertWhenTheBatchThrows() {
    ManasSwingRunRecorder recorder = mock(ManasSwingRunRecorder.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(recorder.runAndRecord()).thenThrow(new IllegalStateException("funnel unreachable"));

    new ManasAroraSwingScheduler(recorder, events).run();

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(captor.capture());
    assertThat(captor.getValue()).isInstanceOf(SwingBatchAlert.class);
    SwingBatchAlert alert = (SwingBatchAlert) captor.getValue();
    assertThat(alert.batch()).isEqualTo("manas-arora");
    assertThat(alert.title()).contains("FAILED");
    assertThat(alert.message()).contains("funnel unreachable");
  }

  @Test
  void swallowsAFailureInTheAlertPublishSoTheCronNeverPropagates() {
    ManasSwingRunRecorder recorder = mock(ManasSwingRunRecorder.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(recorder.runAndRecord()).thenThrow(new IllegalStateException("boom"));
    doThrow(new RuntimeException("event bus down")).when(events).publishEvent(any());

    assertThatCode(() -> new ManasAroraSwingScheduler(recorder, events).run())
        .doesNotThrowAnyException();
  }
}
