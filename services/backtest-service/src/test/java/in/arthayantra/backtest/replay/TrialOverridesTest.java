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
          "entry_rules": {"scoring": {"threshold": 60}},
          "risk": {"position_sizing": {"method": "fixed", "params": {"capital_pct": 10}}}
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
