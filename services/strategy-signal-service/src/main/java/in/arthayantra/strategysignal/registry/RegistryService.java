package in.arthayantra.strategysignal.registry;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategyschema.StrategyParseException;
import in.arthayantra.strategyschema.StrategySchemaV1;
import in.arthayantra.strategyschema.ValidationIssue;
import in.arthayantra.strategyschema.ValidationResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The D18 lifecycle state machine (C-2.10): versions are immutable (a PUT mints a NEW draft;
 * checksum equality is the dedupe guard), exactly one published version per strategy, rollback
 * is copy-forward, every mutating call writes an audit row, and publish re-validates — schema,
 * registry names (Q2: binding HERE, advisory in the JSON Schema), context instruments via
 * market-data REST (A7 FP-19), and the index_constituents publish guard (until Phase 44).
 */
@Service
public class RegistryService {

  /** Result of an enable/disable toggle — the strategy id and its new enabled state. */
  public record ToggleResult(UUID id, boolean enabled) {}

  /** Result of a clone — the new strategy's id + slug and its fresh draft version + status. */
  public record CloneResult(UUID id, String slug, String version, String status) {}

  private final StrategyRepository repository;
  private final MarketDataInstrumentClient instrumentClient;
  private final StrategyChangedPublisher changedPublisher;

  /** Wires the registry. */
  public RegistryService(
      StrategyRepository repository,
      MarketDataInstrumentClient instrumentClient,
      StrategyChangedPublisher changedPublisher) {
    this.repository = repository;
    this.instrumentClient = instrumentClient;
    this.changedPublisher = changedPublisher;
  }

  /**
   * POST /strategies — create as draft v1.0.0 (owner-authored: API path, boot seeders).
   * {@code @Transactional} here (not only on the actor overload) because the delegation is a
   * self-invocation — Spring's proxy is bypassed, so the tx must start at THIS entry point.
   */
  @Transactional
  public Map<String, Object> create(
      String name, String description, List<String> tags, String configYaml) {
    return create(name, description, tags, configYaml, null);
  }

  /**
   * POST /strategies — create as draft v1.0.0, stamping the submitting actor (audit T3 / EVO §13
   * row 4). {@code createdBy} lands on both the version row's {@code created_by} and the audit row's
   * {@code actor}; blank/null resolves to {@code 'owner'} (the single-owner API + seeder default).
   * A machine writer (e.g. {@code 'evo:{campaignId}'}) passes its identity explicitly.
   */
  @Transactional
  public Map<String, Object> create(
      String name, String description, List<String> tags, String configYaml, String createdBy) {
    String actor = actorOrOwner(createdBy);
    StrategyDocuments.Parsed parsed = parseOrThrow(configYaml);
    requireValid(parsed);
    String slug = parsed.config().path("id").asText();
    if (repository.findBySlug(slug).isPresent() || repository.nameTaken(name)) {
      throw new ApiException(
          409, ErrorCodes.CONFLICT_SLUG_EXISTS,
          "a strategy with this id or name already exists");
    }
    UUID strategyId =
        repository.insertStrategy(
            slug, name, description,
            parsed.config().path("author").asText("owner"),
            tags == null ? List.of() : tags);
    repository.insertVersion(
        strategyId, "1.0.0", configYaml, parsed.canonicalJson(),
        StrategySchemaV1.SCHEMA_VERSION, parsed.checksum(), "draft", null, actor);
    repository.audit(strategyId, "CREATE", null, "1.0.0", "created as draft 1.0.0", actor);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", strategyId);
    response.put("version", "1.0.0");
    response.put("status", "draft");
    response.put("checksum", parsed.checksum());
    return response;
  }

  /**
   * POST /{id}/clone — copy the strategy's LATEST config into a brand-new DRAFT strategy under a new
   * slug + name (app-platform audit §2.7 / §6.12). The source's top-level {@code id}/{@code name}/
   * {@code version} are rewritten in the YAML (author formatting + comments otherwise preserved) and
   * the result is fed through {@link #create} — so the clone rides the SAME validation and the SAME
   * 409-on-duplicate-slug-or-name guard (the conflict surfaces honestly). The clone is a fresh draft:
   * nothing is published, enabled defaults TRUE (the identity-row default) but the engine ignores it
   * until a version is published. Tags + description carry over from the source.
   */
  @Transactional
  public CloneResult clone(UUID id, String name, String slug) {
    StrategyRepository.StrategyRow source = strategyOrThrow(id);
    StrategyRepository.VersionRow latest =
        repository.latestVersion(id).orElseThrow(() -> versionNotFound("latest"));
    String clonedYaml = rewriteIdentity(latest.configYaml(), slug, name);
    // Guard the text rewrite: if the source YAML did not carry a top-level `id:` at column 0 the
    // regex would leave the source slug in place and create() would 409 with a MISLEADING "slug
    // exists" for the WRONG slug. Fail closed with the honest reason instead.
    if (!slug.equals(parseOrThrow(clonedYaml).config().path("id").asText())) {
      throw new ApiException(
          422, ErrorCodes.VALIDATION_FAILED,
          "could not set the clone id to '" + slug + "' — the source config has no top-level id");
    }
    Map<String, Object> created = create(name, source.description(), source.tags(), clonedYaml);
    return new CloneResult(
        (UUID) created.get("id"), slug, (String) created.get("version"),
        (String) created.get("status"));
  }

  /**
   * PUT /strategies/{id} — new draft version with auto bump + checksum dedupe (owner path).
   * {@code @Transactional} here too — the delegation is a self-invocation past the proxy.
   */
  @Transactional
  public Map<String, Object> update(UUID id, String configYaml, String versionBump, String notes) {
    return update(id, configYaml, versionBump, notes, null);
  }

  /**
   * PUT /strategies/{id} — new draft version, stamping the submitting actor (audit T3 / EVO §13
   * row 4). {@code createdBy} is the machine-readable provenance the optimizer promote path now
   * writes as {@code 'optimizer:{sweepId}'} (V002:36's stated contract) instead of a free-text note;
   * blank/null resolves to {@code 'owner'}.
   */
  @Transactional
  public Map<String, Object> update(
      UUID id, String configYaml, String versionBump, String notes, String createdBy) {
    String actor = actorOrOwner(createdBy);
    StrategyRepository.StrategyRow strategy = strategyOrThrow(id);
    StrategyDocuments.Parsed parsed = parseOrThrow(configYaml);
    requireValid(parsed);
    String slug = parsed.config().path("id").asText();
    if (!strategy.slug().equals(slug)) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED,
          "the config id must stay '" + strategy.slug() + "' (stable slug; never changes)");
    }
    StrategyRepository.VersionRow latest =
        repository.latestVersion(id).orElseThrow(() -> versionNotFound("latest"));
    if (latest.checksum().equals(parsed.checksum())) {
      throw new ApiException(
          409, ErrorCodes.CONFLICT_NO_CONTENT_CHANGE,
          "identical content (checksum match) — nothing to save");
    }
    String next = SemVer.parse(latest.version()).bump(SemVer.Bump.of(versionBump)).toString();
    repository.insertVersion(
        id, next, configYaml, parsed.canonicalJson(), StrategySchemaV1.SCHEMA_VERSION,
        parsed.checksum(), "draft", notes, actor);
    repository.touch(id);
    // (b) keep the identity-row tags (which drive the list/filters) in lockstep with the config tags —
    // the config is the source of truth the engine arms from, so an edited YAML re-syncs the row and the
    // list can never diverge from the editor. Metadata only; mints no extra version. Guarded on an
    // explicit `tags:` block so a tags-less config never WIPES the row's tags.
    if (parsed.config().has("tags")) {
      List<String> configTags = new ArrayList<>();
      parsed.config().path("tags").forEach(t -> configTags.add(t.asText()));
      if (!strategy.tags().equals(configTags)) {
        repository.updateTags(id, configTags);
      }
    }
    List<ConfigDiff.Op> ops = ConfigDiff.diff(latest.config(), parsed.config());
    repository.audit(
        id, "UPDATE_DRAFT", latest.version(), next, ConfigDiff.summary(ops), actor);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", id);
    response.put("version", next);
    response.put("status", "draft");
    response.put("checksum", parsed.checksum());
    return response;
  }

  /** POST /{id}/publish — one published version per strategy; full re-validation. */
  public Map<String, Object> publish(UUID id, String targetVersion, String notes) {
    // Unconditional publish (all in-process callers + a plain owner publish) — byte-for-byte the
    // pre-CAS behaviour. The concurrency-safe compare-and-set path is the overload below.
    return publish(id, targetVersion, notes, false, null);
  }

  /**
   * Publish with an optional compare-and-set on the current published pointer (PF-01 round-5 #1).
   * When {@code cas} is true the pointer moves ONLY while the strategy's current published version
   * still equals {@code expectedPublishedVersionId} (null = a first publish onto a never-published
   * strategy); otherwise a concurrent promoter already moved the LIVE pointer and this call is
   * rejected with 409 before it can overwrite live state. The whole method is {@code @Transactional},
   * so a lost CAS rolls back the draft's status change too. {@code cas=false} is unconditional and
   * identical to the legacy behaviour.
   */
  @Transactional
  public Map<String, Object> publish(
      UUID id, String targetVersion, String notes, boolean cas, String expectedPublishedVersionId) {
    StrategyRepository.StrategyRow strategy = strategyOrThrow(id);
    StrategyRepository.VersionRow target =
        targetVersion != null
            ? repository.findVersion(id, targetVersion).orElseThrow(() -> versionNotFound(targetVersion))
            : repository.latestDraft(id)
                .orElseThrow(
                    () ->
                        new ApiException(
                            409, ErrorCodes.CONFLICT_NOT_A_DRAFT, "no draft version to publish"));
    if (!"draft".equals(target.status())) {
      throw new ApiException(
          409, ErrorCodes.CONFLICT_NOT_A_DRAFT,
          "version " + target.version() + " is " + target.status() + ", not a draft");
    }
    revalidateForPublish(target);

    String previousVersion = null;
    if (strategy.publishedVersionId() != null) {
      StrategyRepository.VersionRow previous =
          repository.findVersionById(strategy.publishedVersionId()).orElse(null);
      if (previous != null) {
        repository.updateVersionStatus(previous.id(), "archived", false);
        previousVersion = previous.version();
      }
    }
    repository.updateVersionStatus(target.id(), "published", true);
    // Move the LIVE published pointer. In CAS mode this is the atomic claim: exactly one of two
    // concurrent publishers wins the row-locked conditional UPDATE; the loser gets 0 rows and 409s
    // (the @Transactional rolls back this call's archive + draft-status changes), so it never
    // overwrites live state nor reaches the engine-reload publish below. cas=false is unconditional
    // (byte-for-byte the legacy behaviour).
    if (cas) {
      UUID expected =
          expectedPublishedVersionId != null ? UUID.fromString(expectedPublishedVersionId) : null;
      int moved = repository.casPublishedVersion(id, target.id(), expected);
      if (moved == 0) {
        throw new ApiException(
            409, ErrorCodes.CONFLICT_PUBLISHED_VERSION_CHANGED,
            "the published version of strategy " + id + " changed since the caller read it "
                + "(expected " + expectedPublishedVersionId + ") — a concurrent promoter won; "
                + "this publish is rejected to avoid overwriting live state");
      }
    } else {
      repository.setPublishedVersion(id, target.id());
    }
    repository.audit(
        id, "PUBLISH", previousVersion, target.version(),
        notes != null ? notes : "published " + target.version(), "owner");
    changedPublisher.publish(id, strategy.slug(), "PUBLISH", target.version());
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", id);
    response.put("version", target.version());
    response.put("status", "published");
    return response;
  }

  /** POST /{id}/rollback — copy-forward, never history rewrite. */
  @Transactional
  public Map<String, Object> rollback(UUID id, String sourceVersion, boolean andPublish) {
    StrategyRepository.StrategyRow strategy = strategyOrThrow(id);
    StrategyRepository.VersionRow source =
        repository.findVersion(id, sourceVersion).orElseThrow(() -> versionNotFound(sourceVersion));
    StrategyRepository.VersionRow latest =
        repository.latestVersion(id).orElseThrow(() -> versionNotFound("latest"));
    String next = SemVer.parse(latest.version()).bump(SemVer.Bump.PATCH).toString();
    String provenance =
        "rolled back from " + latest.version() + " to content of " + source.version();
    repository.insertVersion(
        id, next, source.configYaml(), source.config().toString(), source.schemaVersion(),
        source.checksum(), "draft", provenance, "owner");
    repository.touch(id);
    repository.audit(id, "ROLLBACK", latest.version(), source.version(), provenance, "owner");
    changedPublisher.publish(id, strategy.slug(), "ROLLBACK", next);
    if (andPublish) {
      publish(id, next, provenance);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", id);
    response.put("newVersion", next);
    response.put("copiedFrom", source.version());
    response.put("status", andPublish ? "published" : "draft");
    return response;
  }

  /** POST /{id}/archive — unload from the signal engine. */
  @Transactional
  public Map<String, Object> archive(UUID id) {
    StrategyRepository.StrategyRow strategy = strategyOrThrow(id);
    String archivedVersion = null;
    if (strategy.publishedVersionId() != null) {
      StrategyRepository.VersionRow published =
          repository.findVersionById(strategy.publishedVersionId()).orElse(null);
      if (published != null) {
        repository.updateVersionStatus(published.id(), "archived", false);
        archivedVersion = published.version();
      }
      repository.setPublishedVersion(id, null);
    }
    // Only record the lifecycle transition when one actually happened (a published version was
    // demoted). Archiving a strategy with nothing published is a no-op: writing an ARCHIVE row
    // would permanently pollute the append-only audit log and emit a phantom version=null hot-swap.
    if (archivedVersion != null) {
      repository.audit(id, "ARCHIVE", archivedVersion, null, "archived", "owner");
      changedPublisher.publish(id, strategy.slug(), "ARCHIVE", archivedVersion);
    }
    return Map.of("id", id, "status", "archived");
  }

  /**
   * POST /{id}/enable | /{id}/disable — flip the master kill-switch the live signal engine reconcile
   * arms from (it loads {@code enabled && publishedVersionId != null}). Writes an ENABLE/DISABLE
   * audit row and, when the strategy is PUBLISHED, emits {@code strategy.changed} so the engine
   * hot-swaps at the next bar boundary (an unpublished strategy is neither loaded nor unloaded, so it
   * emits nothing — matching {@link #archive}'s "only on a real transition" discipline and avoiding a
   * phantom reload). Idempotent: a no-op flip returns the current state WITHOUT appending an audit row
   * (the log is append-only — a no-op ENABLE would permanently pollute the history).
   */
  @Transactional
  public ToggleResult setEnabled(UUID id, boolean enabled) {
    StrategyRepository.StrategyRow strategy = strategyOrThrow(id);
    if (strategy.enabled() == enabled) {
      return new ToggleResult(id, enabled);
    }
    repository.updateEnabled(id, enabled);
    repository.audit(
        id, enabled ? "ENABLE" : "DISABLE", null, null,
        enabled
            ? "enabled — armed for the live signal engine"
            : "disabled — unloaded from the live signal engine",
        "owner");
    if (strategy.publishedVersionId() != null) {
      String publishedVersion =
          repository.findVersionById(strategy.publishedVersionId())
              .map(StrategyRepository.VersionRow::version)
              .orElse(null);
      changedPublisher.publish(
          id, strategy.slug(), enabled ? "ENABLE" : "DISABLE", publishedVersion);
    }
    return new ToggleResult(id, enabled);
  }

  /**
   * DELETE /{id} — hard delete only when every version is a draft. Deliberately NOT one
   * transaction: the archive-instead path must COMMIT before the 409 surfaces (throwing inside
   * a shared tx would roll the archive back).
   */
  public boolean delete(UUID id) {
    StrategyRepository.StrategyRow strategy = strategyOrThrow(id);
    if (repository.allVersionsAreDrafts(id)) {
      repository.deleteStrategy(id);
      return true;
    }
    // Demote a still-live version, but do NOT re-archive a strategy whose published version was
    // already archived (publishedVersionId is null by then) — that re-runs archive() for nothing.
    boolean archivedNow = strategy.publishedVersionId() != null;
    if (archivedNow) {
      archive(id);
    }
    throw new ApiException(
        409, ErrorCodes.CONFLICT_HAS_PUBLISHED_HISTORY,
        archivedNow
            ? "strategy has published history — archived instead of deleted"
            : "strategy has non-draft (published/archived) history — cannot be hard-deleted",
        Map.of("archived", archivedNow));
  }

  /** GET /{id}/diff?from=&to= — SERVER-side structured diff + raw YAML pair. */
  public Map<String, Object> diff(UUID id, String from, String to) {
    strategyOrThrow(id);
    if (from.equals(to)) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "from and to are the same version");
    }
    StrategyRepository.VersionRow fromRow =
        repository.findVersion(id, from).orElseThrow(() -> versionNotFound(from));
    StrategyRepository.VersionRow toRow =
        repository.findVersion(id, to).orElseThrow(() -> versionNotFound(to));
    List<ConfigDiff.Op> ops = ConfigDiff.diff(fromRow.config(), toRow.config());
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("structured", ops);
    response.put("yamlFrom", fromRow.configYaml());
    response.put("yamlTo", toRow.configYaml());
    return response;
  }

  /** POST /strategies/validate — stateless; schema + semantics + advisory server checks. */
  public Map<String, Object> validate(String configYaml) {
    ValidationResult result;
    try {
      result = StrategyDocuments.validate(configYaml);
    } catch (StrategyParseException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, e.getMessage());
    }
    List<ValidationIssue> warnings = new ArrayList<>(result.warnings());
    if (result.valid()) {
      JsonNode config = parseOrThrow(configYaml).config();
      warnings.addAll(advisoryServerChecks(config));
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("valid", result.valid());
    response.put("errors", result.errors());
    response.put("warnings", warnings);
    return response;
  }

  /**
   * Listing with derived status + filters. Filters (status/tag/q) are applied to the FULL ordered
   * set BEFORE limit/offset, so pagination bounds the FILTERED result — applying LIMIT/OFFSET in
   * SQL first would silently truncate matches and make filtered page boundaries meaningless.
   */
  public List<Map<String, Object>> list(String status, String tag, String query, int limit, int offset) {
    String tagLower = tag == null ? null : tag.toLowerCase(Locale.ROOT);
    String queryLower = query == null ? null : query.toLowerCase(Locale.ROOT);
    List<Map<String, Object>> items = new ArrayList<>();
    int skipped = 0;
    for (StrategyRepository.StrategyRow row : repository.listAll()) {
      String derived = derivedStatus(row);
      if (status != null && !status.equalsIgnoreCase(derived)) {
        continue;
      }
      if (tagLower != null && !row.tags().contains(tagLower)) {
        continue;
      }
      if (queryLower != null
          && !row.name().toLowerCase(Locale.ROOT).contains(queryLower)
          && !row.slug().contains(queryLower)) {
        continue;
      }
      if (skipped < offset) { // paginate over the FILTERED stream
        skipped++;
        continue;
      }
      if (items.size() >= limit) {
        break;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", row.id());
      item.put("slug", row.slug());
      item.put("name", row.name());
      Optional<StrategyRepository.VersionRow> latest = repository.latestVersion(row.id());
      item.put("currentVersion", latest.map(StrategyRepository.VersionRow::version).orElse(null));
      item.put(
          "publishedVersion",
          row.publishedVersionId() == null
              ? null
              : repository.findVersionById(row.publishedVersionId())
                  .map(StrategyRepository.VersionRow::version)
                  .orElse(null));
      // version UUIDs let the strategy list fetch each row's last-backtest summary from
      // backtest-service (keyed by strategy_version_id) — the cross-schema join done client-side.
      item.put(
          "currentVersionId",
          latest.map(StrategyRepository.VersionRow::id).map(UUID::toString).orElse(null));
      item.put(
          "publishedVersionId",
          row.publishedVersionId() == null ? null : row.publishedVersionId().toString());
      item.put("status", derived);
      item.put("tags", row.tags());
      item.put("author", row.author());
      item.put("enabled", row.enabled());
      item.put("notificationsEnabled", row.notificationsEnabled());
      item.put("notificationChannel", row.notificationChannel());
      item.put("updatedAt", row.updatedAt());
      items.add(item);
    }
    return items;
  }

  /** Full detail at a version (latest by default). */
  public Map<String, Object> detail(UUID id, String version) {
    StrategyRepository.StrategyRow strategy = strategyOrThrow(id);
    StrategyRepository.VersionRow row =
        version != null
            ? repository.findVersion(id, version).orElseThrow(() -> versionNotFound(version))
            : repository.latestVersion(id).orElseThrow(() -> versionNotFound("latest"));
    verifyChecksumOnRead(row);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", strategy.id());
    response.put("versionId", row.id());
    response.put("slug", strategy.slug());
    response.put("name", strategy.name());
    response.put("description", strategy.description());
    response.put("tags", strategy.tags());
    response.put("enabled", strategy.enabled());
    response.put("version", row.version());
    response.put("status", row.status());
    response.put("config", row.config());
    response.put("configYaml", row.configYaml());
    response.put("checksum", row.checksum());
    response.put("notes", row.notes());
    response.put("createdAt", row.createdAt());
    response.put("updatedAt", strategy.updatedAt());
    response.put("notificationsEnabled", strategy.notificationsEnabled());
    response.put("notificationChannel", strategy.notificationChannel());
    return response;
  }

  /**
   * Toggle notification opt-in (Phase 41 / E-14). Strategy-level metadata — NEVER mints a version
   * or perturbs a D18 checksum (asserted by an IT). A valid channel is required when enabling.
   */
  public Map<String, Object> updateNotifications(UUID id, boolean enabled, String channel) {
    strategyOrThrow(id);
    if (enabled && channel != null && !channel.equals("NTFY") && !channel.equals("TELEGRAM")) {
      throw new ApiException(
          422, ErrorCodes.VALIDATION_FAILED, "notification_channel must be NTFY or TELEGRAM");
    }
    repository.updateNotifications(id, enabled, enabled ? channel : null);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", id);
    response.put("notificationsEnabled", enabled);
    response.put("notificationChannel", enabled ? channel : null);
    return response;
  }

  /**
   * Re-sync the strategy ROW tags — the §12.3 scalper-gate arming the engine reads from the strategy
   * IDENTITY row via {@code strategy.tags()}, NOT the versioned config. Like {@link #updateNotifications}
   * this is strategy METADATA: it mints NO version, changes NO D18 checksum, and never perturbs the
   * published version or its backtest-run links. Idempotent — returns {@code false} (no-op) when the
   * tags already match. When the strategy is already PUBLISHED a CHANGED event hot-swaps the live engine
   * so it re-reads the tags at the next bar boundary; a DRAFT needs none (the engine skips unpublished).
   */
  @Transactional
  public boolean updateTags(UUID id, List<String> tags) {
    StrategyRepository.StrategyRow strategy = strategyOrThrow(id);
    List<String> next = tags == null ? List.of() : tags;
    if (strategy.tags().equals(next)) {
      return false;
    }
    repository.updateTags(id, next);
    if (strategy.publishedVersionId() != null) {
      String publishedVersion =
          repository.findVersionById(strategy.publishedVersionId())
              .map(StrategyRepository.VersionRow::version)
              .orElse(null);
      changedPublisher.publish(id, strategy.slug(), "TAGS", publishedVersion);
    }
    return true;
  }

  /**
   * Re-sync a strategy's CONFIG from the repo YAML BY SLUG (the seeder's idempotent re-arm under the
   * config-is-source-of-truth model): mints a new DRAFT version when the content changed — which also
   * re-syncs the identity-row tags via {@link #update} — and is a no-op (returns {@code false}) on
   * identical content (the D18 checksum dedupe) or an absent slug.
   */
  public boolean resyncConfig(String slug, String configYaml) {
    Optional<StrategyRepository.StrategyRow> existing = repository.findBySlug(slug);
    if (existing.isEmpty()) {
      return false;
    }
    try {
      update(existing.get().id(), configYaml, null, "S24 gate-arming sync (config = source of truth)");
      return true;
    } catch (ApiException e) {
      if (e.httpStatus() == 409) {
        return false; // identical content already stored (D18 checksum dedupe) — nothing to re-sync
      }
      throw e;
    }
  }

  /** Version history page. */
  public List<Map<String, Object>> versions(UUID id, int limit, int offset) {
    strategyOrThrow(id);
    List<Map<String, Object>> items = new ArrayList<>();
    for (StrategyRepository.VersionRow row : repository.versions(id, limit, offset)) {
      Map<String, Object> item = new LinkedHashMap<>();
      // The version-row UUID — lets the UI fetch each version's last-backtest summary (keyed by
      // strategyVersionId) when expanding old versions on the strategies list.
      item.put("versionId", row.id().toString());
      item.put("version", row.version());
      item.put("status", row.status());
      item.put("checksum", row.checksum());
      item.put("author", row.createdBy());
      item.put("notes", row.notes());
      item.put("createdAt", row.createdAt());
      items.add(item);
    }
    return items;
  }

  /** GET /{id}/audit — the append-only lifecycle-history timeline, newest first. */
  public List<StrategyRepository.AuditRow> auditLog(UUID id, int limit, int offset) {
    strategyOrThrow(id);
    return repository.auditLog(id, limit, offset);
  }

  // ---- internals -------------------------------------------------------

  /**
   * Rewrites the top-level {@code id}/{@code name}/{@code version} scalars in a config YAML for a
   * clone, preserving all other bytes (comments, formatting). Only column-0 keys match ({@code (?m)^…}
   * — nested indicator {@code name:} lines are indented / inside flow-mappings), so exactly the three
   * document-level fields are replaced; {@code version} resets to {@code 1.0.0} to match the fresh
   * draft the registry mints. A source without a top-level {@code name:}/{@code version:} simply keeps
   * it absent (the clone guard in {@link #clone} enforces the mandatory {@code id} rewrite).
   */
  private static String rewriteIdentity(String yaml, String slug, String name) {
    String out =
        yaml.replaceFirst(
            "(?m)^id:.*$", java.util.regex.Matcher.quoteReplacement("id: " + slug));
    out =
        out.replaceFirst(
            "(?m)^name:.*$",
            java.util.regex.Matcher.quoteReplacement("name: " + yamlDoubleQuote(name)));
    out = out.replaceFirst("(?m)^version:.*$", "version: 1.0.0");
    return out;
  }

  /** Wraps a scalar as a double-quoted YAML string, escaping backslashes and double-quotes. */
  private static String yamlDoubleQuote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private StrategyRepository.StrategyRow strategyOrThrow(UUID id) {
    return repository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such strategy"));
  }

  private static NotFoundException versionNotFound(String version) {
    return new NotFoundException(ErrorCodes.NOT_FOUND_VERSION, "no such version: " + version);
  }

  // Audit T3 / EVO §13 row 4: the single-owner API path (and boot seeders) leave the actor blank —
  // it defaults to 'owner'. Only a machine writer (optimizer promote, future EVO) passes an explicit
  // prefixed identity ('optimizer:{sweepId}', 'evo:{campaignId}').
  private static String actorOrOwner(String createdBy) {
    return createdBy == null || createdBy.isBlank() ? "owner" : createdBy;
  }

  private StrategyDocuments.Parsed parseOrThrow(String configYaml) {
    try {
      return StrategyDocuments.parse(configYaml);
    } catch (StrategyParseException e) {
      throw new ApiException(400, ErrorCodes.STRATEGY_SCHEMA_INVALID, e.getMessage());
    }
  }

  private void requireValid(StrategyDocuments.Parsed parsed) {
    ValidationResult result = StrategyDocuments.validateTree(parsed.config());
    if (!result.valid()) {
      throw new ApiException(
          400, ErrorCodes.STRATEGY_SCHEMA_INVALID, "config fails strategy-schema/v1",
          Map.of("errors", result.errors()));
    }
  }

  /** Publish-time gates: schema version, full re-validation, Q2 names, A7 context, universe. */
  private void revalidateForPublish(StrategyRepository.VersionRow target) {
    if (!StrategySchemaV1.SCHEMA_VERSION.equals(target.schemaVersion())) {
      throw new ApiException(
          422, ErrorCodes.STRATEGY_SCHEMA_UNSUPPORTED,
          "schema version " + target.schemaVersion() + " is no longer supported");
    }
    verifyChecksumOnRead(target);
    ValidationResult result = StrategyDocuments.validateTree(target.config());
    if (!result.valid()) {
      throw new ApiException(
          422, ErrorCodes.STRATEGY_SCHEMA_INVALID, "config no longer validates",
          Map.of("errors", result.errors()));
    }
    // Phase 44: the index_constituents publish guard is LIFTED — the submission-time universe
    // resolver (UniverseResolver) now pins membership by copy, so these strategies are publishable.
    List<ValidationIssue> hardIssues = new ArrayList<>();
    if ("btst".equals(target.config().at("/risk/session/style").asText())) {
      String preCloseAt =
          target.config().at("/risk/session/pre_close_at").asText("15:20");
      for (JsonNode rule : target.config().path("exit_rules")) {
        String type = rule.path("type").asText();
        if (!BtstExitRuleParityRules.SAFE_TYPES.contains(type)) {
          hardIssues.add(
              new ValidationIssue(
                  "/exit_rules",
                  "BTST exit type '" + type
                      + "' cannot be published: simulation reads a synthetic " + preCloseAt
                      + " bar while live"
                      + " reads dense 15:30 daily bars, so this exit would drift; allowed types are "
                      + BtstExitRuleParityRules.SAFE_TYPES));
        } else if (("stop_loss".equals(type) || "take_profit".equals(type))
            && !BtstExitRuleParityRules.SAFE_LEVEL_BASES
                .contains(rule.path("params").path("basis").asText())) {
          String basis = rule.path("params").path("basis").asText();
          hardIssues.add(
              new ValidationIssue(
                  "/exit_rules",
                  "BTST " + type + " basis '" + basis
                      + "' cannot be published: simulation reads a synthetic " + preCloseAt
                      + " bar while live"
                      + " reads dense 15:30 daily bars, so this exit would drift; allowed bases are "
                      + BtstExitRuleParityRules.SAFE_LEVEL_BASES));
        }
      }
    }
    for (JsonNode indicator : target.config().path("indicators")) {
      String name = indicator.path("name").asText();
      if (!IndicatorRegistry.exists(name)) {
        hardIssues.add(
            new ValidationIssue(
                "/indicators", "indicator '" + name + "' does not exist in the engine registry"));
        continue;
      }
      if (indicator.has("instrument") && !IndicatorRegistry.isSeeded(name)) {
        // Seeded indicators (Minervini VCP_PIVOT/CHEAT_PIVOT/THRUST) carry a SENTINEL instrument key
        // whose series the live batch injects per-symbol at eval time — it is never resolved from the
        // instruments master, so the existence gate does not apply (it guards real second series only).
        String exchange = indicator.get("instrument").path("exchange").asText();
        String symbol = indicator.get("instrument").path("tradingsymbol").asText();
        try {
          if (!instrumentClient.exists(exchange, symbol)) {
            hardIssues.add(
                new ValidationIssue(
                    "/indicators",
                    "context instrument " + exchange + ":" + symbol
                        + " does not resolve in the instruments master"));
          }
        } catch (MarketDataInstrumentClient.MarketDataUnavailableException e) {
          hardIssues.add(
              new ValidationIssue(
                  "/indicators",
                  "could not verify context instrument " + exchange + ":" + symbol
                      + " (market-data unavailable) — publish fails closed"));
        }
      }
    }
    if (!hardIssues.isEmpty()) {
      throw new ApiException(
          422, ErrorCodes.STRATEGY_SCHEMA_INVALID, "publish checks failed",
          Map.of("errors", hardIssues));
    }
  }

  /** Advisory (warning-level) server checks for POST /validate. */
  private List<ValidationIssue> advisoryServerChecks(JsonNode config) {
    List<ValidationIssue> warnings = new ArrayList<>();
    if ("index_constituents".equals(config.path("universe").path("mode").asText())) {
      warnings.add(
          new ValidationIssue(
              "/universe/mode",
              "index_constituents resolves CURRENT membership — windows predating constituent"
                  + " capture carry the survivorship-bias caveat"));
    }
    for (JsonNode indicator : config.path("indicators")) {
      String name = indicator.path("name").asText();
      if (!IndicatorRegistry.exists(name)) {
        warnings.add(
            new ValidationIssue(
                "/indicators",
                "indicator '" + name + "' is not in the engine registry (binding at publish)"));
      }
      if (indicator.has("instrument") && !IndicatorRegistry.isSeeded(name)) {
        String exchange = indicator.get("instrument").path("exchange").asText();
        String symbol = indicator.get("instrument").path("tradingsymbol").asText();
        try {
          if (!instrumentClient.exists(exchange, symbol)) {
            warnings.add(
                new ValidationIssue(
                    "/indicators",
                    "context instrument " + exchange + ":" + symbol
                        + " does not resolve (binding at publish)"));
          }
        } catch (MarketDataInstrumentClient.MarketDataUnavailableException e) {
          warnings.add(
              new ValidationIssue(
                  "/indicators",
                  "context-instrument check skipped (market-data unreachable); binding at publish"));
        }
      }
    }
    return warnings;
  }

  /** D18: checksums verified on read. */
  private void verifyChecksumOnRead(StrategyRepository.VersionRow row) {
    String canonical = in.arthayantra.strategyschema.CanonicalJson.write(row.config());
    String recomputed = in.arthayantra.strategyschema.CanonicalJson.sha256Hex(canonical);
    if (!recomputed.equals(row.checksum())) {
      throw new ApiException(
          500, ErrorCodes.INTERNAL_ERROR,
          "stored version " + row.version() + " fails its checksum — refusing to serve");
    }
  }

  private String derivedStatus(StrategyRepository.StrategyRow row) {
    if (row.publishedVersionId() != null) {
      return "published";
    }
    return repository.latestDraft(row.id()).isPresent() ? "draft" : "archived";
  }
}
