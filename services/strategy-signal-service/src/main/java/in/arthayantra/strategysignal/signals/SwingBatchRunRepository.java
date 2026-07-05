package in.arthayantra.strategysignal.signals;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code swing_batch_runs} dead-man marker (V025, audit P0-4/H10): each swing batch records
 * one row per IST run date; {@code SwingBatchCanary} reads the watermark next morning and alerts
 * when an armed batch has no row for the last NSE trading day.
 */
@Repository
public class SwingBatchRunRepository {

  private final JdbcTemplate jdbc;

  /** Wires the JDBC template. */
  public SwingBatchRunRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Upserts the marker for one batch run (re-runs on the same date re-stamp the counters). */
  public void record(
      String batch, LocalDate runDate, int strategies, int candidates, int entries, int exits,
      int exitSkipped) {
    jdbc.update(
        """
        INSERT INTO swing_batch_runs
            (batch, run_date, ran_at, strategies, candidates, entries, exits, exit_skipped)
        VALUES (?, ?, now(), ?, ?, ?, ?, ?)
        ON CONFLICT (batch, run_date) DO UPDATE SET
            ran_at = now(), strategies = EXCLUDED.strategies, candidates = EXCLUDED.candidates,
            entries = EXCLUDED.entries, exits = EXCLUDED.exits,
            exit_skipped = EXCLUDED.exit_skipped
        """,
        batch, java.sql.Date.valueOf(runDate), strategies, candidates, entries, exits, exitSkipped);
  }

  /** The latest recorded run date for a batch — empty when the batch has never recorded. */
  public Optional<LocalDate> lastRunDate(String batch) {
    return Optional.ofNullable(
        jdbc.queryForObject(
            "SELECT max(run_date) FROM swing_batch_runs WHERE batch = ?", LocalDate.class, batch));
  }
}
