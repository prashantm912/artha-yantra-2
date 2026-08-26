package in.arthayantra.marketdata.context;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.MonitorSchedulingConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Pins that the H31 snapshot refresher is bound to its OWN single-thread scheduler.
 *
 * <p>⚠️ <b>Why this is a Major and not a tidiness point.</b> Boot's default {@code @Scheduled} pool
 * is ONE thread — {@code MonitorSchedulingConfig.taskScheduler} is {@code builder.build()} and its
 * own javadoc says "byte-for-byte the default (pool size 1)" — shared by <b>29</b> scheduled
 * methods in this service (38 {@code @Scheduled} annotations in main; 9 name a scheduler).
 * One of them is {@code OptionsSnapshotService.scheduledSnapshot}, cron
 * {@code 0 *&#47;2 * * * *}, whose javadoc sizes a full pass at "~70 batched calls ≈ 70 s at the 1/s
 * limit".
 *
 * <p>The entire safety margin of the H31 design is the 2 minutes between the :13 refresh and the
 * :15 sweep. On the shared pool a refresh submitted at :13:00 can queue behind an in-flight options
 * pass and land after :15 — the sweep then reads a snapshot past its max age, falls back to an
 * inline compute, and <b>H31 reproduces exactly while every other test in this package stays
 * green</b>. Unit tests call {@code refreshSnapshot()} directly and therefore cannot observe
 * scheduler queueing at all; this test asserts the DECLARATION instead, which is the part that is
 * checkable without a running container.
 *
 * <p><b>Scope.</b> This file pins the DECLARATION — the annotation names the scheduler, and a
 * matching single-thread {@code @Bean} with an unrenamed bean name exists on the config class. The
 * RUNTIME half — that Spring actually routes the job onto that pool — is pinned separately by
 * {@code MonitorSchedulingConfigTest.dayContextRefreshFiresWhileTheDefaultPoolIsBlocked}, an
 * {@code ApplicationContextRunner} with real {@code @EnableScheduling} that wedges the default pool
 * and asserts the probe ran on a {@code day-context-sched-} thread. ⚠️ An earlier cut of this
 * javadoc declared that container test out of reach for this suite; it was already in the repo,
 * with three siblings, in a file that had not been opened.
 */
class DayContextSchedulerBindingTest {

  private static final String SCHEDULER_BEAN = "dayContextTaskScheduler";

  @Test
  @DisplayName("the refresher declares the dedicated scheduler, not the shared default pool")
  void theRefresherIsBoundToItsOwnScheduler() throws NoSuchMethodException {
    Scheduled scheduled =
        DayContextService.class.getMethod("refreshSnapshot").getAnnotation(Scheduled.class);

    assertThat(scheduled).as("refreshSnapshot() lost its @Scheduled annotation entirely").isNotNull();
    assertThat(scheduled.scheduler())
        .as(
            "refreshSnapshot() must run on %s. An empty scheduler puts it on the ONE-thread default"
                + " pool behind OptionsSnapshotService's ~70 s pass, where a :13 refresh can land"
                + " after the :15 sweep and reproduce H31 with every other test still green",
            SCHEDULER_BEAN)
        .isEqualTo(SCHEDULER_BEAN);
    assertThat(scheduled.zone()).isEqualTo("Asia/Kolkata");
  }

  @Test
  @DisplayName("the named scheduler bean exists and is a single dedicated thread")
  void theSchedulerBeanExistsAndIsSingleThreaded() throws NoSuchMethodException {
    // ⚠️ The name in the annotation is a STRING. Without this, a typo — or deleting the bean while
    // leaving the annotation — would leave the test above green against a scheduler that does not
    // exist, which is the guard-that-checks-nothing shape.
    Method bean = MonitorSchedulingConfig.class.getMethod(SCHEDULER_BEAN);
    Bean annotation = bean.getAnnotation(Bean.class);
    assertThat(annotation)
        .as("%s is not a @Bean, so the annotation above names nothing", SCHEDULER_BEAN)
        .isNotNull();
    // ⚠️ @Bean("somethingElse") RENAMES the bean while leaving the method name — and therefore this
    // test's reflection lookup — untouched. Both assertions above would stay green while the
    // qualifier in @Scheduled resolved nothing.
    assertThat(annotation.value())
        .as("%s must take its bean name from the method; an explicit value() renames it", SCHEDULER_BEAN)
        .isEmpty();

    ThreadPoolTaskScheduler scheduler = new MonitorSchedulingConfig().dayContextTaskScheduler();
    scheduler.initialize();
    try {
      // ⚠️ CORE pool size, not getPoolSize(): the latter reports threads ACTUALLY CREATED, which is
      // 0 until the first task is scheduled — so asserting on it would compare against a lazily
      // empty pool and say nothing about the configuration.
      assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
          .as("a second thread would let two refreshes overlap on the same volatile field")
          .isEqualTo(1);
      assertThat(scheduler.getThreadNamePrefix())
          .as("the live check for this change is the refresh INFO line's thread name")
          .isEqualTo("day-context-sched-");
    } finally {
      scheduler.shutdown();
    }
  }
}
