package in.arthayantra.strategysignal.swing;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The fail-closed missed-run detector for the EOD swing batches. Each family scheduler first records
 * its effective arming intent in V048; this detector checks those historical intent rows against exact
 * session V025 run markers. A missing intent row is never treated as armed.
 *
 * <p>V047 is used only as a durable per-session episode latch. This class never invokes a batch,
 * reads paper positions, or performs replay/recovery work.
 */
@Component
@ConditionalOnProperty(
    name = "artha.signals.swing-missed-batch-detector.enabled",
    havingValue = "true")
public class SwingBatchCanary {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchCanary.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final int STALE_LATCH_MINUTES = 30;
  private static final int RECENT_SESSION_LIMIT = 64;

  private final SwingBatchRunRepository runs;
  private final SwingBatchIntentRepository intents;
  private final SwingMissedBatchAlertRepository state;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();

  /** Wires the run marker, schedule-intent ledger, episode latch, event bus, and clock. */
  public SwingBatchCanary(
      SwingBatchRunRepository runs,
      SwingBatchIntentRepository intents,
      SwingMissedBatchAlertRepository state,
      ApplicationEventPublisher events,
      Clock clock) {
    this.runs = runs;
    this.intents = intents;
    this.state = state;
    this.events = events;
    this.clock = clock;
  }

  /** Morning check (08:30 IST, weekdays), on the dedicated detector scheduler. */
  @Scheduled(
      cron = "${artha.swing.canary-cron:0 30 8 * * MON-FRI}",
      zone = "Asia/Kolkata",
      scheduler = "swingDetectorTaskScheduler")
  public void check() {
    LocalDate today = LocalDate.now(clock.withZone(IST));
    LocalDate expected;
    try {
      expected = calendar.previousTradingDay(today);
    } catch (RuntimeException e) {
      log.warn("swing canary: NSE calendar does not cover {} — skipping (calendar-cliff)", today);
      return;
    }
    checkBatch("minervini", today, expected);
    checkBatch("manas-arora", today, expected);
  }

  private void checkBatch(String batch, LocalDate today, LocalDate expected) {
    try {
      // Diagnostic only: the bounded sweep below is authoritative. Keep this point lookup so a
      // missing/false expected-session intent is explicitly visible instead of silently inferred.
      Optional<SwingBatchIntentRepository.Intent> expectedIntent = intents.find(batch, expected);
      if (expectedIntent.isEmpty()) {
        log.info(
            "swing canary: batch {} session {} has no schedule intent — skipping that session"
                + " (fail closed)",
            batch, expected);
      } else if (!expectedIntent.get().armed()) {
        log.info(
            "swing canary: batch {} session {} was not armed at schedule time — skipping",
            batch, expected);
      }

      for (LocalDate session :
          intents.claimableMissedSessionsBefore(
              batch, today, STALE_LATCH_MINUTES, RECENT_SESSION_LIMIT)) {
        // Close the query-to-page race if a manual run stamps the session after the bounded read.
        if (!runs.hasRun(batch, session)) {
          pageOnce(batch, session);
        }
      }
    } catch (RuntimeException e) {
      log.warn("swing canary check for {} failed: {}", batch, e.getMessage());
    }
  }

  private void pageOnce(String batch, LocalDate session) {
    if (state.claim(batch, session, STALE_LATCH_MINUTES).isEmpty()) {
      log.debug("swing canary: {} session {} is already latched", batch, session);
      return;
    }
    String title = batch + " swing batch DID NOT RUN";
    String message =
        "No successful " + batch + " swing batch run was recorded for session " + session
            + ". No automatic replay was attempted. Review the session inputs and paper/risk state,"
            + " then deliberately use POST /api/v1/signals/" + batch
            + "-swing/run by hand if a rerun is still intended. That endpoint uses current/latest"
            + " inputs; it is not a historical as-of replay.";
    log.error("swing canary: {} — {}", title, message);
    try {
      events.publishEvent(new SwingBatchAlert(batch, title, message));
      state.markAbandoned(batch, session, "MISSED_BATCH_ALERTED");
    } catch (RuntimeException e) {
      log.warn("swing canary alert for {} session {} failed: {}", batch, session, e.getMessage());
    }
  }
}
