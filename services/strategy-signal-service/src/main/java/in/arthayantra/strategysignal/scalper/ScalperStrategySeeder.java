package in.arthayantra.strategysignal.scalper;

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
 * Seeds the Siva index-option core scalper strategies as registry DRAFTS on boot, idempotently
 * (re-seeding skips any that already exist). Drafts NEVER emit — the owner reviews and publishes each
 * from the UI when ready, so seeding can never auto-start trading. Opt-in via {@code
 * artha.scalper.seed-strategies=true} (default off, so the test/CI Spring contexts and a fresh stack
 * stay clean); flip it in the live/mock {@code .env} to populate the registry.
 */
@Component
@ConditionalOnProperty(value = "artha.scalper.seed-strategies", havingValue = "true")
public class ScalperStrategySeeder {

  private static final Logger log = LoggerFactory.getLogger(ScalperStrategySeeder.class);

  // 2b-1: each of the 12 Siva scalpers forked into 3 instrument-agnostic variants (all signal on
  // NFO/NIFTY-FUT-CONT): -nifty (NIFTY options), -sensex-niftyoi + -sensex-sensexoi (SENSEX options,
  // the two differ only in the OI-confluence gate index — a forward-paper A/B). The 2 ex-BankNifty
  // strategies (gap-theory, trend-change) are re-homed to NIFTY/SENSEX (BankNifty is now monthly-expiry).
  private static final List<String> STRATEGIES =
      List.of(
          "scalp-connect-the-dots-nifty",
          "scalp-connect-the-dots-sensex-niftyoi",
          "scalp-connect-the-dots-sensex-sensexoi",
          "scalp-two-candle-nifty",
          "scalp-two-candle-sensex-niftyoi",
          "scalp-two-candle-sensex-sensexoi",
          "scalp-trending-oi-nifty",
          "scalp-trending-oi-sensex-niftyoi",
          "scalp-trending-oi-sensex-sensexoi",
          "scalp-golden-crossover-nifty",
          "scalp-golden-crossover-sensex-niftyoi",
          "scalp-golden-crossover-sensex-sensexoi",
          "scalp-gap-theory-nifty",
          "scalp-gap-theory-sensex-niftyoi",
          "scalp-gap-theory-sensex-sensexoi",
          "scalp-trend-change-nifty",
          "scalp-trend-change-sensex-niftyoi",
          "scalp-trend-change-sensex-sensexoi",
          "scalp-open-high-low-nifty",
          "scalp-open-high-low-sensex-niftyoi",
          "scalp-open-high-low-sensex-sensexoi",
          "scalp-morning-trade-nifty",
          "scalp-morning-trade-sensex-niftyoi",
          "scalp-morning-trade-sensex-sensexoi",
          "scalp-hero-zero-nifty",
          "scalp-hero-zero-sensex-niftyoi",
          "scalp-hero-zero-sensex-sensexoi",
          "scalp-straddle-nifty",
          "scalp-straddle-sensex-niftyoi",
          "scalp-straddle-sensex-sensexoi",
          "scalp-market-movers-nifty",
          "scalp-market-movers-sensex-niftyoi",
          "scalp-market-movers-sensex-sensexoi",
          "scalp-btst-stbt-nifty",
          "scalp-btst-stbt-sensex-niftyoi",
          "scalp-btst-stbt-sensex-sensexoi");

  private final RegistryService registry;

  /** Wires the registry. */
  public ScalperStrategySeeder(RegistryService registry) {
    this.registry = registry;
  }

  /** On boot: create each core scalper as a draft if it is not already in the registry. */
  @EventListener(ApplicationReadyEvent.class)
  public void seed() {
    int created = 0;
    for (String id : STRATEGIES) {
      try {
        String yaml = load(id);
        JsonNode config = StrategyDocuments.parse(yaml).config();
        List<String> tags = new ArrayList<>();
        config.path("tags").forEach(t -> tags.add(t.asText()));
        registry.create(
            config.path("name").asText(), config.path("description").asText(null), tags, yaml);
        created++;
        log.info("seeded scalper strategy draft: {}", id);
      } catch (ApiException e) {
        if (e.httpStatus() == 409) {
          log.debug("scalper strategy {} already present — skipping", id);
        } else {
          log.warn("scalper strategy {} failed to seed ({}): {}", id, e.code(), e.getMessage());
        }
      } catch (IOException e) {
        log.error("scalper strategy {} resource unreadable — not seeded: {}", id, e.getMessage());
      }
    }
    if (created > 0) {
      log.info(
          "scalper seeder created {} new draft strategies (publish from the UI to go live)", created);
    }
  }

  private String load(String id) throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/scalper-strategies/" + id + ".yaml")) {
      if (in == null) {
        throw new IOException("missing resource /scalper-strategies/" + id + ".yaml");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
