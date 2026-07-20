package in.arthayantra.strategysignal.signals;

import io.micrometer.core.instrument.Counter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * The bounded single-thread off-eval-thread write queue shared by {@link RejectionWriter},
 * {@link RiskSuppressionWriter} and {@link CompositeRejectionWriter}. It owns the executor, the
 * saturation drop, the in-flight marker and the two-phase shutdown drain — everything those three
 * writers had in common — while each writer keeps its own repository call, its own log context and
 * its own metric NAMES.
 *
 * <p><b>Why it exists as one class.</b> The three writers were three hand-maintained copies of this
 * logic, and the divergence bit twice: the correct two-phase shutdown was worked out in
 * {@code RiskSuppressionWriter}, written down in its comments, copied into
 * {@code CompositeRejectionWriter} — and never reached {@code RejectionWriter}, which sat on the
 * LIVE confluence path silently losing one diagnostic per graceful shutdown while
 * {@code ay_signal_rejection_shutdown_dropped_total} read 0. A test pinning three copies would have
 * caught that instance; it would not catch the fourth writer nobody remembers to enumerate. Making
 * the contract structural rather than conventional is what actually closes the class.
 *
 * <p><b>The eval thread is never blocked.</b> The sole {@code signal-eval} thread must never park on
 * I/O — a DB stall there is the #866 / 2026-07-14 zero-signals starvation class. So a record is
 * ENQUEUED in O(1) onto a bounded queue drained by one daemon thread; when the DB stalls the queue
 * fills and further records are DROPPED and counted on the CALLING thread, never back-pressuring it.
 *
 * <p><b>Shutdown is two-phase, and that is load-bearing.</b> {@code shutdownNow()} returns only the
 * QUEUED tasks — the task that was RUNNING is not in that list. A record still in flight is lost two
 * distinct ways, and BOTH must be counted or the "counter reads healthy while data is lost" shape
 * reappears one layer in:
 *
 * <ul>
 *   <li><b>Interrupt-IGNORING</b> — the insert never honours {@code shutdownNow()}'s interrupt and is
 *       still running when the grace expires. Detected here by {@code !terminated && inFlight}.
 *   <li><b>Interrupt-RESPONSIVE</b> — the insert aborts on {@code Thread.interrupt()} (pgjdbc cancels
 *       a running statement this way), throws, fails, and unwinds WITHIN the grace, so the executor
 *       terminates cleanly. A naive {@code !terminated && inFlight} check reads 0 for this — the
 *       record is gone and the counter says healthy. Caught by the task REPORTING its persistence
 *       outcome: a task that did not succeed AND is unwinding after shutdown began ({@code
 *       shuttingDown}) claims itself as a loss in {@link #submit}.
 * </ul>
 *
 * <p>A normal-operation persistence failure (before shutdown begins) stays fail-soft and is NOT a
 * shutdown loss — only failures after {@code shuttingDown} is set are shutdown-caused. The two loss
 * modes are made mutually exclusive by a single-shot {@link #inFlightLossClaimed} CAS: at the boundary
 * where the interrupt-responsive task is failing exactly as the grace expires, whichever side
 * observes the loss first claims it and the other no-ops — never double, never zero. The WARN fires
 * UNCONDITIONALLY (even on an empty queue). Fail-soft throughout: never throws out of shutdown.
 */
final class BoundedAsyncWriter {

  /**
   * After {@code shutdownNow()} interrupts the drain, give a well-behaved insert this long to unwind
   * before we conclude the still-running insert is stuck and count it as lost.
   */
  static final long SHUTDOWN_INFLIGHT_GRACE_MILLIS = 1_000L;

  /** One asynchronous write, reporting whether it persisted so shutdown can tell loss from success. */
  @FunctionalInterface
  interface WriteAttempt {
    /**
     * Performs one write on the writer thread. Returns {@code true} on success and {@code false} on a
     * failure it has ALREADY logged (a diagnostic must never break the live signal path, so it must
     * not throw — an escaped exception is treated as a failure defensively). The returned outcome is
     * what lets shutdown distinguish a record abandoned mid-flight from one that completed.
     */
    boolean attempt();
  }

  private final String name;
  private final Logger log;
  private final Counter dropped;
  private final Counter shutdownDropped;
  private final ThreadPoolExecutor executor;

  /**
   * True while the single writer thread is inside a task (set on dequeue, cleared in a finally).
   * Lets shutdown detect a write that IGNORED interruption and would otherwise vanish silently.
   */
  private final AtomicBoolean inFlight = new AtomicBoolean(false);

  /**
   * Set once, just before {@code shutdownNow()}. A task that fails while this is true failed because
   * the drain interrupted it — a shutdown-caused loss. A task that fails while this is false is an
   * ordinary fail-soft failure (already logged) and is not a shutdown loss.
   */
  private volatile boolean shuttingDown = false;

  /**
   * Single-shot claim on the one in-flight task at shutdown (the executor is single-threaded, so at
   * most one task is ever in flight). The failing task ({@link #submit}) and the drain's stuck-task
   * branch ({@link #drainAndShutdown}) both try to CAS it true; the winner counts the loss, the loser
   * no-ops. Read directly as the in-flight loss count — no separate counter, so there is no
   * increment-visible-later gap for the drain to read as zero.
   */
  private final AtomicBoolean inFlightLossClaimed = new AtomicBoolean(false);

  /**
   * @param name the writer's identity — used verbatim for the daemon thread name ({@code
   *     <name>-writer}, so live thread dumps read exactly as before) and for the shutdown WARN
   * @param queueCapacity the bounded backlog; each writer sizes this for its own burst shape
   * @param log the OWNING writer's logger, so shutdown WARNs stay attributed to that class rather
   *     than to this helper (operators grep by writer, and the writers' tests assert on it)
   * @param dropped incremented per record shed by a saturated queue
   * @param shutdownDropped incremented per record abandoned at shutdown (queued + lost in-flight)
   */
  BoundedAsyncWriter(
      String name, int queueCapacity, Logger log, Counter dropped, Counter shutdownDropped) {
    this.name = name;
    this.log = log;
    this.dropped = dropped;
    this.shutdownDropped = shutdownDropped;
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            r -> {
              Thread t = new Thread(r, name + "-writer");
              t.setDaemon(true);
              return t;
            },
            // Queue full ⇒ DROP + count on the CALLING (eval) thread in O(1); never blocks, never throws.
            (r, exec) -> dropped.increment());
  }

  /**
   * Enqueue one write for asynchronous execution. NON-BLOCKING: the caller returns in O(1) whether
   * the task is queued or — on a saturated queue — dropped and counted. The task runs wrapped in the
   * in-flight marker and its reported outcome drives shutdown accounting: a task that did not persist
   * AND is unwinding after shutdown began is claimed as an in-flight loss here, which catches the
   * interrupt-RESPONSIVE insert that aborts and terminates within the grace.
   */
  void submit(WriteAttempt task) {
    executor.execute(
        () -> {
          // Marker for shutdown accounting: set on dequeue, cleared once the attempt completes.
          inFlight.set(true);
          boolean ok = false;
          try {
            ok = task.attempt();
          } catch (RuntimeException e) {
            // The attempt is contracted to swallow its own failures; this is belt-and-braces so an
            // escaped exception is accounted as a loss rather than corrupting the count.
            ok = false;
          } finally {
            // Claim the loss BEFORE clearing inFlight: if this ran, the record did not persist and
            // shutdown had begun, so it is a shutdown-caused loss. The CAS makes it exclusive with the
            // drain's stuck-task branch — if that branch already claimed (thought us stuck), this
            // no-ops rather than double-counting.
            if (!ok && shuttingDown) {
              inFlightLossClaimed.compareAndSet(false, true);
            }
            inFlight.set(false);
          }
        });
  }

  /**
   * Graceful shutdown: stop accepting new records, let the queue DRAIN for up to {@code drainMillis},
   * then abandon what is left — COUNTED and LOGGED, never silently lost. A silent
   * {@code shutdownNow()} would drop already-accepted records on an ordinary redeploy, which for a
   * "the block left a queryable row" trail defeats the purpose.
   *
   * <p>Returns immediately once the queue drains cleanly, which is the ordinary redeploy path.
   */
  void drainAndShutdown(long drainMillis) {
    executor.shutdown();
    boolean drained = false;
    try {
      drained = executor.awaitTermination(drainMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // restore the flag; abandon the rest below
    }
    if (drained) {
      return;
    }
    // The drain is giving up. Mark it BEFORE the interrupt so a task that then fails is attributed as
    // a shutdown loss (a task that failed during the graceful window above was a normal fail-soft
    // failure and is deliberately not counted here).
    shuttingDown = true;
    List<Runnable> abandoned = executor.shutdownNow(); // QUEUED tasks only — never the running one
    int queuedLost = abandoned.size();
    boolean terminated = false;
    try {
      terminated = executor.awaitTermination(SHUTDOWN_INFLIGHT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    // Interrupt-IGNORING loss: a task still in flight after the interrupt + grace ignored the
    // interrupt and will vanish at JVM exit. Claim it — unless the interrupt-RESPONSIVE path in
    // submit() already claimed the same task at the grace boundary (then this CAS no-ops).
    if (!terminated && inFlight.get()) {
      inFlightLossClaimed.compareAndSet(false, true);
    }
    // The claim is the single source of truth for the one possible in-flight loss: set by whichever
    // of the failing task or this branch saw it first, read once here as 0 or 1.
    int inFlightLost = inFlightLossClaimed.get() ? 1 : 0;
    int lost = queuedLost + inFlightLost;
    if (lost > 0) {
      shutdownDropped.increment(lost);
    }
    // Unconditional: a lost in-flight record with an EMPTY queue is exactly the case that used to
    // pass in total silence.
    log.warn(
        "{} writer shutdown drain exceeded {}ms — {} queued + {} in-flight record(s) abandoned"
            + " (counted in {})",
        name,
        drainMillis,
        queuedLost,
        inFlightLost,
        shutdownDropped.getId().getName());
  }
}
