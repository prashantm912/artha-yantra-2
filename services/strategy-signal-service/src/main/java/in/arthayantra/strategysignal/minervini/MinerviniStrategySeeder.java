package in.arthayantra.strategysignal.minervini;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.RegistryService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the four Minervini SEPA swing setups as registry DRAFTS on boot, idempotently (re-seeding
 * skips/re-syncs any that already exist) — the Phase-9 (MV-9.1) mirror of {@link
 * in.arthayantra.strategysignal.scalper.ScalperStrategySeeder}. Each is a {@code session.style=swing}
 * strategy whose universe is the SEPA funnel ({@code mode: minervini_funnel}) and whose entry gate
 * reads the per-symbol seeded VCP geometry; the Phase-9 daily batch evaluates them post-close.
 *
 * <p>Drafts never emit — the owner (or the one-time deploy step) publishes each so the go-live is an
 * explicit, audited action, not a boot side effect. Opt-in via {@code
 * artha.minervini.seed-strategies=true} (default off, so CI/test contexts and a fresh stack stay
 * clean); flip it in the live {@code .env} to populate the registry.
 */
@Component
@ConditionalOnProperty(value = "artha.minervini.seed-strategies", havingValue = "true")
public class MinerviniStrategySeeder {

  private static final Logger log = LoggerFactory.getLogger(MinerviniStrategySeeder.class);

  // The four owner-selected SEPA setups (each publishes as its own strategy so it accrues independent
  // per-setup reliability stats): the VCP-pivot breakout, the 52-week primary base, the earlier cheat
  // (3-C) entry, and the thrust-gated power play. All share the 8%-stop + 50-day-MA-trail exit doctrine.
  private static final List<String> STRATEGIES =
      List.of(
          "minervini-vcp",
          "minervini-primary-base",
          "minervini-cheat-3c",
          "minervini-power-play");

  private final RegistryService registry;

  /** Wires the registry. */
  public MinerviniStrategySeeder(RegistryService registry) {
    this.registry = registry;
  }

  /** On boot: create each swing setup as a draft if it is not already in the registry. */
  @EventListener(ApplicationReadyEvent.class)
  public void seed() {
    int created = 0;
    int resynced = 0;
    for (String id : STRATEGIES) {
      try {
        String yaml = load(id);
        JsonNode config = StrategyDocuments.parse(yaml).config();
        List<String> tags = new ArrayList<>();
        config.path("tags").forEach(t -> tags.add(t.asText()));
        registry.create(
            config.path("name").asText(), config.path("description").asText(null), tags, yaml);
        created++;
        log.info("seeded minervini swing strategy draft: {}", id);
      } catch (ApiException e) {
        if (e.httpStatus() == 409) {
          // Already present — RE-SYNC the config from the repo YAML so a later tweak (exit tiers, gate
          // thresholds) propagates on boot; the D18 checksum dedupe makes it idempotent (no churn on
          // identical content).
          try {
            if (registry.resyncConfig(id, load(id))) {
              resynced++;
              log.info("re-synced minervini swing strategy config: {}", id);
            } else {
              log.debug("minervini strategy {} already present + config current — skipping", id);
            }
          } catch (IOException | RuntimeException re) {
            log.warn("minervini strategy {} config re-sync skipped: {}", id, re.getMessage());
          }
        } else {
          log.warn("minervini strategy {} failed to seed ({}): {}", id, e.code(), e.getMessage());
        }
      } catch (IOException e) {
        log.error("minervini strategy {} resource unreadable — not seeded: {}", id, e.getMessage());
      }
    }
    if (created > 0 || resynced > 0) {
      log.info(
          "minervini seeder: {} new draft strategies created, {} existing re-synced (publish each to"
              + " start live-paper swing generation)",
          created, resynced);
    }
  }

  private String load(String id) throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/minervini-strategies/" + id + ".yaml")) {
      if (in == null) {
        throw new IOException("missing resource /minervini-strategies/" + id + ".yaml");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
