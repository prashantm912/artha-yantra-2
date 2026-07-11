package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import org.junit.jupiter.api.Test;

/**
 * §D.12 service-side override application: closed-grammar paths land on existing leaves; the base
 * config is untouched (transient patch); paths outside the grammar or to a missing leaf are
 * rejected 400 INVALID_PARAMETER_PATH.
 */
class TrialOverridesTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode baseConfig() throws Exception {
    return mapper.readTree(
        """
        {
          "indicators": [{"alias": "ema_fast", "type": "ema", "params": {"period": 9}}],
          "entry_rules": {
            "gate": {"all": [
              {"crossover": {"fast": "ema_fast", "slow": "ema_slow"}},
              "vol > 1.2",
              "px > sma20"
            ]},
            "scoring": {"threshold": 60}
          },
          "risk": {"position_sizing": {"method": "fixed", "params": {"capital_pct": 10}}, "max_positions": 5}
        }
        """);
  }

  @Test
  void appliesOverridesAcrossEverySectionAndLeavesTheBaseUntouched() throws Exception {
    JsonNode base = baseConfig();
    JsonNode overrides =
        mapper.readTree(
            """
            {
              "indicators[alias=ema_fast].params.period": 21,
              "entry_rules.scoring.threshold": 75,
              "risk.position_sizing.capital_pct": 5
            }
            """);

    JsonNode patched = TrialOverrides.apply(base, overrides);

    assertThat(patched.path("indicators").get(0).path("params").path("period").asInt()).isEqualTo(21);
    assertThat(patched.path("entry_rules").path("scoring").path("threshold").asInt()).isEqualTo(75);
    assertThat(patched.path("risk").path("position_sizing").path("params").path("capital_pct").asInt())
        .isEqualTo(5);
    // base config untouched
    assertThat(base.path("indicators").get(0).path("params").path("period").asInt()).isEqualTo(9);
  }

  @Test
  void positionalSelectorAlsoResolves() throws Exception {
    JsonNode patched =
        TrialOverrides.apply(
            baseConfig(), mapper.readTree("{\"indicators[0].params.period\": 13}"));
    assertThat(patched.path("indicators").get(0).path("params").path("period").asInt()).isEqualTo(13);
  }

  @Test
  void appliesGateConstantAndPositionCap() throws Exception {
    JsonNode base = baseConfig();
    JsonNode overrides =
        mapper.readTree(
            """
            {
              "entry_rules.gate.vol": 2.5,
              "risk.max_positions": 8
            }
            """);

    JsonNode patched = TrialOverrides.apply(base, overrides);

    // Only the matching literal-RHS comparison is rewritten; LHS + operator preserved.
    assertThat(patched.path("entry_rules").path("gate").path("all").get(1).asText())
        .isEqualTo("vol > 2.5");
    // Sibling comparisons and the crossover node are untouched.
    assertThat(patched.path("entry_rules").path("gate").path("all").get(2).asText())
        .isEqualTo("px > sma20");
    assertThat(patched.path("entry_rules").path("gate").path("all").get(0).path("crossover").path("fast").asText())
        .isEqualTo("ema_fast");
    assertThat(patched.path("risk").path("max_positions").asInt()).isEqualTo(8);
    // base config untouched
    assertThat(base.path("entry_rules").path("gate").path("all").get(1).asText()).isEqualTo("vol > 1.2");
    assertThat(base.path("risk").path("max_positions").asInt()).isEqualTo(5);
  }

  @Test
  void gateConstantRewritesInsideNestedAnyBranch() throws Exception {
    JsonNode base =
        mapper.readTree(
            """
            {
              "entry_rules": {"gate": {"all": [{"not": {"any": ["adx > 20", "close > vwap"]}}]}}
            }
            """);

    JsonNode patched = TrialOverrides.apply(base, mapper.readTree("{\"entry_rules.gate.adx\": 25}"));

    assertThat(
            patched.path("entry_rules").path("gate").path("all").get(0)
                .path("not").path("any").get(0).asText())
        .isEqualTo("adx > 25");
  }

  @Test
  void nonNumericGateConstantIs400() throws Exception {
    JsonNode base = baseConfig();
    assertThatThrownBy(
            () -> TrialOverrides.apply(base, mapper.readTree("{\"entry_rules.gate.vol\": \"high\"}")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("must be numeric");
  }

  @Test
  void gateConstantOnNonLiteralComparisonIs400() throws Exception {
    // px > sma20 compares against another operand — no tunable constant, so it does not resolve.
    JsonNode base = baseConfig();
    assertThatThrownBy(
            () -> TrialOverrides.apply(base, mapper.readTree("{\"entry_rules.gate.px\": 100}")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("does not resolve");
  }

  @Test
  void absentPositionCapIs400() throws Exception {
    JsonNode base = baseConfig();
    assertThatThrownBy(
            () -> TrialOverrides.apply(base, mapper.readTree("{\"risk.max_positions_per_underlying\": 2}")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("does not resolve");
  }

  @Test
  void pathOutsideTheGrammarIs400() throws Exception {
    JsonNode base = baseConfig();
    assertThatThrownBy(
            () ->
                TrialOverrides.apply(
                    base, mapper.readTree("{\"universe.symbols[0]\": \"X\"}")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("closed grammar");
  }

  @Test
  void grammaticalPathToAMissingLeafIs400() throws Exception {
    JsonNode base = baseConfig();
    assertThatThrownBy(
            () ->
                TrialOverrides.apply(
                    base, mapper.readTree("{\"indicators[alias=ema_fast].params.nonesuch\": 1}")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("does not resolve");
  }
}
