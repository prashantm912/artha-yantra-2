package in.arthayantra.strategysignal.minervini;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Phase-9 Minervini swing batch once per trading evening, AFTER the market-data geometry
 * scan has refreshed the SEPA funnel (that runs ~19:30 IST). Gated on {@code
 * artha.minervini.swing.enabled=true} (default off) so the batch only runs once the owner has
 * published the swing strategies and turned it on; CI/test/mock contexts stay inert.
 *
 * <p>Audit P0-4/H10: every outcome is now OBSERVABLE — a completed run records a
 * {@code swing_batch_runs} marker (the 08:30 canary's dead-man input) and pushes a one-line ntfy
 * summary; a failed run pushes a FAILED alert instead of dying as a lone log line.
 */
@Component
@ConditionalOnProperty(value = "artha.minervini.swing.enabled", havingValue = "true")
public class MinerviniSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(MinerviniSwingScheduler.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String BATCH = "minervini";

  private final MinerviniSwingEngine engine;
  private final SwingBatchRunRepository runs;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  /** Wires the swing engine, the run-marker repo, and the event bus. */
  public MinerviniSwingScheduler(
      MinerviniSwingEngine engine,
      SwingBatchRunRepository runs,
      ApplicationEventPublisher events,
      Clock clock) {
    this.engine = engine;
    this.runs = runs;
    this.events = events;
    this.clock = clock;
  }

  /** Post-close daily run (20:00 IST, weekdays) — after the 19:30 geometry/funnel refresh. */
  @Scheduled(cron = "${artha.minervini.swing.cron:0 0 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    MinerviniSwingEngine.SwingRun result;
    try {
      result = engine.runDaily();
    } catch (RuntimeException e) {
      log.error("minervini swing batch failed: {}", e.getMessage(), e);
      publishQuietly(
          new SwingBatchAlert(
              BATCH, "Minervini swing batch FAILED",
              "The 20:00 IST batch threw: " + e.getMessage()
                  + " — open positions' stops were NOT evaluated. Re-run via"
                  + " POST /api/v1/signals/minervini-swing/run."));
      return;
    }
    log.info(
        "minervini swing batch done: {} strategies, {} candidates, {} entries, {} exits,"
            + " {} exit-skipped",
        result.strategies(), result.candidates(), result.entries(), result.exits(),
        result.exitSkipped());
    try {
      runs.record(
          BATCH, LocalDate.now(clock.withZone(IST)), result.strategies(), result.candidates(),
          result.entries(), result.exits(), result.exitSkipped());
    } catch (RuntimeException e) {
      log.warn("minervini swing run-marker record failed: {}", e.getMessage());
    }
    String summary =
        result.candidates() + " candidates, " + result.entries() + " entries, " + result.exits()
            + " exits, " + result.exitSkipped() + " exit-skipped ("
            + result.strategies() + " strategies)";
    publishQuietly(
        result.exitSkipped() > 0
            ? new SwingBatchAlert(
                BATCH, "Minervini swing: " + result.exitSkipped() + " exit(s) NOT evaluated",
                summary + " — see the STOP NOT EVALUATED TODAY errors in the service log.")
            : new SwingBatchAlert(BATCH, "Minervini swing batch done", summary));
  }

  private void publishQuietly(SwingBatchAlert alert) {
    try {
      events.publishEvent(alert);
    } catch (RuntimeException e) {
      log.warn("minervini swing alert publish failed: {}", e.getMessage());
    }
  }
}
