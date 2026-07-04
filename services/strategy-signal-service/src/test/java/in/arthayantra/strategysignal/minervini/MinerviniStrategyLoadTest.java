package in.arthayantra.strategysignal.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every seeded Minervini swing setup (Phase-9 MV-9.1) must be schema-valid, compile into an engine
 * definition, and carry the Phase-9 shape: a {@code minervini_funnel} universe, a {@code swing}
 * session, a {@code 1d} primary, and the 8%-stop + 50-day-MA-trail exit doctrine. Pure: no DB, no
 * market-data — the classpath resources ARE the docs the seeder loads, so this list MUST stay in
 * lockstep with {@link MinerviniStrategySeeder}'s STRATEGIES.
 */
class MinerviniStrategyLoadTest {

  private static final List<String> STRATEGIES =
      List.of("minervini-vcp", "minervini-primary-base", "minervini-cheat-3c", "minervini-power-play");

  private static String yaml(String id) throws IOException {
    try (InputStream in =
        MinerviniStrategyLoadTest.class.getResourceAsStream("/minervini-strategies/" + id + ".yaml")) {
      assertThat(in).as("classpath resource for " + id).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void everyMinerviniSwingSetupIsSchemaValidCompilesAndCarriesTheExitDoctrine() throws IOException {
    for (String id : STRATEGIES) {
      String body = yaml(id);

      var result = StrategyDocuments.validate(body);
      assertThat(result.valid()).as(id + " must be schema-valid; errors=" + result.errors()).isTrue();

      JsonNode config = StrategyDocuments.parse(body).config();
      assertThat(config.path("id").asText()).as(id + " slug").isEqualTo(id);
      assertThat(config.path("universe").path("mode").asText())
          .as(id + " universe = the SEPA funnel")
          .isEqualTo("minervini_funnel");
      assertThat(config.path("risk").path("session").path("style").asText())
          .as(id + " is a swing (no intraday square-off)")
          .isEqualTo("swing");

      StrategyDefinition def = StrategyCompiler.compile(config);
      assertThat(def.primaryTimeframe()).as(id + " is daily-primary").isEqualTo("1d");

      List<String> exitTypes = new ArrayList<>();
      config.path("exit_rules").forEach(e -> exitTypes.add(e.path("type").asText()));
      assertThat(exitTypes)
          .as(id + " carries the 8%-stop + 50-day-MA-trail exit doctrine")
          .containsExactly("stop_loss", "trailing_stop");

      List<String> tags = new ArrayList<>();
      config.path("tags").forEach(t -> tags.add(t.asText()));
      assertThat(tags).as(id + " is tagged minervini + swing").contains("minervini", "swing");
    }
  }
}
