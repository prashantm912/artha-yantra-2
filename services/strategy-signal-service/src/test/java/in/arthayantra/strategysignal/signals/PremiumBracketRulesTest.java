package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The shadow book's premium bracket derivation (mirrors the paper take's semantics). */
class PremiumBracketRulesTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void resolvesPremiumPctRulesAgainstEntryLtp() throws Exception {
    var config =
        mapper.readTree(
            """
            {"exit_rules":[
              {"type":"stop_loss","params":{"basis":"premium_pct","value":50}},
              {"type":"take_profit","params":{"basis":"premium_pct","value":35}},
              {"type":"stop_loss","params":{"basis":"points","value":30}}
            ]}
            """);
    var b = PremiumBracketRules.resolve(config, new BigDecimal("100.00"));
    assertThat(b.stopLoss()).isEqualByComparingTo("50.00");
    assertThat(b.takeProfit()).isEqualByComparingTo("135.00");
  }

  @Test
  void nonPremiumBasesAndMissingRulesYieldNoLevels() throws Exception {
    var config =
        mapper.readTree("{\"exit_rules\":[{\"type\":\"stop_loss\",\"params\":{\"basis\":\"points\",\"value\":30}}]}");
    assertThat(PremiumBracketRules.resolve(config, new BigDecimal("100")).stopLoss()).isNull();
    assertThat(PremiumBracketRules.resolve(null, new BigDecimal("100")))
        .isEqualTo(PremiumBracketRules.Brackets.NONE);
    assertThat(PremiumBracketRules.resolve(config, null))
        .isEqualTo(PremiumBracketRules.Brackets.NONE);
  }
}
