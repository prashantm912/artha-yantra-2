package in.arthayantra.strategysignal;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.paper.PaperScheduler;
import in.arthayantra.strategysignal.signals.CompositeRejectionPruneJob;
import in.arthayantra.strategysignal.signals.DotHealthCanary;
import in.arthayantra.strategysignal.signals.PartialBucketCanary;
import in.arthayantra.strategysignal.signals.RiskSuppressionPruneJob;
import in.arthayantra.strategysignal.signals.SignalEvalOutcomeRollupJob;
import in.arthayantra.strategysignal.signals.SubscriberHealthCanary;
import in.arthayantra.strategysignal.telegram.TelegramCommandBot;
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
 * {@code PartialBucketCanary}, {@code DotHealthCanary} sweeps) run on
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
   * The V045 eval-outcome rollup makes a SYNCHRONOUS JDBC write, so it must own a third, expendable
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

  /**
   * The daily retention prunes issue a SYNCHRONOUS {@code DELETE}. On the default pool — a single
   * thread that live logs show carrying {@code PaperStaleTickAlerter}, {@code SignalEngine}
   * reconcile and {@code PaperScheduler.bracketEvaluation} (the 15-second stop-loss/target sweep) —
   * a DELETE blocking on a lock at 02:30 IST parks stop-loss evaluation for the next session. BOTH
   * prunes are pinned: leaving either on the default pool leaves the identical wedge in place.
   *
   * <p>Also pinned OFF the eval-outcome pool. That pool's output is evidence whose ABSENCE is
   * interpreted as "the process was down", so a wedged prune there would manufacture a false
   * engine-death reading — the 2026-07-20 misdiagnosis that V045 exists to prevent.
   */
  @Test
  void theRetentionPrunesOwnTheMaintenancePoolAndNeverTheDefaultMonitorOrEvalOutcomeOne()
      throws NoSuchMethodException {
    for (Class<?> job : new Class<?>[] {RiskSuppressionPruneJob.class, CompositeRejectionPruneJob.class}) {
      Scheduled scheduled = job.getDeclaredMethod("scheduledPrune").getAnnotation(Scheduled.class);
      assertThat(scheduled).as("%s.scheduledPrune is @Scheduled", job.getSimpleName()).isNotNull();
      assertThat(scheduled.scheduler())
          .as(
              "%s.scheduledPrune must NOT run on the default pool (paper stop-loss evaluation), the"
                  + " fenced monitor pool, or the eval-outcome liveness pool",
              job.getSimpleName())
          .isEqualTo("maintenanceTaskScheduler");
    }
  }

  /** The bean the prune annotations name must actually exist, distinct from all three siblings. */
  @Test
  void theMaintenanceSchedulerBeanExistsAndIsIsolated() {
    runner.run(
        context -> {
          ThreadPoolTaskScheduler scheduler =
              context.getBean("maintenanceTaskScheduler", ThreadPoolTaskScheduler.class);
          assertThat(scheduler).isNotNull();
          assertThat(scheduler)
              .as("a distinct pool from the monitor detectors")
              .isNotSameAs(context.getBean("monitorTaskScheduler"));
          assertThat(scheduler)
              .as("a distinct pool from the default scheduling pool")
              .isNotSameAs(context.getBean("taskScheduler"));
          assertThat(scheduler)
              .as("a distinct pool from the eval-outcome liveness rollup")
              .isNotSameAs(context.getBean("evalOutcomeTaskScheduler"));
        });
  }

  /**
   * S2: the live-armed Telegram command poller owns its own pool. It is the only default-pool job
   * that calls a THIRD PARTY ({@code api.telegram.org}) every 3 s, and it shared that single thread
   * with {@code PaperScheduler.bracketEvaluation} — the 15 s live stop-loss/target sweep. One TLS or
   * long-poll stall on Telegram's side therefore delayed SL/TP evaluation for as long as it lasted.
   * Also pinned OFF the three other pools: the monitor pool is fenced for pure in-memory detectors,
   * and the eval-outcome and maintenance pools exist precisely so nothing can forge or silence a
   * liveness verdict by stalling on them.
   */
  @Test
  void theTelegramPollerOwnsItsOwnPoolAndNeverAnySharedOne() throws NoSuchMethodException {
    Scheduled scheduled =
        TelegramCommandBot.class.getDeclaredMethod("poll").getAnnotation(Scheduled.class);
    assertThat(scheduled).as("TelegramCommandBot.poll is @Scheduled").isNotNull();
    assertThat(scheduled.scheduler())
        .as("poll must NOT share the default pool with the live SL/TP bracket sweep")
        .isEqualTo("telegramTaskScheduler");
    assertThat(scheduled.fixedDelay()).as("the 3 s cadence is unchanged").isEqualTo(3_000);
  }

  /**
   * The counterpart pin: {@code bracketEvaluation} deliberately STAYS on the default pool (its
   * serial ordering with the 15:30/15:35/15:45 paper chain is load-bearing). S2 moves the job with
   * the unbounded external dependency, not the money path.
   */
  @Test
  void theBracketSweepStaysOnTheDefaultPool() throws NoSuchMethodException {
    Scheduled scheduled =
        PaperScheduler.class.getDeclaredMethod("bracketEvaluation").getAnnotation(Scheduled.class);
    assertThat(scheduled).as("PaperScheduler.bracketEvaluation is @Scheduled").isNotNull();
    assertThat(scheduled.scheduler())
        .as("bracketEvaluation keeps Boot's default scheduler")
        .isEqualTo("");
  }

  /** The bean the poller names must exist, distinct from all three siblings and the default pool. */
  @Test
  void theTelegramSchedulerBeanExistsAndIsIsolated() {
    runner.run(
        context -> {
          ThreadPoolTaskScheduler scheduler =
              context.getBean("telegramTaskScheduler", ThreadPoolTaskScheduler.class);
          assertThat(scheduler).isNotNull();
          assertThat(scheduler)
              .as("a distinct pool from the default scheduling pool (paper SL/TP)")
              .isNotSameAs(context.getBean("taskScheduler"));
          assertThat(scheduler)
              .as("a distinct pool from the monitor detectors")
              .isNotSameAs(context.getBean("monitorTaskScheduler"));
          assertThat(scheduler)
              .as("a distinct pool from the eval-outcome liveness rollup")
              .isNotSameAs(context.getBean("evalOutcomeTaskScheduler"));
          assertThat(scheduler)
              .as("a distinct pool from the retention prunes")
              .isNotSameAs(context.getBean("maintenanceTaskScheduler"));
        });
  }

  /** The poller keeps firing on its own thread while the default pool is wedged — the S2 guarantee. */
  @Test
  void theTelegramPollerFiresWhileTheDefaultPoolIsBlocked() {
    runner.run(
        context -> {
          BlockingDefaultJob blocker = context.getBean(BlockingDefaultJob.class);
          TelegramProbeJob probe = context.getBean(TelegramProbeJob.class);
          try {
            assertThat(blocker.entered.await(3, TimeUnit.SECONDS))
                .as("default-pool job started and is holding its only thread")
                .isTrue();
            assertThat(probe.fired.await(3, TimeUnit.SECONDS))
                .as("the Telegram poller fired while the default pool was blocked")
                .isTrue();
            assertThat(probe.threadName)
                .as("the poller ran on its own dedicated pool")
                .startsWith("telegram-poll-sched-");
          } finally {
            blocker.release.countDown();
          }
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

    @Bean
    TelegramProbeJob telegramProbeJob() {
      return new TelegramProbeJob();
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

  /** Stands in for the Telegram poller: same binding, records that it fired and on which thread. */
  static class TelegramProbeJob {
    final CountDownLatch fired = new CountDownLatch(1);
    volatile String threadName;

    @Scheduled(fixedDelay = 60_000, initialDelay = 0, scheduler = "telegramTaskScheduler")
    public void run() {
      threadName = Thread.currentThread().getName();
      fired.countDown();
    }
  }
}
