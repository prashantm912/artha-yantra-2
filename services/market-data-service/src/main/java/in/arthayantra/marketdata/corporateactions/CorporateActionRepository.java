package in.arthayantra.marketdata.corporateactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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

  /** Lifecycle transition; terminal states stamp {@code resolved_at}. */
  public void updateStatus(UUID id, String status) {
    boolean terminal = "RESOLVED".equals(status) || "FAILED".equals(status);
    jdbc.update(
        "UPDATE corporate_action_events SET status = ?, resolved_at = "
            + (terminal ? "now()" : "NULL")
            + " WHERE id = ?",
        status,
        id);
  }

  /** Events for one symbol, newest first. */
  public List<EventRow> eventsFor(String exchange, String tradingsymbol) {
    return jdbc.query(
        """
        SELECT id, exchange, tradingsymbol, detected_at, effective_boundary, ratio,
               anchors_checked, anchors_diverged, status, resolved_at
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
                rs.getObject("resolved_at", OffsetDateTime.class)),
        exchange,
        tradingsymbol);
  }
}
