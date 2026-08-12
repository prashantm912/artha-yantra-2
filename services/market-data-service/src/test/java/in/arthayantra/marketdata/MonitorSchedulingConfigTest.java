package in.arthayantra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.canary.DataHealthCanary;
import in.arthayantra.marketdata.canary.EveningChainCanary;
import in.arthayantra.marketdata.candles.CandlesConfig;
import in.arthayantra.marketdata.feed.FeedWatchdog;
import in.arthayantra.marketdata.kite.session.SessionHealthProbe;
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
 * BEJ-01 (monitor-scheduler isolation): the pure liveness detectors ({@code FeedWatchdog.check},
 * {@code DataHealthCanary.sweep}, {@code SessionHealthProbe.scheduledProbe}) run on a dedicated
 * {@code monitorTaskScheduler}, isolated from the default single-thread pool, so a blocked sibling
 * job can never starve detection.
 *
 * <p>S1 (2026-07-25) adds the same guarantee for the 1 s bar-close sweep on its own
 * {@code barFlushTaskScheduler}, for a different reason — see that test's javadoc.
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
    assertBoundToMonitorScheduler(FeedWatchdog.class, "check");
    assertBoundToMonitorScheduler(DataHealthCanary.class, "sweep");
    assertBoundToMonitorScheduler(SessionHealthProbe.class, "scheduledProbe");
    // MAJOR 4 (review, 2026-08-11): the single-shot "is tonight's chain done" push must not be
    // starvable by the very batch jobs it is checking on — it is exactly the detector that must
    // notice a hung one.
    assertBoundToMonitorScheduler(EveningChainCanary.class, "check");
  }

  /**
   * S1: the 1 s bar-close sweep owns its own pool. On the DEFAULT pool it queued behind
   * {@code OptionsSnapshotService.scheduledSnapshot}, whose javadoc sizes one pass at ~70 s through
   * the 1/s Kite quote limiter — so every 1m bar could close (and land in {@code candles}) that
   * late, dragging the live engine's bar-close eval and receipt heartbeats with it. Also pinned OFF
   * the monitor pool, which is fenced for pure detectors: {@code flushBars} writes (JDBC + Redis per
   * closed bar) and would starve {@code FeedWatchdog.check}.
   */
  @Test
  void theBarFlushSweepOwnsItsOwnPoolAndNeverTheDefaultOrMonitorOne() throws NoSuchMethodException {
    Scheduled scheduled =
        CandlesConfig.CandleHousekeeping.class
            .getDeclaredMethod("flushBars")
            .getAnnotation(Scheduled.class);
    assertThat(scheduled).as("CandleHousekeeping.flushBars is @Scheduled").isNotNull();
    assertThat(scheduled.scheduler())
        .as("flushBars must NOT share the default pool with the ~70 s options-snapshot pass")
        .isEqualTo("barFlushTaskScheduler");
    assertThat(scheduled.fixedDelay()).as("the 1 s cadence is unchanged").isEqualTo(1_000);
  }

  /** The bean the sweep names must exist, on its own single daemon thread, distinct from both siblings. */
  @Test
  void theBarFlushSchedulerBeanExistsAndIsIsolated() {
    runner.run(
        context -> {
          ThreadPoolTaskScheduler scheduler =
              context.getBean("barFlushTaskScheduler", ThreadPoolTaskScheduler.class);
          assertThat(scheduler).isNotNull();
          assertThat(scheduler)
              .as("a distinct pool from the monitor detectors")
              .isNotSameAs(context.getBean("monitorTaskScheduler"));
          assertThat(scheduler)
              .as("a distinct pool from the default scheduling pool")
              .isNotSameAs(context.getBean("taskScheduler"));
        });
  }

  /** The sweep keeps firing on its own thread while the default pool is wedged — the S1 guarantee. */
  @Test
  void theBarFlushSweepFiresWhileTheDefaultPoolIsBlocked() {
    runner.run(
        context -> {
          BlockingDefaultJob blocker = context.getBean(BlockingDefaultJob.class);
          BarFlushProbeJob probe = context.getBean(BarFlushProbeJob.class);
          try {
            assertThat(blocker.entered.await(3, TimeUnit.SECONDS))
                .as("default-pool job started and is holding its only thread")
                .isTrue();
            assertThat(probe.fired.await(3, TimeUnit.SECONDS))
                .as("the bar-flush sweep fired while the default pool was blocked")
                .isTrue();
            assertThat(probe.threadName)
                .as("the sweep ran on its own dedicated pool")
                .startsWith("bar-flush-sched-");
          } finally {
            blocker.release.countDown();
          }
        });
  }

  private static void assertBoundToMonitorScheduler(Class<?> type, String method)
      throws NoSuchMethodException {
    Scheduled scheduled = type.getDeclaredMethod(method).getAnnotation(Scheduled.class);
    assertThat(scheduled).as("%s.%s is @Scheduled", type.getSimpleName(), method).isNotNull();
    assertThat(scheduled.scheduler())
        .as("%s.%s runs on the monitor pool", type.getSimpleName(), method)
        .isEqualTo("monitorTaskScheduler");
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

    @Bean
    BarFlushProbeJob barFlushProbeJob() {
      return new BarFlushProbeJob();
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

  /** Stands in for {@code flushBars}: same binding, records that it fired and on which thread. */
  static class BarFlushProbeJob {
    final CountDownLatch fired = new CountDownLatch(1);
    volatile String threadName;

    @Scheduled(fixedDelay = 60_000, initialDelay = 0, scheduler = "barFlushTaskScheduler")
    public void run() {
      threadName = Thread.currentThread().getName();
      fired.countDown();
    }
  }
}
