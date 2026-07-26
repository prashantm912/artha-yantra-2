package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

/** The missed-batch detector's fail-closed and one-page-per-episode contract. */
class SwingBatchCanaryTest {

  // Monday 2026-07-06 08:30 IST — the last NSE trading day before it is Friday 2026-07-03.
  private static final Clock MONDAY_0830 =
      Clock.fixed(Instant.parse("2026-07-06T03:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 3);

  private final SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);
  private final SwingMissedBatchAlertRepository state = mock(SwingMissedBatchAlertRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

  @Test
  void anArmedSessionWithNoSuccessfulRunIsReported() {
    when(intents.find("minervini", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(intents.find("manas-arora", FRIDAY)).thenReturn(Optional.empty());
    when(intents.claimableMissedSessionsBefore(
            "minervini", LocalDate.of(2026, 7, 6), 30, 64))
        .thenReturn(List.of(FRIDAY));
    when(intents.claimableMissedSessionsBefore(
            "manas-arora", LocalDate.of(2026, 7, 6), 30, 64))
        .thenReturn(List.of());
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(false);
    when(state.claim(eq("minervini"), eq(FRIDAY), anyInt()))
        .thenReturn(Optional.of(new SwingMissedBatchAlertRepository.Claim(1)));

    new SwingBatchCanary(runs, intents, state, events, MONDAY_0830).check();

    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    SwingBatchAlert alert = (SwingBatchAlert) published.getValue();
    assertThat(alert.batch()).isEqualTo("minervini");
    assertThat(alert.title()).contains("DID NOT RUN");
    assertThat(alert.message())
        .contains(
            FRIDAY.toString(),
            "No automatic replay was attempted",
            "POST /api/v1/signals/minervini-swing/run",
            "not a historical as-of replay");
    verify(state).markAbandoned("minervini", FRIDAY, "MISSED_BATCH_ALERTED");
  }

  @Test
  void aSessionThatRanSuccessfullyIsNotReported() {
    when(intents.find("minervini", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(intents.find("manas-arora", FRIDAY)).thenReturn(Optional.empty());
    when(intents.claimableMissedSessionsBefore(
            "minervini", LocalDate.of(2026, 7, 6), 30, 64))
        .thenReturn(List.of(FRIDAY));
    when(intents.claimableMissedSessionsBefore(
            "manas-arora", LocalDate.of(2026, 7, 6), 30, 64))
        .thenReturn(List.of());
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(true);

    new SwingBatchCanary(runs, intents, state, events, MONDAY_0830).check();

    verify(events, never()).publishEvent(any());
    verify(state, never()).claim(any(), any(), anyInt());
  }

  @Test
  void aSessionWithNoIntentIsNotReported() {
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.empty());
    when(intents.find("manas-arora", FRIDAY)).thenReturn(Optional.empty());
    when(intents.claimableMissedSessionsBefore(
            "minervini", LocalDate.of(2026, 7, 6), 30, 64))
        .thenReturn(List.of());
    when(intents.claimableMissedSessionsBefore(
            "manas-arora", LocalDate.of(2026, 7, 6), 30, 64))
        .thenReturn(List.of());

    new SwingBatchCanary(runs, intents, state, events, MONDAY_0830).check();

    verify(events, never()).publishEvent(any());
    verify(state, never()).claim(any(), any(), anyInt());
  }

  @Test
  void aPersistentGapPagesOncePerEpisode() {
    when(intents.find("minervini", FRIDAY))
        .thenReturn(Optional.of(new SwingBatchIntentRepository.Intent(true, null)));
    when(intents.find("manas-arora", FRIDAY)).thenReturn(Optional.empty());
    when(intents.claimableMissedSessionsBefore(
            "minervini", LocalDate.of(2026, 7, 6), 30, 64))
        .thenReturn(List.of(FRIDAY));
    when(intents.claimableMissedSessionsBefore(
            "manas-arora", LocalDate.of(2026, 7, 6), 30, 64))
        .thenReturn(List.of());
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(false);
    when(state.claim(eq("minervini"), eq(FRIDAY), anyInt()))
        .thenReturn(Optional.of(new SwingMissedBatchAlertRepository.Claim(1)), Optional.empty());

    SwingBatchCanary canary = new SwingBatchCanary(runs, intents, state, events, MONDAY_0830);
    canary.check();
    canary.check();

    verify(events).publishEvent(any(SwingBatchAlert.class));
    verify(state).markAbandoned("minervini", FRIDAY, "MISSED_BATCH_ALERTED");
  }
}
