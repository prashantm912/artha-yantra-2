package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
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
    BigDecimal priority,
    JsonNode priorityDetail,
    String dataTrust,
    List<String> trustReasons,
    String dedupeKey,
    OffsetDateTime cooldownUntil,
    boolean suppressed,
    String status,
    OffsetDateTime expiresAt,
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
