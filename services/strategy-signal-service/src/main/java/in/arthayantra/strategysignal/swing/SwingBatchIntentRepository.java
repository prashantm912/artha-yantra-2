package in.arthayantra.strategysignal.swing;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The V048 schedule-time arming ledger used by the missed-batch detector. */
@Repository
public class SwingBatchIntentRepository {

  private final JdbcTemplate jdbc;

  /** Wires the strategy-schema JDBC access. */
  public SwingBatchIntentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The immutable arming intent captured for one scheduled session. */
  public record Intent(boolean armed, OffsetDateTime scheduledAt) {}

  /** Records the effective flag once; a later same-day retry must not rewrite historical intent. */
  public void recordScheduled(String batch, LocalDate session, boolean armed) {
    jdbc.update(
        """
        INSERT INTO swing_batch_schedule_intents (batch, session_date, armed, scheduled_at)
        VALUES (?, ?, ?, now())
        ON CONFLICT (batch, session_date) DO NOTHING
        """,
        batch, java.sql.Date.valueOf(session), armed);
  }

  /** Reads the arming state captured for the session, or empty for pre-V048/failed-capture history. */
  public Optional<Intent> find(String batch, LocalDate session) {
    return jdbc
        .query(
            "SELECT armed, scheduled_at FROM swing_batch_schedule_intents"
                + " WHERE batch = ? AND session_date = ?",
            (rs, n) ->
                new Intent(rs.getBoolean("armed"), rs.getObject("scheduled_at", OffsetDateTime.class)),
            batch, java.sql.Date.valueOf(session))
        .stream()
        .findFirst();
  }

  /**
   * Claimable missed sessions among the most recent schedule-intent rows, oldest first.
   *
   * <p>The inner LIMIT applies before the run/latch filters so the daily detector never rescans an
   * ever-growing history of successful runs. Successful sessions and terminal latches are excluded
   * in SQL; a stale RUNNING row remains eligible so a crash may produce a benign duplicate page.
   */
  public List<LocalDate> claimableMissedSessionsBefore(
      String batch,
      LocalDate exclusiveDate,
      int staleLeaseMinutes,
      int recentSessionLimit) {
    return jdbc.query(
        """
        WITH recent_intents AS MATERIALIZED (
          SELECT session_date, armed
          FROM swing_batch_schedule_intents
          WHERE batch = ? AND session_date < ?
          ORDER BY session_date DESC
          LIMIT ?
        )
        SELECT intent.session_date
        FROM recent_intents intent
        LEFT JOIN swing_missed_batch_alerts latch
          ON latch.batch = ? AND latch.session_date = intent.session_date
        WHERE intent.armed = TRUE
          AND NOT EXISTS (
            SELECT 1
            FROM swing_batch_runs run
            WHERE run.batch = ? AND run.run_date = intent.session_date
          )
          AND (
            latch.batch IS NULL
            OR (
              latch.status = 'RUNNING'
              AND latch.claimed_at IS NOT NULL
              AND latch.claimed_at < now() - make_interval(mins => ?)
            )
          )
        ORDER BY intent.session_date
        """,
        (rs, n) -> rs.getObject("session_date", LocalDate.class),
        batch,
        java.sql.Date.valueOf(exclusiveDate),
        recentSessionLimit,
        batch,
        batch,
        staleLeaseMinutes);
  }
}
