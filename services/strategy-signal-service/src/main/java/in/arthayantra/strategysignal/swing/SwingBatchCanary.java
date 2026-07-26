package in.arthayantra.strategysignal.swing;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The missed-run detector for the EOD swing batches: it pages a human and does nothing else. It
 * never invokes a batch, never reads or mutates a paper position, and carries none of the parked
 * auto-replay machinery (PR #1036).
 *
 * <p>"Was this session supposed to run?" is answered from the V047 schedule-time arming ledger, not
 * from today's flags — reading the current flag cannot tell a deliberately-disarmed session from a
 * missed one, which is what made the predecessor unreliable.
 *
 * <p>Two disjoint ways a session can be missed, and both are swept:
 *
 * <ul>
 *   <li><b>The scheduler fired but the batch did not finish</b> — an intent row exists with no run
 *       marker. {@link SwingBatchIntentRepository#claimableMissedSessionsBefore} finds these.
 *   <li><b>The scheduler never fired at all</b> — the container was down at 20:00, so there is no
 *       intent row to find. This is the shape of the 2026-07-17 incident, so it must not be missed:
 *       {@link #sweepSessionsWithNoIntent} walks back over recent trading days and resolves each
 *       gap against the last arming known at or before it.
 * </ul>
 */
@Component
@ConditionalOnProperty(
    name = "artha.signals.swing-missed-batch-detector.enabled",
    havingValue = "true")
public class SwingBatchCanary {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchCanary.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  /**
   * How long a page suppresses the next one for the same session. Deliberately just under a day, so
   * that against a once-per-weekday-morning sweep an unresolved session pages again every morning
   * until it gets a run marker — see {@link SwingMissedBatchAlertRepository} for why this is a
   * repeating lease and not a one-shot latch.
   */
  private static final int PAGE_LEASE_MINUTES = 20 * 60;

  /** Intent rows scanned per sweep — about three months of sessions. */
  private static final int RECENT_SESSION_LIMIT = 64;

  /**
   * Trading days walked back when looking for sessions with NO intent row at all. Bounds the
   * container-down sweep: an outage longer than this is a whole-host failure, which is the external
   * dead-man heartbeat's job, not this detector's.
   */
  private static final int NO_INTENT_LOOKBACK_SESSIONS = 10;

  private final SwingBatchRunRepository runs;
  private final SwingBatchIntentRepository intents;
  private final SwingMissedBatchAlertRepository alerts;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();

  /** Wires the run marker, schedule-intent ledger, page lease, event bus, and clock. */
  public SwingBatchCanary(
      SwingBatchRunRepository runs,
      SwingBatchIntentRepository intents,
      SwingMissedBatchAlertRepository alerts,
      ApplicationEventPublisher events,
      Clock clock) {
    this.runs = runs;
    this.intents = intents;
    this.alerts = alerts;
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
    checkBatch("minervini", today);
    checkBatch("manas-arora", today);
  }

  private void checkBatch(String batch, LocalDate today) {
    try {
      for (LocalDate session :
          intents.claimableMissedSessionsBefore(
              batch, today, PAGE_LEASE_MINUTES, RECENT_SESSION_LIMIT)) {
        // Close the query-to-page race if a manual run stamps the session after the bounded read.
        if (!runs.hasRun(batch, session)) {
          page(batch, session, "the batch was armed for that session but recorded no run");
        }
      }
      sweepSessionsWithNoIntent(batch, today);
    } catch (RuntimeException e) {
      log.warn("swing canary check for {} failed: {}", batch, e.getMessage());
    }
  }

  /**
   * Pages for recent trading sessions that have NO intent row — the scheduler never fired, so the
   * query above cannot see them. Arming is resolved from the last intent recorded at or before the
   * session, which is why a fresh deploy (no intent anywhere) stays silent rather than paging for
   * every session in the lookback.
   */
  private void sweepSessionsWithNoIntent(String batch, LocalDate today) {
    for (LocalDate session : recentTradingSessionsBefore(today)) {
      if (intents.find(batch, session).isPresent()) {
        continue; // the scheduler fired for this one; the bounded sweep above owns it
      }
      Optional<Boolean> lastKnown = intents.lastKnownArmedOnOrBefore(batch, session);
      if (lastKnown.isEmpty()) {
        log.info(
            "swing canary: batch {} session {} predates any recorded arming intent — skipping"
                + " (fail closed)",
            batch, session);
      } else if (!lastKnown.get()) {
        log.info(
            "swing canary: batch {} session {} has no intent row and the family was last known"
                + " DISARMED — skipping",
            batch, session);
      } else if (!runs.hasRun(batch, session)) {
        page(
            batch,
            session,
            "the scheduler never recorded that session at all, and the batch was last known ARMED"
                + " — the service was probably down at the scheduled time");
      }
    }
  }

  /** The most recent NSE trading days strictly before {@code today}, newest first. */
  private Set<LocalDate> recentTradingSessionsBefore(LocalDate today) {
    Set<LocalDate> sessions = new LinkedHashSet<>();
    LocalDate cursor = today;
    for (int i = 0; i < NO_INTENT_LOOKBACK_SESSIONS; i++) {
      try {
        cursor = calendar.previousTradingDay(cursor);
      } catch (RuntimeException uncoveredYear) {
        // The bundled holiday CSVs cover a fixed year range; walking off the start is not an error.
        break;
      }
      sessions.add(cursor);
    }
    return sessions;
  }

  private void page(String batch, LocalDate session, String why) {
    Optional<SwingMissedBatchAlertRepository.Claim> claim =
        alerts.claim(batch, session, PAGE_LEASE_MINUTES);
    if (claim.isEmpty()) {
      log.debug("swing canary: {} session {} was paged within the lease window", batch, session);
      return;
    }
    String title = batch + " swing batch DID NOT RUN";
    String message =
        "No successful " + batch + " swing batch run was recorded for session " + session + " — "
            + why + ". No automatic replay was attempted, and no paper position was touched."
            + " Review the session inputs and paper/risk state, then deliberately use POST"
            + " /api/v1/signals/" + batch + "-swing/run by hand if a rerun is still intended. That"
            + " endpoint uses current/latest inputs; it is not a historical as-of replay."
            + " (page " + claim.get().pages() + " for this session; it repeats each morning until a"
            + " run is recorded)";
    log.error("swing canary: {} — {}", title, message);
    try {
      events.publishEvent(new SwingBatchAlert(batch, title, message));
    } catch (RuntimeException e) {
      log.warn("swing canary alert for {} session {} failed: {}", batch, session, e.getMessage());
    }
  }
}
