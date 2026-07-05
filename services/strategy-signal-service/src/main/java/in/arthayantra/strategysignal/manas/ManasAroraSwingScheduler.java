package in.arthayantra.strategysignal.manas;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
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
 * <p>Audit P0-4/H10: goes through {@link ManasSwingRunRecorder} (engine → marker → summary — the
 * same path as the on-demand {@code POST /run}); a failed run pushes a FAILED alert instead of
 * dying as a lone log line.
 */
@Component
@ConditionalOnProperty(value = "artha.manas-arora.swing.enabled", havingValue = "true")
public class ManasAroraSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraSwingScheduler.class);

  private final ManasSwingRunRecorder recorder;
  private final ApplicationEventPublisher events;

  /** Wires the run recorder and the event bus. */
  public ManasAroraSwingScheduler(
      ManasSwingRunRecorder recorder, ApplicationEventPublisher events) {
    this.recorder = recorder;
    this.events = events;
  }

  /** Post-close daily run (20:05 IST, weekdays) — after the geometry/funnel refresh. */
  @Scheduled(cron = "${artha.manas-arora.swing.cron:0 5 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    try {
      ManasAroraSwingEngine.ManasSwingRun result = recorder.runAndRecord();
      log.info(
          "manas swing batch done: {} strategies, {} candidates, {} entries, {} exits,"
              + " {} exit-skipped",
          result.strategies(), result.candidates(), result.entries(), result.exits(),
          result.exitSkipped());
    } catch (RuntimeException e) {
      log.error("manas swing batch failed: {}", e.getMessage(), e);
      try {
        events.publishEvent(
            new SwingBatchAlert(
                "manas-arora", "Manas swing batch FAILED",
                "The 20:05 IST batch threw: " + e.getMessage()
                    + " — open positions' stops were NOT evaluated. Re-run via"
                    + " POST /api/v1/signals/manas-arora-swing/run."));
      } catch (RuntimeException publishFailure) {
        log.warn("manas swing alert publish failed: {}", publishFailure.getMessage());
      }
    }
  }
}
