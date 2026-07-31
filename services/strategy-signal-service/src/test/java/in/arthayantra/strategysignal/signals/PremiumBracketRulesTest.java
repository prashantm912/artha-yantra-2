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
   * <p>That mattered when the formula existed as FOUR copies (ledger §9-04): flipping HALF_UP to
   * DOWN failed the shared {@code exit-equivalence.json} suites while THIS test stayed green — so
   * this side had no independent guard on the one property that makes a premium-pct stop fire on the
   * same bar in every engine. (Note this class covers the SHADOW book; the real paper-open path is
   * {@code PaperSignalListener.premiumBrackets}. All four now share {@code PremiumLevels}.)
   *
   * <p>It takes BOTH assertions together to pin HALF_UP uniquely, which is why neither is redundant.
   * The stop lands on {@code 61.725}, an EXACT half: HALF_UP gives 61.73 while HALF_EVEN, HALF_DOWN,
   * DOWN and FLOOR all give 61.72 — but UP and CEILING also give 61.73, so the stop alone is not
   * enough. The target lands on {@code 162.954}: HALF_UP gives 162.95 and UP/CEILING give 162.96.
   * HALF_UP is the only mode producing 61.73 AND 162.95. (Cross-vendor review caught the first
   * version of this test, whose 33% target left UP and CEILING indistinguishable from HALF_UP.)
   */
  @Test
  void premiumLevelsArePaiseRoundedHalfUp() throws Exception {
    var config =
        mapper.readTree(
            """
            {"exit_rules":[
              {"type":"stop_loss","params":{"basis":"premium_pct","value":50}},
              {"type":"take_profit","params":{"basis":"premium_pct","value":32}}
            ]}
            """);
    var b = PremiumBracketRules.resolve(config, new BigDecimal("123.45"));

    // 123.45 x 0.50 = 61.725 exactly -> HALF_UP = 61.73 (HALF_EVEN/HALF_DOWN/DOWN would give 61.72)
    assertThat(b.stopLoss()).isEqualByComparingTo("61.73");
    // 123.45 x 1.32 = 162.954 -> HALF_UP = 162.95 (UP and CEILING would give 162.96)
    assertThat(b.takeProfit()).isEqualByComparingTo("162.95");
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
