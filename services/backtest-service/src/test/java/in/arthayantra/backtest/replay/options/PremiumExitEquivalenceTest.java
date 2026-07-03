package in.arthayantra.backtest.replay.options;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The BACKTEST half of the live-vs-backtest exit-equivalence contract (audit
 * no-live-vs-backtest-exit-equivalence-test): runs {@link PremiumExitEvaluator} over the shared
 * fixture {@code contracts/fixtures/exit-equivalence.json}. The strategy-signal suite runs the SAME
 * scenarios through the live chain (PremiumBracketRules levels + PaperBracketEvaluator.breach) — a
 * divergence in either implementation's semantics turns exactly one suite red.
 */
class PremiumExitEquivalenceTest {

  private static JsonNode fixture() throws Exception {
    // surefire's working dir is the module dir; the fixture lives at the repo root
    Path p = Path.of("..", "..", "contracts", "fixtures", "exit-equivalence.json");
    return new ObjectMapper().readTree(Files.readString(p));
  }

  @Test
  void premiumExitEvaluatorMatchesTheSharedEquivalenceFixture() throws Exception {
    JsonNode fx = fixture();
    BigDecimal entry = new BigDecimal(fx.path("entryPremium").asText());
    // the same YAML-shaped rules the live side resolves: stop_loss 50 / take_profit 35 premium_pct
    BigDecimal slPct = null;
    BigDecimal tpPct = null;
    for (JsonNode rule : fx.path("config").path("exit_rules")) {
      BigDecimal pct = new BigDecimal(rule.path("params").path("value").asText());
      if ("stop_loss".equals(rule.path("type").asText())) {
        slPct = pct;
      } else if ("take_profit".equals(rule.path("type").asText())) {
        tpPct = pct;
      }
    }
    PremiumExitEvaluator.Rules rules = new PremiumExitEvaluator.Rules(slPct, tpPct, null, null, null);

    for (JsonNode sc : fx.path("scenarios")) {
      List<BigDecimal> premiums = new ArrayList<>();
      sc.path("premiums").forEach(n -> premiums.add(new BigDecimal(n.asText())));
      PremiumExitEvaluator.Exit exit =
          PremiumExitEvaluator.evaluate(entry, premiums, rules, premiums.size());

      String name = sc.path("name").asText();
      JsonNode expect = sc.path("expect");
      if (expect.path("reason").isNull()) {
        // no bracket exit — the evaluator falls through to END_OF_DATA at the last bar
        assertThat(exit.reason()).as(name).isEqualTo("END_OF_DATA");
      } else {
        assertThat(exit.reason()).as(name).isEqualTo(expect.path("reason").asText());
        assertThat(exit.barOffset()).as(name).isEqualTo(expect.path("barOffset").asInt());
      }
    }
  }
}
