package in.arthayantra.strategysignal.notifier;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Phase 41: enables @Async + a small bounded executor for notifier pushes (sub-1/min volume). */
@Configuration
@EnableAsync
public class NotifierConfig {

  /** The notifier dispatch pool — small + bounded; retries run on these threads. */
  @Bean("notifierExecutor")
  public Executor notifierExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("notifier-");
    executor.initialize();
    return executor;
  }

  /**
   * A single daemon thread for DELAYED notifier work — today only {@code NotifierHealthCheck}'s
   * retry at claim-lease expiry (task_7e754e11).
   *
   * <p><b>Why a scheduler and not {@link #notifierExecutor()}.</b> That bean is an {@code Executor}:
   * it can run a task, not defer one. The retry's whole job is to happen LATER — when the holder's
   * lease expires — so it needs {@code schedule(task, instant)}.
   *
   * <p><b>Why not an existing pool.</b> Every scheduler in {@code MonitorSchedulingConfig} is fenced
   * for a risk class this does not belong to: the default {@code taskScheduler} is the single thread
   * carrying {@code PaperScheduler.bracketEvaluation} (the 15-second live stop-loss sweep), and
   * {@code monitorTaskScheduler} is fenced (audit BEJ-01 / #919) for pure in-memory detectors — this
   * task ends in two blocking best-effort HTTP pushes, which is exactly what the detectors offload
   * via {@code @Async("notifierExecutor")} to keep off that thread. Owning the pool here also
   * satisfies the rule that the retry must be owned by THIS module: it cannot depend on another boot
   * arriving, because another boot may never come.
   */
  @Bean("notifierRetryScheduler")
  public ThreadPoolTaskScheduler notifierRetryScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("notifier-retry-");
    scheduler.setDaemon(true);
    return scheduler;
  }
}
