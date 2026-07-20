package in.arthayantra.strategysignal.signals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded-retention prune for {@code strategy.composite_rejections} (V044) — the
 * {@link RiskSuppressionPruneJob} shape, applied to a table whose row rate is HIGHER but still
 * small: ~192 rows/session at ~650 bytes of canonical ScoreBreakdown each, so ~141 KB/day, i.e.
 * ~25 MB/year. Retention is deliberately GENEROUS (180 days) — this is tuning history, not a space
 * emergency. Every day at 02:30 IST (off-market) a plain row-wise {@code DELETE} removes rows past
 * the horizon. This is a PLAIN OLTP table, NOT a hypertable, so it uses {@code DELETE} — never
 * TimescaleDB {@code drop_chunks} (the A10 {@code OptionsSnapshotPruneJob} drops chunks because
 * {@code options_chain_snapshots} is a ~1.12B-row compressed hypertable; here a row-wise delete is
 * correct and cheap).
 *
 * <p><b>Nothing pre-existing is at risk.</b> Every row in this table was created by the feature that
 * introduced it, so unlike a prune bolted onto {@code signal_rejections} this can never delete
 * history that predates the change.
 *
 * <p><b>Off the hot path — and off the DEFAULT pool.</b> Bound to the dedicated
 * {@code maintenanceTaskScheduler}, for the same reason as {@link RiskSuppressionPruneJob}: the
 * default {@code @Scheduled} pool is a SINGLE thread shared with {@code PaperStaleTickAlerter},
 * {@code SignalEngine} reconcile and {@code PaperScheduler.bracketEvaluation} (the 15-second
 * stop-loss/target sweep), so a DELETE blocking on a lock there parks stop-loss evaluation. Leaving
 * THIS prune on the default pool while moving its sibling would have left the identical wedge in
 * place. Still NOT the {@code monitorTaskScheduler} (audit BEJ-01 / #919). The DELETE is
 * additionally BOUNDED by a query timeout on the repository's private template. It never touches
 * the engine eval thread, and the golden replay writes no rows here, so this is parity-safe (it
 * cannot run on backtest).
 *
 * <p><b>Fail-soft.</b> A failed delete is logged, ops-alerted via the {@link SwingBatchAlert} bridge
 * (the notifier←signals event direction; this module cannot import notifier), and NEVER propagates
 * out of the {@code @Scheduled} tick.
 *
 * <p><b>Live-only.</b> Like the sibling prunes, the scheduled entry point is gated to the
 * {@code live} profile: the mock/CI database is ephemeral ({@code artha_mock}, wiped by
 * {@code ay reset-db}), so there is nothing to retain there. The IT drives the package-visible
 * {@link #prune()} directly, bypassing the profile gate.
 *
 * <p><b>Disabling it is quiet.</b> A non-positive horizon turns the prune OFF: it would otherwise
 * make the cutoff {@code now()} and delete EVERY row ({@code generated_at < now() - interval
 * '0 days'}), so the DELETE is skipped. That state is announced ONCE at INFO on construction and is
 * silent thereafter — NOT an ERROR and NOT an ops alert on every daily tick. An operator who turns a
 * prune off has done nothing wrong and must not be paged for it daily; a recurring false alert is
 * how a real one gets ignored.
 */
@Component
public class CompositeRejectionPruneJob {

  private static final Logger log = LoggerFactory.getLogger(CompositeRejectionPruneJob.class);

  private final CompositeRejectionRepository repository;
  private final ApplicationEventPublisher events;
  private final boolean live;
  private final int retentionDays;

  /** Wires the repository, the ops-alert event bus, the profile gate and the retention horizon. */
  public CompositeRejectionPruneJob(
      CompositeRejectionRepository repository,
      ApplicationEventPublisher events,
      Environment environment,
      @Value("${artha.signals.composite-rejection-retention-days:180}") int retentionDays) {
    this.repository = repository;
    this.events = events;
    this.live = environment.matchesProfiles("live");
    this.retentionDays = retentionDays;
    if (retentionDays <= 0) {
      // Announced ONCE, at INFO, so "why is nothing being pruned?" is answerable from the boot log
      // without the daily tick having to say anything. Deliberately not an alert: disabling a prune
      // is a legitimate operator choice, not a fault.
      log.info(
          "composite_rejections prune DISABLED (artha.signals.composite-rejection-retention-days={})"
              + " — rows are retained indefinitely",
          retentionDays);
    }
  }

  /**
   * The daily retention tick (02:30 IST). Six-field Spring cron with the explicit Asia/Kolkata zone
   * (the sibling scheduler convention); runs every calendar day since retention has no trading-day
   * dependence. Cron is property-overridable like every sibling scheduler. Live-only.
   *
   * <p>Bound to {@code maintenanceTaskScheduler} and pinned there by
   * {@code MonitorSchedulingConfigTest}, so a refactor cannot silently drop a synchronous DELETE
   * back onto the shared default pool that carries paper stop-loss evaluation.
   */
  @Scheduled(
      cron = "${artha.signals.composite-rejection-prune.cron:0 30 2 * * *}",
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
        // DISABLED, not misconfigured. This is the documented off switch (application.yml), and it
        // doubles as the guard: a non-positive horizon makes the cutoff now(), which would delete
        // EVERY row, so the DELETE must never run in this state.
        //
        // Quiet on purpose. An earlier cut logged ERROR and published an ops alert here, which meant
        // an operator who had legitimately turned the prune off got paged once every day, forever —
        // and a daily false alert is how a real one gets ignored. The state is announced ONCE at
        // startup (see the constructor) so it stays discoverable without being noisy.
        log.debug("composite_rejections prune disabled (retention-days={})", retentionDays);
        return 0;
      }
      int deleted = repository.deleteOlderThanDays(retentionDays);
      log.info(
          "composite_rejections prune: deleted {} row(s) older than {}d", deleted, retentionDays);
      return deleted;
    } catch (RuntimeException e) {
      log.error(
          "composite_rejections prune FAILED (retention={}d): {}", retentionDays, e.getMessage(), e);
      alert(
          "composite_rejections prune FAILED",
          "The daily composite_rejections retention prune ("
              + retentionDays
              + "d) threw: "
              + e.getMessage());
      return 0;
    }
  }

  /** Fail-soft ops alert via the signals→notifier event bridge; a failed publish never propagates. */
  private void alert(String title, String message) {
    try {
      events.publishEvent(new SwingBatchAlert("composite-rejection-prune", title, message));
    } catch (RuntimeException publishFailure) {
      log.warn("composite_rejections prune alert publish failed: {}", publishFailure.getMessage());
    }
  }
}
