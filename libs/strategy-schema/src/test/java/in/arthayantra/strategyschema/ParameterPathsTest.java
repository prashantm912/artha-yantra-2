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
            - "ema_fast > 100"
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
        "risk.position_sizing.percent",
        // EVO row 21: gate-expression constant (unique literal-RHS comparison) + position cap.
        "entry_rules.gate.ema_fast",
        "risk.max_positions"
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
        "universe.instruments[0].exchange",
        "indicators[alias=ema_fast].params.period.extra",
        "exit_rules[type=stop_loss].params",
        // EVO row 21 boundaries: gate needs a leaf; risk caps are the two enumerated fields only.
        "entry_rules.gate",
        "risk.max_positions.extra",
        "risk.max_daily_loss_pct",
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
  void gateAndRiskCapPathsParseButOnlyResolveOnRealLeaves() {
    // Grammatical (they parse) but non-resolving: a structure keyword, a crossover-only operand
    // (no numeric literal), and an absent optional cap — all loud non-resolves, not silent no-ops.
    ParameterPaths.ParsedPath structureWord =
        ParameterPaths.parse("entry_rules.gate.all").orElseThrow();
    ParameterPaths.ParsedPath crossoverOperand =
        ParameterPaths.parse("entry_rules.gate.ema_slow").orElseThrow();
    ParameterPaths.ParsedPath absentCap =
        ParameterPaths.parse("risk.max_positions_per_underlying").orElseThrow();

    assertThat(ParameterPaths.resolves(config, structureWord)).isFalse();
    assertThat(ParameterPaths.resolves(config, crossoverOperand)).isFalse();
    assertThat(ParameterPaths.resolves(config, absentCap)).isFalse();
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
