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

  /**
   * Records a PROVISIONAL observation of the flag — first write wins, later ticks are no-ops.
   *
   * <p>Used by the intraday intent ticks, whose only job is to guarantee that SOME row exists for
   * the session even if the settle never runs (a container down at 16:00 would otherwise leave the
   * catch-up with nothing and forfeit the session's entries).
   *
   * <p>⚠️ It is deliberately NOT the authoritative value — see {@link #recordSettled}. A morning
   * observation is a guess about what the flag will be at settle time, and the flag can move
   * between them.
   */
  public void recordScheduled(String batch, LocalDate session, boolean armed) {
    jdbc.update(
        """
        INSERT INTO swing_batch_schedule_intents (batch, session_date, armed, scheduled_at, settled)
        VALUES (?, ?, ?, now(), false)
        ON CONFLICT (batch, session_date) DO UPDATE
          SET armed = EXCLUDED.armed, scheduled_at = EXCLUDED.scheduled_at
          WHERE swing_batch_schedule_intents.settled = false
        """,
        batch, java.sql.Date.valueOf(session), armed);
  }

  /**
   * Records the AUTHORITATIVE schedule-time arming — the settle's own reading, which OVERWRITES any
   * provisional observation for the session.
   *
   * <p>⚠️ The overwrite is the whole point, and its absence was a Critical (cross-vendor review,
   * 2026-08-10). With the intraday ticks writing {@code ON CONFLICT DO NOTHING}, the first 09:05
   * observation won permanently and the settle could never correct it. Both transition directions
   * then broke, and one of them is a money path:
   *
   * <ul>
   *   <li><b>armed at 09:05, DISARMED before the settle</b> — intent stayed {@code true} and the
   *       settle wrote no marker, so the catch-up replayed the session through the explicitly-armed
   *       historical overload. A deliberately disabled family would take entries.
   *   <li><b>disarmed at 09:05, ARMED before the settle</b> — intent stayed {@code false} and every
   *       entry for the session was forfeited despite the family being live.
   * </ul>
   *
   * <p>Extending the tick window would not have helped: once the row exists, every later tick is
   * ignored. The fix has to be that one writer is allowed to correct the others.
   */
  public void recordSettled(String batch, LocalDate session, boolean armed) {
    jdbc.update(
        """
        INSERT INTO swing_batch_schedule_intents (batch, session_date, armed, scheduled_at, settled)
        VALUES (?, ?, ?, now(), true)
        ON CONFLICT (batch, session_date) DO UPDATE
          SET armed = EXCLUDED.armed, scheduled_at = EXCLUDED.scheduled_at, settled = true
        """,
        batch, java.sql.Date.valueOf(session), armed);
  }

  /**
   * The arming AND whether it is authoritative — what the catch-up must ask before taking entries.
   *
   * <p>{@link #find} deliberately survives unchanged for the detector, which only cares whether the
   * family was armed, not who observed it.
   */
  public Optional<Intent> findIntent(String batch, LocalDate session) {
    return jdbc
        .query(
            "SELECT armed, settled FROM swing_batch_schedule_intents"
                + " WHERE batch = ? AND session_date = ?",
            (rs, n) -> new Intent(rs.getBoolean("armed"), rs.getBoolean("settled")),
            batch, java.sql.Date.valueOf(session))
        .stream()
        .findFirst();
  }

  /**
   * A schedule-intent row: the arming, plus whether the SETTLE wrote it (V061).
   *
   * <p>{@code settled = false} is an intraday observation made hours before the settle, kept only so
   * a container down at 16:00 still leaves the catch-up something to work with. It is NOT enough on
   * its own to justify entering — the flag can have moved since, and the settle's own write is
   * fail-soft, so its absence proves nothing either way.
   */
  public record Intent(boolean armed, boolean settled) {}

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
      int maxPages,
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
              latch.claimed_at < now() - make_interval(mins => ?)
              AND latch.pages < ?
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
        pageLeaseMinutes,
        maxPages);
  }
}
