package in.arthayantra.strategysignal.paper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the F7 graduation evaluator once per trading evening (21:00 IST, after the swing batches at
 * 20:00/20:05). Gated on {@code artha.graduation.promotion-enabled=true} (default off) so the marker
 * pass only runs once the owner arms it — CI/test/mock contexts stay inert; it is dormant anyway
 * until a strategy accrues ≥ the promotion-min-trades closed paper trades.
 */
@Component
@ConditionalOnProperty(value = "artha.graduation.promotion-enabled", havingValue = "true")
public class GraduationPromotionScheduler {

  private static final Logger log = LoggerFactory.getLogger(GraduationPromotionScheduler.class);

  private final GraduationPromotionService promotion;

  /** Wires the promotion evaluator. */
  public GraduationPromotionScheduler(GraduationPromotionService promotion) {
    this.promotion = promotion;
  }

  /** Post-close daily evaluation (21:00 IST, weekdays). */
  @Scheduled(cron = "${artha.graduation.promotion-cron:0 0 21 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    try {
      GraduationPromotionService.PromotionResult r = promotion.evaluate();
      log.info(
          "graduation evaluation done: {} evaluated, {} graduated ({} newly: {})",
          r.evaluated(), r.graduated(), r.newlyGraduated().size(), r.newlyGraduated());
    } catch (RuntimeException e) {
      log.error("graduation evaluation failed: {}", e.getMessage(), e);
    }
  }
}
