package in.arthayantra.strategysignal.registry;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyschema.ValidationIssue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Typed read models for the registry CRUD surface (D3 Map-return burn-down). Every one of these
 * replaces a {@code Map<String,Object>} that springdoc could not enumerate, so the contract gate was
 * blind to it — a renamed or removed key on any of these routes was previously undetectable.
 *
 * <p>Component order matches the {@code LinkedHashMap} insertion order it replaces, so the rendered
 * JSON key order is unchanged. TWO exceptions — {@link ArchiveResponse} and {@link
 * StrategyListResponse} — came from MULTI-key {@code Map.of}, whose iteration order is JVM-salted
 * and was never stable: those records NORMALISE the order rather than preserving it, and must not
 * be called byte-identical. {@link VersionListResponse}'s source was a SINGLE-key {@code Map.of},
 * which is trivially stable, so it is in neither camp.
 *
 * <p>Nullability is spelled {@code @Schema(types = {"…","null"})}: {@code @Schema(nullable = true)}
 * is a silent no-op at OpenAPI 3.1 because swagger-core's 3.1 serializer drops the keyword.
 */
public final class RegistryViews {

  private RegistryViews() {}

  /** One row of {@code GET /api/v1/strategies} — the strategy list with derived status. */
  public record StrategyListItem(
      UUID id,
      String slug,
      String name,
      @Schema(types = {"string", "null"}) String currentVersion,
      @Schema(types = {"string", "null"}) String publishedVersion,
      @Schema(types = {"string", "null"}) String currentVersionId,
      @Schema(types = {"string", "null"}) String publishedVersionId,
      String status,
      List<String> tags,
      String author,
      boolean enabled,
      boolean notificationsEnabled,
      @Schema(types = {"string", "null"}) String notificationChannel,
      OffsetDateTime updatedAt) {}

  /** {@code GET /api/v1/strategies} envelope. */
  public record StrategyListResponse(List<StrategyListItem> items, int limit, int offset) {}

  /** {@code GET /api/v1/strategies/{id}} — full detail at one version. */
  public record StrategyDetail(
      UUID id,
      UUID versionId,
      @Schema(types = {"string", "null"}) UUID publishedVersionId,
      @Schema(types = {"string", "null"}) String publishedVersion,
      String slug,
      String name,
      @Schema(types = {"string", "null"}) String description,
      List<String> tags,
      boolean enabled,
      String version,
      String status,
      JsonNode config,
      String configYaml,
      String checksum,
      @Schema(types = {"string", "null"}) String notes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      boolean notificationsEnabled,
      @Schema(types = {"string", "null"}) String notificationChannel) {}

  /**
   * The shape returned by BOTH {@code POST /strategies} (create) and {@code PUT /strategies/{id}}
   * (update): a new draft version's identity. One record rather than two identically-shaped ones —
   * duplicate simple names collapse to a single OpenAPI schema, so twins would have to be annotated
   * identically anyway.
   */
  public record DraftVersionResponse(UUID id, String version, String status, String checksum) {}

  /** {@code POST /strategies/{id}/publish}. */
  public record PublishResponse(UUID id, String version, UUID versionId, String status) {}

  /**
   * {@code GET /strategies/{id}/universe} — the Phase-44 resolved universe label.
   *
   * <p>Deliberately NOT {@link UniverseResolver.ResolvedUniverse}: the wire form adds the derived
   * {@code constituentCount} and orders {@code items} LAST, so returning the resolver's record
   * directly would silently change both the key set and the order. This pins the shape the
   * {@code LinkedHashMap} emitted, component-for-component in the same sequence.
   *
   * <p>{@code asOf} and {@code survivorshipCaveat} are genuinely nullable — only the
   * {@code index_constituents} branch supplies a caveat, and four of the six resolve branches pass
   * a null {@code asOf} ({@code UniverseResolver:181,214,238} and the two funnels when the screen
   * date is absent). The {@code LinkedHashMap} already emitted both as explicit nulls, so this is a
   * retyping, not a shape change.
   */
  public record UniverseInfo(
      String mode,
      @Schema(types = {"string", "null"}) String asOf,
      int constituentCount,
      String checksum,
      @Schema(types = {"string", "null"}) String survivorshipCaveat,
      List<UniverseResolver.Constituent> items) {}

  /** {@code POST /strategies/{id}/rollback} — copy-forward, never history rewrite. */
  public record RollbackResponse(
      UUID id, String newVersion, String copiedFrom, String status) {}

  /**
   * {@code POST /strategies/{id}/archive}. ⚠️ Source was a multi-key {@code Map.of}, whose iteration
   * order varies per JVM — this record normalises the order, it does not preserve it.
   */
  public record ArchiveResponse(UUID id, String status) {}

  /** {@code PATCH /strategies/{id}/notifications}. */
  public record NotificationsResponse(
      UUID id,
      boolean notificationsEnabled,
      @Schema(types = {"string", "null"}) String notificationChannel) {}

  /** One row of {@code GET /strategies/{id}/versions}. */
  public record VersionListItem(
      String versionId,
      String version,
      String status,
      String checksum,
      String author,
      @Schema(types = {"string", "null"}) String notes,
      OffsetDateTime createdAt) {}

  /** {@code GET /strategies/{id}/versions} envelope. */
  public record VersionListResponse(List<VersionListItem> items) {}

  /** {@code GET /strategies/{id}/diff} — server-side structured diff plus both YAML sides. */
  public record DiffResponse(
      List<ConfigDiff.Op> structured, String yamlFrom, String yamlTo) {}

  /** {@code POST /strategies/validate} — stateless schema + semantic + advisory checks. */
  public record ValidateResponse(
      boolean valid, List<ValidationIssue> errors, List<ValidationIssue> warnings) {}
}
