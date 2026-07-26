package in.arthayantra.strategysignal.swing;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code swing_missed_batch_alerts} durable claim + attempt ledger (V047, 2026-07-17 incident). The
 * detector's episode latch is taken before publishing an alert; no replay coordinator is part of
 * this service.
 */
@Repository
public class SwingMissedBatchAlertRepository {

  private final JdbcTemplate jdbc;

  /** Wires the JDBC template (its own autocommit makes the latch visible before alert delivery). */
  public SwingMissedBatchAlertRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The won claim, carrying the (post-increment) attempt number. */
  public record Claim(int attempts) {}

  /**
   * Atomically claims a session for an episode alert. Returns empty when another caller holds a fresh
   * RUNNING claim, or the session already has a terminal ABANDONED latch.
   */
  public Optional<Claim> claim(String batch, LocalDate session, int staleLeaseMinutes) {
    return jdbc
        .query(
            """
            INSERT INTO swing_missed_batch_alerts (batch, session_date, status, attempts, claimed_at, updated_at)
            VALUES (?, ?, 'RUNNING', 1, now(), now())
            ON CONFLICT (batch, session_date) DO UPDATE
              SET status = 'RUNNING',
                  attempts = swing_missed_batch_alerts.attempts + 1,
                  claimed_at = now(),
                  updated_at = now()
              WHERE swing_missed_batch_alerts.status = 'RUNNING'
                AND swing_missed_batch_alerts.claimed_at < now() - make_interval(mins => ?)
            RETURNING attempts
            """,
            (rs, n) -> new Claim(rs.getInt("attempts")),
            batch,
            java.sql.Date.valueOf(session),
            staleLeaseMinutes)
        .stream()
        .findFirst();
  }

  /** Records a terminal detector state, creating the row when no claim exists yet. */
  public void markAbandoned(String batch, LocalDate session, String reason) {
    jdbc.update(
        """
        INSERT INTO swing_missed_batch_alerts (batch, session_date, status, attempts, reason, updated_at)
        VALUES (?, ?, 'ABANDONED', 0, ?, now())
        ON CONFLICT (batch, session_date) DO UPDATE
          SET status = 'ABANDONED', reason = COALESCE(swing_missed_batch_alerts.reason, EXCLUDED.reason),
              updated_at = now()
          WHERE swing_missed_batch_alerts.status = 'RUNNING'
        """,
        batch, java.sql.Date.valueOf(session), reason);
  }
}
