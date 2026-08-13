package in.arthayantra.strategysignal.signals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded-retention prune for {@code strategy.risk_suppressions} (PF-03, chip task_f168c23c). The
 * table is append-only (V042) with a low row rate — a row lands only when a paper book is AT a
 * governor limit AND a would-be entry fires, so most sessions write zero and the worst case is
 * ~2-3k rows/day. Retention is deliberately GENEROUS (180 days) — this is forward insurance, not a
 * space emergency. Every day at 18:09 IST (post-close) a plain row-wise {@code DELETE} removes rows
 * past the horizon. This is a PLAIN OLTP table, NOT a hypertable, so it uses {@code DELETE} — never
 * TimescaleDB {@code drop_chunks} (the A10 {@code OptionsSnapshotPruneJob} template drops chunks
 * because {@code options_chain_snapshots} is a ~1.12B-row compressed hypertable; here the volume is
 * trivial and a row-wise delete is correct and cheap).
 *
 * <p><b>Off the hot path — and off the DEFAULT pool.</b> Bound to the dedicated
 * {@code maintenanceTaskScheduler}. It used to run on the default {@code @Scheduled} pool, which is
 * a SINGLE thread ({@code application.yml} sizes Hikari, never the scheduler) that a live thread
 * census shows carrying {@code PaperStaleTickAlerter}, {@code SignalEngine} reconcile and
 * {@code PaperScheduler.bracketEvaluation} — the 15-second stop-loss/target sweep. A DELETE blocking
 * on a lock at 18:09 parked that thread, and the next session's stop-loss evaluation would simply
 * never fire. Still NOT the {@code monitorTaskScheduler} (audit BEJ-01 / #919 reserves that for the
 * pure liveness detectors) and not the eval-outcome pool either — see
 * {@code MonitorSchedulingConfig.maintenanceTaskScheduler}. The DELETE itself is additionally
 * BOUNDED by a query timeout on the repository's private template. It never touches the engine eval
 * thread, and the golden replay injects no suppression rows, so this is parity-safe (it cannot run
 * on backtest).
 *
 * <p><b>Fail-soft.</b> A failed delete is logged, ops-alerted via the {@link SwingBatchAlert} bridge
 * (the notifier←signals event direction; this module cannot import notifier), and NEVER propagates
 * out of the {@code @Scheduled} tick. A missed cron while the stack is down does not fire later
 * (Spring has no catch-up) — retention is not time-critical, the next day's tick catches up.
 *
 * <p><b>Live-only.</b> Like the A10 prune, the scheduled entry point is gated to the {@code live}
 * profile: the mock/CI database is ephemeral ({@code artha_mock}, wiped by {@code ay reset-db}), so
 * there is nothing to retain there. The IT drives the package-visible {@link #prune()} directly,
 * bypassing the profile gate.
 *
 * <p><b>Misconfiguration guard.</b> A non-positive horizon would delete EVERY row
 * ({@code generated_at < now() - interval '0 days'} == {@code < now()}); the guard skips and alerts
 * rather than wiping the incidence history.
 */
@Component
public class RiskSuppressionPruneJob {

  private static final Logger log = LoggerFactory.getLogger(RiskSuppressionPruneJob.class);

  private final RiskSuppressionRepository repository;
  private final ApplicationEventPublisher events;
  private final boolean live;
  private final int retentionDays;

  /** Wires the repository, the ops-alert event bus, the profile gate and the retention horizon. */
  public RiskSuppressionPruneJob(
      RiskSuppressionRepository repository,
      ApplicationEventPublisher events,
      Environment environment,
      @Value("${artha.signals.risk-suppression-retention-days:180}") int retentionDays) {
    this.repository = repository;
    this.events = events;
    this.live = environment.matchesProfiles("live");
    this.retentionDays = retentionDays;
  }

  /**
   * The daily retention tick (18:09 IST — The 02:30/03:30 slot was retired on 2026-08-12: the machine is shut down at 19:00 IST and started after 08:00, so an overnight tick never fired at all — the retention this job exists to enforce was simply not happening. Six-field Spring cron with the explicit
   * Asia/Kolkata zone
   * (the sibling scheduler convention); runs every calendar day since retention has no trading-day
   * dependence. Cron is property-overridable like every sibling scheduler. Live-only.
   *
   * <p>Bound to {@code maintenanceTaskScheduler} and pinned there by
   * {@code MonitorSchedulingConfigTest}, so a refactor cannot silently drop a synchronous DELETE
   * back onto the shared default pool that carries paper stop-loss evaluation.
   */
  @Scheduled(
      cron = "${artha.signals.risk-suppression-prune.cron:0 9 18 * * *}",
      zone = "Asia/Kolkata",
      scheduler = "maintenanceTaskScheduler")
  public void scheduledPrune() {
    if (!live) {
      return;
    }
    prune();
  }

  /**
   * One retention pass: delete rows past the horizon. Package-visible so the IT drives it directly
   * (bypassing the live-profile gate on the scheduled entry point). Fully fail-soft — returns rather
   * than throws.
   *
   * @return the number of rows deleted (0 on a no-op day, a guarded non-positive horizon, or a failure)
   */
  int prune() {
    try {
      if (retentionDays <= 0) {
        // Guard: a non-positive horizon makes the cutoff now(), deleting EVERY row. A misconfigured
        // knob must never wipe the incidence history — skip and alert.
        log.error(
            "risk_suppressions prune SKIPPED: non-positive retention-days={} would delete all rows",
            retentionDays);
        alert(
            "risk_suppressions prune misconfigured",
            "artha.signals.risk-suppression-retention-days="
                + retentionDays
                + " (<= 0) — prune skipped to avoid deleting all rows");
        return 0;
      }
      int deleted = repository.deleteOlderThanDays(retentionDays);
      log.info(
          "risk_suppressions prune: deleted {} row(s) older than {}d", deleted, retentionDays);
      return deleted;
    } catch (RuntimeException e) {
      log.error(
          "risk_suppressions prune FAILED (retention={}d): {}", retentionDays, e.getMessage(), e);
      alert(
          "risk_suppressions prune FAILED",
          "The daily risk_suppressions retention prune ("
              + retentionDays
              + "d) threw: "
              + e.getMessage());
      return 0;
    }
  }

  /** Fail-soft ops alert via the signals→notifier event bridge; a failed publish never propagates. */
  private void alert(String title, String message) {
    try {
      events.publishEvent(new SwingBatchAlert("risk-suppression-prune", title, message));
    } catch (RuntimeException publishFailure) {
      log.warn("risk_suppressions prune alert publish failed: {}", publishFailure.getMessage());
    }
  }
}
