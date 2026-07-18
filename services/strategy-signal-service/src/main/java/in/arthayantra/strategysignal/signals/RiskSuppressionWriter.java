package in.arthayantra.strategysignal.signals;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PF-03 bounded ASYNC writer for {@code risk_suppressions}. The sole {@code signal-eval} thread must
 * never park on I/O — a DB stall on the eval loop is the #866 / 2026-07-14 zero-signals starvation
 * class, and CLAUDE.md forbids adding unbounded I/O to the hot path. So a governor-veto record is
 * ENQUEUED (O(1), never blocks the caller) onto a bounded queue drained by one background thread;
 * when the DB stalls the queue fills and further records are DROPPED and counted
 * ({@code ay_risk_suppression_dropped_total}), never back-pressuring the eval thread. Fail-soft end
 * to end: a persistence failure is logged by the writer thread, never thrown into the caller. On
 * shutdown the queue is DRAINED (bounded) before the executor stops, so an ordinary redeploy does
 * not silently lose already-accepted records (any residue is counted + logged, never dropped quietly).
 *
 * <p>This is strictly safer than the existing synchronous {@code signal_rejections} writer
 * ({@code SignalEngine.recordRejection}) — that one still runs on the eval thread (pre-existing; left
 * untouched by the surgical-change rule, flagged as a follow-up).
 */
@Component
public class RiskSuppressionWriter {

  private static final Logger log = LoggerFactory.getLogger(RiskSuppressionWriter.class);

  /** Bounded backlog: at the paper 3m cadence a burst is small; a stalled DB drops past this. */
  static final int QUEUE_CAPACITY = 256;

  /** On shutdown, let the queued inserts DRAIN up to this long before abandoning them. */
  static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  private final RiskSuppressionRepository repository;
  private final Counter dropped;
  private final Counter shutdownDropped;
  private final ThreadPoolExecutor executor;

  /** Wires the JDBC repository + the dropped-record counters (runtime saturation + shutdown loss). */
  public RiskSuppressionWriter(RiskSuppressionRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.dropped = meterRegistry.counter("ay_risk_suppression_dropped_total");
    this.shutdownDropped = meterRegistry.counter("ay_risk_suppression_shutdown_dropped_total");
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
              Thread t = new Thread(r, "risk-suppression-writer");
              t.setDaemon(true);
              return t;
            },
            // Queue full ⇒ DROP + count on the CALLING (eval) thread in O(1); never blocks, never throws.
            (r, exec) -> dropped.increment());
  }

  /**
   * Enqueue one veto record for asynchronous persistence. NON-BLOCKING: the caller (the eval thread)
   * returns in O(1) whether the record is queued or — on a saturated queue — dropped and counted.
   */
  public void record(
      UUID strategyVersionId,
      String strategySlug,
      String book,
      String rail,
      String exchange,
      String tradingsymbol,
      String interval,
      String side,
      String optionType,
      String optionTradingsymbol,
      OffsetDateTime barTime) {
    executor.execute(
        () -> {
          try {
            repository.insert(
                strategyVersionId, strategySlug, book, rail, exchange, tradingsymbol, interval,
                side, optionType, optionTradingsymbol, barTime);
          } catch (RuntimeException e) {
            log.warn(
                "failed to persist risk suppression {} {}:{} rail={}: {}",
                strategySlug, exchange, tradingsymbol, rail, e.toString());
          }
        });
  }

  @PreDestroy
  void shutdown() {
    drainAndShutdown(SHUTDOWN_DRAIN_MILLIS);
  }

  /**
   * Graceful shutdown (package-private so a test can use a short drain window). Stops accepting new
   * records, lets the queued inserts DRAIN for up to {@code drainMillis}, then abandons whatever is
   * left. A silent {@code shutdownNow()} would DROP already-accepted audit records on an ordinary
   * redeploy — for a "the veto left a queryable record" trail that defeats the purpose. So any
   * leftover is COUNTED ({@code ay_risk_suppression_shutdown_dropped_total}) and LOGGED, never
   * silently lost. Fail-soft: never throws out of shutdown.
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
    List<Runnable> abandoned = executor.shutdownNow();
    if (!abandoned.isEmpty()) {
      shutdownDropped.increment(abandoned.size());
      log.warn(
          "risk-suppression writer shutdown drain exceeded {}ms — {} queued veto record(s) abandoned"
              + " (counted in ay_risk_suppression_shutdown_dropped_total)",
          drainMillis, abandoned.size());
    }
  }
}
