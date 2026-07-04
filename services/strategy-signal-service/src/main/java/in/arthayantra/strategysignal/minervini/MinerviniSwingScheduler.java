package in.arthayantra.strategysignal.minervini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Phase-9 Minervini swing batch once per trading evening, AFTER the market-data geometry
 * scan has refreshed the SEPA funnel (that runs ~19:30 IST). Gated on {@code
 * artha.minervini.swing.enabled=true} (default off) so the batch only runs once the owner has
 * published the swing strategies and turned it on; CI/test/mock contexts stay inert.
 */
@Component
@ConditionalOnProperty(value = "artha.minervini.swing.enabled", havingValue = "true")
public class MinerviniSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(MinerviniSwingScheduler.class);

  private final MinerviniSwingEngine engine;

  /** Wires the swing engine. */
  public MinerviniSwingScheduler(MinerviniSwingEngine engine) {
    this.engine = engine;
  }

  /** Post-close daily run (20:00 IST, weekdays) — after the 19:30 geometry/funnel refresh. */
  @Scheduled(cron = "${artha.minervini.swing.cron:0 0 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    try {
      MinerviniSwingEngine.SwingRun result = engine.runDaily();
      log.info(
          "minervini swing batch done: {} strategies, {} entries, {} exits",
          result.strategies(), result.entries(), result.exits());
    } catch (RuntimeException e) {
      log.error("minervini swing batch failed: {}", e.getMessage(), e);
    }
  }
}
