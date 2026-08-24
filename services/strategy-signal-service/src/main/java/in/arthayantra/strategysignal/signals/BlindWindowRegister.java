package in.arthayantra.strategysignal.signals;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Durable register of the windows in which the live signal engine was blind because the PRODUCER
 * was blind (V062).
 *
 * <p>{@link SubscriberHealthCanary} deliberately does not remediate a dead feed — that is
 * market-data's ownership — and so, until this existed, it wrote nothing at all for that case. The
 * 2026-08-19 host-network outage therefore left {@code strategy.subscriber_health_events} empty
 * while the reconnect gap-backfill quietly repaired the candles, so the bars came back and the fact
 * that the engine never saw them live did not. This closes the record half only: it never triggers
 * a replay, because a bar the engine did not see when it was live is not a decision it gets to make
 * later (owner ruling 2026-08-24 — backfill the data, never re-decide the bars).
 *
 * <p>Windows are closed BY ID by the process that opened them. A service that dies while blind
 * leaves {@code ended_at} NULL forever, which is the truth — the end was never observed. Writes are
 * FAIL-SOFT like {@link SubscriberHealthTelemetry}: a register failure must never break the sweep,
 * so a failed {@link #open} returns {@code null} and the matching {@link #close} becomes a no-op.
 */
@Component
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true) // shares the canary's lifecycle — its only writer
public class BlindWindowRegister {

  private static final Logger log = LoggerFactory.getLogger(BlindWindowRegister.class);

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public BlindWindowRegister(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Opens a window at the last moment the engine is known to have had data. Returns the row id, or
   * {@code null} when the write failed — the caller must treat null as "nothing to close".
   */
  public Long open(Instant startedAt, String detail) {
    try {
      return jdbc.queryForObject(
          "INSERT INTO blind_windows (started_at, detail) VALUES (?, ?) RETURNING id",
          Long.class,
          OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
          detail);
    } catch (RuntimeException e) {
      log.warn("blind-window open failed ({}): {}", detail, e.toString());
      return null;
    }
  }

  /**
   * Closes the window this process opened. {@code reason} distinguishes a real recovery
   * ({@code bars-resumed}) from a window that merely outlasted the session ({@code session-ended})
   * — conflating them would report a still-dead feed as healed.
   */
  public void close(Long id, Instant endedAt, String reason) {
    if (id == null) {
      return; // the open never landed; there is no row to close
    }
    try {
      jdbc.update(
          "UPDATE blind_windows SET ended_at = ?, closed_reason = ? WHERE id = ? AND ended_at IS NULL",
          OffsetDateTime.ofInstant(endedAt, ZoneOffset.UTC),
          reason,
          id);
    } catch (RuntimeException e) {
      log.warn("blind-window close failed (id={}, reason={}): {}", id, reason, e.toString());
    }
  }
}
