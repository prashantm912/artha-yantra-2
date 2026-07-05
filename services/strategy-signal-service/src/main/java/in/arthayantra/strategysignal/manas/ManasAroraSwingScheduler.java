package in.arthayantra.strategysignal.manas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Manas Arora swing batch once per trading evening, AFTER the market-data Manas geometry
 * scan has refreshed the selection funnel. Gated on {@code artha.manas-arora.swing.enabled=true}
 * (default off) so the batch only runs once the owner has published the swing strategies and turned it
 * on; CI/test/mock contexts stay inert. The sibling of {@code MinerviniSwingScheduler}; its default
 * cron (20:05 IST) sits just after the Minervini batch (20:00) so the two don't contend.
 */
@Component
@ConditionalOnProperty(value = "artha.manas-arora.swing.enabled", havingValue = "true")
public class ManasAroraSwingScheduler {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraSwingScheduler.class);

  private final ManasAroraSwingEngine engine;

  /** Wires the swing engine. */
  public ManasAroraSwingScheduler(ManasAroraSwingEngine engine) {
    this.engine = engine;
  }

  /** Post-close daily run (20:05 IST, weekdays) — after the geometry/funnel refresh. */
  @Scheduled(cron = "${artha.manas-arora.swing.cron:0 5 20 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    try {
      ManasAroraSwingEngine.ManasSwingRun result = engine.runDaily();
      log.info(
          "manas swing batch done: {} strategies, {} entries, {} exits",
          result.strategies(), result.entries(), result.exits());
    } catch (RuntimeException e) {
      log.error("manas swing batch failed: {}", e.getMessage(), e);
    }
  }
}
