package in.arthayantra.strategysignal.signals;

import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The did-not-run dead-man switch for the EOD swing batches (audit P0-4/H10). A container down at
 * 20:00 IST means {@code @Scheduled} simply never fires — no exception, no log, no catch-up — and
 * the open positions' stops are silently not evaluated that evening. Next morning (08:30 IST) this
 * canary checks that every ARMED batch recorded a {@code swing_batch_runs} row for the last NSE
 * trading day and publishes a {@link SwingBatchAlert} (→ ntfy) when one is missing.
 *
 * <p>A batch that has NEVER recorded is skipped (fresh deploy — the marker table starts empty; the
 * first missing-run alert can only fire after the first successful recording).
 */
@Component
public class SwingBatchCanary {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchCanary.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private final SwingBatchRunRepository runs;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final boolean minerviniArmed;
  private final boolean manasArmed;

  /** Wires the marker repo, the event bus, and the two batch arming flags. */
  public SwingBatchCanary(
      SwingBatchRunRepository runs,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.minervini.swing.enabled:false}") boolean minerviniArmed,
      @Value("${artha.manas-arora.swing.enabled:false}") boolean manasArmed) {
    this.runs = runs;
    this.events = events;
    this.clock = clock;
    this.minerviniArmed = minerviniArmed;
    this.manasArmed = manasArmed;
  }

  /** Morning check (08:30 IST, weekdays) — before the session, after any overnight restart. */
  @Scheduled(cron = "${artha.swing.canary-cron:0 30 8 * * MON-FRI}", zone = "Asia/Kolkata")
  public void check() {
    LocalDate today = LocalDate.now(clock.withZone(IST));
    // The last day a batch SHOULD have run for: the most recent NSE trading day strictly before
    // today. (Batches run post-close on the trading day itself; a weekday-holiday run records
    // against the stale date and is simply an extra row.)
    LocalDate expected = calendar.previousTradingDay(today);
    checkBatch("minervini", minerviniArmed, expected);
    checkBatch("manas-arora", manasArmed, expected);
  }

  private void checkBatch(String batch, boolean armed, LocalDate expected) {
    if (!armed) {
      return;
    }
    try {
      Optional<LocalDate> last = runs.lastRunDate(batch);
      if (last.isEmpty()) {
        log.info("swing canary: batch {} has never recorded — skipping (fresh marker table)", batch);
        return;
      }
      if (last.get().isBefore(expected)) {
        String title = batch + " swing batch DID NOT RUN";
        String message =
            "Expected a run for " + expected + "; last recorded " + last.get()
                + ". Open positions' stops were NOT evaluated that evening — run"
                + " POST /api/v1/signals/" + ("minervini".equals(batch) ? "minervini" : batch)
                + "-swing/run to catch up.";
        log.error("swing canary: {} — {}", title, message);
        events.publishEvent(new SwingBatchAlert(batch, title, message));
      }
    } catch (RuntimeException e) {
      log.warn("swing canary check for {} failed: {}", batch, e.getMessage());
    }
  }
}
