package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.paper.PaperReconciliationService.ReconciliationResult;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the paper-ledger reconciliation (audit §8, V5 + V16) once per trading day at 08:50 IST, in the
 * PRE-OPEN window — AFTER the 08:35 swing entry pass, 25 minutes before the 09:15 open. It moved out of
 * the 21:15 evening slot because the machine is off by 19:00, so an evening run simply never happened.
 *
 * <p>{@link PaperReconciliationService#reconcile()} had to move with it, and that is not cosmetic:
 * it anchors its window to the previous trading SESSION, because the old {@code today 00:00 → now}
 * expression covers nothing at 08:50 and would have reported a clean reconciliation of an empty set.
 *
 * <p>It runs on {@code preOpenTaskScheduler}, a lane of its own. An earlier revision of this change
 * put it on the CATCH-UP's lane so it would queue behind the 08:35 pass — see that bean for why that
 * was the wrong trade.
 *
 * <p>Weekdays only (no paper trading over the weekend). Cron is property-overridable; a missed cron
 * NEVER fires later (Spring {@code @Scheduled} has no catch-up) — the next evening's pass simply widens
 * nothing (each run re-checks its own window). A plain unconditional {@code @Component} (the
 * {@link PaperScheduler} posture): the reconciliation is a passive read-only reporter, safe to run
 * everywhere, with NO {@code SignalEngine} dependency.
 */
@Component
public class PaperReconciliationScheduler {

  private static final Logger log = LoggerFactory.getLogger(PaperReconciliationScheduler.class);

  private final PaperReconciliationService reconciliation;
  private final ApplicationEventPublisher events;

  /** Wires the reconciliation service + the ops-alert event bus. */
  public PaperReconciliationScheduler(
      PaperReconciliationService reconciliation, ApplicationEventPublisher events) {
    this.reconciliation = reconciliation;
    this.events = events;
  }

  /** Pre-open daily reconciliation (08:50 IST, weekdays), serialized after the 08:35 catch-up. */
  @Scheduled(
      cron = "${artha.paper.reconciliation.cron:0 50 8 * * MON-FRI}",
      zone = "Asia/Kolkata",
      scheduler = "preOpenTaskScheduler")
  public void run() {
    try {
      ReconciliationResult r = reconciliation.reconcile();
      log.info(
          "paper reconciliation done: {} positions + {} taken signals checked, {} discrepancies"
              + " (V5 {}, V16 {}, stranded carry {}, dead-anchor orphans {})",
          r.positionsChecked(), r.takenSignalsChecked(), r.totalDiscrepancies(),
          r.v5Discrepancies(), r.v16Discrepancies(), r.strandedCarryDiscrepancies(),
          r.deadAnchorOrphanDiscrepancies());
    } catch (RuntimeException e) {
      // A lone log line is invisible ops (audit P0-4/H10): the reconciler failing must reach the owner —
      // an unrun integrity check hides exactly the drift it exists to surface. Uses the same ops-alert
      // bridge the graduation evaluator neighbour does (SwingBatchAlert → notifier's listener); the
      // service already routes the DISCREPANCY alert via NotifierClient, but a thrown service can't, so
      // the scheduler owns the FAILURE alert.
      log.error("paper reconciliation failed: {}", e.getMessage(), e);
      try {
        events.publishEvent(
            new SwingBatchAlert(
                "reconciliation",
                "Paper reconciliation FAILED",
                "The pre-open paper V5/V16 reconciliation threw: " + e.getMessage()));
      } catch (RuntimeException publishFailure) {
        log.warn("paper reconciliation alert publish failed: {}", publishFailure.getMessage());
      }
    }
  }
}
