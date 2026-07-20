package in.arthayantra.strategysignal;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.signals.DotHealthCanary;
import in.arthayantra.strategysignal.signals.PartialBucketCanary;
import in.arthayantra.strategysignal.signals.SignalEvalOutcomeRollupJob;
import in.arthayantra.strategysignal.signals.SignalStarvationCanary;
import in.arthayantra.strategysignal.signals.SubscriberHealthCanary;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * BEJ-01 (monitor-scheduler isolation): the pure liveness detectors ({@code SubscriberHealthCanary},
 * {@code PartialBucketCanary}, {@code SignalStarvationCanary}, {@code DotHealthCanary} sweeps) run on
 * a dedicated {@code monitorTaskScheduler}, isolated from the default single-thread pool that the
 * synchronous swing batch and every EOD job share, so a blocked sibling can never starve detection.
 */
class MonitorSchedulingConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
          .withUserConfiguration(ProbeConfig.class);

  @Test
  void monitorDetectorFiresWhileTheDefaultPoolIsBlocked() {
    runner.run(
        context -> {
          BlockingDefaultJob blocker = context.getBean(BlockingDefaultJob.class);
          MonitorProbeJob probe = context.getBean(MonitorProbeJob.class);
          try {
            // the single default-pool thread is now wedged inside the blocking job
            assertThat(blocker.entered.await(3, TimeUnit.SECONDS))
                .as("default-pool job started and is holding its only thread")
                .isTrue();
            // the detector on the monitor pool must still fire despite the default pool being stuck
            assertThat(probe.fired.await(3, TimeUnit.SECONDS))
                .as("monitor detector fired while the default pool was blocked")
                .isTrue();
            assertThat(probe.threadName)
                .as("monitor detector ran on the dedicated monitor pool")
                .startsWith("monitor-sched-");
            assertThat(blocker.threadName)
                .as("the blocked job ran on the default (Boot) scheduling pool, not the monitor pool")
                .startsWith("scheduling-");
          } finally {
            blocker.release.countDown(); // release the wedged thread for a clean context shutdown
          }
        });
  }

  @Test
  void productionDetectorsTargetTheMonitorScheduler() throws NoSuchMethodException {
    assertBoundToMonitorScheduler(SubscriberHealthCanary.class, "sweep");
    assertBoundToMonitorScheduler(PartialBucketCanary.class, "sweep");
    assertBoundToMonitorScheduler(SignalStarvationCanary.class, "sweep");
    assertBoundToMonitorScheduler(DotHealthCanary.class, "sweep");
  }

  private static void assertBoundToMonitorScheduler(Class<?> type, String method)
      throws NoSuchMethodException {
    Scheduled scheduled = type.getDeclaredMethod(method).getAnnotation(Scheduled.class);
    assertThat(scheduled).as("%s.%s is @Scheduled", type.getSimpleName(), method).isNotNull();
    assertThat(scheduled.scheduler())
        .as("%s.%s runs on the monitor pool", type.getSimpleName(), method)
        .isEqualTo("monitorTaskScheduler");
  }

  /**
   * The V043 eval-outcome rollup makes a SYNCHRONOUS JDBC write, so it must own a third, expendable
   * pool. Both alternatives are unsafe and this pins it against a silent refactor back onto either:
   * the DEFAULT pool is a single thread that live logs show carrying {@code PaperStaleTickAlerter}
   * (paper SL/TP starvation alerting) plus {@code SignalEngine} reconcile — an observability write
   * stalling there would park stop-loss evaluation — and {@code monitorTaskScheduler} is fenced for
   * pure in-memory detectors, where a DB stall could starve {@code SubscriberHealthCanary}.
   */
  @Test
  void theEvalOutcomeRollupOwnsItsOwnPoolAndNeverTheDefaultOrMonitorOne()
      throws NoSuchMethodException {
    for (String method : new String[] {"scheduledRollup", "scheduledPrune"}) {
      Scheduled scheduled =
          SignalEvalOutcomeRollupJob.class.getDeclaredMethod(method).getAnnotation(Scheduled.class);
      assertThat(scheduled).as("SignalEvalOutcomeRollupJob.%s is @Scheduled", method).isNotNull();
      assertThat(scheduled.scheduler())
          .as(
              "SignalEvalOutcomeRollupJob.%s must NOT run on the default pool (paper SL/TP"
                  + " alerting) or the fenced monitor pool",
              method)
          .isEqualTo("evalOutcomeTaskScheduler");
    }
  }

  /** The bean the annotations name must actually exist, on its own single daemon thread. */
  @Test
  void theEvalOutcomeSchedulerBeanExistsAndIsIsolated() {
    runner.run(
        context -> {
          ThreadPoolTaskScheduler scheduler =
              context.getBean("evalOutcomeTaskScheduler", ThreadPoolTaskScheduler.class);
          assertThat(scheduler).isNotNull();
          assertThat(scheduler)
              .as("a distinct pool from the monitor detectors")
              .isNotSameAs(context.getBean("monitorTaskScheduler"));
          assertThat(scheduler)
              .as("a distinct pool from the default scheduling pool")
              .isNotSameAs(context.getBean("taskScheduler"));
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @Import(MonitorSchedulingConfig.class)
  static class ProbeConfig {
    @Bean
    BlockingDefaultJob blockingDefaultJob() {
      return new BlockingDefaultJob();
    }

    @Bean
    MonitorProbeJob monitorProbeJob() {
      return new MonitorProbeJob();
    }
  }

  /** Occupies the default pool's single thread for the duration of the probe. */
  static class BlockingDefaultJob {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile String threadName;

    @Scheduled(fixedDelay = 60_000, initialDelay = 0)
    public void run() throws InterruptedException {
      threadName = Thread.currentThread().getName();
      entered.countDown();
      release.await(10, TimeUnit.SECONDS);
    }
  }

  /** A detector bound to the monitor pool; records that it fired and on which thread. */
  static class MonitorProbeJob {
    final CountDownLatch fired = new CountDownLatch(1);
    volatile String threadName;

    @Scheduled(fixedDelay = 60_000, initialDelay = 0, scheduler = "monitorTaskScheduler")
    public void run() {
      threadName = Thread.currentThread().getName();
      fired.countDown();
    }
  }
}
