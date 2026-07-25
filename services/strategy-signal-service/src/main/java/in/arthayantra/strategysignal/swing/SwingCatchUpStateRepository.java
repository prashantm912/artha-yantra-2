package in.arthayantra.strategysignal.swing;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code swing_catchup_runs} durable claim + attempt ledger (V047, 2026-07-17 incident). The
 * catch-up's money-effecting run must be gated by an ATOMIC claim taken BEFORE any emission: two
 * overlapping invocations (the 08:35 cron racing a manual {@code POST /run}, or a crash-restart)
 * would otherwise both read "not yet run", both emit an ENTRY for the same symbol, and the second
 * auto-paper AVERAGES a doubled fill into the first's position ({@code PaperService.upsertPosition} —
 * {@code uq_paper_positions_open} guards the ROW, never the qty).
 *
 * <p>{@link #claim} is that gate: a single {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE ...
 * RETURNING} that wins iff the session is un-attempted, previously PENDING (a retryable partial), or
 * a STALE-RUNNING claim from a crashed attempt. A fresh RUNNING claim, a DONE, or an ABANDONED row
 * loses — so at most one caller ever proceeds to emit for a given session at a time.
 */
@Repository
public class SwingCatchUpStateRepository {

  private final JdbcTemplate jdbc;

  /** Wires the JDBC template (its own autocommit — the claim must be visible before the run starts). */
  public SwingCatchUpStateRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The won claim, carrying the (post-increment) attempt number for the age-out decision. */
  public record Claim(int attempts) {}

  /**
   * Atomically claims a session for a catch-up attempt. Returns the {@link Claim} (with the new
   * attempt count) iff this caller won — i.e. there was no row, the row was PENDING (a retryable
   * partial), or it was a RUNNING claim staler than {@code staleLeaseMinutes} (a crashed attempt).
   * Empty when another caller holds a fresh RUNNING claim, or the session is terminal (DONE /
   * ABANDONED). The whole decision is one statement, so it is safe under concurrent callers.
   */
  public Optional<Claim> claim(String batch, LocalDate session, int staleLeaseMinutes) {
    return jdbc
        .query(
            """
            INSERT INTO swing_catchup_runs (batch, session_date, status, attempts, claimed_at, updated_at)
            VALUES (?, ?, 'RUNNING', 1, now(), now())
            ON CONFLICT (batch, session_date) DO UPDATE
              SET status = 'RUNNING',
                  attempts = swing_catchup_runs.attempts + 1,
                  claimed_at = now(),
                  updated_at = now()
              WHERE swing_catchup_runs.status = 'PENDING'
                 OR (swing_catchup_runs.status = 'RUNNING'
                     AND swing_catchup_runs.claimed_at < now() - make_interval(mins => ?))
            RETURNING attempts
            """,
            (rs, n) -> new Claim(rs.getInt("attempts")),
            batch,
            java.sql.Date.valueOf(session),
            staleLeaseMinutes)
        .stream()
        .findFirst();
  }

  /**
   * Records a terminal DISARMED marker for a session the family was intentionally OFF for — so a later
   * re-arm cannot replay it as a phantom outage (a disabled scheduled run writes NO {@code
   * swing_batch_runs} marker, so without this the missing marker is indistinguishable from an outage).
   * INSERT-if-absent: it never overwrites an existing claim/partial/terminal row (a session that was
   * genuinely worked stays as it was); {@link #claim} already excludes DISARMED (only PENDING or a
   * stale RUNNING wins), so a DISARMED session is never recovered.
   */
  public void recordDisarmed(String batch, LocalDate session) {
    jdbc.update(
        """
        INSERT INTO swing_catchup_runs (batch, session_date, status, attempts, updated_at)
        VALUES (?, ?, 'DISARMED', 0, now())
        ON CONFLICT (batch, session_date) DO NOTHING
        """,
        batch, java.sql.Date.valueOf(session));
  }

  /** Terminal success — the exit pass fully evaluated (V025 also carries the completeness marker). */
  public void markDone(String batch, LocalDate session) {
    setStatus(batch, session, "DONE");
  }

  /** Retryable partial — a held anchor's daily bar was missing; a later session may resolve it. */
  public void markPending(String batch, LocalDate session) {
    setStatus(batch, session, "PENDING");
  }

  /** Terminal give-up — the session exhausted its attempt budget without completing. */
  public void markAbandoned(String batch, LocalDate session) {
    setStatus(batch, session, "ABANDONED");
  }

  private void setStatus(String batch, LocalDate session, String status) {
    jdbc.update(
        "UPDATE swing_catchup_runs SET status = ?, updated_at = now() WHERE batch = ? AND session_date = ?",
        status, batch, java.sql.Date.valueOf(session));
  }
}
