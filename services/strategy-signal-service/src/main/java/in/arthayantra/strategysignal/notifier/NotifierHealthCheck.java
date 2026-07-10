package in.arthayantra.strategysignal.notifier;

import in.arthayantra.strategysignal.notifier.NotificationRepository.DeliveryStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V15 notifier delivery-health check (app-platform audit 2026-07-10 §8 V15). The notifier already
 * RECORDS every give-up as a {@code notification_events} FAILED row (after {@code retry-max-attempts}
 * retries), but NOTHING watched those rows — a silently dead push channel meant every signal alert
 * was dropped with no alarm. This is the daily failure-rate check the audit prescribes: once a day it
 * asks {@link NotificationRepository} how many deliveries failed in the trailing window and, when the
 * failure rate crosses the threshold, raises the alarm.
 *
 * <p>The irony is intentional (audit §8 V15): the alarm goes out over BOTH channels — ntfy AND
 * Telegram — so a failure that is specific to the ntfy path cannot swallow its own alert. Each push
 * is best-effort and unconfigured channels are skipped (mock/dev have none).
 *
 * <p>SUPPRESSED rows are flood control, not delivery failures, so they are excluded from the
 * denominator; the rate is {@code failed / (sent + failed)}. Below {@code min-attempts} deliveries in
 * the window the sample is too small to judge (a quiet, signal-free day never false-alarms).
 *
 * <p>Modulith-clean: this lives INSIDE the notifier module and touches only {@link
 * NotificationRepository} + {@link NotifierClient} — never {@code signals}/{@code paper}, and never
 * the {@code SignalEngine} (so it loads safely in engine-disabled paper ITs). A missed cron while the
 * stack is down never fires later (batch-liveness trap); this reports on a window, it is not a
 * dead-man switch.
 */
@Component
public class NotifierHealthCheck {

  /** The trailing-window delivery-health verdict. */
  public record HealthReport(
      Instant since, long attempts, long failed, double failureRate, boolean healthy) {}

  private static final Logger log = LoggerFactory.getLogger(NotifierHealthCheck.class);

  private final NotificationRepository repo;
  private final NotifierClient client;
  private final Clock clock;
  private final Counter alertCounter;
  private final long windowHours;
  private final long minAttempts;
  private final double failureRateThreshold;

  /** Wires the audit repo, the outbound client, the clock and the tunable window/threshold. */
  public NotifierHealthCheck(
      NotificationRepository repo,
      NotifierClient client,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${artha.notifier.health.window-hours:24}") long windowHours,
      @Value("${artha.notifier.health.min-attempts:1}") long minAttempts,
      @Value("${artha.notifier.health.failure-rate-threshold:0.5}") double failureRateThreshold) {
    this.repo = repo;
    this.client = client;
    this.clock = clock;
    this.alertCounter = meterRegistry.counter("ay_notifier_delivery_health_alert_total");
    this.windowHours = windowHours;
    this.minAttempts = minAttempts;
    this.failureRateThreshold = failureRateThreshold;
  }

  /** Daily check (08:30 IST). Evaluates the trailing window and alerts over both channels on a gap. */
  @Scheduled(cron = "${artha.notifier.health.cron:0 30 8 * * *}", zone = "Asia/Kolkata")
  public void check() {
    HealthReport report;
    try {
      report = evaluate(clock.instant().minus(Duration.ofHours(windowHours)));
    } catch (RuntimeException e) {
      log.warn("notifier health check failed: {}", e.getMessage());
      return;
    }
    if (report.healthy()) {
      log.info(
          "notifier delivery health OK — {}/{} attempts failed in the last {}h",
          report.failed(),
          report.attempts(),
          windowHours);
      return;
    }
    alert(report);
  }

  /**
   * Compute the delivery-health verdict for events since {@code since}. Healthy when the sample is
   * below {@code min-attempts} OR the failure rate is under the threshold. Side-effect-free — the
   * scheduled check and the tests both call it.
   */
  public HealthReport evaluate(Instant since) {
    DeliveryStats stats = repo.deliveryStats(since);
    long attempts = stats.sent() + stats.failed();
    double rate = attempts == 0 ? 0.0 : (double) stats.failed() / attempts;
    boolean healthy = attempts < minAttempts || rate < failureRateThreshold;
    return new HealthReport(since, attempts, stats.failed(), rate, healthy);
  }

  private void alert(HealthReport report) {
    alertCounter.increment();
    String title = "ArthaYantra notifier delivery health";
    String message =
        "Notifier deliveries failing: "
            + report.failed()
            + "/"
            + report.attempts()
            + " attempts failed ("
            + Math.round(report.failureRate() * 100)
            + "%) in the last "
            + windowHours
            + "h — signal alerts are being dropped.";
    log.error("notifier delivery health RED — {}", message);
    push("NTFY", title, message);
    push("TELEGRAM", title, message);
  }

  /** Best-effort push; skips an unconfigured channel and never lets a delivery failure propagate. */
  private void push(String channel, String title, String message) {
    try {
      if (client.configured(channel)) {
        client.send(channel, title, message);
      }
    } catch (RuntimeException e) {
      log.warn("notifier health {} push failed: {}", channel, e.getMessage());
    }
  }
}
