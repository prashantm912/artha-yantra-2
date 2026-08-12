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
    when(intents.claimableMissedSessionsBefore(any(), any(), anyInt(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(intents.find(any(), any())).thenReturn(Optional.empty());
    when(intents.lastKnownArmedOnOrBefore(any(), any())).thenReturn(Optional.empty());
  }

  private SwingBatchCanary canary() {
    return canary(false);
  }

  private SwingBatchCanary canary(boolean catchupEnabled) {
    return new SwingBatchCanary(runs, intents, alerts, events, MONDAY_0830, catchupEnabled);
  }

  private void claimSucceeds(int pages) {
    when(alerts.claim(eq("minervini"), eq(FRIDAY), anyInt(), anyInt()))
        .thenReturn(Optional.of(new SwingMissedBatchAlertRepository.Claim(pages)));
  }

  @Test
  void theEntryWatchdogPagesWhenTheEntryPassNeverCompleted() {
    // The gap it exists for: the 08:30 sweep above checks hasRun, which the 16:00 exit pass already
    // satisfied, so a hung 08:35 ENTRY pass is invisible to it — and since the 2026-08-12 schedule
    // move that same hang also holds the 08:50 reconciler and 08:52 past-expiry recovery on the
    // shared single-thread lane. This is the only thing that pages for it.
    when(runs.hasRunWithEntries(any(), eq(FRIDAY))).thenReturn(false);
    when(runs.hasRun(any(), eq(FRIDAY))).thenReturn(true); // the 16:00 exit pass already stamped it

    canary(true).entryPassWatchdog();

    verify(events, times(2)).publishEvent(any(SwingBatchAlert.class));
  }

  @Test
  void theEntryWatchdogIsSilentOnceTheEntryPassHasCompleted() {
    // ⚠️ Without this the watchdog could page unconditionally and the test above would still pass —
    // a detector that always fires is as useless as one that never does, and noisier.
    when(runs.hasRunWithEntries(any(), eq(FRIDAY))).thenReturn(true);

    canary(true).entryPassWatchdog();

    verify(events, never()).publishEvent(any(SwingBatchAlert.class));
  }

  @Test
  void theEntryWatchdogIsInertWhileTheCatchUpIsDisarmed() {
    // With the flag off there is no 08:35 entry pass to complete, so an unconditional page would be
    // a nightly false alarm on a stack that is behaving exactly as configured.
    when(runs.hasRunWithEntries(any(), eq(FRIDAY))).thenReturn(false);

    canary(false).entryPassWatchdog();

    verify(events, never()).publishEvent(any(SwingBatchAlert.class));
  }

  @Test
  void anArmedSessionWithNoSuccessfulRunIsReported() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), eq(MONDAY), anyInt(), anyInt(), anyInt()))
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

  /**
   * With the catch-up ARMED the page must tell the truth about what the machine will do (cross-vendor
   * round 5): an automatic pinned replay is queued for 08:35 and stamps the marker — so the message
   * must warn AGAINST a manual run instead of recommending one, or the operator fires a duplicate
   * money run on top of the queued replay.
   */
  @Test
  void withCatchupArmedThePageWarnsAgainstAManualRunInsteadOfRecommendingOne() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), eq(MONDAY), anyInt(), anyInt(), anyInt()))
        .thenReturn(List.of(FRIDAY));
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(false);
    claimSucceeds(1);

    canary(true).check();

    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    SwingBatchAlert alert = (SwingBatchAlert) published.getValue();
    assertThat(alert.message())
        .contains("armed catch-up", "do NOT run POST", "swing_catchup_runs")
        .doesNotContain("No automatic replay was attempted");
  }

  @Test
  void aSessionThatRanSuccessfullyIsNotReported() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), eq(MONDAY), anyInt(), anyInt(), anyInt()))
        .thenReturn(List.of(FRIDAY));
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(true);

    canary().check();

    verify(events, never()).publishEvent(any());
    verify(alerts, never()).claim(any(), any(), anyInt(), anyInt());
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
    verify(alerts, never()).claim(any(), any(), anyInt(), anyInt());
  }

  /** Fail closed: a fresh deploy has no intent anywhere, so it must not page for every session. */
  @Test
  void aFreshDeployWithNoIntentHistoryAtAllIsSilent() {
    canary().check();

    verify(events, never()).publishEvent(any());
    verify(alerts, never()).claim(any(), any(), anyInt(), anyInt());
  }

  /**
   * A session that stays missing must page again on the next sweep — the alert is published to an
   * async listener that swallows failures, so one page is never proof of delivery.
   */
  @Test
  void aPersistentGapPagesAgainOnceTheLeaseExpires() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), eq(MONDAY), anyInt(), anyInt(), anyInt()))
        .thenReturn(List.of(FRIDAY));
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(false);
    when(alerts.claim(eq("minervini"), eq(FRIDAY), anyInt(), anyInt()))
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

  /**
   * The two sweeps must be independently fault-isolated, not just the two families: if the
   * scheduled-session query fails, the container-down sweep — the one that closes the blind spot —
   * still has to run.
   */
  @Test
  void aFailureInTheScheduledSweepStillLeavesTheContainerDownSweepRunning() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), any(), anyInt(), anyInt(), anyInt()))
        .thenThrow(new IllegalStateException("intent ledger unreachable"));
    when(intents.find("minervini", FRIDAY)).thenReturn(Optional.empty());
    when(intents.lastKnownArmedOnOrBefore("minervini", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("minervini", FRIDAY)).thenReturn(false);
    claimSucceeds(1);

    canary().check();

    verify(events).publishEvent(any(SwingBatchAlert.class));
  }

  /** A repository failure must not take the other family's sweep down with it. */
  @Test
  void aFailureCheckingOneBatchDoesNotStopTheOther() {
    when(intents.claimableMissedSessionsBefore(eq("minervini"), any(), anyInt(), anyInt(), anyInt()))
        .thenThrow(new IllegalStateException("intent ledger unreachable"));
    when(intents.claimableMissedSessionsBefore(eq("manas-arora"), eq(MONDAY), anyInt(), anyInt(), anyInt()))
        .thenReturn(List.of(FRIDAY));
    when(intents.find("manas-arora", FRIDAY)).thenReturn(Optional.of(true));
    when(runs.hasRun("manas-arora", FRIDAY)).thenReturn(false);
    when(alerts.claim(eq("manas-arora"), eq(FRIDAY), anyInt(), anyInt()))
        .thenReturn(Optional.of(new SwingMissedBatchAlertRepository.Claim(1)));

    canary().check();

    verify(events).publishEvent(any(SwingBatchAlert.class));
  }
}
