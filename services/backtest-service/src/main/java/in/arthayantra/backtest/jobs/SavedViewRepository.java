package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists owner-scoped named filter sets without interpreting their JSON payload. */
@Repository
public class SavedViewRepository {

  private static final String COLUMNS = "id, kind, name, filter, created_at";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires JDBC and Jackson. */
  public SavedViewRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Creates one named view and returns the database-assigned id and timestamp. */
  public SavedView create(String owner, String kind, String name, JsonNode filter) {
    return jdbc.query(
            connection -> {
              var statement =
                  connection.prepareStatement(
                      "INSERT INTO saved_views (owner, kind, name, filter) "
                          + "VALUES (?, ?, ?, ?::jsonb) RETURNING "
                          + COLUMNS);
              statement.setString(1, owner);
              statement.setString(2, kind);
              statement.setString(3, name);
              statement.setString(4, filter.toString());
              return statement;
            },
            this::mapRow)
        .get(0);
  }

  /** Lists one owner's views for one surface, newest first. */
  public List<SavedView> list(String owner, String kind) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM saved_views WHERE owner=? AND kind=? ORDER BY created_at DESC, name",
        this::mapRow,
        owner,
        kind);
  }

  /** Deletes only when both the id and owner match; an unknown id is an idempotent no-op. */
  public void delete(String owner, UUID id) {
    jdbc.update("DELETE FROM saved_views WHERE owner=? AND id=?", owner, id);
  }

  private SavedView mapRow(ResultSet rs, int rowNum) throws SQLException {
    JsonNode filter;
    try {
      filter = objectMapper.readTree(rs.getString("filter"));
    } catch (JsonProcessingException e) {
      throw new SQLException("corrupt saved-view filter JSONB on " + rs.getString("id"), e);
    }
    return new SavedView(
        rs.getString("id"),
        rs.getString("kind"),
        rs.getString("name"),
        filter,
        offset(rs.getTimestamp("created_at")));
  }

  private static OffsetDateTime offset(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
  }
}
