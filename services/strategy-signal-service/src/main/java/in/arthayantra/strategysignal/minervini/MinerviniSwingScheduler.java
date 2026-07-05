package in.arthayantra.strategysignal.minervini;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
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
 * <p>Audit P0-4/H10: goes through {@link MinerviniSwingRunRecorder} (engine → marker → summary —
 * the same path as the on-demand {@code POST /run}); a failed run pushes a FAILED alert instead of
 * dying as a lone log line.
 */
@Component
@ConditionalOnProperty(value = "artha.minervini.swing.enabled", havingValue = "true")
public class MinerviniSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(MinerviniSwingScheduler.class);

  private final MinerviniSwingRunRecorder recorder;
  private final ApplicationEventPublisher events;

  /** Wires the run recorder and the event bus. */
  public MinerviniSwingScheduler(
      MinerviniSwingRunRecorder recorder, ApplicationEventPublisher events) {
    this.recorder = recorder;
    this.events = events;
  }

  /** Post-close daily run (20:00 IST, weekdays) — after the 19:30 geometry/funnel refresh. */
  @Scheduled(cron = "${artha.minervini.swing.cron:0 0 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    try {
      MinerviniSwingEngine.SwingRun result = recorder.runAndRecord();
      log.info(
          "minervini swing batch done: {} strategies, {} candidates, {} entries, {} exits,"
              + " {} exit-skipped",
          result.strategies(), result.candidates(), result.entries(), result.exits(),
          result.exitSkipped());
    } catch (RuntimeException e) {
      log.error("minervini swing batch failed: {}", e.getMessage(), e);
      try {
        events.publishEvent(
            new SwingBatchAlert(
                "minervini", "Minervini swing batch FAILED",
                "The 20:00 IST batch threw: " + e.getMessage()
                    + " — open positions' stops were NOT evaluated. Re-run via"
                    + " POST /api/v1/signals/minervini-swing/run."));
      } catch (RuntimeException publishFailure) {
        log.warn("minervini swing alert publish failed: {}", publishFailure.getMessage());
      }
    }
  }
}
