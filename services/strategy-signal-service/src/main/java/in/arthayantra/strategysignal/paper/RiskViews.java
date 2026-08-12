package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository.AuditEntry;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Typed read models for the risk-settings surface (D3 Map-return burn-down). These replace the
 * {@code Map<String,Object>} that {@code GET/PUT /api/v1/risk/settings} returned, which springdoc
 * could not enumerate — a renamed or removed key on that route was previously undetectable by the
 * contract gate.
 *
 * <p>Component order mirrors the {@code LinkedHashMap} insertion order it replaces, so the rendered
 * key order is unchanged. Both handlers were UNCONDITIONAL — every key was {@code put} on every
 * path, and the {@code LinkedHashMap} already emitted explicit nulls — so no response gains or
 * loses a key.
 */
public final class RiskViews {

  private RiskViews() {}

  /**
   * One limit row of the settings panel. {@code value} is the JSONB payload (never null — V006
   * declares {@code value JSONB NOT NULL}), {@code updatedAt} likewise NOT NULL.
   */
  public record RiskSettingRow(String key, JsonNode value, OffsetDateTime updatedAt) {}

  /** {@code GET/PUT /api/v1/risk/settings} — a book's limits plus its recent trip/flip audit. */
  public record RiskSettings(String book, List<RiskSettingRow> items, List<AuditEntry> audit) {}
}
