package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
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
  private final ApplicationEventPublisher events;

  /** Wires the promotion evaluator and the event bus. */
  public GraduationPromotionScheduler(
      GraduationPromotionService promotion, ApplicationEventPublisher events) {
    this.promotion = promotion;
    this.events = events;
  }

  /** Post-close daily evaluation (21:00 IST, weekdays). */
  @Scheduled(cron = "${artha.graduation.promotion-cron:0 55 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void run() {
    try {
      GraduationPromotionService.PromotionResult r = promotion.evaluate();
      log.info(
          "graduation evaluation done: {} evaluated, {} graduated ({} newly: {})",
          r.evaluated(), r.graduated(), r.newlyGraduated().size(), r.newlyGraduated());
    } catch (RuntimeException e) {
      // Audit P0-4/H10: a lone log line is invisible ops — the armed evaluator failing must reach
      // the owner (the promotion board is a decision input).
      log.error("graduation evaluation failed: {}", e.getMessage(), e);
      try {
        events.publishEvent(
            new SwingBatchAlert(
                "graduation", "Graduation evaluation FAILED",
                "The 21:00 IST promotion evaluator threw: " + e.getMessage()));
      } catch (RuntimeException publishFailure) {
        log.warn("graduation alert publish failed: {}", publishFailure.getMessage());
      }
    }
  }
}
