package in.arthayantra.strategysignal.manas;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the Manas Arora swing setups on boot, idempotently, and PUBLISHES each so the daily batch can
 * evaluate them right away — the sibling of {@link
 * in.arthayantra.strategysignal.minervini.MinerviniStrategySeeder}, which differs by leaving its drafts
 * for the owner to publish. Boot flow per strategy: create-if-absent (or re-sync the config from the
 * repo YAML on a change — the D18 checksum dedupe makes it idempotent), then publish the latest draft;
 * a strategy already published + current has no draft to publish, so the publish step is a no-op.
 *
 * <p>Seeding + publishing NEVER starts trading on its own: the daily engine is separately gated on
 * {@code artha.manas-arora.swing.enabled} (default off), so a published strategy only fires once the
 * owner arms that flag. Opt-in via {@code artha.manas-arora.seed-strategies=true} (default off, so
 * CI/test contexts and a fresh stack stay clean); flip it in the live {@code .env} to populate + publish.
 */
@Component
@ConditionalOnProperty(value = "artha.manas-arora.seed-strategies", havingValue = "true")
public class ManasAroraStrategySeeder {

  private static final Logger log = LoggerFactory.getLogger(ManasAroraStrategySeeder.class);

  // The Manas swing setups: the base-pivot breakout and the tighter VCP-base variant. Both share the
  // ~10%-stop + 20-day-MA-trail exit doctrine and the manas_arora_funnel universe.
  private static final List<String> STRATEGIES = List.of("manas-arora-breakout", "manas-arora-vcp");

  private final RegistryService registry;
  private final StrategyRepository repository;

  /** Wires the registry service + repository (the repo resolves slug→id for the publish step). */
  public ManasAroraStrategySeeder(RegistryService registry, StrategyRepository repository) {
    this.registry = registry;
    this.repository = repository;
  }

  /** On boot: create/re-sync each swing setup and publish its latest draft. */
  @EventListener(ApplicationReadyEvent.class)
  public void seed() {
    int created = 0;
    int resynced = 0;
    int published = 0;
    for (String id : STRATEGIES) {
      try {
        String yaml = load(id);
        JsonNode config = StrategyDocuments.parse(yaml).config();
        List<String> tags = new ArrayList<>();
        config.path("tags").forEach(t -> tags.add(t.asText()));
        registry.create(
            config.path("name").asText(), config.path("description").asText(null), tags, yaml);
        created++;
        log.info("seeded manas swing strategy draft: {}", id);
      } catch (ApiException e) {
        if (e.httpStatus() == 409) {
          // Already present — RE-SYNC the config from the repo YAML so a later tweak (exit tiers, gate
          // thresholds) propagates on boot; the D18 checksum dedupe makes it idempotent (no churn on
          // identical content).
          try {
            if (registry.resyncConfig(id, load(id))) {
              resynced++;
              log.info("re-synced manas swing strategy config: {}", id);
            } else {
              log.debug("manas strategy {} already present + config current — skipping resync", id);
            }
          } catch (IOException | RuntimeException re) {
            log.warn("manas strategy {} config re-sync skipped: {}", id, re.getMessage());
          }
        } else {
          log.warn("manas strategy {} failed to seed ({}): {}", id, e.code(), e.getMessage());
        }
      } catch (IOException e) {
        log.error("manas strategy {} resource unreadable — not seeded: {}", id, e.getMessage());
      }
      if (publishLatestDraft(id)) {
        published++;
      }
    }
    if (created > 0 || resynced > 0 || published > 0) {
      log.info(
          "manas seeder: {} new drafts, {} re-synced, {} published (batch fires once"
              + " artha.manas-arora.swing.enabled is on)",
          created, resynced, published);
    }
  }

  /**
   * Publishes the strategy's latest draft, if it has one. A strategy already published + config-current
   * has no draft, so this is a no-op (the publish call would 409 "no draft to publish"); a fresh
   * create or a re-synced change leaves a draft that becomes the published version.
   */
  private boolean publishLatestDraft(String slug) {
    Optional<StrategyRepository.StrategyRow> strategy = repository.findBySlug(slug);
    if (strategy.isEmpty()) {
      return false;
    }
    UUID id = strategy.get().id();
    if (repository.latestDraft(id).isEmpty()) {
      return false; // nothing to publish (already published + current)
    }
    try {
      registry.publish(id, null, "manas seeder auto-publish");
      log.info("published manas swing strategy: {}", slug);
      return true;
    } catch (ApiException e) {
      log.warn("manas strategy {} publish skipped ({}): {}", slug, e.code(), e.getMessage());
      return false;
    }
  }

  private String load(String id) throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/manas-arora-strategies/" + id + ".yaml")) {
      if (in == null) {
        throw new IOException("missing resource /manas-arora-strategies/" + id + ".yaml");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
