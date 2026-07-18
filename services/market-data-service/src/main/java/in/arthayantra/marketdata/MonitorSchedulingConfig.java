package in.arthayantra.marketdata;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler isolation for the pure liveness DETECTORS (audit BEJ-01). Boot gives {@code @Scheduled}
 * a single default {@code ThreadPoolTaskScheduler} (pool size 1) shared by all ~32 scheduled methods
 * here, so a blocked sibling job (an EOD/backfill/snapshot job that hangs on I/O) silently freezes
 * every watchdog/canary sweep on the same thread — detection is starvable exactly when the stack is
 * most broken, even though the RECOVERY paths (feed restart, off-pool daemons) are decoupled. This
 * gives the detectors their own dedicated single-thread scheduler so their sweeps keep firing
 * regardless of what the default pool is doing.
 *
 * <p>Scope-fenced: ONLY pure detectors move onto {@link #monitorTaskScheduler()} via
 * {@code @Scheduled(scheduler = "monitorTaskScheduler")} — {@code FeedWatchdog.check},
 * {@code DataHealthCanary.sweep}, {@code SessionHealthProbe.scheduledProbe}. Every other job keeps
 * the default pool (its serial single-thread assumption is load-bearing for the batch jobs).
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
}
