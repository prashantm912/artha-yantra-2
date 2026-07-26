package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The detector's two sweeps: sessions the scheduler fired for, and sessions it never fired for at
 * all. The second is the container-down case and is the one the predecessor was blind to.
 */
class SwingBatchCanaryTest {

  // Monday 2026-07-06 08:30 IST — the last NSE trading day before it is Friday 2026-07-03.
  private static final Clock MONDAY_0830 =
      Clock.fixed(Instant.parse("2026-07-06T03:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);
  private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 3);

  private final SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
  private final SwingBatchIntentRepository intents = mock(SwingBatchIntentRepository.class);
  private final SwingMissedBatchAlertRepository alerts =
      mock(SwingMissedBatchAlertRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

  /** Default: neither family has an intent row anywhere and the bounded sweep finds nothing. */
  @BeforeEach
  void quietByDefault() {
    when(intents.claimableMissedSessionsBefore(any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(intents.find(any(), any())).thenReturn(Optional.empty());
    when(intents.lastKnownArmedOnOrBefore(any(), any())).thenReturn(Optional.empty());
  }

  private SwingBatchCanary canary() {
    return new SwingBatchCanary(runs, intents, alerts, events, MONDAY_0830);
  }

  private void claimSucceeds(int pages) {
    when(alerts.claim(eq("minervini"), eq(FRIDAY), anyInt()))
        .thenReturn(Optional.of(new SwingMissedBatchAlertRepository.Claim(pages)));
  }

  @Test
  void anArmedSessionWithNoSuccessfulRunIsReported() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), eq(MONDAY), anyInt(), anyInt()))
        .thenReturn(List.of(FRIDAY));
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(false);
    claimSucceeds(1);

    canary().check();

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
  }

  @Test
  void aSessionThatRanSuccessfullyIsNotReported() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), eq(MONDAY), anyInt(), anyInt()))
        .thenReturn(List.of(FRIDAY));
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(true);

    canary().check();

    verify(events, never()).publishEvent(any());
    verify(alerts, never()).claim(any(), any(), anyInt());
  }

  /**
   * The container-down case, and the whole reason the intent table is not the only oracle. The
   * scheduler never fired, so there is no intent row for Friday — but the family was last known
   * ARMED, so the miss is real and must page.
   */
  @Test
  void aSessionTheSchedulerNeverRecordedStillPagesWhenTheFamilyWasLastKnownArmed() {
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.empty());
    when(intents.lastKnownArmedOnOrBefore("minervini", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(false);
    claimSucceeds(1);

    canary().check();

    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    SwingBatchAlert alert = (SwingBatchAlert) published.getValue();
    assertThat(alert.message())
        .as("the page names the likely cause so the reader is not left guessing")
        .contains("never recorded that session at all", "probably down");
  }

  @Test
  void aSessionWithNoIntentIsSilentWhenTheFamilyWasLastKnownDisarmed() {
    when(intents.find(eq("minervini"), any())).thenReturn(Optional.empty());
    when(intents.lastKnownArmedOnOrBefore(eq("minervini"), any())).thenReturn(Optional.of(false));

    canary().check();

    verify(events, never()).publishEvent(any());
    verify(alerts, never()).claim(any(), any(), anyInt());
  }

  /** Fail closed: a fresh deploy has no intent anywhere, so it must not page for every session. */
  @Test
  void aFreshDeployWithNoIntentHistoryAtAllIsSilent() {
    canary().check();

    verify(events, never()).publishEvent(any());
    verify(alerts, never()).claim(any(), any(), anyInt());
  }

  /**
   * A session that stays missing must page again on the next sweep — the alert is published to an
   * async listener that swallows failures, so one page is never proof of delivery.
   */
  @Test
  void aPersistentGapPagesAgainOnceTheLeaseExpires() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), eq(MONDAY), anyInt(), anyInt()))
        .thenReturn(List.of(FRIDAY));
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(false);
    when(alerts.claim(eq("minervini"), eq(FRIDAY), anyInt()))
        .thenReturn(
            Optional.of(new SwingMissedBatchAlertRepository.Claim(1)),
            Optional.empty(),
            Optional.of(new SwingMissedBatchAlertRepository.Claim(2)));

    SwingBatchCanary canary = canary();
    canary.check(); // pages
    canary.check(); // inside the lease — suppressed
    canary.check(); // lease expired — pages again

    verify(events, times(2)).publishEvent(any(SwingBatchAlert.class));
  }

  /** A repository failure must not take the other family's sweep down with it. */
  @Test
  void aFailureCheckingOneBatchDoesNotStopTheOther() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), any(), anyInt(), anyInt()))
        .thenThrow(new IllegalStateException("intent ledger unreachable"));
    when(intents.claimableMissedSessionsBefore(eq("manas-arora"), eq(MONDAY), anyInt(), anyInt()))
        .thenReturn(List.of(FRIDAY));
    when(intents.find("manas-arora", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false);
    when(alerts.claim(eq("manas-arora"), eq(FRIDAY), anyInt()))
        .thenReturn(Optional.of(new SwingMissedBatchAlertRepository.Claim(1)));

    canary().check();

    verify(events).publishEvent(any(SwingBatchAlert.class));
  }
}
