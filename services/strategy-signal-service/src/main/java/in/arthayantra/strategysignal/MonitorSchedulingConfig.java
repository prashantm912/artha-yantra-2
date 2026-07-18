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
}
