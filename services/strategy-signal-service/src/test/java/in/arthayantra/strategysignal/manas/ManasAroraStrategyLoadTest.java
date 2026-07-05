package in.arthayantra.strategysignal.manas;

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
 * Every seeded Manas Arora swing setup must be schema-valid, compile into an engine definition, and
 * carry the live-swing shape: a {@code manas_arora_funnel} universe, a {@code swing} session, a
 * {@code 1d} primary, and the §3.5 exit doctrine — a 2×ATR(20) stop capped ~10%, an armed 2×ATR trail,
 * and a too-fast/parabolic square-off. Pure: no DB, no market-data — the classpath resources ARE the
 * docs the seeder loads, so this list MUST stay in lockstep with {@link ManasAroraStrategySeeder}'s
 * STRATEGIES. Mirrors {@code MinerviniStrategyLoadTest}.
 */
class ManasAroraStrategyLoadTest {

  private static final List<String> STRATEGIES = List.of("manas-arora-breakout", "manas-arora-vcp");

  private static String yaml(String id) throws IOException {
    try (InputStream in =
        ManasAroraStrategyLoadTest.class.getResourceAsStream(
            "/manas-arora-strategies/" + id + ".yaml")) {
      assertThat(in).as("classpath resource for " + id).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void everyManasSwingSetupIsSchemaValidCompilesAndCarriesTheExitDoctrine() throws IOException {
    for (String id : STRATEGIES) {
      String body = yaml(id);

      var result = StrategyDocuments.validate(body);
      assertThat(result.valid()).as(id + " must be schema-valid; errors=" + result.errors()).isTrue();

      JsonNode config = StrategyDocuments.parse(body).config();
      assertThat(config.path("id").asText()).as(id + " slug").isEqualTo(id);
      assertThat(config.path("universe").path("mode").asText())
          .as(id + " universe = the Manas selection funnel")
          .isEqualTo("manas_arora_funnel");
      assertThat(config.path("risk").path("session").path("style").asText())
          .as(id + " is a swing (no intraday square-off)")
          .isEqualTo("swing");

      StrategyDefinition def = StrategyCompiler.compile(config);
      assertThat(def.primaryTimeframe()).as(id + " is daily-primary").isEqualTo("1d");
      assertThat(def.sizing().method()).as(id + " sizes by atr_risk").isEqualTo("atr_risk");

      List<JsonNode> exitRules = new ArrayList<>();
      config.path("exit_rules").forEach(exitRules::add);
      List<String> exitTypes = exitRules.stream().map(e -> e.path("type").asText()).toList();
      assertThat(exitTypes)
          .as(id + " carries the §3.5 ATR-stop + armed-ATR-trail + square-off doctrine")
          .containsExactly("stop_loss", "trailing_stop", "square_off");

      JsonNode stopParams = exitRules.get(0).path("params");
      assertThat(stopParams.path("basis").asText()).as(id + " stop is 2×ATR").isEqualTo("atr_multiple");
      assertThat(stopParams.path("cap_pct").asInt()).as(id + " stop caps at ~10%").isEqualTo(10);
      JsonNode trailParams = exitRules.get(1).path("params");
      assertThat(trailParams.path("basis").asText()).as(id + " trail is 2×ATR").isEqualTo("atr_multiple");
      assertThat(trailParams.path("arm_pct").asInt()).as(id + " trail arms once up ~9%").isEqualTo(9);
      JsonNode squareOffParams = exitRules.get(2).path("params");
      assertThat(squareOffParams.path("fast_pct").isMissingNode())
          .as(id + " square-off has a too-fast clause")
          .isFalse();
      assertThat(squareOffParams.path("parabolic_ma").isMissingNode())
          .as(id + " square-off has a parabolic clause")
          .isFalse();

      List<String> tags = new ArrayList<>();
      config.path("tags").forEach(t -> tags.add(t.asText()));
      assertThat(tags).as(id + " is tagged manas-arora + swing").contains("manas-arora", "swing");
    }
  }
}
