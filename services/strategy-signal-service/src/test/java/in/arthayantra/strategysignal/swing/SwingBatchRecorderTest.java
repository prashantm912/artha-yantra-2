package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import java.time.Clock;
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
    when(d.alertLabel()).thenReturn("Manas swing");
    return d;
  }

  @Test
  void runScheduledPublishesAFailedAlertWhenTheBatchThrows() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(doctrine)).thenThrow(new IllegalStateException("funnel unreachable"));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(engine, mock(SwingBatchRunRepository.class), events, Clock.systemUTC());
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
    when(engine.runDaily(doctrine)).thenThrow(new IllegalStateException("boom"));
    doThrow(new RuntimeException("event bus down")).when(events).publishEvent(any());

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(engine, mock(SwingBatchRunRepository.class), events, Clock.systemUTC());

    assertThatCode(() -> recorder.runScheduled(doctrine)).doesNotThrowAnyException();
  }

  @Test
  void runScheduledDoesNotPublishAFailedAlertOnASuccessfulRun() {
    SwingBatchEngine engine = mock(SwingBatchEngine.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingDoctrine doctrine = manasDoctrine();
    when(engine.runDaily(doctrine)).thenReturn(new SwingBatchEngine.SwingRun(3, 12, 2, 1, 0));

    SwingBatchRecorder recorder =
        new SwingBatchRecorder(engine, mock(SwingBatchRunRepository.class), events, Clock.systemUTC());
    recorder.runScheduled(doctrine);

    // the "done" summary alert may fire, but never a FAILED one
    verify(events, never())
        .publishEvent(
            org.mockito.ArgumentMatchers.argThat(
                (Object e) -> e instanceof SwingBatchAlert s && s.title().contains("FAILED")));
  }
}
