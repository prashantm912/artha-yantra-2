package in.arthayantra.strategysignal.swing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The V047 schedule-time arming ledger used by the missed-batch detector. */
@Repository
public class SwingBatchIntentRepository {

  private final JdbcTemplate jdbc;

  /** Wires the strategy-schema JDBC access. */
  public SwingBatchIntentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

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

  /** The arming captured for exactly this session, or empty when the scheduler never fired for it. */
  public Optional<Boolean> find(String batch, LocalDate session) {
    return jdbc
        .query(
            "SELECT armed FROM swing_batch_schedule_intents WHERE batch = ? AND session_date = ?",
            (rs, n) -> rs.getBoolean("armed"),
            batch, java.sql.Date.valueOf(session))
        .stream()
        .findFirst();
  }

  /**
   * The newest arming captured at or before {@code session} — how the detector reasons about a
   * session the scheduler never fired for at all.
   *
   * <p>This is the container-down case the detector exists for: no JVM at 20:00 means no intent row,
   * so treating "no row" as "not armed" would leave the detector blind to precisely the incident it
   * replaces. Carrying the last known arming forward keeps every property the design needs — a
   * family deliberately disarmed yesterday reads {@code false} and stays quiet, re-enabling it today
   * cannot invent a past miss because the read is still historical, and a fresh deploy with no
   * intent rows at all returns empty and stays silent.
   */
  public Optional<Boolean> lastKnownArmedOnOrBefore(String batch, LocalDate session) {
    return jdbc
        .query(
            """
            SELECT armed FROM swing_batch_schedule_intents
            WHERE batch = ? AND session_date <= ?
            ORDER BY session_date DESC
            LIMIT 1
            """,
            (rs, n) -> rs.getBoolean("armed"),
            batch, java.sql.Date.valueOf(session))
        .stream()
        .findFirst();
  }

  /**
   * Sessions that were armed at schedule time, have no run marker, and are outside their page lease
   * — oldest first. Covers every session the scheduler DID fire for.
   *
   * <p>The inner LIMIT applies before the run/lease filters so the daily sweep never rescans an
   * ever-growing history of successful runs. <b>Effect, not just intent:</b> a missed session that
   * has since fallen outside the newest {@code recentSessionLimit} intent rows will never be paged
   * by this query again — roughly a three-month horizon at 64, far beyond any window in which a
   * missed EOD batch is still actionable.
   */
  public List<LocalDate> claimableMissedSessionsBefore(
      String batch,
      LocalDate exclusiveDate,
      int pageLeaseMinutes,
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
            OR latch.claimed_at < now() - make_interval(mins => ?)
          )
        ORDER BY intent.session_date
        """,
        (rs, n) -> rs.getObject("session_date", LocalDate.class),
        batch,
        java.sql.Date.valueOf(exclusiveDate),
        recentSessionLimit,
        batch,
        batch,
        pageLeaseMinutes);
  }
}
