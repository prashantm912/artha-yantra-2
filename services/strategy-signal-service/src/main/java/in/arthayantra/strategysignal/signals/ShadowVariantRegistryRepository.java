package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code strategy.shadow_variant_registry} (EVO E3 §11) — the DURABLE store of
 * runtime-registered shadow challenger variants. SELECT feeds the boot/hot-reload merge and the API
 * list; INSERT registers; UPDATE soft-disables (a retire). There is no DELETE path (history stays —
 * {@code shadow_positions.variant} references a variant by name). The service connects as
 * {@code artha} (D10 single-writer); the per-schema role is read/append/update-only.
 */
@Repository
public class ShadowVariantRegistryRepository {

  /** One registry row. {@code spec} is the ShadowVariants vocabulary body {rails, compositeThreshold}. */
  public record RegistryRow(
      UUID id,
      String name,
      UUID campaignId,
      JsonNode spec,
      boolean enabled,
      String createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime disabledAt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  /** Wires the strategy datasource + the JSON codec for the spec column. */
  public ShadowVariantRegistryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** Registers a variant; returns the inserted row (id + timestamps DB-assigned). */
  public RegistryRow insert(String name, UUID campaignId, JsonNode spec, String createdBy) {
    return jdbc.queryForObject(
        """
        INSERT INTO shadow_variant_registry (name, campaign_id, spec, created_by)
        VALUES (?, ?::uuid, ?::jsonb, ?)
        RETURNING id, name, campaign_id, spec, enabled, created_by, created_at, disabled_at
        """,
        this::row,
        name,
        campaignId == null ? null : campaignId.toString(),
        writeJson(spec),
        createdBy);
  }

  /** True when a row with this name exists (ENABLED or already retired — the name is immutable). */
  public boolean existsByName(String name) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM shadow_variant_registry WHERE name = ?", Long.class, name);
    return n != null && n > 0;
  }

  /** The ENABLED rows — the boot/hot-reload merge source (oldest first, a stable merge order). */
  public List<RegistryRow> findEnabled() {
    return jdbc.query(
        "SELECT * FROM shadow_variant_registry WHERE enabled ORDER BY created_at", this::row);
  }

  /** Every row (enabled + retired), newest first — the API list surface (history stays visible). */
  public List<RegistryRow> findAll() {
    return jdbc.query("SELECT * FROM shadow_variant_registry ORDER BY created_at DESC", this::row);
  }

  /** One row by name (used to echo the registered row back on POST). */
  public Optional<RegistryRow> findByName(String name) {
    return jdbc
        .query("SELECT * FROM shadow_variant_registry WHERE name = ?", this::row, name)
        .stream()
        .findFirst();
  }

  /** Soft-disables a variant; returns rows updated (1 = retired now, 0 = absent OR already retired). */
  public int disable(String name) {
    return jdbc.update(
        "UPDATE shadow_variant_registry SET enabled = false, disabled_at = now() "
            + "WHERE name = ? AND enabled",
        name);
  }

  private RegistryRow row(ResultSet rs, int rowNum) throws SQLException {
    return new RegistryRow(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getObject("campaign_id", UUID.class),
        readJson(rs.getString("spec")),
        rs.getBoolean("enabled"),
        rs.getString("created_by"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("disabled_at", OffsetDateTime.class));
  }

  private String writeJson(JsonNode spec) {
    try {
      return mapper.writeValueAsString(spec == null ? mapper.createObjectNode() : spec);
    } catch (Exception e) {
      throw new IllegalArgumentException("unserializable shadow-variant spec: " + e.getMessage());
    }
  }

  private JsonNode readJson(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("corrupt shadow-variant spec JSON: " + e.getMessage());
    }
  }
}
