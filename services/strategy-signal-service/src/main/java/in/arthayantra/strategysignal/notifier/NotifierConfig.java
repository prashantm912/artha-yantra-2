package in.arthayantra.strategysignal.notifier;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
}
