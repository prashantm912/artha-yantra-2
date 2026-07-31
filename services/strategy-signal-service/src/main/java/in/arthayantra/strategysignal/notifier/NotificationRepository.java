package in.arthayantra.strategysignal.notifier;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
 *
 * <p>It also owns this module's {@code canary_runs} claim/completion pair (V052), so {@link
 * NotifierHealthCheck} keeps its promise of touching only this repository and {@link
 * NotifierClient}.
 */
@Repository
public class NotificationRepository {

  /** A strategy's notification opt-in. */
  public record Target(UUID strategyId, boolean enabled, String channel) {}

  /** Delivery-outcome counts over a window (V15 notifier-health input; SUPPRESSED = flood control, not a failure). */
  public record DeliveryStats(long sent, long failed, long suppressed) {}

  /** A canary day's ledger state (V052): {@code CLAIMED} or {@code DONE}, and when it was claimed. */
  public record CanaryRunState(String status, Instant claimedAt) {}

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

  /**
   * Takes the PROVISIONAL per-IST-day claim for a scheduled canary (V052 {@code canary_runs});
   * {@code true} iff THIS call now holds the day and may publish. Succeeds when there is no row,
   * when the existing row is a {@code CLAIMED} one whose {@code claimed_at} is older than {@code
   * staleBefore} (an interrupted run, reclaimed), and never when the row is {@code DONE}.
   *
   * <p>It is the whole idempotency gate, so it has to stay ONE atomic statement — a read-then-write
   * would let two doors in the same morning both see "not done" and both alert. {@code istDay} is
   * derived from the caller's clock via {@code Ist.ZONE}, never {@code now()::date} (the container
   * clock is UTC), and both instants come from that same injected clock rather than SQL {@code
   * now()}, so the lease is drivable by a fixed clock in tests.
   */
  public boolean claimCanaryRun(
      String canary, LocalDate istDay, String source, Instant now, Instant staleBefore) {
    return jdbc.update(
            """
            INSERT INTO canary_runs (canary, run_day, source, status, claimed_at)
            VALUES (?, ?, ?, 'CLAIMED', ?)
            ON CONFLICT (canary, run_day) DO UPDATE
               SET source = EXCLUDED.source,
                   status = 'CLAIMED',
                   claimed_at = EXCLUDED.claimed_at
             WHERE canary_runs.status <> 'DONE'
               AND canary_runs.claimed_at < ?
            """,
            canary,
            istDay,
            source,
            Timestamp.from(now),
            Timestamp.from(staleBefore))
        == 1;
  }

  /**
   * Current state of a canary's run row (V052), or empty when the day has no row. Read ONLY after a
   * lost claim, to tell the two losses apart: {@code DONE} is final, a live {@code CLAIMED} lease is
   * "not yet" and tells the caller when the day becomes reclaimable.
   */
  public Optional<CanaryRunState> canaryRunState(String canary, LocalDate istDay) {
    return jdbc
        .query(
            "SELECT status, claimed_at FROM canary_runs WHERE canary = ? AND run_day = ?",
            (rs, n) -> new CanaryRunState(rs.getString(1), rs.getTimestamp(2).toInstant()),
            canary,
            istDay)
        .stream()
        .findFirst();
  }

  /**
   * Flips this caller's claim to {@code DONE} once publication has actually happened; {@code true}
   * iff it landed. Compare-and-set on {@code claimed_at}: if a later door reclaimed the day (this
   * holder overran its lease), the timestamps differ, zero rows update, and the reclaimer's run
   * stands — a superseded holder can never mark someone else's claim complete.
   */
  public boolean completeCanaryRun(String canary, LocalDate istDay, Instant claimedAt, Instant ranAt) {
    return jdbc.update(
            """
            UPDATE canary_runs SET status = 'DONE', ran_at = ?
             WHERE canary = ? AND run_day = ? AND claimed_at = ?
            """,
            Timestamp.from(ranAt),
            canary,
            istDay,
            Timestamp.from(claimedAt))
        == 1;
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
}
