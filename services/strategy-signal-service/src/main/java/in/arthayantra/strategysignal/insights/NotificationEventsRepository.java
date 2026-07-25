package in.arthayantra.strategysignal.insights;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the notifier's {@code notification_events} delivery-audit ledger (INT design
 * §13 row 15 — "notification_events gets its read endpoint as part of I1"). A plain SQL read of the
 * existing table (no {@code notifier} Java import); it surfaces the V033 additions ({@code insight_id}
 * + nullable {@code strategy_id}) so insight-scoped pushes are auditable once delivery arms in I3/I4.
 */
@Repository
public class NotificationEventsRepository {

  /** One delivery-audit row. */
  public record NotificationEventRow(
      long id,
      @Schema(types = {"integer", "null"}) Long signalId,
      @Schema(types = {"string", "null"}) UUID strategyId,
      @Schema(types = {"string", "null"}) UUID insightId,
      String channel,
      String status,
      int attempts,
      @Schema(types = {"string", "null"}) String detail,
      OffsetDateTime createdAt) {}

  private final JdbcTemplate jdbc;

  public NotificationEventsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Recent delivery-audit rows, newest first. */
  public List<NotificationEventRow> recent(int limit) {
    return jdbc.query(
        """
        SELECT id, signal_id, strategy_id, insight_id, channel, status, attempts, detail, created_at
        FROM notification_events
        ORDER BY created_at DESC, id DESC
        LIMIT ?
        """,
        NotificationEventsRepository::map,
        limit);
  }

  private static NotificationEventRow map(ResultSet rs, int rowNum) throws SQLException {
    return new NotificationEventRow(
        rs.getLong("id"),
        (Long) rs.getObject("signal_id"),
        rs.getObject("strategy_id", UUID.class),
        rs.getObject("insight_id", UUID.class),
        rs.getString("channel"),
        rs.getString("status"),
        rs.getInt("attempts"),
        rs.getString("detail"),
        rs.getObject("created_at", OffsetDateTime.class));
  }
}
