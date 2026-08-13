package in.arthayantra.strategysignal.paper;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.paper.PaperReconciliationService.ReconciliationResult;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.swing.SwingRunMutex;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the paper-ledger reconciliation (audit §8, V5 + V16) once per trading day at 08:50 IST, in the
 * PRE-OPEN window — fifteen minutes after the 08:35 swing entry pass STARTS, 25 minutes before the
 * 09:15 open. It moved out of the 21:15 evening slot because the machine is off by 19:00, so an evening
 * run simply never happened.
 *
 * <p>{@link PaperReconciliationService#reconcile()} had to move with it, and that is not cosmetic:
 * it anchors its window to the previous trading SESSION, because the old {@code today 00:00 → now}
 * expression covers nothing at 08:50 and would have reported a clean reconciliation of an empty set.
 *
 * <p>It runs on {@code preOpenTaskScheduler}, a lane of its own, and WAITS for the entry pass rather
 * than sharing its thread — see {@link #awaitSwingBatchIdle()} for why those are different things and
 * why only one of them is safe.
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
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final PaperReconciliationService reconciliation;
  private final ApplicationEventPublisher events;
  private final SwingRunMutex swingRuns;
  private final Clock clock;
  private final int deadlineReserveMinutes;
  private final long pollMillis;
  private final Counter declinedTotal;

  /** Wires the reconciliation service, the ops-alert event bus, the swing run observable + knobs. */
  public PaperReconciliationScheduler(
      PaperReconciliationService reconciliation,
      ApplicationEventPublisher events,
      SwingRunMutex swingRuns,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${artha.paper.reconciliation.catchup-deadline-reserve-minutes:15}")
          int deadlineReserveMinutes,
      @Value("${artha.paper.reconciliation.catchup-poll-millis:5000}") long pollMillis) {
    this.reconciliation = reconciliation;
    this.events = events;
    this.swingRuns = swingRuns;
    this.clock = clock;
    this.deadlineReserveMinutes = Math.max(0, deadlineReserveMinutes);
    this.pollMillis = Math.max(1L, pollMillis);
    this.declinedTotal = meterRegistry.counter("ay_paper_recon_declined_total");
  }

  /**
   * Pre-open daily reconciliation (08:50 IST, weekdays), STARTING fifteen minutes after the 08:35
   * entry pass — which is not the same as running after it finishes. It now waits for the pass, with
   * a hard deadline, and DECLINES LOUDLY rather than reading a book mid-change.
   *
   * <p>The decline is deliberately noisy on three independent channels — a {@code log.error}, an ops
   * alert, and the {@code ay_paper_recon_declined_total} counter. A reconciliation that quietly did
   * not happen is indistinguishable from one that found nothing wrong, and an unrun integrity check
   * hides exactly the drift it exists to surface (audit P0-4/H10).
   */
  @Scheduled(
      cron = "${artha.paper.reconciliation.cron:0 50 8 * * MON-FRI}",
      zone = "Asia/Kolkata",
      scheduler = "preOpenTaskScheduler")
  public void run() {
    if (!awaitSwingBatchIdle()) {
      declinedTotal.increment();
      LocalTime deadline = deadlineTime();
      log.error(
          "paper reconciliation DECLINED: a swing batch run was still in flight at the {} IST"
              + " pre-open deadline — refusing to reconcile a book that is mid-change",
          deadline);
      publish(
          "Paper reconciliation DECLINED (swing batch still running)",
          "The pre-open paper V5/V16 reconciliation did NOT run: a swing batch run was still in"
              + " flight at the "
              + deadline
              + " IST deadline, so any reconciliation would have read the book mid-change and could"
              + " have reported discrepancies that are not real. NOTHING WAS RECONCILED TODAY —"
              + " this is not a clean result. Check the swing catch-up (08:35) for an overrun or a"
              + " hang; swing_catchup_runs carries its per-session reasons.");
      return;
    }
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
      publish(
          "Paper reconciliation FAILED",
          "The pre-open paper V5/V16 reconciliation threw: " + e.getMessage());
    }
  }

  /**
   * Waits until no swing family run is in flight, bounded by a HARD pre-open deadline. True = the
   * book is quiet and the caller may reconcile; false = the deadline passed with a run still going.
   *
   * <p><b>Why wait rather than share the catch-up's thread.</b> An earlier revision of the 2026-08-12
   * schedule move put this job on {@code swingCatchUpTaskScheduler} so it genuinely queued behind the
   * 08:35 pass. That was withdrawn: it widened a HUNG catch-up's blast radius from "no swing entries"
   * to "no swing entries AND no reconciliation AND no past-expiry recovery", with nothing detecting
   * it. This buys the same ordering guarantee WITHOUT that failure mode, because the wait is bounded
   * and its expiry is loud — a hung catch-up costs a paged decline, not silence.
   *
   * <p><b>Why the lock and not {@code swing_catchup_runs}.</b> See {@link SwingRunMutex#anyRunInFlight()}:
   * the durable ledger has no RUNNING row between claimed sessions, so it would report "idle" in the
   * middle of a live sweep. {@code isLocked()} is an observation, never an acquisition, so this never
   * queues behind the run it is watching.
   *
   * <p><b>The deadline</b> is anchored to {@link MarketCalendar#SESSION_OPEN} — the same 09:15 the
   * catch-up's own {@code marketOpenDeadlinePassed()} uses, not a second notion of "too late" — minus
   * a reserve (default 15 min → 09:00 IST) that leaves the reconciliation and the 08:52 past-expiry
   * recovery behind it room to finish pre-open. The fast path costs NOTHING: with no run in flight
   * (every normal day — 81 s for both families measured 2026-08-12, against a 900 s gap) this returns
   * immediately without consulting the clock at all.
   *
   * <p>⚠️ <b>Residual, deliberately not closed.</b> This samples "in flight NOW". A manual
   * {@code POST /run} that starts AFTER the wait clears can still tear a read. Closing that needs a
   * generation counter bumped at the lock ACQUISITION sites — i.e. changing the money-path catch-up
   * to fix a read-only reporter — and a leaky before/after boolean check would report "clean" on the
   * very interleaving it cannot see, which is worse than not claiming it. The overrun this exists to
   * fix is the scheduled one, and that is sound.
   */
  boolean awaitSwingBatchIdle() {
    if (!swingRuns.anyRunInFlight()) {
      return true;
    }
    ZonedDateTime deadline =
        ZonedDateTime.now(clock.withZone(IST)).toLocalDate().atTime(deadlineTime()).atZone(IST);
    log.warn("paper reconciliation: a swing batch run is in flight — waiting until {}", deadline);
    while (swingRuns.anyRunInFlight()) {
      if (!ZonedDateTime.now(clock.withZone(IST)).isBefore(deadline)) {
        return false;
      }
      try {
        Thread.sleep(pollMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("paper reconciliation: interrupted while waiting for the swing batch to finish");
        return false;
      }
    }
    return true;
  }

  /**
   * The wait's cutoff, as an IST wall-clock time. ONE expression, used by both the wait and the
   * decline message, so the alert can never name a deadline different from the one enforced.
   */
  private LocalTime deadlineTime() {
    return MarketCalendar.SESSION_OPEN.minusMinutes(deadlineReserveMinutes);
  }

  private void publish(String title, String message) {
    try {
      events.publishEvent(new SwingBatchAlert("reconciliation", title, message));
    } catch (RuntimeException publishFailure) {
      log.warn("paper reconciliation alert publish failed: {}", publishFailure.getMessage());
    }
  }
}
