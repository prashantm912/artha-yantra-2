package in.arthayantra.marketdata.corporateactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code corporate_action_events} (B-17 audit rows). */
@Repository
public class CorporateActionRepository {

  /** One event row (read view). */
  public record EventRow(
      UUID id,
      String exchange,
      String tradingsymbol,
      OffsetDateTime detectedAt,
      LocalDate effectiveBoundary,
      BigDecimal ratio,
      int anchorsChecked,
      int anchorsDiverged,
      String status,
      int refreshAttempts,
      OffsetDateTime resolvedAt) {}

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public CorporateActionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a DETECTED row; returns its id. */
  public UUID insertDetected(
      String exchange,
      String tradingsymbol,
      LocalDate effectiveBoundary,
      BigDecimal ratio,
      int anchorsChecked,
      int anchorsDiverged,
      String detailsJson) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO corporate_action_events
          (id, exchange, tradingsymbol, effective_boundary, ratio, anchors_checked,
           anchors_diverged, details, status)
        VALUES (?,?,?,?,?,?,?,?::jsonb,'DETECTED')
        """,
        id,
        exchange,
        tradingsymbol,
        effectiveBoundary == null ? null : java.sql.Date.valueOf(effectiveBoundary),
        ratio,
        anchorsChecked,
        anchorsDiverged,
        detailsJson);
    return id;
  }

  /**
   * Lifecycle transition; terminal states stamp {@code resolved_at}. {@code REFRESH_ABANDONED} is
   * terminal (the retry bound is spent — an operator, not the job, moves it next);
   * {@code REFRESH_FAILED} is NOT, because the next sweep resumes it (V051).
   */
  public void updateStatus(UUID id, String status) {
    boolean terminal = isTerminal(status);
    jdbc.update(
        "UPDATE corporate_action_events SET status = ?, resolved_at = "
            + (terminal ? "now()" : "NULL")
            + " WHERE id = ?",
        status,
        id);
  }

  /**
   * Compare-and-set transition: writes {@code next} only while the row is STILL at
   * {@code expected}, and reports whether it moved. The async remediation worker and the sweep
   * thread both write this row, and the worker's decision is made from a status it read seconds or
   * minutes earlier — so an unconditional write lets a finishing worker RESURRECT a terminal state
   * the sweep wrote in the meantime (a {@code REFRESH_ABANDONED} row silently becoming
   * {@code RESOLVED}, or worse {@code FAILED}). Every write that lands on a terminal state, or that
   * a concurrent sweep could race, goes through here (task_6903cd5e).
   */
  public boolean updateStatusIf(UUID id, String expected, String next) {
    boolean terminal = isTerminal(next);
    int rows =
        jdbc.update(
            "UPDATE corporate_action_events SET status = ?, resolved_at = "
                + (terminal ? "now()" : "NULL")
                + " WHERE id = ? AND status = ?",
            next,
            id,
            expected);
    return rows > 0;
  }

  /** Terminal states stamp {@code resolved_at}; {@code REFRESH_FAILED} is explicitly NOT one. */
  private static boolean isTerminal(String status) {
    return "RESOLVED".equals(status)
        || "FAILED".equals(status)
        || "REFRESH_ABANDONED".equals(status);
  }

  /**
   * The RECORDED status of one event. A failing remediation reads its own phase back from the row
   * rather than trusting the in-flight lambda: {@code status} is one overwritten column, so whether
   * a run committed its base is only knowable from what was actually written (V051).
   */
  public Optional<String> statusOf(UUID id) {
    return jdbc
        .query(
            "SELECT status FROM corporate_action_events WHERE id = ?",
            (rs, n) -> rs.getString("status"),
            id)
        .stream()
        .findFirst();
  }

  /**
   * Counts one cagg-refresh ATTEMPT against the event. Recorded before the attempt runs, so the
   * retry bound holds even when a run dies without unwinding (the OOM kill this checkpoint exists
   * for) — an in-memory counter would reset on every restart, and the sweep is daily (V051).
   */
  public void incrementRefreshAttempts(UUID id) {
    jdbc.update(
        "UPDATE corporate_action_events SET refresh_attempts = refresh_attempts + 1 WHERE id = ?",
        id);
  }

  /** Events for one symbol, newest first. */
  public List<EventRow> eventsFor(String exchange, String tradingsymbol) {
    return jdbc.query(
        """
        SELECT id, exchange, tradingsymbol, detected_at, effective_boundary, ratio,
               anchors_checked, anchors_diverged, status, refresh_attempts, resolved_at
        FROM corporate_action_events
        WHERE exchange = ? AND tradingsymbol = ? ORDER BY detected_at DESC
        """,
        (rs, n) ->
            new EventRow(
                rs.getObject("id", UUID.class),
                rs.getString("exchange"),
                rs.getString("tradingsymbol"),
                rs.getObject("detected_at", OffsetDateTime.class),
                rs.getDate("effective_boundary") == null
                    ? null
                    : rs.getDate("effective_boundary").toLocalDate(),
                rs.getBigDecimal("ratio"),
                rs.getInt("anchors_checked"),
                rs.getInt("anchors_diverged"),
                rs.getString("status"),
                rs.getInt("refresh_attempts"),
                rs.getObject("resolved_at", OffsetDateTime.class)),
        exchange,
        tradingsymbol);
  }

  /**
   * The MOST-RECENT event for a symbol, or empty. The A14 resume checkpoint reads its status: a
   * {@code BASE_REBUILT} (crashed mid-refresh) or {@code REFRESH_FAILED} (errored mid-refresh)
   * latest event means a prior remediation re-fetched the base but never finished the cagg refresh
   * → resume the refresh only, never re-purge. Any other status is NOT resumable.
   */
  public Optional<EventRow> latestEvent(String exchange, String tradingsymbol) {
    List<EventRow> rows = eventsFor(exchange, tradingsymbol);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }
}
