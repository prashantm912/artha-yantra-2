package in.arthayantra.strategysignal.registry;

import in.arthayantra.strategyschema.StrategySchemaV1;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The C-2.13 CRUD surface, verbatim routes under /api/v1/strategies/**. */
@RestController
@RequestMapping("/api/v1/strategies")
public class RegistryController {

  /**
   * POST body. {@code createdBy} (audit T3 / EVO §13 row 4) is the OPTIONAL machine-writer actor —
   * omitted on the single-owner API path (defaults to {@code 'owner'}), set to a prefixed identity
   * (e.g. {@code 'evo:{campaignId}'}) by an internal service-to-service caller.
   */
  public record CreateRequest(
      String name, String description, List<String> tags, String config, String createdBy) {}

  /**
   * PUT body. {@code createdBy} (audit T3 / EVO §13 row 4) is OPTIONAL — the optimizer promote path
   * sends {@code 'optimizer:{sweepId}'} (V002:36's stated contract) so provenance is a first-class
   * column, not a free-text note; omitted → {@code 'owner'}.
   */
  public record UpdateRequest(
      String config, String versionBump, String notes, String createdBy) {}

  /** Publish body. */
  public record PublishRequest(String targetVersion, String notes) {}

  /** Rollback body. */
  public record RollbackRequest(String version, Boolean andPublish) {}

  /** Validate body. */
  public record ValidateRequest(String config) {}

  /** Notification toggle body (Phase 41). */
  public record NotificationRequest(Boolean enabled, String channel) {}

  /** Clone body — the new strategy's display name + slug (the config {@code id}). */
  public record CloneRequest(String name, String slug) {}

  /** {items}-enveloped lifecycle history (GET /{id}/audit). */
  public record AuditLogResponse(List<StrategyRepository.AuditRow> items) {}

  private final RegistryService service;

  /** Wires the registry service. */
  public RegistryController(RegistryService service) {
    this.service = service;
  }

  /** Paged list with filters. */
  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false, name = "q") String query,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    int boundedLimit = Math.min(Math.max(limit, 1), 500);
    List<Map<String, Object>> items =
        service.list(status, tag, query, boundedLimit, Math.max(offset, 0));
    return Map.of("items", items, "limit", boundedLimit, "offset", Math.max(offset, 0));
  }

  /** Create as draft 1.0.0. */
  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.create(
                request.name(), request.description(), request.tags(), request.config(),
                request.createdBy()));
  }

  /** Full detail (latest or explicit version). */
  @GetMapping("/{id}")
  public Map<String, Object> get(
      @PathVariable UUID id, @RequestParam(required = false) String version) {
    return service.detail(id, version);
  }

  /** New draft version (auto patch-bump; checksum dedupe). */
  @PutMapping("/{id}")
  public Map<String, Object> update(@PathVariable UUID id, @RequestBody UpdateRequest request) {
    return service.update(
        id, request.config(), request.versionBump(), request.notes(), request.createdBy());
  }

  /** Hard-delete drafts-only; archives otherwise (409 with archived flag). */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  /** Version history. */
  @GetMapping("/{id}/versions")
  public Map<String, Object> versions(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return Map.of("items", service.versions(id, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0)));
  }

  /** Full config at one version. */
  @GetMapping("/{id}/versions/{version}")
  public Map<String, Object> version(@PathVariable UUID id, @PathVariable String version) {
    return service.detail(id, version);
  }

  /** Publish a draft. */
  @PostMapping("/{id}/publish")
  public Map<String, Object> publish(
      @PathVariable UUID id, @RequestBody(required = false) PublishRequest request) {
    return service.publish(
        id,
        request == null ? null : request.targetVersion(),
        request == null ? null : request.notes());
  }

  /** Copy-forward rollback. */
  @PostMapping("/{id}/rollback")
  public ResponseEntity<Map<String, Object>> rollback(
      @PathVariable UUID id, @RequestBody RollbackRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.rollback(
                id, request.version(), Boolean.TRUE.equals(request.andPublish())));
  }

  /** Archive (signal engine unloads). */
  @PostMapping("/{id}/archive")
  public Map<String, Object> archive(@PathVariable UUID id) {
    return service.archive(id);
  }

  /** Toggle notification opt-in (Phase 41) — strategy-level, never mints a version. */
  @PatchMapping("/{id}/notifications")
  public Map<String, Object> notifications(
      @PathVariable UUID id, @RequestBody NotificationRequest request) {
    return service.updateNotifications(id, Boolean.TRUE.equals(request.enabled()), request.channel());
  }

  /** Arm the strategy for the live signal engine (master kill-switch ON). */
  @PostMapping("/{id}/enable")
  public RegistryService.ToggleResult enable(@PathVariable UUID id) {
    return service.setEnabled(id, true);
  }

  /** Disarm the strategy — the live engine unloads it (master kill-switch OFF). */
  @PostMapping("/{id}/disable")
  public RegistryService.ToggleResult disable(@PathVariable UUID id) {
    return service.setEnabled(id, false);
  }

  /** Append-only lifecycle-history timeline (create/edit/publish/rollback/archive/enable/disable). */
  @GetMapping("/{id}/audit")
  public AuditLogResponse audit(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return new AuditLogResponse(
        service.auditLog(id, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0)));
  }

  /** Clone the latest config into a new draft strategy (409 on a duplicate slug/name). */
  @PostMapping("/{id}/clone")
  public ResponseEntity<RegistryService.CloneResult> clone(
      @PathVariable UUID id, @RequestBody CloneRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.clone(id, request.name(), request.slug()));
  }

  /** Server-side structured diff. */
  @GetMapping("/{id}/diff")
  public Map<String, Object> diff(
      @PathVariable UUID id, @RequestParam String from, @RequestParam String to) {
    return service.diff(id, from, to);
  }

  /** Stateless validation (the editor's keystroke-debounced companion). */
  @PostMapping("/validate")
  public Map<String, Object> validate(@RequestBody ValidateRequest request) {
    return service.validate(request.config());
  }

  /** The frozen schema document — served byte-identically (Phase 18 acceptance). */
  @GetMapping(value = "/schema/v1", produces = MediaType.APPLICATION_JSON_VALUE)
  public String schema() {
    return StrategySchemaV1.documentText();
  }
}
