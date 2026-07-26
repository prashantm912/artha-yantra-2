package in.arthayantra.strategysignal.swing;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code swing_missed_batch_alerts} page-rate lease (V047, 2026-07-17 incident).
 *
 * <p>This is a rate limiter, NOT a terminal "already paged" latch. {@link #claim} succeeds the first
 * time a session is seen and again once the previous claim's lease has expired, so a session that
 * stays missing pages once per sweep until it finally gets a {@code swing_batch_runs} marker.
 *
 * <p>A one-shot latch would be unsafe here: the detector publishes to an {@code @Async} listener
 * that swallows every exception, so "we alerted" can only ever mean "we enqueued". An ntfy 5xx, a
 * saturated notifier queue, or an unconfigured channel would then bury the single page forever —
 * the same shape as the 2026-07-20 outage that went unnoticed because every call site logged and
 * moved on. Re-paging is the safe direction.
 */
@Repository
public class SwingMissedBatchAlertRepository {

  private final JdbcTemplate jdbc;

  /** Wires the JDBC template (its own autocommit makes the claim visible before alert delivery). */
  public SwingMissedBatchAlertRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The won claim, carrying how many sweeps have now paged this session. */
  public record Claim(int pages) {}

  /**
   * Atomically claims the right to page this session, returning empty when a claim taken inside the
   * lease window already exists. Concurrent callers cannot both win — the conflicting UPDATE takes
   * the row lock and its {@code WHERE} re-reads the just-written {@code claimed_at}.
   */
  public Optional<Claim> claim(String batch, LocalDate session, int leaseMinutes) {
    return jdbc
        .query(
            """
            INSERT INTO swing_missed_batch_alerts (batch, session_date, pages, claimed_at)
            VALUES (?, ?, 1, now())
            ON CONFLICT (batch, session_date) DO UPDATE
              SET pages = swing_missed_batch_alerts.pages + 1,
                  claimed_at = now()
              WHERE swing_missed_batch_alerts.claimed_at < now() - make_interval(mins => ?)
            RETURNING pages
            """,
            (rs, n) -> new Claim(rs.getInt("pages")),
            batch,
            java.sql.Date.valueOf(session),
            leaseMinutes)
        .stream()
        .findFirst();
  }
}
