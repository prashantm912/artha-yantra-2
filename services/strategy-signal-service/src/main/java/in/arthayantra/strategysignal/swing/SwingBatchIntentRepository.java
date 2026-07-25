package in.arthayantra.strategysignal.swing;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The V048 schedule-time arming ledger used to decide whether a missed session may be replayed. */
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
}
