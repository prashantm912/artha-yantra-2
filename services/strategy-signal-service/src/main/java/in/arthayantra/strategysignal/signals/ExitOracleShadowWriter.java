package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategysignal.scalper.SentimentLevelShadow;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded ASYNC writer for {@code exit_oracle_shadow} (V056) — the measurement-only counterfactual
 * recorded on every E9 D4 confluence-flip EXIT oracle evaluation.
 *
 * <p>The sole {@code signal-eval} thread must never park on I/O, and this record is produced on the
 * PROTECTIVE EXIT path — the one path where a stall is most expensive, because a position that cannot
 * be evaluated cannot be exited. So the row is ENQUEUED (O(1), never blocks the caller) onto a bounded
 * queue drained by one background thread; a stalled DB fills the queue and further records are DROPPED
 * and counted ({@code ay_exit_oracle_shadow_dropped_total}), never back-pressuring the eval thread.
 * Fail-soft end to end: a persistence failure is logged by the writer thread, never thrown into the
 * caller. Same seam and same guarantees as {@link RiskSuppressionWriter}.
 *
 * <p><b>Dropping a record is the CORRECT failure.</b> This is observability for a decision nobody has
 * made yet; losing a measurement row costs a data point, whereas delaying an exit costs money. If the
 * drop counter is non-zero the measurement is incomplete for that window and the analysis must say so
 * — which is exactly why the drop is counted rather than swallowed.
 */
@Component
public class ExitOracleShadowWriter {

  private static final Logger log = LoggerFactory.getLogger(ExitOracleShadowWriter.class);

  /**
   * Bounded backlog. The oracle fires at most once per held position per bar across the 12 strategies
   * carrying {@code oi-confluence-exit}, so a burst is bounded by the number of concurrently-held
   * positions — far below this. A stalled DB drops past it.
   */
  static final int QUEUE_CAPACITY = 256;

  /** On shutdown, let the queued inserts DRAIN up to this long before abandoning them. */
  static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  private final ExitOracleShadowRepository repository;
  private final BoundedAsyncWriter queue;

  /** Wires the JDBC repository + the dropped-record counters (runtime saturation + shutdown loss). */
  public ExitOracleShadowWriter(
      ExitOracleShadowRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.queue =
        new BoundedAsyncWriter(
            "exit-oracle-shadow",
            QUEUE_CAPACITY,
            log,
            meterRegistry.counter("ay_exit_oracle_shadow_dropped_total"),
            meterRegistry.counter("ay_exit_oracle_shadow_shutdown_dropped_total"));
  }

  /**
   * Enqueue one oracle evaluation for asynchronous persistence. NON-BLOCKING: the caller (the eval
   * thread, mid-exit-evaluation) returns in O(1) whether the record is queued or — on a saturated
   * queue — dropped and counted. Never throws.
   */
  public void record(
      long entrySignalId,
      String strategySlug,
      OffsetDateTime barTime,
      String heldSide,
      String evaluatedSide,
      String liveOracleSide,
      boolean liveFlip,
      SentimentLevelShadow shadow) {
    queue.submit(
        () -> {
          try {
            repository.insert(
                entrySignalId, strategySlug, barTime, heldSide, evaluatedSide, liveOracleSide,
                liveFlip, shadow.flowPct(), shadow.levelPct(), shadow.sentimentDotWouldSupport(),
                shadow.oiSlopeAgreeWouldPass());
            return true;
          } catch (RuntimeException e) {
            log.warn(
                "failed to persist exit-oracle shadow for entry {} ({} @ {}): {}",
                entrySignalId, strategySlug, barTime, e.toString());
            return false;
          }
        });
  }

  @PreDestroy
  void shutdown() {
    drainAndShutdown(SHUTDOWN_DRAIN_MILLIS);
  }

  /**
   * Graceful shutdown (package-private so a test can use a short drain window). Leftovers are COUNTED
   * ({@code ay_exit_oracle_shadow_shutdown_dropped_total}) and LOGGED rather than silently lost, so an
   * ordinary redeploy leaves a visible mark on the measurement rather than a quiet hole in it.
   */
  void drainAndShutdown(long drainMillis) {
    queue.drainAndShutdown(drainMillis);
  }
}
