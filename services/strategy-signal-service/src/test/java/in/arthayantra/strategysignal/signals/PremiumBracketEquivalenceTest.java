package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The LIVE level-derivation half of the exit-equivalence contract (audit
 * no-live-vs-backtest-exit-equivalence-test): {@link PremiumBracketRules} must resolve the shared
 * fixture's YAML-shaped {@code exit_rules} to the SAME absolute bracket levels the backtest's
 * {@code PremiumExitEvaluator} computes from the percentages ({@code entry×(1∓pct/100)}). The
 * fixture pins the levels; {@code PaperBracketEquivalenceTest} pins their enforcement.
 */
class PremiumBracketEquivalenceTest {

  @Test
  void liveBracketLevelsMatchTheSharedEquivalenceFixture() throws Exception {
    Path p = Path.of("..", "..", "contracts", "fixtures", "exit-equivalence.json");
    JsonNode fx = new ObjectMapper().readTree(Files.readString(p));

    for (JsonNode sc : fx.path("scenarios")) {
      // a scenario may override entryPremium/config/expectedLevels (the sub-paise case does); else
      // inherit the top-level defaults. Proves the LIVE derivation paise-rounds identically per case.
      JsonNode config = sc.has("config") ? sc.path("config") : fx.path("config");
      BigDecimal entry =
          new BigDecimal(
              (sc.has("entryPremium") ? sc.path("entryPremium") : fx.path("entryPremium")).asText());
      JsonNode levels = sc.has("expectedLevels") ? sc.path("expectedLevels") : fx.path("expectedLevels");

      PremiumBracketRules.Brackets brackets = PremiumBracketRules.resolve(config, entry);
      String name = sc.path("name").asText();
      assertThat(brackets.stopLoss()).as(name).isEqualByComparingTo(levels.path("stopLoss").asText());
      assertThat(brackets.takeProfit())
          .as(name)
          .isEqualByComparingTo(levels.path("takeProfit").asText());
    }
  }
}
