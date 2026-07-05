package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code risk_settings} (typed JSONB rows, never YAML) and the append-only
 * {@code risk_audit} log. Toggling a limit here never touches a strategy version or a D18 checksum.
 */
@Repository
public class RiskSettingsRepository {

  /** One risk-limit row. */
  public record Setting(String key, JsonNode value, OffsetDateTime updatedAt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires the strategy datasource. */
  public RiskSettingsRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** All limit rows for a book. */
  public List<Setting> all(String book) {
    return jdbc.query(
        "SELECT key, value, updated_at FROM risk_settings WHERE book=? ORDER BY key", this::map, book);
  }

  /** One limit row by (book, key). */
  public Optional<Setting> get(String book, String key) {
    return jdbc
        .query(
            "SELECT key, value, updated_at FROM risk_settings WHERE book=? AND key=?", this::map, book, key)
        .stream()
        .findFirst();
  }

  /** Upserts a limit row for a book (the typed JSONB payload). */
  public void upsert(String book, String key, String valueJson) {
    jdbc.update(
        """
        INSERT INTO risk_settings (book, key, value, updated_at) VALUES (?, ?, ?::jsonb, now())
        ON CONFLICT (book, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
        """,
        book,
        key,
        valueJson);
  }

  /** Appends a risk trip / flip audit row for a book. */
  public void audit(String book, String key, String action, String detail) {
    jdbc.update(
        "INSERT INTO risk_audit (book, key, action, detail) VALUES (?,?,?,?)", book, key, action, detail);
  }

  /** Recent audit rows for a book (newest first). */
  public List<java.util.Map<String, Object>> auditTail(String book, int limit) {
    return jdbc.queryForList(
        "SELECT key, action, detail, created_at FROM risk_audit WHERE book=? ORDER BY created_at DESC LIMIT ?",
        book,
        Math.min(Math.max(limit, 1), 200));
  }

  private Setting map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    try {
      return new Setting(
          rs.getString("key"),
          objectMapper.readTree(rs.getString("value")),
          rs.getObject("updated_at", OffsetDateTime.class));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("risk_settings value is not valid JSON", e);
    }
  }
}
