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
import org.springframework.scheduling.annotation.Scheduled;
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
 * the champion's fired signals, invisible to the shadow book). Enforcement is two-layer: registration
 * rejects a tightening composite floor ({@link #enforceRelaxingOrNeutral}), and rail-threshold
 * overrides are CLAMPED per bar at evaluation against the champion's own recorded threshold
 * ({@code ShadowVariants.accepts}) so they can only relax — a mixed relax+tighten spec degrades to
 * "the tightened rail behaves as champion". The accepted-entry shadow extension that would lift the
 * restriction entirely is an explicit follow-up (§11, HOLD), NOT this change.
 *
 * <p><b>Scope note (variants are GLOBAL).</b> A registered variant applies to every scalper's
 * rejection stream — {@code campaign_id} is provenance, not an application filter. Campaign isolation
 * = distinct variant names + the per-strategy league read
 * ({@code GET /api/v1/signal-rejections/shadow-summary?strategySlug=...}). Scoping application by
 * campaign inside the live writer is deliberately out of v1.
 */
@Component
public class ShadowVariantRegistry {

  private static final Logger log = LoggerFactory.getLogger(ShadowVariantRegistry.class);

  private final ShadowVariants envFallback;
  private final ShadowVariantRegistryRepository repo;
  private final ObjectMapper mapper;

  /**
   * Safety ceiling on the TOTAL active set (env fallback ∪ enabled DB rows). A global guard (default
   * 16) bounding the per-bar re-scoring + DB-write cost — it is NOT the design's §3.1 "≤ 6 concurrent
   * challenger variants per strategy" budget, which is enforced ORCHESTRATOR-side (optimizer-service)
   * when the evo loop lands; this ceiling only stops a runaway registrant.
   */
  private final int maxVariants;

  /**
   * The champion composite-threshold reference for the relaxing-or-neutral gate. A variant composite
   * FLOOR strictly above this is tightening (§3.3.3). Default {@code 0.60} MIRRORS
   * {@code ScalperConfig.THRESHOLD} ("0.6", the hardcoded UNIVERSAL v1 confluence threshold every
   * scalper gate runs — not per-strategy YAML), so "floor ≤ ref" is an exact relaxing-or-neutral
   * guarantee for every scalper. If {@code ScalperConfig.THRESHOLD} ever changes or goes
   * per-strategy, change BOTH sites together (a comment there points back here); the env override is
   * {@code artha.scalper.shadow-book.champion-composite-threshold}.
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
   * single volatile write. Fail-soft, never destructive: an unparsable row is skipped; a DB READ
   * failure keeps the PRIOR active set (a transient hiccup must not silently evict every registered
   * challenger mid-session), except at boot — no prior set exists, so it degrades to the env fallback
   * and the periodic {@link #reconcile()} heals it once the DB answers. Synchronized so concurrent
   * register/retire/reconcile calls rebuild serially; the hot eval path is lock-free (one volatile
   * read).
   */
  public final synchronized void reload() {
    LinkedHashMap<String, ShadowVariants.Variant> byName = new LinkedHashMap<>();
    for (ShadowVariants.Variant v : envFallback.all()) {
      byName.put(v.name(), v);
    }
    List<RegistryRow> rows;
    try {
      rows = repo.findEnabled();
    } catch (RuntimeException e) {
      if (active.isEmpty()) {
        this.active = List.copyOf(byName.values());
        log.error(
            "shadow-variant registry DB read failed at boot — env fallback only until the"
                + " reconcile heals it: {}",
            e.toString());
      } else {
        log.error(
            "shadow-variant registry DB read failed — keeping the prior active set: {}",
            e.toString());
      }
      return;
    }
    for (RegistryRow row : rows) {
      try {
        byName.put(row.name(), ShadowVariants.validatedFromSpec(mapper, row.name(), row.spec()));
      } catch (RuntimeException e) {
        log.warn("shadow-variant registry: skipping unparsable row '{}': {}", row.name(), e.toString());
      }
    }
    List<ShadowVariants.Variant> next = List.copyOf(byName.values());
    if (!names(next).equals(names(active))) {
      log.info("shadow-variant active set now: {}", names(next));
    }
    this.active = next;
  }

  /**
   * Periodic self-heal (default 5 min, {@code artha.scalper.shadow-book.registry-reconcile-ms}): a
   * reload lost to a crash between the DB commit and the in-process swap — or a boot-time DB outage —
   * must not let a retired variant keep trading (or a registered one stay dark) indefinitely. Quiet
   * on no-change ({@link #reload()} only logs when the name set differs). Engine-independent — safe
   * in engine-disabled paper contexts (#634).
   */
  @Scheduled(fixedDelayString = "${artha.scalper.shadow-book.registry-reconcile-ms:300000}")
  public void reconcile() {
    reload();
  }

  private static List<String> names(List<ShadowVariants.Variant> variants) {
    return variants.stream().map(ShadowVariants.Variant::name).toList();
  }

  /**
   * Registers a challenger variant at runtime, then hot-reloads the active set. Synchronized so the
   * validate → uniqueness → cap → insert sequence is atomic within the process (no two concurrent
   * registrations can both slip past the cap). Rejects, in order: an unknown knob kind / bad name /
   * bad rail shape (422 {@code VALIDATION_FAILED}); a tightening knob-set (422 {@code
   * EVIDENCE_PLANE_UNSUPPORTED}); a name already used in the MERGED namespace — env fallback OR any
   * DB row (409 — names are immutable); a full active set (422 {@code CONFLICT_SHADOW_VARIANT_CAP}).
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

    // Uniqueness spans the MERGED namespace: an env-fallback name would be silently shadowed by the
    // DB row (DB wins on collision) — reject instead so the env definition stays authoritative.
    if (envFallback.all().stream().anyMatch(v -> v.name().equals(variant.name()))) {
      throw new ApiException(
          409,
          "CONFLICT_SHADOW_VARIANT_EXISTS",
          "'"
              + variant.name()
              + "' is defined by the boot-time env fallback"
              + " (artha.scalper.shadow-book.variants-json) — pick a different name");
    }
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

    // The insert's RETURNING row IS the result — no re-read (a transient hiccup on a re-read would
    // 500 a fully-committed, already-live registration, and the confused retry then 409s).
    RegistryRow row = repo.insert(variant.name(), campaignId, spec, createdBy);
    reload();
    return row;
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
   *       Enforced statically here — the champion composite threshold IS resolvable at registration
   *       ({@code ScalperConfig.THRESHOLD} is universal).
   *   <li><b>rail threshold override</b> — the champion's per-strategy, per-bar threshold is NOT
   *       resolvable at registration, so the direction is enforced at EVALUATION instead: {@code
   *       ShadowVariants.accepts} clamps every override against the champion's recorded per-bar
   *       threshold (floors may only lower, caps may only raise) — a tightening override degrades to
   *       champion behaviour on that rail, per strategy and per bar. Registration validates the
   *       override structurally (rail + threshold + GTE|LTE, via {@link
   *       ShadowVariants#validatedFromSpec}).
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
