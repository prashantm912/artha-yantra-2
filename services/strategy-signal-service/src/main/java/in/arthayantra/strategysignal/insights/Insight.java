package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A persisted insight row (INT design §2.1) — also the wire DTO for {@code GET /api/v1/insights} and
 * {@code /{id}}. A single {@code GET /{id}} response is self-contained for audit (§9.4): everything
 * needed to re-derive the insight by hand is in it or one evidence link away — hence {@code evidence},
 * {@code priorityDetail}, {@code engineVersion}, {@code configHash} and {@code dataTrust} all ride the
 * DTO. Typed record (never {@code Map}) so it enumerates into the OpenAPI contract.
 */
public record Insight(
    UUID id,
    OffsetDateTime generatedAt,
    String type,
    String severity,
    String scope,
    String title,
    String explanation,
    JsonNode evidence,
    @Schema(types = {"number", "null"}) BigDecimal priority,
    // InsightEngine:211 builds this as `c.priorityDetail() == null ? null : valueToTree(...)`, so it
    // is explicitly nullable at its ONLY construction site. `evidence` above is NOT: toJson()
    // normalises a null list to List.of(), so it is genuinely always present.
    @Schema(types = {"object", "null"}) JsonNode priorityDetail,
    String dataTrust,
    List<String> trustReasons,
    String dedupeKey,
    @Schema(types = {"string", "null"}) OffsetDateTime cooldownUntil,
    boolean suppressed,
    String status,
    @Schema(types = {"string", "null"}) OffsetDateTime expiresAt,
    String engineVersion,
    String configHash) {

  /** Insight lifecycle status (§2.1). */
  public enum Status {
    OPEN,
    ACKED,
    ACTED,
    DISMISSED,
    EXPIRED
  }
}
