package in.arthayantra.strategysignal.swing;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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

  /** The latest durable catch-up row for one swing family. */
  public record LatestCatchUpRow(
      LocalDate sessionDate, String status, int attempts, String reason, OffsetDateTime updatedAt) {}

  /**
   * Atomically claims a session for a catch-up attempt. Returns the {@link Claim} (with the new
   * attempt count) iff this caller won — i.e. there was no row, the row was PENDING (a retryable
   * partial), or it was a RUNNING claim staler than {@code staleLeaseMinutes} (a crashed attempt).
   * Empty when another caller holds a fresh RUNNING claim, or the session is terminal (DONE /
   * ABANDONED). The whole decision is one statement, so it is safe under concurrent callers.
   *
   * <p>⚠️ {@code attempts} counts <b>IST DAYS attempted</b>, not claims. It always meant days — the
   * budget exists to stop retrying a session whose daily bar never arrives, its exhaustion alert
   * says "a held position's daily bar is still missing", and the sweep window is expressed in
   * {@code max-attempts + 2} trading DAYS. With one catch-up run per morning, "claims" was an exact
   * proxy for "days" and the distinction never surfaced.
   *
   * <p>It surfaces the moment the catch-up runs more than once a morning. At the default budget of
   * 5, six passes in one morning exhaust it and mark the session {@code ABANDONED} — whose alert
   * reads "its stop for that session is UNRECOVERABLE" — before the morning is even over, purely
   * because someone widened a cron. The {@code claimed_at} comparison below restores the original
   * meaning without a migration: a second claim on the same IST day is free, and behaviour for a
   * once-a-day caller is byte-identical to what it always was.
   *
   * <p>⚠️ {@code claimed_at IS NULL} counts as a new day, and that clause is the whole fix working
   * on real data. Production ALWAYS calls {@link #seedMissing} first, and a seeded row has
   * {@code attempts = 0} with {@code claimed_at} unset — so without it the comparison evaluates
   * {@code NULL < today}, which is NULL, not true. The first genuine claim would then return
   * attempt ZERO, and a five-day budget would quietly run for six. Cross-vendor review found this;
   * the original test claimed a row that did not exist yet, exercising the INSERT path, which is
   * the EXCEPTIONAL one — every real session goes through the conflict path instead.
   *
   * <p>Rendering uses {@code AT TIME ZONE 'Asia/Kolkata'}, never {@code '+05:30'} — the POSIX sign
   * convention makes the offset form INVERT, so it would compare a time 11 hours adrift.
   */
  public Optional<Claim> claim(String batch, LocalDate session, int staleLeaseMinutes) {
    return jdbc
        .query(
            """
            INSERT INTO swing_catchup_runs (batch, session_date, status, attempts, claimed_at, updated_at)
            VALUES (?, ?, 'RUNNING', 1, now(), now())
            ON CONFLICT (batch, session_date) DO UPDATE
              SET status = 'RUNNING',
                  attempts = swing_catchup_runs.attempts
                             + CASE WHEN swing_catchup_runs.claimed_at IS NULL
                                       OR (swing_catchup_runs.claimed_at
                                             AT TIME ZONE 'Asia/Kolkata')::date
                                           < (now() AT TIME ZONE 'Asia/Kolkata')::date
                                    THEN 1 ELSE 0 END,
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
   * Seeds every missing session before the catch-up takes its JVM mutex or starts funnel/candle work.
   * A COMPLETE run marker wins over a seed, and an existing PENDING/RUNNING/terminal row is never
   * overwritten.
   *
   * <p>⚠️ "Complete" means {@code entries_enabled} (V060), NOT merely "a row exists", and the
   * difference is the whole 16:00/08:35 split. The 16:00 exits-only settle writes a marker row for
   * every session — correctly, because it evaluated every held stop, which is what the 08:30 canary
   * and the 20:15 heartbeat ask about. A bare row-exists test here refuses to SEED that session, so
   * it never reaches {@code retryableSessions}, the catch-up never sees it, and the 08:35 entry pass
   * silently never happens — with the canary and heartbeat both green throughout.
   *
   * <p>Cross-vendor review caught this one layer above where I had fixed it: the skip check in
   * {@code SwingBatchCatchUp} was already asking {@code hasRunWithEntries}, but this SEED gate runs
   * first and never let the session get that far. Worse, the unit test masked it — its fake
   * retryable-state helper filtered on {@code hasRunWithEntries}, which is not what this SQL does.
   * That is why {@code SwingCatchUpStateRepositoryIntegrationTest} drives the real statement.
   */
  public void seedMissing(String batch, java.util.List<LocalDate> sessions) {
    for (LocalDate session : sessions) {
      jdbc.update(
          """
          INSERT INTO swing_catchup_runs (batch, session_date, status, attempts, updated_at)
          SELECT ?, ?, 'PENDING', 0, now()
          WHERE NOT EXISTS (
            SELECT 1 FROM swing_batch_runs
            WHERE batch=? AND run_date=? AND COALESCE(entries_enabled, true)
          )
          ON CONFLICT (batch, session_date) DO NOTHING
          """,
          batch,
          java.sql.Date.valueOf(session),
          batch,
          java.sql.Date.valueOf(session));
    }
  }

  /** Name used by the catch-up coordinator: seed the window before taking its JVM mutex. */
  public void seedWindow(String batch, java.util.List<LocalDate> sessions) {
    seedMissing(batch, sessions);
  }

  /** Seeds unresolved paper-effect sessions even when their canonical run marker was already written. */
  public void seedPending(String batch, java.util.List<LocalDate> sessions) {
    for (LocalDate session : sessions) {
      jdbc.update(
          "INSERT INTO swing_catchup_runs (batch, session_date, status, attempts, updated_at)"
              + " VALUES (?, ?, 'PENDING', 0, now()) ON CONFLICT (batch, session_date) DO NOTHING",
          batch,
          java.sql.Date.valueOf(session));
    }
  }

  /** Returns every retryable row, including rows older than the ordinary rolling session window. */
  public java.util.List<LocalDate> retryableSessions(String batch, int staleLeaseMinutes) {
    return jdbc.query(
        """
        SELECT session_date
        FROM swing_catchup_runs
        WHERE batch = ?
          AND (status = 'PENDING'
               OR (status = 'RUNNING'
                   AND claimed_at IS NOT NULL
                   AND claimed_at < now() - make_interval(mins => ?)))
        ORDER BY session_date
        """,
        (rs, n) -> rs.getObject("session_date", LocalDate.class), batch, staleLeaseMinutes);
  }

  /** Returns the most recently updated catch-up row for a family, if any. */
  public Optional<LatestCatchUpRow> latest(String batch) {
    return jdbc
        .query(
            """
            SELECT session_date, status, attempts, reason, updated_at
            FROM swing_catchup_runs
            WHERE batch = ?
            ORDER BY updated_at DESC, session_date DESC
            LIMIT 1
            """,
            (rs, n) ->
                new LatestCatchUpRow(
                    rs.getObject("session_date", LocalDate.class),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getString("reason"),
                    rs.getObject("updated_at", OffsetDateTime.class)),
            batch)
        .stream()
        .findFirst();
  }

  /**
   * Records a terminal DISARMED marker for a session the family was intentionally OFF for — so a later
   * re-arm cannot replay it as a phantom outage (a disabled scheduled run writes NO {@code
   * swing_batch_runs} marker, so without this the missing marker is indistinguishable from an outage).
   * It never overwrites a row some caller has actually engaged with (a session that was genuinely
   * worked stays as it was); {@link #claim} already excludes DISARMED (only PENDING or a stale RUNNING
   * wins), so a DISARMED session is never recovered.
   *
   * <p>⚠️ It must TAKE OVER an untouched seed placeholder, and a plain {@code ON CONFLICT DO NOTHING}
   * did not — which made this status UNREACHABLE for every session the sweep can actually reach.
   * {@code SwingBatchCatchUp.catchUp} calls {@code seedWindow} BEFORE {@code catchUpFamily}, and the
   * only sessions {@code catchUpFamily} iterates are the ones {@link #retryableSessions} returns — so
   * by the time the disarm branch runs, a PENDING placeholder for that session always exists and the
   * insert was silently swallowed, every time. Measured on the live ledger 2026-08-13: zero DISARMED
   * rows have ever been written. The row stayed PENDING, was re-scanned by every later sweep, and
   * would eventually be ABANDONED with {@code NO_SCHEDULE_INTENT} if its intent row ever aged out.
   */
  public void recordDisarmed(String batch, LocalDate session) {
    recordDisarmed(batch, session, "DISARMED_AT_SCHEDULE_TIME");
  }

  /**
   * Records the historical reason for an intentional schedule-time disarm.
   *
   * <p>The {@code WHERE} is what separates an untouched placeholder from a row a worker has engaged
   * with, and all three clauses are load-bearing:
   *
   * <ul>
   *   <li>{@code status = 'PENDING'} — {@link #markAbandoned} also creates rows with
   *       {@code attempts = 0} and {@code claimed_at} unset (two such live rows exist, both
   *       {@code NO_SCHEDULE_INTENT} for 2026-07-17), and a terminal row must never be relabelled.
   *   <li>{@code claimed_at IS NULL} — {@link #claim} sets it in the same atomic statement that flips
   *       the row to RUNNING, so this is exactly "no claim has ever succeeded here". It also makes the
   *       two writers serialize on the row: if the disarm lands first the claim's own {@code WHERE}
   *       fails and the caller simply does not run (correct — the settled intent says the family was
   *       off); if the claim lands first this {@code WHERE} fails and no live work is stomped.
   *   <li>{@code attempts = 0} — implied by the clause above (attempts only moves inside {@link
   *       #claim}), kept as a cheap independent witness that nothing has worked this row.
   * </ul>
   *
   * <p>Deliberately NOT covered: a STALE RUNNING row, which {@link #retryableSessions} also returns.
   * It keeps its status and stays retryable rather than being stomped mid-flight. That combination is
   * unreachable today — the 16:00 settle writes the SETTLED intent this branch reads before the 08:35
   * sweep first sees the session, so a session that reaches here has never been claimed — and
   * widening the predicate to cover it would trade a bookkeeping gap for the risk of terminating a
   * live claim.
   */
  public void recordDisarmed(String batch, LocalDate session, String reason) {
    jdbc.update(
        """
        INSERT INTO swing_catchup_runs (batch, session_date, status, attempts, reason, updated_at)
        VALUES (?, ?, 'DISARMED', 0, ?, now())
        ON CONFLICT (batch, session_date) DO UPDATE
          SET status = 'DISARMED',
              reason = COALESCE(swing_catchup_runs.reason, EXCLUDED.reason),
              updated_at = now()
          WHERE swing_catchup_runs.status = 'PENDING'
            AND swing_catchup_runs.attempts = 0
            AND swing_catchup_runs.claimed_at IS NULL
        """,
        batch, java.sql.Date.valueOf(session), reason);
  }

  /** Terminal success — the exit pass fully evaluated (V025 also carries the completeness marker). */
  public void markDone(String batch, LocalDate session) {
    setStatus(batch, session, "DONE");
  }

  /** Retryable partial — a held anchor's daily bar was missing; a later session may resolve it. */
  public void markPending(String batch, LocalDate session) {
    jdbc.update(
        "UPDATE swing_catchup_runs SET status = 'PENDING', updated_at = now()"
            + " WHERE batch = ? AND session_date = ?"
            + " AND status IN ('RUNNING', 'PENDING')",
        batch,
        java.sql.Date.valueOf(session));
  }

  /** Retryable partial with a durable, structured reason that survives later retries. */
  public void markPending(String batch, LocalDate session, String reason) {
    jdbc.update(
        "UPDATE swing_catchup_runs SET status = 'PENDING', reason = COALESCE(?, reason),"
            + " updated_at = now() WHERE batch = ? AND session_date = ?"
            + " AND status IN ('RUNNING', 'PENDING')",
        reason,
        batch,
        java.sql.Date.valueOf(session));
  }

  /** Terminal give-up — the session exhausted its attempt budget without completing. */
  public void markAbandoned(String batch, LocalDate session) {
    markAbandoned(batch, session, "ATTEMPT_BUDGET_EXHAUSTED");
  }

  /** Records a terminal refusal/abandonment, creating the ledger row when no claim exists yet. */
  public void markAbandoned(String batch, LocalDate session, String reason) {
    jdbc.update(
        """
        INSERT INTO swing_catchup_runs (batch, session_date, status, attempts, reason, updated_at)
        VALUES (?, ?, 'ABANDONED', 0, ?, now())
        ON CONFLICT (batch, session_date) DO UPDATE
          SET status = 'ABANDONED', reason = COALESCE(swing_catchup_runs.reason, EXCLUDED.reason),
              updated_at = now()
          WHERE swing_catchup_runs.status NOT IN ('DONE', 'ABANDONED', 'DISARMED')
        """,
        batch, java.sql.Date.valueOf(session), reason);
  }

  private void setStatus(String batch, LocalDate session, String status) {
    jdbc.update(
        "UPDATE swing_catchup_runs SET status = ?, updated_at = now()"
            + " WHERE batch = ? AND session_date = ?"
            + " AND status IN ('RUNNING', 'PENDING')",
        status, batch, java.sql.Date.valueOf(session));
  }
}
