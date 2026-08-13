package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SwingRunMutex#anyRunInFlight()} — the observable {@code PaperReconciliationScheduler}'s
 * bounded pre-open wait keys on.
 *
 * <p>These matter more than their size suggests. If this method ever answered a flat {@code false} it
 * would not throw, would not log, and would not fail any reconciliation test — the reconciler would
 * simply stop waiting and go back to reading a book mid-change, with its own suite still green. That
 * is the "success-shaped nothing" shape: an armed gate whose operand is structurally always-idle. So
 * the load-bearing assertion here is the POSITIVE one — that a genuinely held lock is SEEN.
 */
class SwingRunMutexTest {

  @Test
  @DisplayName("a lock held by ANOTHER thread is seen as in-flight, and released is seen as idle")
  void seesALockHeldByAnotherThread() throws Exception {
    SwingRunMutex mutex = new SwingRunMutex();
    // The real case: SwingBatchCatchUp holds the family lock on swing-catchup-sched-1 while the
    // reconciler asks from pre-open-sched-1. Same-thread reentrancy would mask a broken check.
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread runner =
        new Thread(
            () -> {
              ReentrantLock lock = mutex.lockFor("minervini");
              lock.lock();
              try {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            });
    runner.start();
    try {
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(mutex.anyRunInFlight()).isTrue();
    } finally {
      release.countDown();
      runner.join(10_000);
    }
    assertThat(mutex.anyRunInFlight()).isFalse();
  }

  @Test
  @DisplayName("ANY family in flight counts — the reconciler reads one book, not one family's")
  void anyFamilyCounts() {
    SwingRunMutex mutex = new SwingRunMutex();
    // Both families exist as locks; only the second is held. A per-family check that happened to ask
    // the wrong name would pass a torn read straight through.
    mutex.lockFor("minervini");
    ReentrantLock manas = mutex.lockFor("manas");
    manas.lock();
    try {
      assertThat(mutex.anyRunInFlight()).isTrue();
    } finally {
      manas.unlock();
    }
  }

  @Test
  @DisplayName("a fresh mutex with no runs ever taken is idle — the zero-wait normal-day fast path")
  void freshMutexIsIdle() {
    assertThat(new SwingRunMutex().anyRunInFlight()).isFalse();
    // lockFor() alone must not register as in-flight: the catch-up calls it before tryLock, and the
    // reconciler would otherwise wait out its whole budget on a lock nobody holds.
    SwingRunMutex mutex = new SwingRunMutex();
    mutex.lockFor("minervini");
    assertThat(mutex.anyRunInFlight()).isFalse();
  }

  @Test
  @DisplayName("observing never acquires — the wait must not queue behind the run it is watching")
  void observationDoesNotAcquire() {
    // The constraint that makes waiting safe at all: the withdrawn revision put the reconciler on the
    // catch-up's own lane, so a hung catch-up silently blocked two money jobs. If anyRunInFlight()
    // acquired, this call would deadlock against the held lock rather than return.
    SwingRunMutex mutex = new SwingRunMutex();
    ReentrantLock lock = mutex.lockFor("minervini");
    lock.lock();
    try {
      assertThat(mutex.anyRunInFlight()).isTrue();
      // Still held by us, and by exactly one hold count — nothing observed its way into the lock.
      assertThat(lock.getHoldCount()).isEqualTo(1);
      assertThat(lock.isHeldByCurrentThread()).isTrue();
    } finally {
      lock.unlock();
    }
  }
}
