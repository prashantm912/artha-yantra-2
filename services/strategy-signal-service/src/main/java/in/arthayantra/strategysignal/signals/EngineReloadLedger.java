package in.arthayantra.strategysignal.signals;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * T15 (bug queue B7): persists each reload outcome to {@code strategy.engine_reloads} so the boot
 * line survives the deploy that would destroy its container log — the 2026-07-17 and 07-20
 * forensics both lost their headline root cause to exactly that race.
 *
 * <p>Bounded ASYNC (codex round 2): reloads run on the signal-eval executor and the kite-CONNECTED
 * recovery chain, and the datasource carries no statement timeout — a synchronous insert would let
 * a DB stall park bar evaluation and Kite recovery indefinitely (the #866 starvation class). So the
 * record is ENQUEUED in O(1) onto the shared {@link BoundedAsyncWriter} (one daemon drain thread;
 * a stalled DB fills the tiny queue and further records are dropped + counted, never
 * back-pressuring the caller), with the standard drain-on-shutdown so an ordinary redeploy keeps
 * its own boot row. Rows are rare (a handful per day), so the queue is small. Health reads
 * {@code unresolved == 0}, never {@code loaded > 0} (the 2026-07-16 F10 lesson).
 */
@Component
public class EngineReloadLedger {

  private static final Logger log = LoggerFactory.getLogger(EngineReloadLedger.class);

  /** A handful of reloads/day; anything queued past this during a DB stall is dropped + counted. */
  static final int QUEUE_CAPACITY = 16;

  /** On shutdown, let queued inserts DRAIN up to this long (the boot row must survive a redeploy). */
  static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final BoundedAsyncWriter queue;

  /** Wires the strategy-schema datasource, clock and the dropped-row counters. */
  public EngineReloadLedger(JdbcTemplate jdbc, Clock clock, MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.queue =
        new BoundedAsyncWriter(
            "engine-reload-ledger",
            QUEUE_CAPACITY,
            log,
            meterRegistry.counter("ay_engine_reload_ledger_dropped_total"),
            meterRegistry.counter("ay_engine_reload_ledger_shutdown_dropped_total"));
  }

  /**
   * Enqueue one reload outcome; NON-BLOCKING for the caller. {@code installed} = banks cleared +
   * resubscribed vs structurally unchanged. The timestamp is captured HERE (the reload moment),
   * not at drain time.
   */
  public void record(int loaded, int unresolved, int loadErrors, boolean installed) {
    Instant reloadAt = clock.instant();
    queue.submit(
        () -> {
          try {
            jdbc.update(
                "INSERT INTO engine_reloads (reload_at, loaded, unresolved, load_errors, installed)"
                    + " VALUES (?, ?, ?, ?, ?)",
                Timestamp.from(reloadAt), loaded, unresolved, loadErrors, installed);
            return true;
          } catch (RuntimeException e) {
            log.warn("engine reload ledger write failed (reload unaffected): {}", e.toString());
            return false;
          }
        });
  }

  @PreDestroy
  void shutdown() {
    queue.drainAndShutdown(SHUTDOWN_DRAIN_MILLIS);
  }
}
