package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Live signal-output watchdog for the evaluates-fast-but-emits-nothing starvation signature.
 *
 * <p>The subscriber watchdog owns receive and evaluation stalls. This canary only pages when bars
 * are arriving, evaluation is keeping up, and the live gate has produced no block or fire for the
 * configured window.
 */
@Component
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true)
@ConditionalOnProperty(
    prefix = "artha.signals.starvation-watchdog",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false) // DORMANT by default — owner arms after validating window-ms (see yml)
public class SignalStarvationCanary {

  private static final Logger log = LoggerFactory.getLogger(SignalStarvationCanary.class);
  private static final LocalTime ARMED_FROM = LocalTime.of(9, 20);
  private static final LocalTime SESSION_END = LocalTime.of(15, 30);

  private final SignalEngine engine;
  private final ApplicationEventPublisher events;
  private final SubscriberHealthTelemetry telemetry;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final boolean enabled;
  private final long barGapMs;
  private final long windowMs;

  // Single-writer (@Scheduled fixedDelay does not overlap); volatile for test/read visibility.
  private volatile boolean starved;

  /** Wires the engine, event bus, telemetry, clock, and tunable thresholds. */
  public SignalStarvationCanary(
      SignalEngine engine,
      ApplicationEventPublisher events,
      SubscriberHealthTelemetry telemetry,
      Clock clock,
      @Value("${artha.signals.starvation-watchdog.enabled:false}") boolean enabled,
      @Value("${artha.signals.starvation-watchdog.bar-gap-ms:180000}") long barGapMs,
      @Value("${artha.signals.starvation-watchdog.window-ms:300000}") long windowMs) {
    this.engine = engine;
    this.events = events;
    this.telemetry = telemetry;
    this.clock = clock;
    this.enabled = enabled;
    this.barGapMs = barGapMs;
    this.windowMs = windowMs;
  }

  /** The per-minute in-session output-gap check. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 120_000, scheduler = "monitorTaskScheduler")
  public void sweep() {
    try {
      if (!enabled) {
        return;
      }
      ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
      if (!inSession(now) || !engine.hasOneMinuteSubscriptions()) {
        starved = false; // re-arm after a session boundary or a no-subscriptions interval
        return;
      }

      long nowMs = clock.millis();
      long receivedAtMs = engine.lastBarReceivedAtMs();
      long receiveGap = nowMs - receivedAtMs;
      long evalLag = receivedAtMs - engine.lastBarEvaluatedAtMs();
      long outputAtMs = engine.lastGateOutputAtMs();
      if (outputAtMs == 0) {
        return; // no valid live bar yet; zero is initialization state, not an epoch-sized stall
      }
      long outputGap = nowMs - outputAtMs;

      if (outputGap <= windowMs) {
        if (starved) {
          starved = false;
          log.info("signal starvation watchdog: gate output RECOVERED (gap {}s)", outputGap / 1000);
          telemetry.record("recovery", "gate output recovered (gap " + (outputGap / 1000) + "s)");
        }
        return;
      }
      if (receiveGap >= barGapMs || evalLag >= barGapMs) {
        return; // subscriber watchdog owns receive/eval stalls; do not double-alarm
      }

      if (!starved) {
        starved = true;
        String detail =
            "no gate output for " + (outputGap / 1000) + "s while bars are arriving and evaluation "
                + "is keeping up (receipt " + (receiveGap / 1000) + "s old, eval lag "
                + (evalLag / 1000) + "s)";
        log.error("signal starvation watchdog: {}", detail);
        telemetry.record("output-stall", detail);
        publish("ArthaYantra signal watchdog: SIGNAL STARVATION", detail);
      }
    } catch (RuntimeException e) {
      log.warn("signal starvation watchdog sweep failed: {}", e.toString());
    }
  }

  private void publish(String title, String message) {
    try {
      events.publishEvent(new StarvationAlert(title, message));
    } catch (RuntimeException e) {
      log.warn("signal starvation watchdog alert failed: {}", e.getMessage());
    }
  }

  private boolean inSession(ZonedDateTime now) {
    try {
      return calendar.isOpen(now.toInstant())
          && !now.toLocalTime().isBefore(ARMED_FROM)
          && now.toLocalTime().isBefore(SESSION_END);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
