package in.arthayantra.strategysignal.manas;

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
 * Fires the Manas Arora swing batch once per trading evening, AFTER the market-data Manas geometry
 * scan has refreshed the selection funnel. Gated on {@code artha.manas-arora.swing.enabled=true}
 * (default off) so the batch only runs once the owner has published the swing strategies and turned it
 * on; CI/test/mock contexts stay inert. The sibling of {@code MinerviniSwingScheduler}; its default
 * cron (20:05 IST) sits just after the Minervini batch (20:00) so the two don't contend.
 *
 * <p>Audit P0-4/H10: every outcome is now OBSERVABLE — a completed run records a
 * {@code swing_batch_runs} marker (the 08:30 canary's dead-man input) and pushes a one-line ntfy
 * summary; a failed run pushes a FAILED alert instead of dying as a lone log line.
 */
@Component
@ConditionalOnProperty(value = "artha.manas-arora.swing.enabled", havingValue = "true")
public class ManasAroraSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraSwingScheduler.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String BATCH = "manas-arora";

  private final ManasAroraSwingEngine engine;
  private final SwingBatchRunRepository runs;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  /** Wires the swing engine, the run-marker repo, and the event bus. */
  public ManasAroraSwingScheduler(
      ManasAroraSwingEngine engine,
      SwingBatchRunRepository runs,
      ApplicationEventPublisher events,
      Clock clock) {
    this.engine = engine;
    this.runs = runs;
    this.events = events;
    this.clock = clock;
  }

  /** Post-close daily run (20:05 IST, weekdays) — after the geometry/funnel refresh. */
  @Scheduled(cron = "${artha.manas-arora.swing.cron:0 5 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    ManasAroraSwingEngine.ManasSwingRun result;
    try {
      result = engine.runDaily();
    } catch (RuntimeException e) {
      log.error("manas swing batch failed: {}", e.getMessage(), e);
      publishQuietly(
          new SwingBatchAlert(
              BATCH, "Manas swing batch FAILED",
              "The 20:05 IST batch threw: " + e.getMessage()
                  + " — open positions' stops were NOT evaluated. Re-run via"
                  + " POST /api/v1/signals/manas-arora-swing/run."));
      return;
    }
    log.info(
        "manas swing batch done: {} strategies, {} candidates, {} entries, {} exits,"
            + " {} exit-skipped",
        result.strategies(), result.candidates(), result.entries(), result.exits(),
        result.exitSkipped());
    try {
      runs.record(
          BATCH, LocalDate.now(clock.withZone(IST)), result.strategies(), result.candidates(),
          result.entries(), result.exits(), result.exitSkipped());
    } catch (RuntimeException e) {
      log.warn("manas swing run-marker record failed: {}", e.getMessage());
    }
    String summary =
        result.candidates() + " candidates, " + result.entries() + " entries, " + result.exits()
            + " exits, " + result.exitSkipped() + " exit-skipped ("
            + result.strategies() + " strategies)";
    publishQuietly(
        result.exitSkipped() > 0
            ? new SwingBatchAlert(
                BATCH, "Manas swing: " + result.exitSkipped() + " exit(s) NOT evaluated",
                summary + " — see the STOP NOT EVALUATED TODAY errors in the service log.")
            : new SwingBatchAlert(BATCH, "Manas swing batch done", summary));
  }

  private void publishQuietly(SwingBatchAlert alert) {
    try {
      events.publishEvent(alert);
    } catch (RuntimeException e) {
      log.warn("manas swing alert publish failed: {}", e.getMessage());
    }
  }
}
