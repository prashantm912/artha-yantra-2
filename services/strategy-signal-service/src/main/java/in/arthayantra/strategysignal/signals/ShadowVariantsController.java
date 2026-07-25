package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.signals.ShadowVariantRegistryRepository.RegistryRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime shadow-challenger-variant registration API (EVO E3 §11). Lets the evolution engine register
 * and retire challenger knob-sets per campaign WITHOUT a redeploy — the durable complement to the
 * boot-time env JSON ({@code artha.scalper.shadow-book.variants-json}), which remains the fallback.
 * All work is delegated to {@link ShadowVariantRegistry} (validation, the relaxing-or-neutral gate,
 * the cap, the atomic hot-reload). Typed records throughout (never {@code Map<String,Object>}).
 */
@RestController
@RequestMapping("/api/v1/shadow-variants")
public class ShadowVariantsController {

  /** One rail override in the response (echoes the stored vocabulary body). */
  public record RailOverrideView(
      String rail,
      boolean disable,
      @Schema(types = {"number", "null"}) BigDecimal threshold,
      @Schema(types = {"string", "null"}) String passWhen) {}

  /** The variant vocabulary body (the {@code spec} column), typed for the contract. */
  public record VariantSpecView(
      @Schema(types = {"array", "null"}) List<RailOverrideView> rails,
      @Schema(types = {"number", "null"}) BigDecimal compositeThreshold) {}

  /** One registered variant. */
  public record ShadowVariantView(
      UUID id,
      String name,
      @Schema(types = {"string", "null"}) UUID campaignId,
      VariantSpecView spec,
      boolean enabled,
      @Schema(types = {"string", "null"}) String createdBy,
      OffsetDateTime createdAt,
      @Schema(types = {"string", "null"}) OffsetDateTime disabledAt) {}

  /** The list envelope ({items} — the repo-wide list convention). */
  public record ShadowVariantListResponse(List<ShadowVariantView> items) {}

  /**
   * POST body. {@code name} is the immutable variant tag; {@code campaignId} the OPTIONAL soft
   * campaign ref; {@code spec} the vocabulary BODY ({@code {rails, compositeThreshold}}, strict —
   * unknown knobs 422); {@code createdBy} the OPTIONAL machine-writer actor (e.g. {@code
   * 'evo:{campaignId}'}).
   */
  public record RegisterRequest(String name, UUID campaignId, JsonNode spec, String createdBy) {}

  private final ShadowVariantRegistry registry;
  private final ObjectMapper mapper;

  /** Wires the registry control plane + the JSON codec for the spec projection. */
  public ShadowVariantsController(ShadowVariantRegistry registry, ObjectMapper mapper) {
    this.registry = registry;
    this.mapper = mapper;
  }

  /**
   * Registers a challenger variant and hot-reloads the live set (201 with the created row). The
   * variant applies GLOBALLY — it re-scores every scalper's rejection stream; {@code campaignId} is
   * provenance, not an application filter. Campaign isolation = a distinct name per campaign + the
   * per-strategy league read ({@code GET /api/v1/signal-rejections/shadow-summary?strategySlug=...}).
   */
  @PostMapping
  public ResponseEntity<ShadowVariantView> register(@RequestBody RegisterRequest request) {
    RegistryRow row =
        registry.register(request.name(), request.campaignId(), request.spec(), request.createdBy());
    return ResponseEntity.status(HttpStatus.CREATED).body(view(row));
  }

  /** Every registered variant (enabled + retired), newest first. */
  @GetMapping
  public ShadowVariantListResponse list() {
    return new ShadowVariantListResponse(registry.list().stream().map(this::view).toList());
  }

  /**
   * Retires (soft-disables) a variant by name and hot-reloads the live set. 204 whether it was
   * enabled (retired now) or already retired (idempotent); 404 when the name was never registered.
   * History stays — the row and its book are preserved; an OPEN position tagged with a retired
   * variant still settles normally ({@code ShadowExitMonitor} sweeps by status, not variant).
   *
   * <p><b>Retire is TERMINAL, not pause:</b> the name can never be re-enabled or re-registered
   * (names are immutable — the book references them). "DELETE then re-register under a new name" is
   * LOSSY by design: the new name starts a NEW book with zero history. Don't retire to pause an
   * experiment.
   */
  @DeleteMapping("/{name}")
  public ResponseEntity<Void> retire(@PathVariable String name) {
    if (registry.disable(name) == 0 && !registry.exists(name)) {
      throw new ApiException(
          404, ErrorCodes.NOT_FOUND_RESOURCE, "no shadow variant named '" + name + "'");
    }
    return ResponseEntity.noContent().build();
  }

  private ShadowVariantView view(RegistryRow row) {
    VariantSpecView spec = mapper.convertValue(row.spec(), VariantSpecView.class);
    return new ShadowVariantView(
        row.id(),
        row.name(),
        row.campaignId(),
        spec,
        row.enabled(),
        row.createdBy(),
        row.createdAt(),
        row.disabledAt());
  }
}
