package in.arthayantra.marketdata.screener;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * A single-permit bulkhead shared across BOTH deep swing backtests (Minervini + Manas Arora). Each
 * backtest is an ~11-year event-driven sim over ~1,800 daily-bar equities that materialises large
 * per-symbol series and trade lists on the heap; the market-data JVM this runs in also carries the
 * live tick capture on a ~448&nbsp;MB heap (audit M25). Each service already single-flights its OWN
 * runs via an {@code AtomicBoolean}, but nothing stopped the owner POSTing {@code /run} to BOTH
 * families within the minutes-long window — two concurrent sims could OOM-crash the live-capture JVM
 * (the same failure class as the expired-backfill OOM incident).
 *
 * <p>This gate serialises them: a backtest takes the single permit before it starts and releases it
 * when it finishes, so the second family's trigger is refused (its endpoint returns the same
 * "already running" 409 it returns for a same-family re-trigger) rather than piling a second sim onto
 * the heap. Fair ordering so a waiter is not starved — though callers {@code tryAcquire} (never
 * block) so a refused run just tells the owner to retry once the first completes.
 */
@Component
public class SwingBacktestGate {

  private final Semaphore permit = new Semaphore(1, true);

  /** Attempts to take the single deep-backtest permit; false when another sim already holds it. */
  public boolean tryAcquire() {
    return permit.tryAcquire();
  }

  /** Releases the permit. MUST be called exactly once per successful {@link #tryAcquire}. */
  public void release() {
    permit.release();
  }
}
