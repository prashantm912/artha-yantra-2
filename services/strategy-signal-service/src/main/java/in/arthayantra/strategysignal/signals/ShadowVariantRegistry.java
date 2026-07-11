package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.signals.ShadowVariantRegistryRepository.RegistryRow;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The LIVE active shadow-challenger set + its runtime registration control plane (EVO E3 §11).
 *
 * <p>The engine's active challenger variants = the boot-time env-JSON fallback ({@link ShadowVariants})
 * MERGED with the ENABLED {@code shadow_variant_registry} rows, <b>DB wins on a name collision</b>.
 * The set is an IMMUTABLE list held behind a single {@code volatile} reference: the rejection-path
 * eval thread reads it with one volatile load ({@link #active()}), and a register/retire rebuilds a
 * fresh immutable list and swaps the reference in one write. The eval thread therefore always sees a
 * COMPLETE set (the old one or the new one) — never a torn/partial state — and registration failures
 * never disturb the running set (the swap only happens after a successful, fully-built rebuild). No
 * restart is needed; the env JSON remains the durable fallback if the DB read ever fails.
 *
 * <p><b>Relaxing-or-neutral gate (§3.3.3).</b> Today's shadow writer fires on the REJECTION path only
 * — champion-<i>accepted</i> entries never reach variant scoring — so a knob-set that <i>tightens</i>
 * relative to the champion has no paired evidence plane (the entries it would additionally block are
 * the champion's fired signals, invisible to the shadow book). Registration therefore restricts
 * candidates to relaxing-or-neutral knob-sets, enforced per knob kind (see {@link
 * #enforceRelaxingOrNeutral}). The accepted-entry shadow extension that would lift this is an explicit
 * follow-up (§11, HOLD), NOT this change.
 */
@Component
public class ShadowVariantRegistry {

  private static final Logger log = LoggerFactory.getLogger(ShadowVariantRegistry.class);

  private final ShadowVariants envFallback;
  private final ShadowVariantRegistryRepository repo;
  private final ObjectMapper mapper;

  /**
   * Safety ceiling on the TOTAL active set (env fallback ∪ enabled DB rows). A global guard bounding
   * the per-bar re-scoring + DB-write cost; the evo orchestrator enforces the tighter §3.1 per-family
   * / per-strategy budget on top of this.
   */
  private final int maxVariants;

  /**
   * The champion composite-threshold reference for the relaxing-or-neutral gate. A variant composite
   * FLOOR strictly above this is presumed tightening (§3.3.3). Default {@code 0.60} = the documented
   * MIN scalper champion threshold, which makes "floor ≤ ref" a relaxing-or-neutral guarantee for
   * EVERY scalper (variants are global, not per-strategy); raise it only if the min champion threshold
   * across active scalpers rises.
   */
  private final BigDecimal championCompositeRef;

  /** Immutable, atomically-swapped. The eval thread reads this with a single volatile load. */
  private volatile List<ShadowVariants.Variant> active = List.of();

  /** Wires the env fallback + the durable registry; builds the initial active set (fail-soft). */
  public ShadowVariantRegistry(
      ShadowVariants envFallback,
      ShadowVariantRegistryRepository repo,
      ObjectMapper mapper,
      @Value("${artha.scalper.shadow-book.max-variants:16}") int maxVariants,
      @Value("${artha.scalper.shadow-book.champion-composite-threshold:0.60}")
          BigDecimal championCompositeRef) {
    this.envFallback = envFallback;
    this.repo = repo;
    this.mapper = mapper;
    this.maxVariants = maxVariants;
    this.championCompositeRef = championCompositeRef;
    reload();
  }

  /** The live active challenger set (immutable). One volatile load — never a torn set. */
  public List<ShadowVariants.Variant> active() {
    return active;
  }

  /**
   * Rebuilds the immutable active set from the env fallback + enabled DB rows and swaps it in with a
   * single volatile write. Fail-soft: a DB hiccup or an unparsable row degrades to the env fallback
   * (a broken experiment config must never break the live signal path). Synchronized so concurrent
   * register/retire calls rebuild serially; the hot eval path is lock-free (it only reads the field).
   */
  public final synchronized void reload() {
    LinkedHashMap<String, ShadowVariants.Variant> byName = new LinkedHashMap<>();
    for (ShadowVariants.Variant v : envFallback.all()) {
      byName.put(v.name(), v);
    }
    try {
      for (RegistryRow row : repo.findEnabled()) {
        try {
          byName.put(row.name(), ShadowVariants.validatedFromSpec(mapper, row.name(), row.spec()));
        } catch (RuntimeException e) {
          log.warn("shadow-variant registry: skipping unparsable row '{}': {}", row.name(), e.toString());
        }
      }
    } catch (RuntimeException e) {
      log.error("shadow-variant registry DB read failed — using env fallback only: {}", e.toString());
    }
    this.active = List.copyOf(byName.values());
  }

  /**
   * Registers a challenger variant at runtime, then hot-reloads the active set. Synchronized so the
   * validate → uniqueness → cap → insert sequence is atomic within the process (no two concurrent
   * registrations can both slip past the cap). Rejects, in order: an unknown knob kind / bad name /
   * bad rail shape (422 {@code VALIDATION_FAILED}); a tightening knob-set (422 {@code
   * EVIDENCE_PLANE_UNSUPPORTED}); a name already used (409 — names are immutable); a full active set
   * (422 {@code CONFLICT_SHADOW_VARIANT_CAP}).
   */
  public synchronized RegistryRow register(
      String name, UUID campaignId, JsonNode spec, String createdBy) {
    String trimmed = name == null ? null : name.trim();

    ShadowVariants.Variant variant;
    try {
      variant = ShadowVariants.validatedFromSpec(mapper, trimmed, spec);
    } catch (IllegalArgumentException e) {
      throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, e.getMessage());
    }

    enforceRelaxingOrNeutral(variant);

    if (repo.existsByName(variant.name())) {
      throw new ApiException(
          409,
          "CONFLICT_SHADOW_VARIANT_EXISTS",
          "a shadow variant named '"
              + variant.name()
              + "' already exists — names are immutable (retire never frees a name; the book"
              + " references it), register under a new name");
    }

    Set<String> activeNames = new HashSet<>();
    for (ShadowVariants.Variant v : envFallback.all()) {
      activeNames.add(v.name());
    }
    for (RegistryRow r : repo.findEnabled()) {
      activeNames.add(r.name());
    }
    int existing = activeNames.size();
    activeNames.add(variant.name());
    if (activeNames.size() > maxVariants) {
      throw new ApiException(
          422,
          "CONFLICT_SHADOW_VARIANT_CAP",
          "active shadow-variant cap "
              + maxVariants
              + " reached ("
              + existing
              + " active) — retire one before registering another",
          Map.of("cap", maxVariants, "active", existing));
    }

    repo.insert(variant.name(), campaignId, spec, createdBy);
    reload();
    return repo.findByName(variant.name())
        .orElseThrow(() -> new IllegalStateException("registered variant vanished: " + variant.name()));
  }

  /**
   * The per-knob-kind relaxing-or-neutral rules (§3.3.3). A tightening knob throws 422 {@code
   * EVIDENCE_PLANE_UNSUPPORTED}.
   *
   * <ul>
   *   <li><b>rail disable</b> — always relaxing-or-neutral: a disabled rail can only turn a champion
   *       FAIL into a variant pass, never the reverse. Allowed unconditionally.
   *   <li><b>composite floor</b> — tightening iff strictly ABOVE {@link #championCompositeRef} (the
   *       design's canonical example: "a composite floor above the strategy's current threshold").
   *       Enforced here.
   *   <li><b>rail threshold override</b> — whether it relaxes or tightens depends on the champion's
   *       per-strategy, per-bar LIVE threshold for that rail, which is NOT resolvable at registration
   *       (variants are global; the champion thresholds live in each strategy's YAML). v1 therefore
   *       validates the override STRUCTURALLY (rail + threshold + a GTE|LTE polarity, via {@link
   *       ShadowVariants#validatedFromSpec}) but does NOT statically enforce the relaxing DIRECTION —
   *       that guarantee is the registrant's contract (the evo orchestrator only submits loosening
   *       thresholds) plus the runtime rejection-only plane, which bounds a tightening override to
   *       re-scoring the already-rejected stream. This is a deliberate v1 limitation (open-doubt),
   *       lifted by the accepted-entry shadow extension (§11, HOLD).
   * </ul>
   */
  private void enforceRelaxingOrNeutral(ShadowVariants.Variant variant) {
    BigDecimal floor = variant.compositeThreshold();
    if (floor != null && floor.compareTo(championCompositeRef) > 0) {
      throw new ApiException(
          422,
          "EVIDENCE_PLANE_UNSUPPORTED",
          "composite floor "
              + floor
              + " is above the champion reference "
              + championCompositeRef
              + " — a tightening variant has NO paired evidence plane on the rejection-only shadow"
              + " writer (§3.3.3); route tightening candidates through counterfactual replay + the"
              + " paper A/B lane instead",
          Map.of("knob", "compositeThreshold", "value", floor, "championRef", championCompositeRef));
    }
  }

  /** Soft-disables a variant, hot-reloading if it changed. Returns rows updated (0 = absent/already retired). */
  public synchronized int disable(String name) {
    int updated = repo.disable(name);
    if (updated > 0) {
      reload();
    }
    return updated;
  }

  /** True when a row with this name exists (enabled or retired). */
  public boolean exists(String name) {
    return repo.existsByName(name);
  }

  /** Every registry row (enabled + retired), newest first — the API list surface. */
  public List<RegistryRow> list() {
    return repo.findAll();
  }
}
