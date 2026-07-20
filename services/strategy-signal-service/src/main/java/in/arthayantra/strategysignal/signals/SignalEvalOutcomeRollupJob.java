package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.signals.SignalEngine.Outcome;
import in.arthayantra.strategysignal.signals.SignalEngine.OutcomeSnapshot;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * only evidence of it.
 *
 * <p><b>Zero added work on the eval thread — non-negotiable.</b> This is a scheduled READER of
 * counters that already live in memory ({@link SignalEngine#outcomeSnapshot()}), never an inline
 * write in the evaluation path. A row per no-entry per strategy per bar was deliberately rejected
 * when the counters were introduced (~63 writes every bar all session on the sole eval thread);
 * nothing here reintroduces it.
 *
 * <p><b>Stateless writer, DURABLE checkpoint.</b> Each tick reads the counters, reads the last
 * persisted {@code cumulative_count} for this boot back FROM THE DATABASE, and writes the
 * difference. This object keeps no state between ticks. An earlier revision held the checkpoint in a
 * field and cross-vendor review rejected it: an in-memory baseline double-counts when Postgres
 * commits but the acknowledgement is lost (the next tick re-covers evaluations the committed row
 * already holds), and loses everything since the last successful write if a failure is followed by a
 * restart. Differencing against durable state closes both. Full protocol + the precise guarantee
 * boundary: the {@code V043__signal_eval_outcomes.sql} header.
 *
 * <p><b>What is and is not guaranteed.</b> No double count on any failure path; no negative delta; a
 * failed or delayed tick loses nothing (the next successful tick absorbs every skipped window
 * exactly once, degrading only 3m resolution). NOT guaranteed: evaluations between the last
 * successful write and process death — they exist only in JVM memory. {@link #flushOnShutdown()}
 * closes that window on a GRACEFUL stop, so a planned restart or deploy loses nothing; a hard kill
 * leaves it open, bounded by the time since the last successful write (which is longer than 3
 * minutes if writes were already failing — it is not a fixed 3-minute bound).
 *
 * <p><b>Zeros are written on purpose.</b> All seven outcomes are persisted every bucket. Rows
 * present with some count &gt; 0 means the process was up AND evaluating; rows present with all
 * counts 0 means the process was up and the scheduler ticking but the eval loop produced nothing;
 * rows ABSENT means the process was down or its rollup could not write. Those three states used to
 * be one DB signature, which is the whole defect.
 *
 * <p><b>Scheduler.</b> Both ticks bind {@link #SCHEDULER}, a dedicated single daemon thread. Neither
 * alternative is safe: the DEFAULT pool is a single thread that live logs show carrying
 * {@code PaperStaleTickAlerter} (paper SL/TP starvation alerting) and {@code SignalEngine}
 * reconcile, so a stalled observability write there would park stop-loss evaluation; and
 * {@code monitorTaskScheduler} is fenced (audit BEJ-01 / #919) for pure in-memory detectors, where a
 * synchronous JDBC write could starve {@code SubscriberHealthCanary}. See
 * {@code MonitorSchedulingConfig.evalOutcomeTaskScheduler}. The write is additionally BOUNDED by a
 * query timeout on the repository's private template.
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
   * The dedicated scheduler bean name. Referenced by both {@code @Scheduled} methods and pinned by
   * {@code MonitorSchedulingConfigTest}, so a refactor cannot silently drop these back onto the
   * shared default pool that carries paper stop-loss alerting.
   */
  static final String SCHEDULER = "evalOutcomeTaskScheduler";

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
      zone = "Asia/Kolkata",
      scheduler = SCHEDULER)
  public void scheduledRollup() {
    rollup();
  }

  /**
   * A final snapshot on GRACEFUL shutdown, closing the one window the protocol cannot otherwise
   * cover: evaluations since the last successful write live only in JVM memory, and a planned
   * restart or deploy would take them with it. Flushing here means the 2026-07-20 shape — a
   * deliberate restart of a healthy engine — no longer destroys the evidence of that session.
   *
   * <p>Cheap and safe to run even if the scheduled tick just ran: the delta is then simply the few
   * evaluations since, or zero. A hard kill (SIGKILL / OOM) skips this by definition.
   */
  @PreDestroy
  void flushOnShutdown() {
    log.info("signal_eval_outcomes: final flush before shutdown");
    rollup();
  }

  /**
   * One snapshot: read the counters, difference them against the last DURABLE row for this counter
   * epoch, and persist. Package-visible so tests drive it directly. Fully fail-soft — returns rather
   * than throws.
   *
   * <p>No state is carried between calls. A failure therefore needs no compensation: the next tick
   * re-reads the checkpoint, sees whatever actually committed, and covers exactly the windows that
   * are genuinely unrecorded.
   *
   * @return total evaluations recorded in this bucket (0 on an idle bucket or a failure)
   */
  long rollup() {
    try {
      OutcomeSnapshot snapshot = engine.outcomeSnapshot();
      Map<String, Long> cumulative = byTag(snapshot.counts());
      SignalEvalOutcomeRepository.Checkpoint checkpoint =
          repository.lastCumulativeForBoot(snapshot.epoch());
      Map<String, Long> deltas = deltas(checkpoint.cumulative(), cumulative);
      OffsetDateTime bucket = bucketFor(clock.instant());
      OffsetDateTime recoveredFrom = crossDateOrigin(checkpoint.bucketTime(), bucket);
      repository.upsertBucket(bucket, snapshot.epoch(), deltas, cumulative, recoveredFrom);
      long total = deltas.values().stream().mapToLong(Long::longValue).sum();
      if (recoveredFrom != null) {
        log.warn(
            "signal_eval_outcomes: {} evaluation(s) recovered across an IST date boundary"
                + " ({} -> {}) and recorded as UNATTRIBUTABLE — the counters carry no timing, so"
                + " they cannot be split between the dates they span. Both dates' attributable"
                + " totals exclude them; the canonical query surfaces them separately.",
            total, recoveredFrom, bucket);
      }
      log.debug(
          "signal_eval_outcomes rollup: bucket={} boot={} evaluations={} unattributable={}",
          bucket, snapshot.epoch(), total, recoveredFrom != null);
      return total;
    } catch (RuntimeException e) {
      // Nothing to compensate: the checkpoint is durable, so the next tick differences against
      // whatever actually committed and covers this window exactly once.
      log.error("signal_eval_outcomes rollup FAILED: {}", e.getMessage(), e);
      alert(
          "signal_eval_outcomes rollup FAILED",
          "The 3m engine-evaluation counter rollup threw (engine liveness history has a gap for"
              + " this bucket; counts are NOT lost — the next successful snapshot absorbs them): "
              + e.getMessage());
      return 0;
    }
  }

  /**
   * Re-keys a counter snapshot by the stable wire tag, emitting EVERY outcome including those still
   * at zero — a zero row is what proves the process was alive.
   */
  static Map<String, Long> byTag(Map<Outcome, Long> counts) {
    Map<String, Long> byTag = new LinkedHashMap<>();
    for (Outcome outcome : Outcome.values()) {
      byTag.put(outcome.tag(), counts.getOrDefault(outcome, 0L));
    }
    return byTag;
  }

  /**
   * Per-outcome growth between the durable checkpoint and a fresh counter read. Pure and static so
   * the restart/failure semantics are unit-testable without a clock, an engine or a database.
   *
   * <p>A missing checkpoint entry reads as 0, which is exactly right for a boot's first tick: it
   * reports everything since boot. A negative delta is unreachable — the counters are monotonic
   * within an epoch, and a new epoch necessarily has no checkpoint of its own — so there is no clamp
   * hiding one.
   */
  static Map<String, Long> deltas(Map<String, Long> checkpoint, Map<String, Long> cumulative) {
    Map<String, Long> deltas = new LinkedHashMap<>();
    cumulative.forEach((tag, now) -> deltas.put(tag, now - checkpoint.getOrDefault(tag, 0L)));
    return deltas;
  }

  /**
   * The originating checkpoint bucket IF this tick's delta window crosses an IST session date, else
   * {@code null}.
   *
   * <p><b>Why this exists.</b> A delta covers {@code (checkpoint, bucket]}, and the recovery of a
   * failed or skipped stretch makes that window arbitrarily long. Without this check the ENTIRE
   * recovered window is attributed to the CURRENT bucket. The realistic path: the process runs
   * continuously across a weekend (so {@code boot_id} is stable), writes start failing Friday 15:00,
   * no ticks fire over the weekend, and Monday's first tick dumps Friday's final half hour into
   * Monday 09:00 — Friday under-reports, Monday over-reports, and BOTH days' outcome mix is wrong.
   * That is precisely the question this table exists to answer, silently wrong on two days at once.
   *
   * <p><b>Why it MARKS rather than re-attributes.</b> The counters carry no timing — only a total —
   * so a span covering Wednesday through Friday genuinely mixes three sessions and cannot be split.
   * Attributing it to the origin date would be exactly as wrong as attributing it to today, just
   * less obviously. So a cross-date recovery is recorded in full (nothing is ever lost) and MARKED:
   * the canonical query excludes marked rows from the attributable total and surfaces them as
   * {@code unattributable}. Honest beats convenient, and no guessing is smuggled in.
   *
   * <p><b>IST normalization is load-bearing.</b> JDBC returns {@code bucket_time} in whatever offset
   * the driver/session uses, typically UTC, so both sides are converted to the IST offset before
   * comparing calendar dates. Skipping that would silently misjudge any bucket whose UTC date
   * differs from its IST date — reachable via {@link #flushOnShutdown()}, which can write at any
   * wall-clock time (a 02:00 IST restart is the previous calendar day in UTC).
   *
   * @param checkpoint the previous durable bucket, or null when this boot has none yet
   * @param bucket the bucket being written now
   */
  static OffsetDateTime crossDateOrigin(OffsetDateTime checkpoint, OffsetDateTime bucket) {
    if (checkpoint == null) {
      // A boot's FIRST tick has no prior date to span. Its delta is "since boot", and boot is
      // necessarily after the previous process ended, so there is nothing to mis-attribute.
      return null;
    }
    return istDate(checkpoint).equals(istDate(bucket)) ? null : checkpoint;
  }

  /**
   * The IST calendar date of an instant. Sessions run 09:15–15:30 IST and never cross IST midnight,
   * so the IST calendar date IS the session date. Always normalize through {@link Ist#OFFSET} first
   * — reading {@code toLocalDate()} off a UTC-offset value is the repo's standing off-by-one trap.
   */
  static LocalDate istDate(OffsetDateTime instant) {
    return instant.withOffsetSameInstant(Ist.OFFSET).toLocalDate();
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
   * is ephemeral, wiped by {@code ay reset-db}, so there is nothing to retain there). Bound to the
   * dedicated pool for the same reason as the rollup: a slow DELETE must not park paper stop-loss
   * alerting.
   */
  @Scheduled(
      cron = "${artha.signals.eval-outcome-prune.cron:0 30 2 * * *}",
      zone = "Asia/Kolkata",
      scheduler = SCHEDULER)
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
        // A non-positive horizon makes the cutoff now(), deleting EVERY row — including every live
        // boot's checkpoint, which would make the next delta re-report the whole session. A
        // misconfigured knob must never do that: skip and alert.
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
