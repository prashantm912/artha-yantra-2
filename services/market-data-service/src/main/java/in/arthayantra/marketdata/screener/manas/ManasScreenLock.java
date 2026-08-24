package in.arthayantra.marketdata.screener.manas;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The one lock serialising every door that publishes the Manas screen (ledger H13).
 *
 * <p>⚠️ <b>Why this is its own bean rather than a field on {@link ManasScheduler}.</b> The scheduler
 * is {@code @ConditionalOnProperty(artha.manas-arora.screen.enabled)} while {@link ManasController}
 * is an unconditional {@code @RestController} that publishes the screen INLINE — it calls
 * {@code screen} then {@code replaceAll} itself instead of delegating (unlike the minervini pair,
 * where the controller goes through {@code MinerviniScheduler.runOnce} and is covered by that
 * class's own lock). A lock living on the conditional bean therefore could not be shared with the
 * door most likely to be open when the scheduler is off.
 *
 * <p>⚠️ <b>What an unprotected door costs here, and it is worse than duplicated work.</b>
 * {@code ManasScreenRepository.replaceAll} is a DELETE-by-date plus a batch upsert in ONE
 * transaction. Under READ COMMITTED the second transaction's DELETE cannot see rows the first
 * inserted after its snapshot, so the two candidate sets MERGE through {@code ON CONFLICT} — and a
 * symbol the trailing-bar guard dropped survives into the published screen, which is exactly what
 * that DELETE exists to prevent.
 *
 * <p>A JVM lock is sufficient because every door is in-process and market-data runs one container.
 * It is not a distributed claim.
 */
@Component
public class ManasScreenLock {

  private final ReentrantLock lock = new ReentrantLock();

  /** Blocks until the screen is free — for HTTP threads, where waiting is acceptable. */
  public void lock() {
    lock.lock();
  }

  /** Takes the lock only if it is free right now. */
  public boolean tryLock() {
    return lock.tryLock();
  }

  /** Waits up to {@code millis} for the lock; restores the interrupt flag and reports false. */
  public boolean tryLock(long millis) {
    try {
      return lock.tryLock(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Releases the lock. Always call from a {@code finally}. */
  public void unlock() {
    lock.unlock();
  }
}
