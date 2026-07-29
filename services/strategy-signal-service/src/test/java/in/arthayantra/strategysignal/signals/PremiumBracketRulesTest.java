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

  /**
   * Pins the ROUNDING MODE, which the case above cannot: {@code 100.00} produces exact levels, so it
   * stays green under any rounding mode and says nothing about AY-SL-06's paise rounding.
   *
   * <p>That mattered when the live formula and the backtest's {@code PremiumExitEvaluator.level} were
   * two copies (ledger §9-04): flipping HALF_UP to DOWN failed the shared {@code
   * exit-equivalence.json} suites while THIS test stayed green — so the live side had no independent
   * guard on the one property that makes a premium-pct stop fire on the same bar in both engines.
   *
   * <p>Both inputs are chosen so the mode is decidable. The stop lands on {@code 61.725}, an EXACT
   * half, which separates HALF_UP (61.73) from HALF_EVEN and HALF_DOWN (61.72) as well as from
   * DOWN/FLOOR; the target lands on {@code 164.1885}, separating HALF_UP (164.19) from DOWN (164.18).
   */
  @Test
  void premiumLevelsArePaiseRoundedHalfUp() throws Exception {
    var config =
        mapper.readTree(
            """
            {"exit_rules":[
              {"type":"stop_loss","params":{"basis":"premium_pct","value":50}},
              {"type":"take_profit","params":{"basis":"premium_pct","value":33}}
            ]}
            """);
    var b = PremiumBracketRules.resolve(config, new BigDecimal("123.45"));

    // 123.45 x 0.50 = 61.725 exactly -> HALF_UP = 61.73 (HALF_EVEN/HALF_DOWN/DOWN would give 61.72)
    assertThat(b.stopLoss()).isEqualByComparingTo("61.73");
    // 123.45 x 1.33 = 164.1885 -> HALF_UP = 164.19 (DOWN would give 164.18)
    assertThat(b.takeProfit()).isEqualByComparingTo("164.19");
    // and the levels really are 2dp, not full precision carried through
    assertThat(b.stopLoss().scale()).isEqualTo(2);
    assertThat(b.takeProfit().scale()).isEqualTo(2);
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
