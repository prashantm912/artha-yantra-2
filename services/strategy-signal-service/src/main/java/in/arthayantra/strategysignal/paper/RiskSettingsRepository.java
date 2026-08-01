package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
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

  /**
   * One append-only {@code risk_audit} row (D3 Map-return burn-down — this replaced the
   * {@code jdbc.queryForList} column map that {@link #auditTail} used to return).
   *
   * <p>⚠️ {@code created_at} keeps its SNAKE_CASE wire name deliberately. The old value was a raw
   * {@code queryForList} map whose keys are the SQL COLUMN LABELS, not Jackson property names, so
   * this key has always been {@code created_at} on the wire while its sibling {@code
   * Setting.updatedAt} (a real record component) has always been camelCase. Renaming it here to
   * match would be a silent wire break, so the {@code @JsonProperty} pins it.
   *
   * <p>{@code detail} is the one nullable column (V006: {@code detail TEXT}, no NOT NULL); {@code
   * key}/{@code action}/{@code created_at} are all NOT NULL there.
   */
  public record AuditEntry(
      String key,
      String action,
      @Schema(types = {"string", "null"}) String detail,
      @JsonProperty("created_at") OffsetDateTime createdAt) {}

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

  /**
   * Inserts a limit row ONLY when absent — never clobbers a concurrent owner write. The boot
   * seeder's atomic insert-if-missing primitive (a check-then-{@link #upsert} would DO UPDATE on
   * conflict and silently revert an owner value written in the race window).
   */
  public void insertIfMissing(String book, String key, String valueJson) {
    jdbc.update(
        """
        INSERT INTO risk_settings (book, key, value, updated_at) VALUES (?, ?, ?::jsonb, now())
        ON CONFLICT (book, key) DO NOTHING
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
  public List<AuditEntry> auditTail(String book, int limit) {
    return jdbc.query(
        "SELECT key, action, detail, created_at FROM risk_audit WHERE book=? ORDER BY created_at DESC LIMIT ?",
        (rs, n) ->
            new AuditEntry(
                rs.getString("key"),
                rs.getString("action"),
                rs.getString("detail"),
                rs.getObject("created_at", OffsetDateTime.class)),
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
