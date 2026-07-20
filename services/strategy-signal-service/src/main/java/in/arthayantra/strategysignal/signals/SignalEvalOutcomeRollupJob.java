package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.signals.SignalEngine.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes engine liveness answerable RETROACTIVELY: snapshots the {@link SignalEngine.Outcome}
 * evaluation counters to {@code strategy.signal_eval_outcomes} (V043) every 3 minutes through the
 * session, and prunes that table daily.
 *
 * <p><b>Why.</b> {@code ay_signal_eval_outcome_total} is an in-memory Micrometer counter — it resets
 * to zero on every restart, so it answers only "what has happened since this JVM booted". "Was the
 * engine alive on 2026-07-16?" was therefore unanswerable, and that gap produced two false
 * starvation diagnoses: the 2026-07-17 84-minute silence that was merely a SuperTrend-DOWN leg
 * ({@link SignalEngine} Outcome javadoc), and 2026-07-20, where a dead engine was inferred from an
 * empty {@code signal_rejections} table and a LIVE trading service was restarted unnecessarily — the
 * engine had been healthy throughout (399 evaluations, 0 failures) and the restart destroyed the
 * only evidence of it. The counters were always the right signal; they just did not survive a
 * restart.
 *
 * <p><b>Zero added work on the eval thread — non-negotiable.</b> This is a scheduled READER of
 * counters that already live in memory ({@link SignalEngine#outcomeCounts()}), never an inline write
 * in the evaluation path. A row per no-entry per strategy per bar was deliberately rejected when the
 * counters were introduced (~63 writes every bar all session on the sole eval thread); nothing here
 * reintroduces it.
 *
 * <p><b>Deltas, and what a restart looks like.</b> Each row carries the evaluations that landed in
 * the window ENDING at its bucket, i.e. {@code counter now - counter at the last SUCCESSFUL
 * snapshot}. The baseline ({@link #lastSnapshot}) is an in-memory field, so it shares its lifetime
 * with the counters themselves: both reset together, and the first post-boot snapshot reports
 * everything since boot. Therefore a counter reset can never read as negative activity (impossible
 * by construction, not by clamping) and never as a gap ({@code SUM(eval_count)} over a day stays
 * correct across any number of restarts, with no boot-marker column and no boot-aware query). The
 * only loss is a hard stop between the last snapshot and process exit: at most one bucket, &le; 3
 * minutes. Cumulative-per-boot was the alternative and would have needed a {@code boot_id} plus a
 * max-per-boot-then-sum query to answer the same question.
 *
 * <p><b>Missed ticks are self-healing.</b> The baseline advances only AFTER a durable write, so a
 * failed write or a delayed tick is absorbed by the next successful snapshot's delta — nothing is
 * lost, only the 3m resolution of that stretch degrades.
 *
 * <p><b>Zeros are written on purpose.</b> All seven outcomes are persisted every bucket. Rows
 * present with some count &gt; 0 means the process was up AND evaluating; rows present with all
 * counts 0 means the process was up and the scheduler ticking but the eval loop produced nothing;
 * rows ABSENT means the process was down (or the default pool was wedged). Those three states used
 * to be one DB signature, which is the whole defect.
 *
 * <p><b>Scheduler.</b> Unqualified {@code @Scheduled} → the service-wide default
 * {@code taskScheduler}. It deliberately does NOT bind {@code monitorTaskScheduler}: that
 * single-thread pool is fenced (audit BEJ-01 / #919) for pure detectors doing fast in-memory work,
 * and putting a SYNCHRONOUS Postgres write on it would let a DB stall park the sole detector thread
 * — the exact hazard {@link RejectionWriter} and {@link RiskSuppressionWriter} are both async to
 * avoid. The isolation this job would gain is not worth it because its deltas already self-heal
 * across a delayed tick; the sibling DB-writing scheduled job {@link RiskSuppressionPruneJob} makes
 * the same call.
 *
 * <p><b>Lifecycle + parity.</b> Gated on {@code artha.signals.engine-enabled} exactly like
 * {@link SessionLivenessHeartbeat} — it injects {@link SignalEngine}, so it is unwireable without
 * it. It reads counters and writes rows; it touches no {@code SignalEvent}/{@code Trade}, no
 * scoring, no evaluation path, and the golden replay boots no scheduler, so goldens/parity are
 * unaffected by construction. Fully fail-soft: a failure is logged and ops-alerted via the
 * {@link SwingBatchAlert} bridge (the notifier←signals event direction; this module cannot import
 * notifier) and never propagates out of a {@code @Scheduled} tick.
 */
@Component
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true) // shares SignalEngine's lifecycle — it injects the engine
public class SignalEvalOutcomeRollupJob {

  private static final Logger log = LoggerFactory.getLogger(SignalEvalOutcomeRollupJob.class);

  /**
   * The rollup cadence, matching the live scalper primary timeframe so one bucket is roughly one
   * bar's worth of evaluations. IST is +05:30 = 19800s = 110 × 180, so a 3m epoch floor lands on the
   * same instants in UTC and IST and 09:15 IST is always a boundary.
   */
  static final int BUCKET_SECONDS = 180;

  private final SignalEngine engine;
  private final SignalEvalOutcomeRepository repository;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final boolean live;
  private final int retentionDays;

  /**
   * The delta baseline: counter values as of the last SUCCESSFUL write. In-memory on purpose — it
   * must share the counters' JVM lifetime so both reset together (see the class javadoc). Only the
   * single scheduler thread touches it, so a plain {@link EnumMap} is sufficient.
   */
  private final Map<Outcome, Long> lastSnapshot = new EnumMap<>(Outcome.class);

  /** Wires the engine whose counters are snapshotted, the table, the alert bus and the knobs. */
  public SignalEvalOutcomeRollupJob(
      SignalEngine engine,
      SignalEvalOutcomeRepository repository,
      ApplicationEventPublisher events,
      Clock clock,
      Environment environment,
      @Value("${artha.signals.eval-outcome-retention-days:180}") int retentionDays) {
    this.engine = engine;
    this.repository = repository;
    this.events = events;
    this.clock = clock;
    this.live = environment.matchesProfiles("live");
    this.retentionDays = retentionDays;
  }

  /**
   * The 3m snapshot tick. Fires every 3 minutes over hours 09–15 IST on weekdays — a little wider
   * than the 09:15–15:30 session on both sides on purpose, so the pre-open and post-close ticks also
   * witness that the process was up. 140 ticks × 7 outcomes ≈ 980 rows/day. Runs on ALL profiles
   * (unlike the prune): the mock stack writes to its own DB and the rows are how a change here is
   * verified. A weekday holiday simply records all-zero buckets, which is the honest answer.
   */
  @Scheduled(
      cron = "${artha.signals.eval-outcome-rollup.cron:0 */3 9-15 * * MON-FRI}",
      zone = "Asia/Kolkata")
  public void scheduledRollup() {
    rollup();
  }

  /**
   * One snapshot: read the counters, write the per-outcome deltas for this bucket, then advance the
   * baseline. Package-visible so tests drive it directly. Fully fail-soft — returns rather than
   * throws.
   *
   * <p>The baseline is advanced ONLY after {@code upsertBucket} returns, and that write is a single
   * all-or-nothing statement. A failure therefore leaves the baseline where it was, so the counts
   * roll into the next successful snapshot instead of being lost — and can never be written twice.
   *
   * @return total evaluations recorded in this bucket (0 on an idle bucket or a failure)
   */
  long rollup() {
    try {
      Map<Outcome, Long> current = engine.outcomeCounts();
      Map<String, Long> deltas = deltas(lastSnapshot, current);
      OffsetDateTime bucket = bucketFor(clock.instant());
      repository.upsertBucket(bucket, deltas);
      lastSnapshot.putAll(current); // durable — the baseline may now advance
      long total = deltas.values().stream().mapToLong(Long::longValue).sum();
      log.debug("signal_eval_outcomes rollup: bucket={} evaluations={}", bucket, total);
      return total;
    } catch (RuntimeException e) {
      // Baseline untouched: the next successful tick's delta covers this window too.
      log.error("signal_eval_outcomes rollup FAILED: {}", e.getMessage(), e);
      alert(
          "signal_eval_outcomes rollup FAILED",
          "The 3m engine-evaluation counter rollup threw (engine liveness history has a gap for"
              + " this bucket; counts are NOT lost — they roll into the next successful"
              + " snapshot): "
              + e.getMessage());
      return 0;
    }
  }

  /**
   * Per-outcome deltas between the baseline and a fresh counter read, keyed by the stable wire tag.
   * Pure and static so the restart/self-healing semantics are unit-testable without a clock, an
   * engine or a database.
   *
   * <p>Iterates {@code Outcome.values()} rather than the map's keys, so every outcome is emitted
   * every bucket INCLUDING zeros — a zero row is what proves the process was alive. A missing
   * baseline entry reads as 0, which is exactly right at boot: the first snapshot reports everything
   * since boot. A negative delta is unreachable (the counters are monotonic within a JVM and the
   * baseline resets with them), so there is no clamp to hide one.
   */
  static Map<String, Long> deltas(Map<Outcome, Long> baseline, Map<Outcome, Long> current) {
    Map<String, Long> deltas = new LinkedHashMap<>();
    for (Outcome outcome : Outcome.values()) {
      long now = current.getOrDefault(outcome, 0L);
      long then = baseline.getOrDefault(outcome, 0L);
      deltas.put(outcome.tag(), now - then);
    }
    return deltas;
  }

  /**
   * The snapshot instant floored to a {@value #BUCKET_SECONDS}-second boundary, carrying the IST
   * offset (the {@code bar_time} convention of the sibling tables). Static + parameterized for unit
   * testing.
   */
  static OffsetDateTime bucketFor(Instant now) {
    long epochSecond = now.getEpochSecond();
    long floored = epochSecond - Math.floorMod(epochSecond, (long) BUCKET_SECONDS);
    return Instant.ofEpochSecond(floored).atOffset(Ist.OFFSET);
  }

  /**
   * The daily retention tick (02:30 IST), mirroring {@link RiskSuppressionPruneJob} exactly —
   * six-field cron with an explicit Asia/Kolkata zone, property-overridable, live-only (the mock DB
   * is ephemeral, wiped by {@code ay reset-db}, so there is nothing to retain there).
   */
  @Scheduled(
      cron = "${artha.signals.eval-outcome-prune.cron:0 30 2 * * *}",
      zone = "Asia/Kolkata")
  public void scheduledPrune() {
    if (!live) {
      return;
    }
    prune();
  }

  /**
   * One retention pass. Package-visible so the IT drives it directly, bypassing the live gate.
   * Fail-soft.
   *
   * @return rows deleted (0 on a no-op day, a guarded non-positive horizon, or a failure)
   */
  int prune() {
    try {
      if (retentionDays <= 0) {
        // A non-positive horizon makes the cutoff now(), deleting EVERY row. A misconfigured knob
        // must never wipe the liveness history it exists to preserve — skip and alert.
        log.error(
            "signal_eval_outcomes prune SKIPPED: non-positive retention-days={} would delete all"
                + " rows",
            retentionDays);
        alert(
            "signal_eval_outcomes prune misconfigured",
            "artha.signals.eval-outcome-retention-days="
                + retentionDays
                + " (<= 0) — prune skipped to avoid deleting all rows");
        return 0;
      }
      int deleted = repository.deleteOlderThanDays(retentionDays);
      log.info(
          "signal_eval_outcomes prune: deleted {} row(s) older than {}d", deleted, retentionDays);
      return deleted;
    } catch (RuntimeException e) {
      log.error(
          "signal_eval_outcomes prune FAILED (retention={}d): {}", retentionDays, e.getMessage(), e);
      alert(
          "signal_eval_outcomes prune FAILED",
          "The daily signal_eval_outcomes retention prune ("
              + retentionDays
              + "d) threw: "
              + e.getMessage());
      return 0;
    }
  }

  /** Fail-soft ops alert via the signals→notifier event bridge; a failed publish never propagates. */
  private void alert(String title, String message) {
    try {
      events.publishEvent(new SwingBatchAlert("signal-eval-outcome-rollup", title, message));
    } catch (RuntimeException publishFailure) {
      log.warn("signal_eval_outcomes alert publish failed: {}", publishFailure.getMessage());
    }
  }
}
