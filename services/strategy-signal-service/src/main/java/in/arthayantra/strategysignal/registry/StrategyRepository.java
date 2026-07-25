package in.arthayantra.strategysignal.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the registry tables. Version IMMUTABILITY is structural here: the only UPDATE
 * on {@code strategy_versions} ever issued touches {@code status}/{@code published_at} (D18);
 * the audit log only ever sees INSERT (and the grant enforces it).
 */
@Repository
public class StrategyRepository {

  /** strategies row. */
  public record StrategyRow(
      UUID id,
      String slug,
      String name,
      String description,
      String author,
      List<String> tags,
      boolean enabled,
      UUID publishedVersionId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      boolean notificationsEnabled,
      String notificationChannel) {}

  /** One strategy_audit_log row (the lifecycle-history read; append-only by grant). */
  public record AuditRow(
      long id,
      String action,
      @Schema(types = {"string", "null"}) String fromVersion,
      @Schema(types = {"string", "null"}) String toVersion,
      @Schema(types = {"string", "null"}) String diffSummary,
      String actor,
      OffsetDateTime createdAt) {}

  /** strategy_versions row. */
  public record VersionRow(
      UUID id,
      UUID strategyId,
      String version,
      String configYaml,
      JsonNode config,
      String schemaVersion,
      String checksum,
      String status,
      String notes,
      String createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime publishedAt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires the strategy-schema datasource. */
  public StrategyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Inserts the identity row. */
  public UUID insertStrategy(
      String slug, String name, String description, String author, List<String> tags) {
    return jdbc.queryForObject(
        """
        INSERT INTO strategies (slug, name, description, author, tags)
        VALUES (?, ?, ?, ?, ?) RETURNING id
        """,
        UUID.class,
        slug, name, description, author, tags.toArray(new String[0]));
  }

  /** Inserts an immutable version row. */
  public UUID insertVersion(
      UUID strategyId,
      String version,
      String configYaml,
      String canonicalJson,
      String schemaVersion,
      String checksum,
      String status,
      String notes,
      String createdBy) {
    return jdbc.queryForObject(
        """
        INSERT INTO strategy_versions
          (strategy_id, version, config_yaml, config, schema_version, checksum, status, notes, created_by)
        VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING id
        """,
        UUID.class,
        strategyId, version, configYaml, canonicalJson, schemaVersion, checksum, status, notes,
        createdBy);
  }

  /** Appends one audit row (INSERT-only by grant). */
  public void audit(
      UUID strategyId, String action, String fromVersion, String toVersion, String diffSummary,
      String actor) {
    jdbc.update(
        """
        INSERT INTO strategy_audit_log (strategy_id, action, from_version, to_version, diff_summary, actor)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        strategyId, action, fromVersion, toVersion, diffSummary, actor);
  }

  /** Strategy by id. */
  public Optional<StrategyRow> findById(UUID id) {
    return jdbc.query("SELECT * FROM strategies WHERE id = ?", this::strategyRow, id).stream()
        .findFirst();
  }

  /** Strategy by YAML slug. */
  public Optional<StrategyRow> findBySlug(String slug) {
    return jdbc.query("SELECT * FROM strategies WHERE slug = ?", this::strategyRow, slug).stream()
        .findFirst();
  }

  /** True when a different strategy already owns this name. */
  public boolean nameTaken(String name) {
    Integer count =
        jdbc.queryForObject("SELECT count(*) FROM strategies WHERE name = ?", Integer.class, name);
    return count != null && count > 0;
  }

  /**
   * Every strategy, newest-updated first. The service applies derived-status/tag/text filters and
   * THEN paginates, so filtering happens before limit/offset (a single-owner registry is small;
   * the cap is a defensive backstop, not a real page size).
   */
  public List<StrategyRow> listAll() {
    return jdbc.query(
        "SELECT * FROM strategies ORDER BY updated_at DESC LIMIT 10000", this::strategyRow);
  }

  /** Number of enabled strategies whose published version is still marked published. */
  public long countEnabledPublished() {
    Long count =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM strategies s
            JOIN strategy_versions v ON v.id = s.published_version_id
            WHERE s.enabled = TRUE AND v.status = 'published'
            """,
            Long.class);
    return count == null ? 0L : count;
  }

  /** All versions of a strategy, newest first. */
  public List<VersionRow> versions(UUID strategyId, int limit, int offset) {
    return jdbc.query(
        """
        SELECT * FROM strategy_versions WHERE strategy_id = ?
        ORDER BY created_at DESC LIMIT ? OFFSET ?
        """,
        this::versionRow,
        strategyId, limit, offset);
  }

  /** A specific version row. */
  public Optional<VersionRow> findVersion(UUID strategyId, String version) {
    return jdbc.query(
            "SELECT * FROM strategy_versions WHERE strategy_id = ? AND version = ?",
            this::versionRow,
            strategyId, version)
        .stream()
        .findFirst();
  }

  /** Version row by primary key. */
  public Optional<VersionRow> findVersionById(UUID versionId) {
    return jdbc.query(
            "SELECT * FROM strategy_versions WHERE id = ?", this::versionRow, versionId)
        .stream()
        .findFirst();
  }

  /** The newest version row (any status). */
  public Optional<VersionRow> latestVersion(UUID strategyId) {
    return jdbc.query(
            """
            SELECT * FROM strategy_versions WHERE strategy_id = ?
            ORDER BY created_at DESC LIMIT 1
            """,
            this::versionRow,
            strategyId)
        .stream()
        .findFirst();
  }

  /** The newest DRAFT version row. */
  public Optional<VersionRow> latestDraft(UUID strategyId) {
    return jdbc.query(
            """
            SELECT * FROM strategy_versions WHERE strategy_id = ? AND status = 'draft'
            ORDER BY created_at DESC LIMIT 1
            """,
            this::versionRow,
            strategyId)
        .stream()
        .findFirst();
  }

  /** True when every version of the strategy is a draft (hard-delete precondition). */
  public boolean allVersionsAreDrafts(UUID strategyId) {
    Integer nonDrafts =
        jdbc.queryForObject(
            "SELECT count(*) FROM strategy_versions WHERE strategy_id = ? AND status <> 'draft'",
            Integer.class,
            strategyId);
    return nonDrafts != null && nonDrafts == 0;
  }

  /** THE only version mutation in the system: status (+ published_at on publish). */
  public void updateVersionStatus(UUID versionId, String status, boolean stampPublishedAt) {
    if (stampPublishedAt) {
      jdbc.update(
          "UPDATE strategy_versions SET status = ?, published_at = now() WHERE id = ?",
          status, versionId);
    } else {
      jdbc.update("UPDATE strategy_versions SET status = ? WHERE id = ?", status, versionId);
    }
  }

  /** Updates the published-version pointer (+ updated_at). */
  public void setPublishedVersion(UUID strategyId, UUID versionId) {
    jdbc.update(
        "UPDATE strategies SET published_version_id = ?, updated_at = now() WHERE id = ?",
        versionId, strategyId);
  }

  /**
   * Compare-and-set the published-version pointer: move it to {@code versionId} ONLY while the
   * strategy's current {@code published_version_id} still equals {@code expectedVersionId}
   * ({@code IS NOT DISTINCT FROM} so a null-expected first-publish matches). Returns the rows
   * affected — 1 when this caller won the CAS, 0 when the pointer moved under it (a concurrent
   * promoter won). PF-01 round-5 #1: the {@code UPDATE ... WHERE} row-locks the strategy row, so
   * exactly one of two concurrent publishers moves the LIVE pointer; the loser sees 0.
   */
  public int casPublishedVersion(UUID strategyId, UUID versionId, UUID expectedVersionId) {
    return jdbc.update(
        "UPDATE strategies SET published_version_id = ?, updated_at = now() "
            + "WHERE id = ? AND published_version_id IS NOT DISTINCT FROM ?",
        versionId, strategyId, expectedVersionId);
  }

  /** Touches updated_at (draft writes). */
  public void touch(UUID strategyId) {
    jdbc.update("UPDATE strategies SET updated_at = now() WHERE id = ?", strategyId);
  }

  /**
   * Flips the {@code enabled} master kill-switch on the strategy IDENTITY row (app-platform audit
   * §2.7). Orthogonal to the versioned config — it never touches strategy_versions, so no D18
   * checksum changes. The live engine's reconcile filters on {@code enabled && published_version_id}
   * (SignalEngine), so this is what arms/disarms live evaluation.
   */
  public void updateEnabled(UUID strategyId, boolean enabled) {
    jdbc.update(
        "UPDATE strategies SET enabled = ?, updated_at = now() WHERE id = ?", enabled, strategyId);
  }

  /**
   * Toggles notification opt-in on the strategy IDENTITY row (Phase 41). This is orthogonal to the
   * versioned config — it never touches strategy_versions, so no D18 checksum changes.
   */
  public void updateNotifications(UUID strategyId, boolean enabled, String channel) {
    jdbc.update(
        "UPDATE strategies SET notifications_enabled = ?, notification_channel = ?, updated_at = now() "
            + "WHERE id = ?",
        enabled, channel, strategyId);
  }

  /**
   * Re-set the strategy IDENTITY-row tags (the §12.3 scalper-gate arming the engine reads via
   * {@code strategy.tags()}). Like {@link #updateNotifications} this is orthogonal to the versioned
   * config — it never touches strategy_versions, so no D18 checksum changes. The {@code text[]} array
   * is bound via an explicit setter to avoid the JdbcTemplate vararg-array spreading ambiguity.
   */
  public void updateTags(UUID strategyId, List<String> tags) {
    jdbc.update(
        "UPDATE strategies SET tags = ?, updated_at = now() WHERE id = ?",
        ps -> {
          ps.setArray(1, ps.getConnection().createArrayOf("text", tags.toArray()));
          ps.setObject(2, strategyId);
        });
  }

  /** Hard delete (drafts-only path; cascades versions + audit). */
  public void deleteStrategy(UUID strategyId) {
    jdbc.update("DELETE FROM strategies WHERE id = ?", strategyId);
  }

  /** Audit rows, newest first (IT assertions + the lifecycle history view). */
  public List<String> auditActions(UUID strategyId) {
    return jdbc.queryForList(
        """
        SELECT action FROM strategy_audit_log WHERE strategy_id = ?
        ORDER BY created_at ASC, id ASC
        """,
        String.class,
        strategyId);
  }

  /** The full lifecycle-history timeline, newest first (GET /{id}/audit). */
  public List<AuditRow> auditLog(UUID strategyId, int limit, int offset) {
    return jdbc.query(
        """
        SELECT id, action, from_version, to_version, diff_summary, actor, created_at
        FROM strategy_audit_log WHERE strategy_id = ?
        ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
        """,
        (rs, rowNum) ->
            new AuditRow(
                rs.getLong("id"),
                rs.getString("action"),
                rs.getString("from_version"),
                rs.getString("to_version"),
                rs.getString("diff_summary"),
                rs.getString("actor"),
                rs.getObject("created_at", OffsetDateTime.class)),
        strategyId, limit, offset);
  }

  private StrategyRow strategyRow(ResultSet rs, int rowNum) throws SQLException {
    String[] tags = (String[]) rs.getArray("tags").getArray();
    return new StrategyRow(
        rs.getObject("id", UUID.class),
        rs.getString("slug"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("author"),
        Arrays.asList(tags),
        rs.getBoolean("enabled"),
        rs.getObject("published_version_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class),
        rs.getBoolean("notifications_enabled"),
        rs.getString("notification_channel"));
  }

  private VersionRow versionRow(ResultSet rs, int rowNum) throws SQLException {
    return new VersionRow(
        rs.getObject("id", UUID.class),
        rs.getObject("strategy_id", UUID.class),
        rs.getString("version"),
        rs.getString("config_yaml"),
        readTree(rs.getString("config")),
        rs.getString("schema_version"),
        rs.getString("checksum"),
        rs.getString("status"),
        rs.getString("notes"),
        rs.getString("created_by"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("published_at", OffsetDateTime.class));
  }

  private JsonNode readTree(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored config is not valid JSON", e);
    }
  }
}
