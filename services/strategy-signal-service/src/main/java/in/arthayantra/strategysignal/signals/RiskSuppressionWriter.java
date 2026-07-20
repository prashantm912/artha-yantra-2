package in.arthayantra.strategysignal.signals;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.UUID;
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
  private final BoundedAsyncWriter queue;

  /** Wires the JDBC repository + the dropped-record counters (runtime saturation + shutdown loss). */
  public RiskSuppressionWriter(RiskSuppressionRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.queue =
        new BoundedAsyncWriter(
            "risk-suppression",
            QUEUE_CAPACITY,
            log,
            meterRegistry.counter("ay_risk_suppression_dropped_total"),
            meterRegistry.counter("ay_risk_suppression_shutdown_dropped_total"));
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
    queue.submit(
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
   * silently lost.
   *
   * <p>The two-phase drain that also accounts for the RUNNING insert (which {@code shutdownNow()}
   * does not report) now lives in the shared {@link BoundedAsyncWriter} — see its javadoc. It was
   * worked out here first and, being a copy rather than a shared implementation, never reached
   * {@link RejectionWriter}. Fail-soft: never throws out of shutdown.
   */
  void drainAndShutdown(long drainMillis) {
    queue.drainAndShutdown(drainMillis);
  }
}
