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

  /** All limit rows. */
  public List<Setting> all() {
    return jdbc.query("SELECT key, value, updated_at FROM risk_settings ORDER BY key", this::map);
  }

  /** One limit row by key. */
  public Optional<Setting> get(String key) {
    return jdbc.query("SELECT key, value, updated_at FROM risk_settings WHERE key=?", this::map, key).stream()
        .findFirst();
  }

  /** Upserts a limit row (the typed JSONB payload). */
  public void upsert(String key, String valueJson) {
    jdbc.update(
        """
        INSERT INTO risk_settings (key, value, updated_at) VALUES (?, ?::jsonb, now())
        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
        """,
        key,
        valueJson);
  }

  /** Appends a risk trip / flip audit row. */
  public void audit(String key, String action, String detail) {
    jdbc.update("INSERT INTO risk_audit (key, action, detail) VALUES (?,?,?)", key, action, detail);
  }

  /** Recent audit rows (newest first). */
  public List<java.util.Map<String, Object>> auditTail(int limit) {
    return jdbc.queryForList(
        "SELECT key, action, detail, created_at FROM risk_audit ORDER BY created_at DESC LIMIT ?",
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
