package in.arthayantra.strategysignal.signals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The did-not-run dead-man switch (audit P0-4/H10): a container down at 20:00 IST means the swing
 * batch silently never runs — the canary must alert next morning when an ARMED batch has no
 * {@code swing_batch_runs} row for the last NSE trading day.
 */
class SwingBatchCanaryTest {

  // Monday 2026-07-06 08:30 IST — the last NSE trading day before it is Friday 2026-07-03.
  private static final Clock MONDAY_0830 =
      Clock.fixed(Instant.parse("2026-07-06T03:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 3);

  private final SwingBatchRunRepository runs = mock(SwingBatchRunRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

  @Test
  void alertsWhenAnArmedBatchMissedTheLastTradingDay() {
    when(runs.lastRunDate("minervini")).thenReturn(Optional.of(FRIDAY.minusDays(1))); // Thursday
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(FRIDAY)); // ran fine

    new SwingBatchCanary(runs, events, MONDAY_0830, true, true).check();

    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    SwingBatchAlert alert = (SwingBatchAlert) published.getValue();
    org.assertj.core.api.Assertions.assertThat(alert.batch()).isEqualTo("minervini");
    org.assertj.core.api.Assertions.assertThat(alert.title()).contains("DID NOT RUN");
    org.assertj.core.api.Assertions.assertThat(alert.message()).contains(FRIDAY.toString());
  }

  @Test
  void staysQuietWhenEveryArmedBatchRanForTheLastTradingDay() {
    when(runs.lastRunDate("minervini")).thenReturn(Optional.of(FRIDAY));
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(FRIDAY));

    new SwingBatchCanary(runs, events, MONDAY_0830, true, true).check();

    verify(events, never()).publishEvent(any());
  }

  @Test
  void unarmedBatchesAreNeverChecked() {
    new SwingBatchCanary(runs, events, MONDAY_0830, false, false).check();

    verifyNoInteractions(runs, events);
  }

  @Test
  void staysQuietWhenABatchRanLATERThanExpected() {
    // A weekday-holiday run records against the (stale) last-trading-day BUT a batch could also
    // record a date >= expected (e.g. the batch already ran today's session before the 08:30
    // check on a same-day cron misconfig). isBefore — not !isEqual — keeps this quiet; a
    // regression to !isEqual would false-alarm every weekday-holiday morning.
    when(runs.lastRunDate("minervini")).thenReturn(Optional.of(FRIDAY.plusDays(3))); // Monday
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.of(FRIDAY));

    new SwingBatchCanary(runs, events, MONDAY_0830, true, true).check();

    verify(events, never()).publishEvent(any());
  }

  @Test
  void neverRecordedBatchIsSkippedNotAlerted() {
    // Fresh deploy: the marker table starts empty — a missing-run alert can only fire after the
    // first successful recording (else every fresh stack false-alarms on day one).
    when(runs.lastRunDate("minervini")).thenReturn(Optional.empty());
    when(runs.lastRunDate("manas-arora")).thenReturn(Optional.empty());

    new SwingBatchCanary(runs, events, MONDAY_0830, true, true).check();

    verify(events, never()).publishEvent(any());
  }
}
