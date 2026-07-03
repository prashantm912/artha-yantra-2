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

    PremiumBracketRules.Brackets brackets =
        PremiumBracketRules.resolve(
            fx.path("config"), new BigDecimal(fx.path("entryPremium").asText()));

    assertThat(brackets.stopLoss())
        .isEqualByComparingTo(fx.path("expectedLevels").path("stopLoss").asText());
    assertThat(brackets.takeProfit())
        .isEqualByComparingTo(fx.path("expectedLevels").path("takeProfit").asText());
  }
}
