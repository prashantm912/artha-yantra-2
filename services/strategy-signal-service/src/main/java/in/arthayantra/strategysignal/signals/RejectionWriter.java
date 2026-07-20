package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded ASYNC writer for {@code signal_rejections} (task_084d4d01, same #866 / 2026-07-14
 * zero-signals starvation class as {@link RiskSuppressionWriter}). The sole {@code signal-eval}
 * thread must never park on I/O — a DB stall on the eval loop is the starvation class, and CLAUDE.md
 * forbids adding unbounded I/O to the hot path. So a live confluence-rejection diagnostic is ENQUEUED
 * (O(1), never blocks the caller) onto a bounded queue drained by one background thread; when the DB
 * stalls the queue fills and further records are DROPPED and counted
 * ({@code ay_signal_rejection_dropped_total}), never back-pressuring the eval thread. Fail-soft end to
 * end: a persistence failure is logged by the writer thread, never thrown into the caller. On shutdown
 * the queue is DRAINED (bounded) before the executor stops, so an ordinary redeploy does not silently
 * lose already-accepted records (any residue is counted + logged, never dropped quietly).
 *
 * <p>The write is TWO coupled operations that both belong OFF the eval thread: the
 * {@code signal_rejections} INSERT (which mints the row id) and the default-OFF shadow book open,
 * which needs that id and does its own DB I/O. Both run here on the writer thread — the id is produced
 * and consumed on the same background thread, so the eval loop never waits for either.
 *
 * <p>LIVE path only by construction — the deterministic golden replay never reaches the confluence
 * gate, so this writer is never exercised on a backtest (no rows there → parity-safe).
 */
@Component
public class RejectionWriter {

  private static final Logger log = LoggerFactory.getLogger(RejectionWriter.class);

  /** Bounded backlog: at the primary-bar cadence a burst is small; a stalled DB drops past this. */
  static final int QUEUE_CAPACITY = 256;

  /** On shutdown, let the queued inserts DRAIN up to this long before abandoning them. */
  static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  private final SignalRejectionRepository repository;
  private final ShadowBookService shadowBook;
  private final BoundedAsyncWriter queue;

  /** Wires the JDBC repository + shadow book + the dropped-record counters (saturation + shutdown). */
  public RejectionWriter(
      SignalRejectionRepository repository,
      ShadowBookService shadowBook,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.shadowBook = shadowBook;
    this.queue =
        new BoundedAsyncWriter(
            "signal-rejection",
            QUEUE_CAPACITY,
            log,
            meterRegistry.counter("ay_signal_rejection_dropped_total"),
            meterRegistry.counter("ay_signal_rejection_shutdown_dropped_total"));
  }

  /**
   * Enqueue one confluence-rejection diagnostic for asynchronous persistence. NON-BLOCKING: the
   * caller (the eval thread) returns in O(1) whether the record is queued or — on a saturated queue —
   * dropped and counted. On the writer thread the {@code signal_rejections} INSERT runs first and its
   * minted id feeds the default-OFF shadow-book open; a failure of either is swallowed with a warning
   * (a diagnostic must never break the live signal path).
   */
  public void record(
      UUID strategyVersionId,
      String strategySlug,
      String exchange,
      String tradingsymbol,
      String interval,
      String side,
      String blockingRail,
      BigDecimal blockingOperand,
      BigDecimal blockingThreshold,
      BigDecimal blockingMargin,
      String blockingReason,
      BigDecimal compositeScore,
      BigDecimal compositeThreshold,
      String diagnosticJson,
      OffsetDateTime barTime,
      ScalperConfluenceGate.RejectionDiagnostic diagnostic) {
    queue.submit(
        () -> {
          try {
            long rejectionId =
                repository.insert(
                    strategyVersionId, strategySlug, exchange, tradingsymbol, interval, side,
                    blockingRail, blockingOperand, blockingThreshold, blockingMargin, blockingReason,
                    compositeScore, compositeThreshold, diagnosticJson, barTime);
            // Shadow book (default-OFF): an eligible rejection opens as a virtual 1-lot position so
            // the exit sweep labels it with a real PnL. maybeOpen never throws (diagnostics never
            // break live) — the guard here is belt-and-braces for the shared writer thread.
            shadowBook.maybeOpen(
                rejectionId, strategyVersionId, strategySlug, exchange, tradingsymbol, barTime,
                diagnostic);
            return true;
          } catch (RuntimeException e) {
            log.warn(
                "failed to persist scalper rejection {} {}:{}: {}",
                strategySlug, exchange, tradingsymbol, e.toString());
            return false;
          }
        });
  }

  @PreDestroy
  void shutdown() {
    drainAndShutdown(SHUTDOWN_DRAIN_MILLIS);
  }

  /**
   * Graceful shutdown (package-private so a test can use a short drain window). Delegates to the
   * shared {@link BoundedAsyncWriter} two-phase drain: the queue drains for up to
   * {@code drainMillis}, then whatever is left — INCLUDING an insert still running, which
   * {@code shutdownNow()} does not report — is COUNTED in
   * {@code ay_signal_rejection_shutdown_dropped_total} and WARNed. This class previously counted
   * only the queued residue, so a graceful shutdown that caught the sole insert mid-flight lost that
   * diagnostic with the counter reading 0 and no log line at all.
   */
  void drainAndShutdown(long drainMillis) {
    queue.drainAndShutdown(drainMillis);
  }
}
