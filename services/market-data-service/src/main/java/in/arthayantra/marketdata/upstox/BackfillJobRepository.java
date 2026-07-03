package in.arthayantra.marketdata.upstox;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC for the {@code backfill_jobs} run-audit ledger (V030): one row per backfill RUN, transitioned
 * {@code RUNNING → COMPLETED | FAILED} by {@link ExpiredBackfillService} / {@link EquityDailyBackfillService}.
 * Coverage ({@link ExpiredBackfillRepository}) records what data EXISTS; this records what RUNS happened
 * (kind, params, status, rows, error, timings) so the Data-Ops console can show a history. The service
 * calls here are wrapped fail-soft (a logging-DB failure is swallowed with a warn), so this is a
 * best-effort audit that must never break the backfill it records.
 */
@Repository
public class BackfillJobRepository {

  /** One audited backfill run — newest-first for the {@code GET /backfill-jobs} history read. */
  public record BackfillJobRow(
      long id,
      String kind,
      String params,
      String status,
      Long rowsWritten,
      String error,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {}

  /** Errors are stored verbatim but bounded — a stack-trace-laden message must not bloat the row. */
  private static final int MAX_ERROR_LEN = 2000;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public BackfillJobRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a RUNNING row for a starting backfill and returns its generated id. */
  public long start(String kind, String paramsJson) {
    return jdbc.queryForObject(
        "INSERT INTO backfill_jobs (kind, params, status) VALUES (?, ?::jsonb, 'RUNNING') RETURNING id",
        Long.class,
        kind,
        paramsJson);
  }

  /** Marks a run COMPLETED with its final row tally + finish time. */
  public void complete(long id, long rowsWritten) {
    jdbc.update(
        "UPDATE backfill_jobs SET status = 'COMPLETED', rows_written = ?, finished_at = now() WHERE id = ?",
        rowsWritten,
        id);
  }

  /** Marks a run FAILED with a (bounded) error message + finish time. */
  public void fail(long id, String error) {
    jdbc.update(
        "UPDATE backfill_jobs SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ?",
        truncate(error),
        id);
  }

  /** The most recent {@code limit} runs, newest first. */
  public List<BackfillJobRow> recent(int limit) {
    return jdbc.query(
        "SELECT id, kind, params::text AS params, status, rows_written, error, started_at, finished_at "
            + "FROM backfill_jobs ORDER BY started_at DESC LIMIT ?",
        (rs, n) -> {
          long rows = rs.getLong("rows_written");
          Long rowsWritten = rs.wasNull() ? null : rows; // wasNull() reflects the column just read
          return new BackfillJobRow(
              rs.getLong("id"),
              rs.getString("kind"),
              rs.getString("params"),
              rs.getString("status"),
              rowsWritten,
              rs.getString("error"),
              rs.getObject("started_at", OffsetDateTime.class),
              rs.getObject("finished_at", OffsetDateTime.class));
        },
        limit);
  }

  private static String truncate(String error) {
    if (error == null) {
      return null;
    }
    return error.length() <= MAX_ERROR_LEN ? error : error.substring(0, MAX_ERROR_LEN);
  }
}
