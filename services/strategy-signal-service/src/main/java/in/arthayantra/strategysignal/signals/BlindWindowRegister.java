package in.arthayantra.strategysignal.signals;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
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

  /**
   * Seconds any single register statement may run before the driver aborts it.
   *
   * <p>⚠️ This is the concession to the monitor-pool invariant, and it is worth stating plainly
   * rather than leaving as a magic number. {@code SubscriberHealthCanary.sweep} runs on a
   * SINGLE-thread detector pool whose contract is that "a detector that gains ANY blocking call must
   * move off this pool too, because a STALLED call starves every sibling while it hangs". The
   * unbounded HANG is the property that rule exists to prevent, so these statements are bounded:
   * they cannot hold the detector thread indefinitely, whatever the database does. It is a bound,
   * not the only measure: as of #1453 the whole sweep runs on its own
   * {@code subscriberWatchdogTaskScheduler}, so a stall here cannot starve the fenced detector pool
   * at all. {@link SubscriberHealthTelemetry}'s insert on the same sweep is still UNBOUNDED — that
   * predates this and is filed separately; the ordering in {@code openOrRetry} is what stops it
   * costing the artifact.
   */
  private static final int STATEMENT_TIMEOUT_SECONDS = 2;

  private final JdbcTemplate jdbc;

  /**
   * Wires the strategy datasource through its OWN {@link JdbcTemplate} rather than the shared bean,
   * so the timeout above applies here and cannot leak onto every other caller in the service.
   */
  public BlindWindowRegister(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
  }

  /**
   * Opens a window at the last moment the engine is known to have had data, IDEMPOTENTLY on
   * {@code episodeKey}. Returns the row id, or {@code null} when the write failed — the caller must
   * treat null as "nothing to close".
   *
   * <p>The key is what makes a retry safe after an AMBIGUOUS COMMIT: a plain INSERT that commits but
   * loses its {@code RETURNING} response looks exactly like one that never ran, and retrying it
   * would leave the first row open forever while recovery closed the second — breaking
   * {@code ended_at IS NULL}, the only thing this table is read for.
   */
  public Long open(String episodeKey, Instant startedAt, String detail) {
    try {
      // ON CONFLICT ... DO UPDATE (not DO NOTHING) because DO NOTHING returns no row, so a retry
      // after an ambiguous commit could not recover the id it needs in order to close the window.
      // The SET is a deliberate no-op: it re-writes the key to itself purely to make the row
      // returnable, never overwriting a start or detail already recorded.
      return jdbc.queryForObject(
          "INSERT INTO blind_windows (episode_key, started_at, detail) VALUES (?, ?, ?)"
              + " ON CONFLICT (episode_key)"
              + " DO UPDATE SET episode_key = blind_windows.episode_key"
              + " RETURNING id",
          Long.class,
          episodeKey,
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
   *
   * <p>Returns whether the close is now DURABLE, so the caller can keep retryable state instead of
   * dropping it on a swallowed failure. A no-op {@code null} id counts as durable (there was never a
   * row), and an id already closed counts as durable too (the guard makes the close idempotent
   * rather than restamping a window with a second, contradicting reason).
   */
  public boolean close(Long id, Instant endedAt, String reason) {
    if (id == null) {
      return true; // the open never landed; there is no row to close
    }
    try {
      jdbc.update(
          "UPDATE blind_windows SET ended_at = ?, closed_reason = ? WHERE id = ? AND ended_at IS NULL",
          OffsetDateTime.ofInstant(endedAt, ZoneOffset.UTC),
          reason,
          id);
      return true;
    } catch (RuntimeException e) {
      log.warn("blind-window close failed (id={}, reason={}): {}", id, reason, e.toString());
      return false;
    }
  }
}
