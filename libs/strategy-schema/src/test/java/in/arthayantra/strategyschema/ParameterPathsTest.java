package in.arthayantra.strategyschema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The closed whitelist grammar (COMMON 12.5): literal match only, resolution is a pure walk. */
class ParameterPathsTest {

  private static final String CONFIG_YAML =
      """
      schema: strategy-schema/v1
      id: path-sample
      name: "Path Sample"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: RELIANCE }
      timeframes: { primary: 1m }
      indicators:
        - { name: EMA, alias: ema_fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
        - { name: EMA, alias: ema_slow, timeframe: 1m, params: { period: 21 }, weight: 1.0 }
      entry_rules:
        direction: long
        gate:
          all:
            - crossover: { fast: ema_fast, slow: ema_slow }
        scoring: { threshold: 0.5 }
      exit_rules:
        - { type: stop_loss, params: { basis: r_multiple, value: 1.5 } }
      risk:
        position_sizing: { method: percent_equity, params: { percent: 10 } }
        max_positions: 1
        session: { style: intraday }
      """;

  private final JsonNode config = YamlStrategyLoader.load(CONFIG_YAML);

  @ParameterizedTest
  @ValueSource(
      strings = {
        "indicators[alias=ema_fast].params.period",
        "indicators[0].params.period",
        "exit_rules[type=stop_loss].params.value",
        "exit_rules[0].params.value",
        "entry_rules.scoring.threshold",
        "risk.position_sizing.percent"
      })
  void grammarAcceptsAndResolvesWhitelistedForms(String path) {
    Optional<ParameterPaths.ParsedPath> parsed = ParameterPaths.parse(path);

    assertThat(parsed).isPresent();
    assertThat(ParameterPaths.resolves(config, parsed.get())).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "indicators[*].params.period",
        "indicators[alias=ema_fast].weight",
        "indicators[alias=EMA_FAST].params.period",
        "risk.max_positions",
        "universe.instruments[0].exchange",
        "entry_rules.gate.all",
        "indicators[alias=ema_fast].params.period.extra",
        "exit_rules[type=stop_loss].params",
        "../etc/passwd"
      })
  void grammarRejectsEverythingOutsideTheWhitelist(String path) {
    assertThat(ParameterPaths.parse(path)).isEmpty();
  }

  @Test
  void unresolvedSelectorsReportFalse() {
    ParameterPaths.ParsedPath ghostAlias =
        ParameterPaths.parse("indicators[alias=ghost].params.period").orElseThrow();
    ParameterPaths.ParsedPath outOfRange =
        ParameterPaths.parse("indicators[9].params.period").orElseThrow();
    ParameterPaths.ParsedPath missingField =
        ParameterPaths.parse("indicators[alias=ema_fast].params.multiplier").orElseThrow();

    assertThat(ParameterPaths.resolves(config, ghostAlias)).isFalse();
    assertThat(ParameterPaths.resolves(config, outOfRange)).isFalse();
    assertThat(ParameterPaths.resolves(config, missingField)).isFalse();
  }

  @Test
  void positionalSelectorsAreFlagged() {
    assertThat(ParameterPaths.parse("indicators[0].params.period").orElseThrow().positional())
        .isTrue();
    assertThat(
            ParameterPaths.parse("indicators[alias=ema_fast].params.period")
                .orElseThrow()
                .positional())
        .isFalse();
  }
}
