package in.arthayantra.strategysignal.insights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled insight sweeps (INT design §2.3): a 15-min data-trust sweep and a 5-min risk-heat
 * sweep. Split out from {@link InsightEngine} and gated on {@code artha.signals.engine-enabled}
 * (default true) so it loads exactly where the live engine runs — and stays OUT of the
 * engine-disabled paper ITs, which prevents surprise trust/heat rows landing in the shared IT DB
 * (the #634 discipline: gate the schedule-driven bean; the engine's event listener + read/persist
 * beans stay always-on and load fine with the engine off — asserted by the context-load IT).
 *
 * <p>Large initial delays keep the first sweep well clear of short integration tests. Every sweep is
 * fail-soft (the engine methods swallow their own errors); a scheduler exception is caught here too.
 */
@Component
@ConditionalOnProperty(name = "artha.signals.engine-enabled", havingValue = "true", matchIfMissing = true)
public class InsightSweeper {

  private static final Logger log = LoggerFactory.getLogger(InsightSweeper.class);

  private final InsightEngine engine;

  public InsightSweeper(InsightEngine engine) {
    this.engine = engine;
  }

  /** 15-min data-trust sweep (§2.3 scheduled sweeps). */
  @Scheduled(fixedDelay = 900_000, initialDelay = 200_000)
  public void trustSweep() {
    try {
      engine.runTrustSweep();
    } catch (RuntimeException e) {
      log.warn("insight trust sweep failed: {}", e.toString());
    }
  }

  /** 5-min risk-heat sweep (§2.3 scheduled sweeps). */
  @Scheduled(fixedDelay = 300_000, initialDelay = 240_000)
  public void riskSweep() {
    try {
      engine.runRiskSweep();
    } catch (RuntimeException e) {
      log.warn("insight risk sweep failed: {}", e.toString());
    }
  }
}
