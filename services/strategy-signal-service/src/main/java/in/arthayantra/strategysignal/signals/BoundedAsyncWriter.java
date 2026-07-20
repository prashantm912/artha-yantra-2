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
 * QUEUED tasks — the task that was RUNNING is not in that list. A record still in flight after the
 * interrupt would vanish at JVM exit with no counter and, when it is the sole task, no WARN at all:
 * the "counter reads healthy while data is lost" shape. So the drain timeout is followed by a second
 * bounded await, a still-in-flight record is counted as lost alongside the abandoned queue, and the
 * WARN fires UNCONDITIONALLY (even on an empty queue). Fail-soft throughout: never throws out of
 * shutdown.
 */
final class BoundedAsyncWriter {

  /**
   * After {@code shutdownNow()} interrupts the drain, give a well-behaved insert this long to unwind
   * before we conclude the still-running insert is stuck and count it as lost.
   */
  static final long SHUTDOWN_INFLIGHT_GRACE_MILLIS = 1_000L;

  private final String name;
  private final Logger log;
  private final Counter dropped;
  private final Counter shutdownDropped;
  private final ThreadPoolExecutor executor;

  /**
   * True while the single writer thread is inside a task (set on dequeue, cleared in a finally).
   * Lets shutdown detect a write that ignored interruption and would otherwise vanish silently.
   */
  private final AtomicBoolean inFlight = new AtomicBoolean(false);

  /**
   * @param name the writer's identity — used verbatim for the daemon thread name ({@code
   *     <name>-writer}, so live thread dumps read exactly as before) and for the shutdown WARN
   * @param queueCapacity the bounded backlog; each writer sizes this for its own burst shape
   * @param log the OWNING writer's logger, so shutdown WARNs stay attributed to that class rather
   *     than to this helper (operators grep by writer, and the writers' tests assert on it)
   * @param dropped incremented per record shed by a saturated queue
   * @param shutdownDropped incremented per record abandoned at shutdown (queued + stuck in-flight)
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
   * in-flight marker so shutdown can account for it; the task itself is responsible for swallowing
   * its own failures (a diagnostic must never break the live signal path).
   */
  void submit(Runnable task) {
    executor.execute(
        () -> {
          // Marker for shutdown accounting: set on dequeue, cleared once the attempt completes.
          inFlight.set(true);
          try {
            task.run();
          } finally {
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
    List<Runnable> abandoned = executor.shutdownNow(); // QUEUED tasks only — never the running one
    int queuedLost = abandoned.size();
    boolean terminated = false;
    try {
      terminated = executor.awaitTermination(SHUTDOWN_INFLIGHT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    // A record still in flight after the interrupt + grace ignored the interrupt: it will vanish at
    // JVM exit, so count it as lost alongside the abandoned queue.
    int inFlightLost = (!terminated && inFlight.get()) ? 1 : 0;
    int lost = queuedLost + inFlightLost;
    if (lost > 0) {
      shutdownDropped.increment(lost);
    }
    // Unconditional: a stuck in-flight record with an EMPTY queue is exactly the case that used to
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
