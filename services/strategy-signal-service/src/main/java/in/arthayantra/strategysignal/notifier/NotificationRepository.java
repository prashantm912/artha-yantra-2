package in.arthayantra.strategysignal.notifier;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Notifier persistence (Phase 41): resolves a strategy's opt-in (from the {@code strategies} row,
 * NOT the versioned config) and appends per-attempt {@code notification_events} audit rows. Reads
 * the notification columns + writes the append-only audit via its own JDBC — no cross-module Java
 * dependency on the registry/signals beans (Modulith-clean).
 */
@Repository
public class NotificationRepository {

  /** A strategy's notification opt-in. */
  public record Target(UUID strategyId, boolean enabled, String channel) {}

  /** Delivery-outcome counts over a window (V15 notifier-health input; SUPPRESSED = flood control, not a failure). */
  public record DeliveryStats(long sent, long failed, long suppressed) {}

  private final JdbcTemplate jdbc;

  /** Wires the JDBC template. */
  public NotificationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Opt-in for the strategy that owns a version (the notifier resolves from the signal's version). */
  public Optional<Target> targetForVersion(UUID versionId) {
    return one(
        "SELECT s.id, s.notifications_enabled, s.notification_channel "
            + "FROM strategies s JOIN strategy_versions v ON v.strategy_id = s.id WHERE v.id = ?",
        versionId);
  }

  /** Opt-in by strategy id (the test-send path). */
  public Optional<Target> targetForStrategy(UUID strategyId) {
    return one(
        "SELECT id, notifications_enabled, notification_channel FROM strategies WHERE id = ?",
        strategyId);
  }

  private Optional<Target> one(String sql, UUID id) {
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              sql,
              (rs, n) ->
                  new Target(
                      UUID.fromString(rs.getString(1)),
                      rs.getBoolean(2),
                      rs.getString(3)),
              id));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * Delivery-outcome counts for events created at or after {@code since} (V15 notifier-health
   * check). An aggregate {@code queryForObject} always returns one row — zero events yields
   * {@code (0, 0, 0)}, never an empty result.
   */
  public DeliveryStats deliveryStats(Instant since) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FILTER (WHERE status = 'SENT')      AS sent,
               count(*) FILTER (WHERE status = 'FAILED')     AS failed,
               count(*) FILTER (WHERE status = 'SUPPRESSED') AS suppressed
        FROM notification_events
        WHERE created_at >= ?
        """,
        (rs, n) -> new DeliveryStats(rs.getLong("sent"), rs.getLong("failed"), rs.getLong("suppressed")),
        Timestamp.from(since));
  }

  /** Append one delivery-attempt audit row (signalId null for a manual test-send). */
  public void record(
      Long signalId, UUID strategyId, String channel, String status, int attempts, String detail) {
    jdbc.update(
        "INSERT INTO notification_events (signal_id, strategy_id, channel, status, attempts, detail) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        signalId,
        strategyId,
        channel,
        status,
        attempts,
        detail);
  }

  /** Append one insight delivery-attempt audit row; strategyId is nullable for market-scoped insights. */
  public void recordInsight(
      UUID insightId, UUID strategyId, String channel, String status, int attempts, String detail) {
    jdbc.update(
        "INSERT INTO notification_events (signal_id, strategy_id, insight_id, channel, status, attempts, detail) "
            + "VALUES (NULL, ?, ?, ?, ?, ?, ?)",
        strategyId,
        insightId,
        channel,
        status,
        attempts,
        detail);
  }
}
