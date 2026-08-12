package in.arthayantra.strategysignal;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler isolation for the pure liveness DETECTORS (audit BEJ-01). Boot gives {@code @Scheduled}
 * a single default {@code ThreadPoolTaskScheduler} (pool size 1) shared by all ~31 scheduled methods
 * here, so a blocked sibling job (notably the synchronous swing batch) silently freezes
 * every watchdog/canary sweep on the same thread — detection is starvable exactly when the engine is
 * most broken, even though the RECOVERY paths (SignalEngine {@code recoveryExecutor}, off-pool
 * daemons) are decoupled. This gives the detectors their own dedicated single-thread scheduler so
 * their sweeps keep firing regardless of what the default pool is doing.
 *
 * <p>Scope-fenced: ONLY pure detectors move onto {@link #monitorTaskScheduler()} via
 * {@code @Scheduled(scheduler = "monitorTaskScheduler")} — {@code SubscriberHealthCanary.sweep},
 * {@code PartialBucketCanary.sweep}, {@code DotHealthCanary.sweep}. The engine reload trio, PaperScheduler, and every EOD/batch job
 * keep the default pool (their serial single-thread assumption is load-bearing), except for the
 * synchronous swing missed-batch detector and the synchronous multi-session
 * {@code SwingBatchCatchUp}, which each have their own fenced pool below.
 *
 * <p>A FIFTH pool, {@link #evalOutcomeTaskScheduler()}, carries the V045 eval-outcome rollup. It
 * belongs on none of the earlier pools — see that method's javadoc for the shared-pool hazards.
 *
 * <p>A SIXTH, {@link #maintenanceTaskScheduler()}, carries the daily retention prunes. Same
 * reasoning as the fifth, applied to a different risk class — see that method's javadoc, including
 * why the prunes are NOT folded onto the eval-outcome pool.
 *
 * <p>An EIGHTH, {@link #preOpenTaskScheduler()}, carries the two PRE-OPEN paper jobs that moved to
 * morning on 2026-08-12 — see that method for why they are NOT on the catch-up's lane.
 *
 * <p>A SEVENTH, {@link #telegramTaskScheduler()}, carries the live-armed Telegram command poller —
 * the only default-pool job that makes an outbound call to a THIRD PARTY. See that method's javadoc.
 */
@Configuration(proxyBeanMethods = false)
public class MonitorSchedulingConfig {

  /**
   * Re-declares Boot's default scheduler EXPLICITLY. {@code TaskSchedulingAutoConfiguration} backs
   * off ({@code @ConditionalOnMissingBean(TaskScheduler.class)}) the instant any TaskScheduler bean
   * exists, so without this the monitor pool would become the context's SOLE TaskScheduler and
   * {@code TaskSchedulerRouter} would route EVERY unqualified {@code @Scheduled} job onto it —
   * collapsing all jobs back onto one thread and defeating the isolation. Named "taskScheduler" so
   * the router's by-name default fallback (taken on {@code NoUniqueBeanDefinitionException} when two
   * TaskScheduler beans exist) resolves it. Built through the Boot builder, so it stays byte-for-byte
   * the default (pool size 1, {@code scheduling-} prefix, any registered customizers applied).
   */
  @Bean
  public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
    return builder.build();
  }

  /**
   * The dedicated detector pool: a <b>single</b> daemon thread with the {@code monitor-sched-} prefix.
   * One thread is sufficient here (market-data uses two) because the strategy-signal detectors do only
   * fast, bounded work on the sweep thread — in-memory engine heartbeats and the live series store, and
   * at most one bounded local-Postgres read (DotHealthCanary: today's newest 40 rejection rows, LIMIT
   * 40). None makes an external-broker HTTP call with multi-attempt retries the way market-data's
   * session/contract probes do, so no sweep holds this thread long enough to starve a sibling.
   * {@code PartialBucketCanary} left this pool at G9 when it acquired external dependencies — see
   * {@link #partialBucketTaskScheduler()}. <b>A detector that gains ANY blocking call must move off
   * this pool too; catching the exception is not containment, because a STALLED call starves every
   * sibling while it hangs.</b>
   * Detectors bind by qualifier/bean-name; their recovery triggers stay off-pool
   * ({@code SignalEngine.forceResubscribe} only enqueues on the recovery executor and returns).
   */
  @Bean
  public ThreadPoolTaskScheduler monitorTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("monitor-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code PartialBucketCanary}. It was a pure in-memory
   * detector on {@code monitorTaskScheduler} until G9 gave it two external dependencies — an
   * instrument-master lot-size lookup and a Redis-backed store for the at-most-one deferred
   * straddle half — and the monitor pool is fenced for detectors that never make a blocking call.
   * The lot lookup is already non-blocking (cache read + off-thread prefetch) and the Redis calls
   * are individually time-bounded, so this pool exists to contain the residual: a slow or stalled
   * Redis must delay only this canary's own next sweep, never {@code SubscriberHealthCanary} or
   * {@code DotHealthCanary} sitting beside it.
   */
  @Bean
  public ThreadPoolTaskScheduler partialBucketTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("partial-bucket-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by the swing missed-batch detector. Its bounded local
   * Postgres reads and alert publication must not share the default pool with paper SL/TP evaluation,
   * and it is deliberately separate from {@code monitorTaskScheduler}, which is fenced for pure
   * in-memory liveness detectors only.
   */
  @Bean
  public ThreadPoolTaskScheduler swingDetectorTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("swing-detector-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code SwingBatchCatchUp.catchUp}. The catch-up is a
   * synchronous multi-session DB + market-data HTTP sweep that can run for several minutes, and it
   * can emit real paper entries/exits. Leaving it on the DEFAULT pool would let it park
   * {@code PaperScheduler.bracketEvaluation}, the 15-second stop-loss/target sweep, along with every
   * other default scheduled job.
   *
   * <p><b>Why not {@code monitorTaskScheduler}.</b> That pool is fenced for pure liveness DETECTORS.
   * The catch-up has money effects and blocking I/O; putting it there could starve
   * {@code SubscriberHealthCanary} and {@code DotHealthCanary} exactly
   * when the recovery path is slow. <b>Why not {@code swingDetectorTaskScheduler}.</b> Same fence
   * from the other side: a multi-minute replay parked on the detector thread would suppress the
   * next-morning missed-batch page — the detector must keep firing precisely while recovery runs.
   *
   * <p>⚠️ <b>And why the two morning paper jobs are NOT here, though an earlier revision of the
   * 2026-08-12 schedule move put them here.</b> Sharing this thread would have bought a real thing —
   * a cron minute is not a dependency, so 08:50 on another pool can read a catch-up that is still
   * mid-run, while queueing behind it cannot. It was still the wrong trade. It widened a hung
   * catch-up's blast radius from "no swing entries" to "no swing entries AND no reconciliation AND
   * no past-expiry recovery", and NOTHING detects that hang: {@code SwingBatchCanary} fires at 08:30,
   * before the pass starts, on {@code hasRun} — a marker the 16:00 exit pass has already written —
   * while the entry pass needs {@code hasRunWithEntries}.
   *
   * <p>Weighed both ways: the cost of NOT queueing is that a read-only reporter may observe torn
   * mid-catch-up state and report a discrepancy that is not real. The cost of queueing is two money
   * jobs silently not running at all. A noisy read-only report is the lesser harm, and the overlap
   * needs a catch-up lasting the full 15 minutes between 08:35 and 08:50 — measured at 81 s for both
   * families on 2026-08-12 — which is the hang case that the shared lane made worse rather than
   * better. A watchdog keyed to entry completion is still worth building; it is a change of its own,
   * not a rider, and this arrangement does not depend on it.
   *
   * <p>The per-family {@code SwingRunMutex} remains the run-serialization guard. This pool only removes
   * scheduler starvation; it does not replace the mutex or provide durable idempotency.
   */
  @Bean
  public ThreadPoolTaskScheduler swingCatchUpTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("swing-catchup-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * An EIGHTH pool: one daemon thread for the two PRE-OPEN paper jobs —
   * {@code PaperReconciliationScheduler.run} (08:50) and {@code PaperScheduler.pastExpiryRecovery}
   * (08:52), both moved to morning on 2026-08-12 because the machine is off by 19:00 and their
   * 21:15/21:20 evening slots therefore never ran.
   *
   * <p>Past-expiry recovery is the reason this exists rather than leaving them on the default pool:
   * it does sequential per-position REST reads with 30-second timeouts, and on the default pool an
   * overrun past 09:15 would stall {@code PaperScheduler.bracketEvaluation}, the 15-second
   * stop-loss/target sweep, at exactly the wrong moment of the day.
   *
   * <p>Pool size 1 so the two serialize against each other — past-expiry's own javadoc places it
   * "just after" the reconciler, and one thread makes that true rather than merely scheduled. The
   * blast radius of a hang is these two only, which is strictly narrower than the default pool they
   * came from, where a hang took the bracket sweep with it.
   */
  @Bean
  public ThreadPoolTaskScheduler preOpenTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("pre-open-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code SignalEvalOutcomeRollupJob} (V045). Unlike the
   * pools above, this one exists to be EXPENDABLE: the rollup makes a synchronous JDBC write, and
   * the whole point is that a DB lock, a lock wait, or a network stall can park nothing but the
   * rollup itself.
   *
   * <p>The earlier pools were rejected for concrete reasons:
   *
   * <ul>
   *   <li><b>The default {@code taskScheduler} is a single thread shared with money-adjacent work.</b>
   *       There is no scheduler pool sizing anywhere in {@code application.yml} (the
   *       {@code maximum-pool-size: 5} there is Hikari, the datasource — not this), so Boot's
   *       default of ONE applies. A live thread census on {@code ay-strategy-signal-service} shows
   *       {@code scheduling-1} carrying {@code PaperStaleTickAlerter} (paper SL/TP starvation
   *       alerting) alongside {@code SignalEngine} reconcile. A stalled observability write there
   *       would park stop-loss evaluation — precisely the class of silent failure this table exists
   *       to make visible.
   *   <li><b>{@code monitorTaskScheduler} is fenced</b> (audit BEJ-01 / #919) for pure detectors
   *       doing fast, bounded, in-memory work. A synchronous Postgres write on that single thread
   *       could starve {@code SubscriberHealthCanary} and {@code PartialBucketCanary} — the exact
   *       hazard {@code RejectionWriter} and {@code RiskSuppressionWriter} are both async to avoid.
   *   <li><b>{@code swingDetectorTaskScheduler} is detector-dedicated.</b> A stalled rollup there
   *       would suppress the next-morning missed-batch page.
   *   <li><b>{@code swingCatchUpTaskScheduler} is the money-effect recovery lane.</b> A rollup
   *       stall there would delay a catch-up that can emit real paper entries and exits.
   * </ul>
   *
   * <p>Belt-and-braces, the write is also BOUNDED: {@code SignalEvalOutcomeRepository} runs on its
   * own {@code JdbcTemplate} with an explicit query timeout, so even this thread cannot hang
   * indefinitely. A missed tick is harmless by design — the V045 delta protocol differences against
   * the last DURABLE row, so the next successful tick absorbs every skipped window exactly once.
   */
  @Bean
  public ThreadPoolTaskScheduler evalOutcomeTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("eval-outcome-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread for the daily 02:30 IST retention prunes —
   * {@code RiskSuppressionPruneJob} and {@code CompositeRejectionPruneJob}. Both issue a synchronous
   * row-wise {@code DELETE}, and both were on the DEFAULT pool.
   *
   * <p><b>Why they had to move.</b> There is no scheduler pool sizing anywhere in
   * {@code application.yml} (the {@code maximum-pool-size: 5} there is Hikari, the datasource), so
   * Boot's default of ONE thread applies and a live thread census shows {@code scheduling-1}
   * carrying {@code PaperStaleTickAlerter} (paper SL/TP starvation alerting), {@code SignalEngine}
   * reconcile, and {@code PaperScheduler.bracketEvaluation} — the 15-second stop-loss/target sweep.
   * A DELETE that blocks on a Postgres lock at 02:30 parks that thread indefinitely, and the next
   * session's stop-loss evaluation simply never fires. Money-adjacent, and silent.
   *
   * <p><b>Why BOTH prunes move, not just one.</b> The hazard is the pool, not the job: leaving
   * either prune on the default pool leaves the identical wedge in place, so moving one alone would
   * buy nothing.
   *
   * <p><b>Why not {@link #evalOutcomeTaskScheduler()}, which is already the "expendable synchronous
   * JDBC" pool.</b> Because that pool's OUTPUT is evidence whose ABSENCE is interpreted: a missing
   * {@code signal_eval_outcomes} row is read as "the process was down or its rollup could not
   * write". A prune wedging at 02:30 would silence the 09:00–15:30 rollup ticks and manufacture
   * exactly the false "engine was dead" reading that V045 exists to prevent — the same misreading
   * that cost a live trading service an unnecessary restart on 2026-07-20. Retention work must not
   * be able to forge a liveness verdict, so it gets its own thread.
   *
   * <p><b>Why not {@code monitorTaskScheduler}.</b> Fenced (audit BEJ-01 / #919) for pure in-memory
   * detectors; a synchronous DELETE there could starve {@code SubscriberHealthCanary},
   * {@code DotHealthCanary} and {@code PartialBucketCanary}.
   *
   * <p>Belt-and-braces, both DELETEs are also BOUNDED by a query timeout on their repository's
   * private {@code JdbcTemplate}, so a wedge cannot outlive the statement timeout and cannot hold a
   * Hikari connection (pool of 5) for the session. Pool isolation alone would leave that tail open;
   * the timeout alone would still let a slow-but-legal DELETE delay stop-loss evaluation. Both are
   * needed.
   */
  @Bean
  public ThreadPoolTaskScheduler maintenanceTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("maintenance-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code TelegramCommandBot.poll} (S2, scheduler-binding
   * sweep 2026-07-25). The poller is LIVE-ARMED, fires every 3 s, and is the only default-pool job
   * that makes an outbound HTTPS call to a THIRD PARTY ({@code api.telegram.org}) — a dependency we
   * neither run nor monitor. It shared the single default thread with
   * {@code PaperScheduler.bracketEvaluation}, the 15-second live stop-loss/target sweep: one
   * long-poll or TLS stall on Telegram's side delays SL/TP evaluation for as long as it lasts. That
   * is money-adjacent, and it is the wrong risk to take for a convenience surface — the bot's own
   * javadoc already promises "the bot can never break the signal path", and this makes the schedule
   * honour it too.
   *
   * <p><b>Why the poller moves and {@code bracketEvaluation} stays.</b> The paper jobs' serial
   * single-thread ordering on the default pool is load-bearing (the 15:30/15:35/15:45 expiry →
   * settle → mark-to-close chain runs in sequence there); the Telegram poller has no such
   * relationship to anything. Moving the one job with an external, unbounded dependency off the pool
   * is the minimum change that removes the coupling.
   *
   * <p><b>Why not the other pools.</b> {@code monitorTaskScheduler} is fenced (audit BEJ-01 / #919)
   * for pure in-memory detectors — a Telegram stall there would starve
   * {@code SubscriberHealthCanary} and {@code DotHealthCanary}. {@code evalOutcomeTaskScheduler}'s
   * output is evidence whose ABSENCE is read as "the engine was dead", so a stalled poller there
   * would forge a liveness verdict (the 2026-07-20 misdiagnosis). {@code maintenanceTaskScheduler}
   * exists precisely so retention work cannot do that either.
   *
   * <p><b>Concurrency.</b> {@code poll()}'s own state is safe by construction: it is the sole writer
   * of its two {@code volatile} offset/drain fields and of the {@code ConcurrentHashMap} of pending
   * confirmations, and one thread still runs it serially. What DOES change is that the two levers
   * {@code /confirm} pulls are no longer serialized against the default pool's paper jobs — but
   * neither was single-threaded to begin with: {@code RiskSettingsRepository.upsert} is already
   * driven concurrently by {@code PUT /api/v1/risk/settings} (RiskController → RiskService.update),
   * and the close path behind {@code /flatten} is already driven concurrently by {@code POST
   * /api/v1/paper/positions/{id}/close} (PaperController → PaperService.closePosition) on Tomcat
   * worker threads. Both are guarded where it counts — the close is a compare-and-set
   * ({@code UPDATE paper_positions SET status='CLOSED' ... WHERE id=? AND status='OPEN'}), so a
   * duplicate settle updates zero rows. This adds a caller to an already-concurrent path rather than
   * a new concurrency class.
   */
  @Bean
  public ThreadPoolTaskScheduler telegramTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("telegram-poll-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }
}
