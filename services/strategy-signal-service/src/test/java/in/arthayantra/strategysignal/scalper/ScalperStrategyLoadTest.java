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
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every seeded scalper strategy (Siva index-option core) must be schema-valid, compile into an
 * engine definition, and carry the §12.3 seam wiring — the Phase-3 exit-gate fixture. Pure: no DB,
 * no market-data; the classpath resources ARE the authoritative strategy docs the seeder loads.
 */
class ScalperStrategyLoadTest {

  private static final List<String> STRATEGIES =
      List.of(
          "scalp-connect-the-dots-nifty",
          "scalp-two-candle-nifty",
          "scalp-trending-oi-nifty",
          "scalp-golden-crossover-nifty");

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
    for (String id : STRATEGIES) {
      String body = yaml(id);

      var result = StrategyDocuments.validate(body);
      assertThat(result.valid()).as(id + " must be schema-valid; errors=" + result.errors()).isTrue();

      JsonNode config = StrategyDocuments.parse(body).config();
      assertThat(config.path("id").asText()).as(id + " slug").isEqualTo(id);
      assertThat(config.path("universe").path("mode").asText()).isEqualTo("options_of_underlying");

      List<String> tags = new ArrayList<>();
      config.path("tags").forEach(t -> tags.add(t.asText()));
      assertThat(tags).as(id + " must be tagged scalper (engine detection)").contains("scalper");

      StrategyDefinition def = StrategyCompiler.compile(config);
      assertThat(def.primaryTimeframe()).as(id + " scalps on 3m").isEqualTo("3m");

      ScalperConfig cfg = ScalperConfig.from(config.path("universe"));
      assertThat(cfg.underlying()).isEqualTo("NIFTY 50");
      assertThat(cfg.strikeParams().deltaLo()).isEqualTo(0.6);
      assertThat(cfg.confluenceThreshold()).isEqualByComparingTo("0.6");

      Set<String> declared = new HashSet<>();
      config.path("indicators").forEach(i -> declared.add(i.path("alias").asText()));
      assertThat(declared).as(id + " declares the seam aliases").containsAll(SEAM_ALIASES);
    }
  }
}
