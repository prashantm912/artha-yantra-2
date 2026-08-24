package in.arthayantra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.canary.DataHealthCanary;
import in.arthayantra.marketdata.candles.CandlesConfig;
import in.arthayantra.marketdata.feed.FeedWatchdog;
import in.arthayantra.marketdata.kite.session.SessionHealthProbe;
import in.arthayantra.marketdata.nse.NseEodScheduler;
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

  /**
   * The intra-day NSE retry owns its own pool. Found by the post-merge review of #1451: it fires at
   * 09:50/11:50/14:50 IST, INSIDE market hours, and its worst case is long -- LiveParticipantOiFetcher
   * walks back six days sequentially through an 8 s connect + 12 s read budget, so a blackholed
   * network (connections that HANG rather than refuse -- the 2026-08-19/20 shape) holds the thread
   * ~140 s. On the default pool that stalls all ~32 scheduled methods, including
   * OptionsSnapshotService's ~70 s live OI capture every 2 minutes.
   *
   * <p>⚠️ Moving the cron minute was the cheaper alternative and is NOT what was done: it would dodge
   * the two jobs that happen to share 09:50, but the hold stalls the pool from any minute at all.
   */
  @Test
  void theNseRetryOwnsItsOwnPoolAndNeverTheDefaultOne() throws NoSuchMethodException {
    Scheduled scheduled =
        NseEodScheduler.class.getDeclaredMethod("retryFailedSources").getAnnotation(Scheduled.class);
    assertThat(scheduled).as("NseEodScheduler.retryFailedSources is @Scheduled").isNotNull();
    assertThat(scheduled.scheduler())
        .as("an in-session external-HTTP job must never hold the shared single thread")
        .isEqualTo("nseRetryTaskScheduler");
  }

  /** The bean it names must exist, on its own single daemon thread, distinct from every sibling. */
  @Test
  void theNseRetrySchedulerBeanExistsAndIsIsolated() {
    runner.run(
        context -> {
          ThreadPoolTaskScheduler scheduler =
              context.getBean("nseRetryTaskScheduler", ThreadPoolTaskScheduler.class);
          assertThat(scheduler).isNotNull();
          assertThat(scheduler)
              .as("a distinct pool from the monitor detectors")
              .isNotSameAs(context.getBean("monitorTaskScheduler"));
          assertThat(scheduler)
              .as("a distinct pool from the default scheduling pool")
              .isNotSameAs(context.getBean("taskScheduler"));
          assertThat(scheduler)
              .as("and from the bar-flush pool")
              .isNotSameAs(context.getBean("barFlushTaskScheduler"));
        });
  }

  /**
   * The guarantee that actually matters, stated the way the S1 test states it: the isolation must
   * hold in BOTH directions. Here the retry is the SLOW job, so this proves the default pool keeps
   * running while the retry's own thread is the wedged one -- the inverse of the bar-flush case.
   */
  @Test
  void theDefaultPoolKeepsRunningWhileTheNseRetryPoolIsBlocked() {
    runner.run(
        context -> {
          NseRetryBlockingJob blocker = context.getBean(NseRetryBlockingJob.class);
          DefaultPoolProbeJob probe = context.getBean(DefaultPoolProbeJob.class);
          // BlockingDefaultJob wedges the default pool in EVERY test in this class, so let it go
          // first -- otherwise this test would prove only that a blocked pool stays blocked.
          context.getBean(BlockingDefaultJob.class).release.countDown();
          try {
            assertThat(blocker.entered.await(3, TimeUnit.SECONDS))
                .as("the retry-pool job started and is holding its only thread")
                .isTrue();
            assertThat(blocker.threadName)
                .as("it ran on the dedicated retry pool")
                .startsWith("nse-retry-sched-");
            assertThat(probe.fired.await(3, TimeUnit.SECONDS))
                .as("a default-pool job still fired while the retry pool was wedged")
                .isTrue();
            assertThat(probe.threadName).startsWith("scheduling-");
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

    @Bean
    NseRetryBlockingJob nseRetryBlockingJob() {
      return new NseRetryBlockingJob();
    }

    @Bean
    DefaultPoolProbeJob defaultPoolProbeJob() {
      return new DefaultPoolProbeJob();
    }
  }

  /** Stands in for a hung NSE fetch: occupies the retry pool's only thread. */
  static class NseRetryBlockingJob {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile String threadName;

    @Scheduled(fixedDelay = 60_000, initialDelay = 0, scheduler = "nseRetryTaskScheduler")
    public void run() throws InterruptedException {
      threadName = Thread.currentThread().getName();
      entered.countDown();
      release.await(10, TimeUnit.SECONDS);
    }
  }

  /** Fires on the DEFAULT pool - it must keep firing while the retry pool is wedged. */
  static class DefaultPoolProbeJob {
    final CountDownLatch fired = new CountDownLatch(1);
    volatile String threadName;

    @Scheduled(fixedDelay = 200, initialDelay = 50)
    public void run() {
      threadName = Thread.currentThread().getName();
      fired.countDown();
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
