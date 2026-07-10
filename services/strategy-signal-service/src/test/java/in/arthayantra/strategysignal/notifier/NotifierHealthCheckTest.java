package in.arthayantra.strategysignal.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.notifier.NotificationRepository.DeliveryStats;
import in.arthayantra.strategysignal.notifier.NotifierHealthCheck.HealthReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * V15 notifier-health check (audit §8 V15): a daily failure-rate check over {@code
 * notification_events}. State transitions across the failure-rate threshold, the too-small-sample
 * guard, the SUPPRESSED-excluded denominator, and the alarm going out over BOTH channels.
 */
class NotifierHealthCheckTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-03T03:00:00Z"), ZoneOffset.UTC);

  private final NotificationRepository repo = mock(NotificationRepository.class);
  private final NotifierClient client = mock(NotifierClient.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private NotifierHealthCheck check(double threshold, long minAttempts) {
    return new NotifierHealthCheck(repo, client, CLOCK, meters, 24, minAttempts, threshold);
  }

  @Test
  void aHighFailureRateAlertsOverBothChannelsAndCounts() {
    when(repo.deliveryStats(any())).thenReturn(new DeliveryStats(1, 3, 0)); // 3/4 = 0.75
    when(client.configured(anyString())).thenReturn(true);

    check(0.5, 1).check();

    verify(client).send(eq("NTFY"), anyString(), anyString());
    verify(client).send(eq("TELEGRAM"), anyString(), anyString()); // the intentional-irony second channel
    assertThat(meters.counter("ay_notifier_delivery_health_alert_total").count()).isEqualTo(1.0);
  }

  @Test
  void aHealthyRateIsSilent() {
    when(repo.deliveryStats(any())).thenReturn(new DeliveryStats(19, 1, 5)); // 1/20 = 0.05

    check(0.5, 1).check();

    verify(client, never()).send(anyString(), anyString(), anyString());
    assertThat(meters.counter("ay_notifier_delivery_health_alert_total").count()).isZero();
  }

  @Test
  void tooFewDeliveriesNeverAlarms() {
    when(repo.deliveryStats(any())).thenReturn(new DeliveryStats(0, 0, 3)); // only flood-control suppressions

    check(0.5, 1).check();

    verify(client, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  void onlyAConfiguredChannelReceivesTheAlarm() {
    when(repo.deliveryStats(any())).thenReturn(new DeliveryStats(0, 2, 0)); // 2/2 = 1.0
    when(client.configured("NTFY")).thenReturn(true);
    when(client.configured("TELEGRAM")).thenReturn(false);

    check(0.5, 1).check();

    verify(client).send(eq("NTFY"), anyString(), anyString());
    verify(client, never()).send(eq("TELEGRAM"), anyString(), anyString());
  }

  @Test
  void evaluateExcludesSuppressedFromTheDenominator() {
    when(repo.deliveryStats(any())).thenReturn(new DeliveryStats(2, 2, 100)); // 2/(2+2) = 0.5

    HealthReport report = check(0.5, 1).evaluate(Instant.parse("2026-02-02T03:00:00Z"));

    assertThat(report.attempts()).isEqualTo(4L);
    assertThat(report.failed()).isEqualTo(2L);
    assertThat(report.failureRate()).isEqualTo(0.5);
    assertThat(report.healthy()).isFalse(); // rate == threshold ⇒ not (rate < threshold) ⇒ unhealthy
  }
}
