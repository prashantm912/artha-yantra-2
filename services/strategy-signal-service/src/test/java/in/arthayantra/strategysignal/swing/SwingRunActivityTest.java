package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@link SwingRunActivity} — the completion boundary {@code PaperReconciliationScheduler} waits on.
 *
 * <p>These use the REAL mutex and a REAL single-thread scheduler, driving a task shaped like
 * {@code SwingBatchCatchUp.catchUp}'s doctrine loop, because the defect these exist for is invisible
 * to anything that mocks the observable or that only samples a currently-held lock. The first version
 * of this fix keyed on the per-family lock alone; its suites were green over a live inter-family
 * overlap, because a mocked mutex answers whatever it was told to and a single held lock never
 * exercises the transition between two.
 *
 * <p>The load-bearing assertion is therefore the one taken with NO family lock held and the sweep
 * still running.
 */
class SwingRunActivityTest {

  private static ThreadPoolTaskScheduler catchUpPool() {
    ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
    pool.setPoolSize(1);
    pool.setThreadNamePrefix("swing-catchup-sched-");
    pool.setDaemon(true);
    pool.initialize();
    return pool;
  }

  /** Polls until the activity reports idle, so a just-finished task's worker can wind down. */
  private static boolean idleWithin(SwingRunActivity activity, long millis) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (System.nanoTime() < deadline) {
      if (!activity.anyRunInFlight()) {
        return true;
      }
      Thread.sleep(5L);
    }
    return !activity.anyRunInFlight();
  }

  @Test
  @DisplayName("THE GAP: a sweep BETWEEN two families holds no lock, and must still read in-flight")
  void seesTheWindowBetweenTwoFamilies() throws Exception {
    ThreadPoolTaskScheduler pool = catchUpPool();
    try {
      SwingRunMutex mutex = new SwingRunMutex();
      SwingRunActivity activity = new SwingRunActivity(mutex, pool);

      CountDownLatch betweenFamilies = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch swept = new CountDownLatch(1);

      // The shape of SwingBatchCatchUp.catchUp's doctrine loop (:196-235): lock family, sweep,
      // unlock (:233) — then back round the loop for marker reads and window seeding (:200-218)
      // holding NOTHING — then lock the next family (:224).
      pool.execute(
          () -> {
            ReentrantLock first = mutex.lockFor("minervini");
            first.lock();
            try {
              assertThat(mutex.anyRunInFlight()).isTrue();
            } finally {
              first.unlock();
            }
            betweenFamilies.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            ReentrantLock second = mutex.lockFor("manas");
            second.lock();
            try {
              assertThat(mutex.anyRunInFlight()).isTrue();
            } finally {
              second.unlock();
            }
            swept.countDown();
          });

      assertThat(betweenFamilies.await(10, TimeUnit.SECONDS)).isTrue();

      // Pins that the gap is REAL rather than hypothetical: the per-family observation, on its own,
      // says the book is quiet here — in the middle of a live sweep, with a whole family still to
      // run. This is the exact state the first version of the fix reconciled into.
      assertThat(mutex.anyRunInFlight()).isFalse();

      // ...and the sweep-wide observation does not. THE assertion.
      assertThat(activity.anyRunInFlight()).isTrue();

      release.countDown();
      assertThat(swept.await(10, TimeUnit.SECONDS)).isTrue();
      // And it clears once the whole invocation is over, or the reconciler would wait out its budget
      // every day and decline on a book nobody was touching.
      assertThat(idleWithin(activity, 5_000)).isTrue();
    } finally {
      pool.shutdown();
    }
  }

  @Test
  @DisplayName("a manual POST /run never touches the pool, so the family lock still has to count")
  void seesAManualRunThatNeverEntersTheCatchUpPool() {
    ThreadPoolTaskScheduler pool = catchUpPool();
    try {
      SwingRunMutex mutex = new SwingRunMutex();
      SwingRunActivity activity = new SwingRunActivity(mutex, pool);

      // SwingBatchRecorder:118/150/169 lock the family on whatever thread called it — for a manual
      // POST /run that is a Tomcat request thread, so the catch-up pool is idle throughout.
      ReentrantLock lock = mutex.lockFor("minervini");
      lock.lock();
      try {
        assertThat(pool.getActiveCount()).isZero();
        assertThat(activity.anyRunInFlight()).isTrue();
      } finally {
        lock.unlock();
      }
      assertThat(activity.anyRunInFlight()).isFalse();
    } finally {
      pool.shutdown();
    }
  }

  @Test
  @DisplayName("an idle pool and no held lock is idle — the zero-wait normal-day fast path")
  void quietIsQuiet() {
    ThreadPoolTaskScheduler pool = catchUpPool();
    try {
      SwingRunMutex mutex = new SwingRunMutex();
      // lockFor() alone must not register: the catch-up calls it before tryLock, and the reconciler
      // would otherwise wait out its whole budget on a lock nobody holds.
      mutex.lockFor("minervini");
      assertThat(new SwingRunActivity(mutex, pool).anyRunInFlight()).isFalse();
    } finally {
      pool.shutdown();
    }
  }

  @Test
  @DisplayName("the catch-up is still BOUND to the pool this gate watches")
  void catchUpIsStillBoundToTheObservedPool() throws Exception {
    // The operand's dedication is what makes "pool active" mean "a sweep is running". If catchUp were
    // ever moved to another scheduler, this gate would not fail — it would go permanently, silently
    // idle, and the reconciler would resume reading torn state with every test still green. That is
    // the "armed gate whose operand is structurally zero" shape, so it gets a ratchet rather than a
    // comment.
    Method catchUp = SwingBatchCatchUp.class.getMethod("catchUp");
    Scheduled scheduled = catchUp.getAnnotation(Scheduled.class);
    assertThat(scheduled).isNotNull();
    assertThat(scheduled.scheduler()).isEqualTo("swingCatchUpTaskScheduler");
    assertThat(scheduled.zone()).isEqualTo("Asia/Kolkata");
  }
}
