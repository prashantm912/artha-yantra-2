package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every seeded scalper strategy (Siva index-option core + derived #4/#12/#2/#9) must be schema-valid,
 * compile into an engine definition, and carry the §12.3 seam wiring — the Phase-3 exit-gate fixture.
 * Pure: no DB, no market-data; the classpath resources ARE the authoritative strategy docs the seeder
 * loads, so this list MUST stay in lockstep with {@link ScalperStrategySeeder}'s STRATEGIES.
 */
class ScalperStrategyLoadTest {

  // The full seeded set: 4 core (NIFTY) + 4 derived (#4/#12 BANKNIFTY, #2/#9 NIFTY). Each maps to its
  // expected underlying so the per-index ScalperConfig assertions hold across both indices.
  private static final Map<String, String> UNDERLYING =
      Map.of(
          "scalp-connect-the-dots-nifty", "NIFTY 50",
          "scalp-two-candle-nifty", "NIFTY 50",
          "scalp-trending-oi-nifty", "NIFTY 50",
          "scalp-golden-crossover-nifty", "NIFTY 50",
          "scalp-gap-theory-banknifty", "NIFTY BANK",
          "scalp-trend-change-banknifty", "NIFTY BANK",
          "scalp-open-high-low-nifty", "NIFTY 50",
          "scalp-morning-trade-nifty", "NIFTY 50",
          "scalp-hero-zero-nifty", "NIFTY 50");

  // Each derived strategy must carry the tag that arms its §12.3 gate (the seeder reads the same tag).
  private static final Map<String, String> EXPECTED_TAG =
      Map.of(
          "scalp-gap-theory-banknifty", "gap-theory",
          "scalp-trend-change-banknifty", "trend-change",
          "scalp-open-high-low-nifty", "open-high-low",
          "scalp-morning-trade-nifty", "opening-tick",
          "scalp-hero-zero-nifty", "hero-zero");

  // the aliases ScalperConfluenceGate reads off the bank — each strategy must declare all four.
  private static final Set<String> SEAM_ALIASES = Set.of("vwma20", "psar", "rsi14", "supertrend");

  private static String yaml(String id) throws IOException {
    try (InputStream in =
        ScalperStrategyLoadTest.class.getResourceAsStream("/scalper-strategies/" + id + ".yaml")) {
      assertThat(in).as("classpath resource for " + id).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void everyScalperStrategyIsSchemaValidCompilesAndIsSeamWired() throws IOException {
    for (String id : UNDERLYING.keySet()) {
      String body = yaml(id);

      var result = StrategyDocuments.validate(body);
      assertThat(result.valid()).as(id + " must be schema-valid; errors=" + result.errors()).isTrue();

      JsonNode config = StrategyDocuments.parse(body).config();
      assertThat(config.path("id").asText()).as(id + " slug").isEqualTo(id);
      assertThat(config.path("universe").path("mode").asText()).isEqualTo("options_of_underlying");

      List<String> tags = new ArrayList<>();
      config.path("tags").forEach(t -> tags.add(t.asText()));
      assertThat(tags).as(id + " must be tagged scalper (engine detection)").contains("scalper");

      // Each derived strategy carries the tag that arms its §12.3 gate (gap-theory / trend-change /
      // open-high-low / opening-tick); the seeder reads the same tag onto the registry draft.
      String expectedTag = EXPECTED_TAG.get(id);
      if (expectedTag != null) {
        assertThat(tags).as(id + " must carry its gate tag").contains(expectedTag);
      }

      StrategyDefinition def = StrategyCompiler.compile(config);
      assertThat(def.primaryTimeframe()).as(id + " scalps on 3m").isEqualTo("3m");

      ScalperConfig cfg = ScalperConfig.from(config.path("universe"), tags);
      assertThat(cfg.underlying()).as(id + " underlying").isEqualTo(UNDERLYING.get(id));
      assertThat(cfg.strikeParams().deltaLo()).isEqualTo(0.6);
      assertThat(cfg.confluenceThreshold()).isEqualByComparingTo("0.6");

      Set<String> declared = new HashSet<>();
      config.path("indicators").forEach(i -> declared.add(i.path("alias").asText()));
      assertThat(declared).as(id + " declares the seam aliases").containsAll(SEAM_ALIASES);

      // #5 (T2.1): only scalp-trending-oi carries the oi-cross-filter tag → the HARD call-put dOI
      // pre-gate. ScalperConfig.requireCallPutDeltaFilter mirrors the tag; the others stay off.
      boolean isTrendingOi = id.equals("scalp-trending-oi-nifty");
      assertThat(cfg.requireCallPutDeltaFilter())
          .as(id + " oi-cross-filter pre-gate")
          .isEqualTo(isTrendingOi);
    }
  }
}
