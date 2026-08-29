package in.arthayantra.marketdata;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler isolation for the pure liveness DETECTORS (audit BEJ-01). Boot gives {@code @Scheduled}
 * a single default {@code ThreadPoolTaskScheduler} (pool size 1) shared by most scheduled methods
 * here (**30 of 41 as of 2026-08-29 — the exact figure is machine-checked by {@code
 * ScheduledPoolCensusTest}, which is the only thing that can re-derive it; this sentence said
 * "~32" for months with nothing able to confirm or refute it**), so a blocked sibling job (an EOD/backfill/snapshot job that hangs on I/O) silently freezes
 * every watchdog/canary sweep on the same thread — detection is starvable exactly when the stack is
 * most broken, even though the RECOVERY paths (feed restart, off-pool daemons) are decoupled. This
 * gives the detectors their own dedicated single-thread scheduler so their sweeps keep firing
 * regardless of what the default pool is doing.
 *
 * <p>Scope-fenced: ONLY pure detectors move onto {@link #monitorTaskScheduler()} via
 * {@code @Scheduled(scheduler = "monitorTaskScheduler")} — {@code FeedWatchdog.check},
 * {@code DataHealthCanary.sweep}, {@code SessionHealthProbe.scheduledProbe}, (2026-08-11)
 * {@code EveningChainCanary.check} — the single-shot pre-shutdown "is tonight's chain done" push,
 * bound here for the same reason as its siblings: it is exactly the detector that must never be
 * starved by a hung batch job, since a hung job is precisely what it exists to notice — and
 * (2026-08-26) {@code KiteAutoLoginService.scheduledLogin} + {@code .watchdog}, which sit beside
 * {@code SessionHealthProbe} for the same reason it does: bounded synchronous HTTP that must not
 * queue behind the ~70 s options pass on the morning it is the live feed's precondition. (Both are
 * default-OFF: their bean is {@code @ConditionalOnProperty}, so today they occupy no thread at
 * all.) Every other job keeps the default pool (its serial single-thread assumption is load-bearing for the batch jobs).
 *
 * <p>A THIRD pool, {@link #barFlushTaskScheduler()}, carries the 1 s bar-close sweep. It is neither a
 * detector nor a batch job — see that method's javadoc (S1, 2026-07-25).
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
   * The dedicated detector pool. <b>Two threads</b> here (vs. one in strategy-signal): unlike the
   * strategy-signal detectors — pure in-memory reads that never block — two of the market-data
   * detectors do bounded-but-slow synchronous network I/O on the monitor thread:
   * {@code SessionHealthProbe.scheduledProbe} does a {@code getProfile} HTTP call (bounded by the
   * global {@code read-timeout: 60s}) and, on the first LIVE transition of the day, its on-live hook
   * runs {@code ContractCanary.runNow()} — four sequential Kite probes through {@code KiteCallExecutor}
   * (Retry max 4 attempts, backoff to 8s), all on the caller thread, so up to a couple of minutes.
   * With a single monitor thread that would queue {@code FeedWatchdog.check} + {@code DataHealthCanary.sweep}
   * behind it — re-introducing the exact starvation this fixes. A second thread keeps the fast
   * feed/data detectors live while the session probe is mid-call. Their own recovery triggers stay
   * off-pool ({@code FeedPipeline.restartFeed} is a bounded stop/start).
   */
  @Bean
  public ThreadPoolTaskScheduler monitorTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("monitor-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code CandlesConfig.CandleHousekeeping.flushBars} — the
   * 1 s sweep that closes every 1m bar past its minute+grace (S1, scheduler-binding sweep
   * 2026-07-25). It was on the DEFAULT pool, which is one thread shared with
   * {@code OptionsSnapshotService.scheduledSnapshot}, whose own javadoc sizes one full pass at
   * "~70 batched calls ≈ 70 s at the 1/s limit". While that pass holds the thread NO bar closes: a
   * 1m bar can land in {@code candles} up to ~70 s late, and the live engine's bar-close eval and
   * receipt heartbeats drift toward their thresholds behind it. This is an AVAILABILITY fix — bar
   * CONTENT is unchanged either way, only when it becomes readable.
   *
   * <p>Widening the default pool was rejected: more threads REDUCE the odds of the collision without
   * removing it, and the batch jobs on that pool rely on its serial single-thread ordering. A
   * dedicated thread removes the queueing entirely — the sweep can only ever wait on itself.
   *
   * <p><b>Why not {@link #monitorTaskScheduler()}.</b> Fenced (audit BEJ-01 / #919) for pure liveness
   * DETECTORS. {@code flushBars} is a write path — per closed bar it does synchronous JDBC plus a
   * Redis publish through {@code BarWriter} — so parking it there could starve
   * {@code FeedWatchdog.check} and {@code DataHealthCanary.sweep}, i.e. it would trade a data-latency
   * problem for a detection-blindness one.
   *
   * <p>No new concurrency: {@code CandleBuilder.flush()} already ran concurrently with the feed
   * thread's {@code onNormalizedTick} and guards every accumulator with the same {@code
   * synchronized (acc)} monitor, so which scheduler thread calls it is immaterial. Nothing on the
   * default pool observes the sweep's ordering — it is a fire-and-forget sink write.
   */
  @Bean
  public ThreadPoolTaskScheduler barFlushTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("bar-flush-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code FuturesOiSnapshotService.scheduledSnapshot} (T12 /
   * ledger G5). Its per-minute pass needs exactly ONE batched {@code quotes()} permit, but it was
   * losing roughly every other minute — 161 single-minute skips — and the cause is neither the
   * budget nor the cron.
   *
   * <p><b>The primary cause is SCHEDULER STARVATION, not the rate limiter.</b> Both snapshot jobs sat
   * on the single default {@code taskScheduler} (pool size 1, ~32 scheduled methods). While
   * {@code OptionsSnapshotService}'s pass held that one thread — {@link #barFlushTaskScheduler}
   * already sizes it at "~70 batched calls ≈ 70 s at the 1/s limit" — this cron could not even be
   * INVOKED, so its fires were dropped before any permit was requested. Moving it to its own thread
   * is therefore the fix; the patience ladder in {@code FuturesOiSnapshotService} is the secondary
   * half, and it only becomes relevant AFTER this isolation, because only then can the two passes
   * contend at the limiter concurrently rather than one simply blocking the other.
   *
   * <p><b>Budget.</b> Kite documents the quote endpoint at 1 req/second, exactly what the
   * {@code kite-quote} limiter is configured to — so any SECOND dedicated limiter would put us at
   * 2/s, over Kite's published budget. That is why the row's literal "dedicated quote limiter"
   * framing is NOT what was built. Nominal scheduled demand is close to the ceiling, not comfortably
   * under it: ~35/min from the options snapshot pass, up to ~24/min from
   * {@code OptionsSnapshotService.scheduledBroadcast} (six configured underlyings × two quote calls
   * through the default chain), plus 1/min here — roughly 60 of 60 permits/min. Completion-based
   * {@code fixedDelay} keeps ACTUAL throughput below that, so the budget is not proven to be
   * exceeded, but it is not "well inside" either. If {@code ay_futures_oi_snapshot_skips_total}
   * starts advancing, capacity — not patience — is the thing to look at.
   *
   * <p><b>Why not {@link #monitorTaskScheduler()}.</b> Same fence as {@code barFlushTaskScheduler}:
   * that pool is reserved for pure liveness DETECTORS, and this is a write path (batched quote fetch
   * + JDBC insert). Parking it there could starve {@code FeedWatchdog.check} and
   * {@code DataHealthCanary.sweep}.
   */
  @Bean
  public ThreadPoolTaskScheduler oiCaptureTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("oi-capture-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code NseEodScheduler.retryFailedSources} — the
   * intra-day retry for the three NSE EOD sources, which fires at 09:50/11:50/14:50 IST, INSIDE
   * market hours.
   *
   * <p><b>Why it cannot sit on the default pool.</b> Its worst case is long and its bad day is
   * exactly the day it runs. {@code LiveParticipantOiFetcher} walks back six days SEQUENTIALLY,
   * each call through {@code NseHttpClient}'s 8 s connect + 12 s read budget, so a blackholed
   * network — connections that HANG rather than refuse, the 2026-08-19/20 shape — holds the thread
   * for ~120 s, plus ~20 s for FII/DII. On the default pool that stalls all ~32 scheduled methods,
   * and two of them are in-session write paths that would visibly lose work:
   * {@code OptionsSnapshotService.scheduledSnapshot} (every 2 minutes, ~70 s per pass — so a hold
   * this long costs a whole live OI capture) and {@code InstrumentSyncScheduler.morningSyncCatchUp},
   * whose own javadoc names this hazard as "the exact defect S1 was just fixed for".
   *
   * <p><b>Why not {@link #monitorTaskScheduler()}.</b> Same fence as {@code barFlushTaskScheduler}
   * and {@code oiCaptureTaskScheduler}: that pool is reserved for pure liveness DETECTORS, and this
   * is an external-HTTP write path — the single worst thing to park next to
   * {@code FeedWatchdog.check}.
   *
   * <p>Moving the CRON MINUTE was the cheaper alternative and is deliberately not what was done. It
   * would dodge the two collisions named above, but the ~140 s hold stalls the default pool from
   * whatever minute it starts on; the minute was never the hazard.
   */
  @Bean
  public ThreadPoolTaskScheduler nseRetryTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("nse-retry-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code BhavcopyCloseCanary.prefetchPopulation} — the
   * 16:05 IST pass that fetches the close canary's OWN comparison population (the NIFTY 200
   * reference list) through the rate-limited Kite historical path.
   *
   * <p><b>Why it cannot sit on the default pool.</b> That pool is ONE thread shared by ~32
   * scheduled methods, including the entire 18:45→18:58 evening chain (bhavcopy → NSE EOD →
   * screens → context → data-quality → breadth → buyable → close canary), and the machine is hard
   * off at 19:00. This pass is 202 sequential calls paced by the {@code kite-historical} limiter
   * at 3 req/s — ~71 s measured. But its WORST case is not 71 s: every call goes through {@code
   * KiteCallExecutor}'s Retry (4 attempts, backoff to 8 s) over a 60 s HTTP read-timeout, so a Kite
   * brown-out makes this pass arbitrarily long. Sharing the default pool, an overrun starting at
   * 16:05 would still be holding the only thread at 18:45 and would push the bhavcopy ingest and
   * every screen behind it off the end of the evening — trading the whole batch for a data-quality
   * check. Its own thread means an overrun can only ever starve itself.
   *
   * <p><b>Why not {@link #monitorTaskScheduler()}.</b> Same fence as {@link
   * #barFlushTaskScheduler()} and {@link #oiCaptureTaskScheduler()}: that pool is reserved for pure
   * liveness DETECTORS. This is a WRITE path (Kite fetch + {@code upsertAuthoritativeAll}), so
   * parking it there could starve {@code FeedWatchdog.check} and {@code DataHealthCanary.sweep}.
   *
   * <p><b>The 16:05 cron is not the only caller of that pass.</b> {@code
   * BhavcopyCloseCanary.catchUpPopulation} replays a MISSED pass at boot, and it runs on {@code
   * BhavcopyStartupCatchup}'s own one-shot thread rather than here. ⚠️ The reason is NOT that it
   * must precede that bean's bhavcopy projection — an earlier version of this note said so, and
   * {@code BhavcopyStartupCatchup} (:50, :89-93) now says the opposite outright: nothing orders the
   * two, any ordering is an accident of timing, and the wait that used to enforce it was deleted
   * because the provenance CASE makes bhavcopy-first harmless. The surviving reason is the other
   * half: queueing the replay behind this single-thread scheduler would put a hung 16:05 cron in
   * front of it. The two can only overlap on a boot landing inside the same second as the cron, and
   * the pass is idempotent (cache-first upserts), so the overlap costs duplicate fetches and
   * nothing else.
   *
   * <p><b>The canary's own {@code sweep()} deliberately does NOT move here.</b> Serialising the
   * 18:58 evaluation behind the 16:05 fetch on one thread would guarantee a complete population —
   * and would also mean a HUNG fetch silently cancels the evaluation entirely. A short population
   * evaluated loudly (the coverage floor turns it YELLOW) beats no evaluation at all; the sweep is
   * two SQL reads and does not need protecting.
   */
  @Bean
  public ThreadPoolTaskScheduler closeCanaryTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("close-canary-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }

  /**
   * A single daemon thread owned solely by {@code DayContextService.refreshSnapshot} — the H31
   * intraday precompute that fires at :13/:28/:43/:58, 08–15 IST, i.e. INSIDE market hours.
   *
   * <p><b>Why it cannot sit on the default pool — it would defeat the fix it is part of.</b> That
   * pool is ONE thread ({@link #taskScheduler} is {@code builder.build()}, pool size 1) shared by
   * <b>30 scheduled methods on the shared default pool (of 41 {@code @Scheduled} annotations in
   * this service's main sources; 11 name a scheduler)</b> — figures RE-DERIVED, not asserted from
   * memory: {@code ScheduledPoolCensusTest} walks the main sources and reddens when any of the
   * three moves, printing the per-class, per-pool breakdown. The number here was once <b>55</b>,
   * from a grep that also counted {@code @Scheduled} written inside javadoc. And one of the 29 is
   * {@code OptionsSnapshotService.scheduledSnapshot}:
   * every 2 minutes, and its own javadoc sizes a pass at "~70 batched calls ≈ 70 s at the 1/s
   * limit". The entire safety margin of the H31 design is the 2 minutes between the :13 refresh and
   * the :15 sweep. A refresh submitted at :13:00 that queues behind an in-flight options pass can
   * land AFTER :15; the sweep then reads a snapshot older than
   * {@code artha.context.day-context-snapshot-max-age-seconds}, falls back to an inline compute, and
   * <b>H31 reproduces exactly — with every unit test still green</b>, because nothing in a unit test
   * can observe scheduler queueing.
   *
   * <p>The traffic runs the other way too. {@code DayContextService.overnightCues} is an UNCACHED
   * Upstox call whose 429 ladder backs off 1→16 s (longer on a {@code Retry-After}), so on the
   * default pool a throttled refresh would block in-session OI capture — work that used to run on a
   * Tomcat request thread and never touched a scheduler at all. Its own thread means an overrun can
   * only ever starve itself.
   *
   * <p><b>Why not {@link #monitorTaskScheduler()}.</b> Same fence as {@link #barFlushTaskScheduler},
   * {@link #oiCaptureTaskScheduler}, {@link #nseRetryTaskScheduler} and {@link
   * #closeCanaryTaskScheduler}: that pool is reserved for pure liveness DETECTORS. This is a
   * multi-upstream fetch path (Kite quote + Upstox world indices + a JDBC candle read + the ingest
   * board), which is exactly what must not sit next to {@code FeedWatchdog.check}.
   */
  @Bean
  public ThreadPoolTaskScheduler dayContextTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("day-context-sched-");
    scheduler.setDaemon(true);
    return scheduler;
  }
}
