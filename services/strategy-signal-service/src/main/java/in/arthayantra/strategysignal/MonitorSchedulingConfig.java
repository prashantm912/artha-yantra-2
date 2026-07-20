package in.arthayantra.strategysignal;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler isolation for the pure liveness DETECTORS (audit BEJ-01). Boot gives {@code @Scheduled}
 * a single default {@code ThreadPoolTaskScheduler} (pool size 1) shared by all ~31 scheduled methods
 * here, so a blocked sibling job (notably the synchronous 20:00/20:05 swing batch) silently freezes
 * every watchdog/canary sweep on the same thread — detection is starvable exactly when the engine is
 * most broken, even though the RECOVERY paths (SignalEngine {@code recoveryExecutor}, off-pool
 * daemons) are decoupled. This gives the detectors their own dedicated single-thread scheduler so
 * their sweeps keep firing regardless of what the default pool is doing.
 *
 * <p>Scope-fenced: ONLY pure detectors move onto {@link #monitorTaskScheduler()} via
 * {@code @Scheduled(scheduler = "monitorTaskScheduler")} — {@code SubscriberHealthCanary.sweep},
 * {@code PartialBucketCanary.sweep}, {@code SignalStarvationCanary.sweep} (dormant),
 * {@code DotHealthCanary.sweep}. The engine reload trio, PaperScheduler, and every EOD/batch job
 * keep the default pool (their serial single-thread assumption is load-bearing).
 *
 * <p>A THIRD pool, {@link #evalOutcomeTaskScheduler()}, carries the V045 eval-outcome rollup. It
 * belongs on neither of the other two — see that method's javadoc for why both were rejected.
 *
 * <p>A FOURTH, {@link #maintenanceTaskScheduler()}, carries the daily retention prunes. Same
 * reasoning as the third, applied to a different risk class — see that method's javadoc, including
 * why the prunes are NOT folded onto the eval-outcome pool.
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
   * A single daemon thread owned solely by {@code SignalEvalOutcomeRollupJob} (V045). Unlike the two
   * pools above, this one exists to be EXPENDABLE: the rollup makes a synchronous JDBC write, and
   * the whole point is that a DB lock, a lock wait, or a network stall can park nothing but the
   * rollup itself.
   *
   * <p>Both other pools were rejected, each for a concrete reason:
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
}
