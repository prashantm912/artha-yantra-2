package in.arthayantra.strategyschema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The Phase 18 corpus runner: every accept fixture validates clean; every reject fixture fails
 * with the issue its {@code # expect:} header names (matched against path + message of any
 * error); the bare-positional fixture is accepted WITH a lint warning.
 */
class CorpusTest {

  @TestFactory
  Stream<DynamicTest> acceptFixturesValidate() throws Exception {
    return fixtures("accept")
        .map(
            file ->
                DynamicTest.dynamicTest(
                    file.getFileName().toString(),
                    () -> {
                      ValidationResult result = StrategyDocuments.validate(read(file));
                      assertThat(result.errors())
                          .as("accept fixture must have zero errors: %s", result.errors())
                          .isEmpty();
                      assertThat(result.valid()).isTrue();
                    }));
  }

  @TestFactory
  Stream<DynamicTest> rejectFixturesFailWithExpectedIssue() throws Exception {
    return fixtures("reject")
        .map(
            file ->
                DynamicTest.dynamicTest(
                    file.getFileName().toString(),
                    () -> {
                      String yaml = read(file);
                      String expect = expectHeader(yaml, file);
                      ValidationResult result = StrategyDocuments.validate(yaml);
                      assertThat(result.valid())
                          .as("reject fixture must fail validation")
                          .isFalse();
                      assertThat(result.errors())
                          .as("expected an error mentioning '%s', got: %s", expect, result.errors())
                          .anySatisfy(
                              issue ->
                                  assertThat(issue.path() + " " + issue.message())
                                      .contains(expect));
                    }));
  }

  @Test
  void barePositionalPathIsAcceptedButLinted() throws Exception {
    String yaml = read(fixture("accept", "bare-positional-path.yaml"));

    ValidationResult result = StrategyDocuments.validate(yaml);

    assertThat(result.valid()).isTrue();
    assertThat(result.warnings())
        .anySatisfy(w -> assertThat(w.message()).contains("positional"));
  }

  @Test
  void optionalIndicatorWithoutNormalizeIsLinted() {
    String yaml =
        """
        schema: strategy-schema/v1
        id: lint-sample
        name: "Lint Sample"
        version: 1.0.0
        universe:
          mode: explicit
          instruments:
            - { exchange: NSE, tradingsymbol: RELIANCE }
        timeframes: { primary: 1m }
        indicators:
          - { name: EMA, alias: ema_fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
          - { name: VOLUME_RATIO, alias: vol_x, timeframe: 1m, weight: 0.5, optional: true }
        entry_rules:
          direction: long
          gate:
            all:
              - "close > ema_fast"
          scoring: { threshold: 0.5 }
        exit_rules:
          - { type: stop_loss, params: { basis: r_multiple, value: 1.5 } }
        risk:
          position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
          max_positions: 1
          session: { style: intraday }
        """;

    ValidationResult result = StrategyDocuments.validate(yaml);

    assertThat(result.valid()).isTrue();
    assertThat(result.warnings())
        .anySatisfy(w -> assertThat(w.message()).contains("can never activate"));
  }

  /**
   * The refusal of {@code percent} on the options plane is SCOPED, not blanket — it must reject
   * exactly one of the five level bases, and only when the strategy executes options.
   *
   * <p>Both legs matter as controls. If the rule were too broad the other four bases (or the
   * cash-equity leg) would fail here, and the corpus would break too: {@code accept/options-scalper}
   * uses {@code premium_pct} and {@code accept/manas-arora-funnel} uses {@code percent} on a
   * {@code manas_arora_funnel} strategy. So a passing run of THIS test plus a green corpus is what
   * shows the rule discriminates rather than merely refusing things.
   */
  @Test
  void percentLevelBasisIsRefusedOnTheOptionsPlaneOnly() {
    for (String basis : List.of("premium_pct", "atr_multiple", "r_multiple", "index_points")) {
      assertThat(StrategyDocuments.validate(optionsStrategyWithStopBasis(basis)).valid())
          .as("basis '%s' must stay legal on an options strategy", basis)
          .isTrue();
    }

    ValidationResult refused = StrategyDocuments.validate(optionsStrategyWithStopBasis("percent"));

    assertThat(refused.valid()).as("'percent' is ambiguous on the options plane").isFalse();
    assertThat(refused.errors())
        .anySatisfy(
            issue -> {
              assertThat(issue.path()).isEqualTo("/exit_rules/0/params/basis");
              assertThat(issue.message()).contains("cash-equity basis");
            });

    // ...and the SAME rule on a cash-equity strategy stays legal: one plane, no ambiguity.
    assertThat(StrategyDocuments.validate(equityStrategyWithPercentStop()).valid())
        .as("'percent' is the cash-equity basis and must remain legal off the options plane")
        .isTrue();
  }

  private static String optionsStrategyWithStopBasis(String basis) {
    return """
        schema: strategy-schema/v1
        id: options-basis-probe
        name: "Options Basis Probe"
        version: 1.0.0
        universe:
          mode: options_of_underlying
          underlying: { exchange: NSE, tradingsymbol: "NIFTY 50" }
          options:
            expiry: nearest_weekly
            strikes: { selector: atm_window, width: 2 }
            option_types: [CE, PE]
        timeframes: { primary: 1m }
        indicators:
          - { name: EMA, alias: ema_fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
        entry_rules:
          direction: long
          gate:
            all:
              - "close > ema_fast"
          scoring: { threshold: 0.5 }
        exit_rules:
          - { type: stop_loss, params: { basis: %s, value: 25 } }
          - { type: time_stop, params: { max_bars: 15 } }
        risk:
          position_sizing: { method: premium_budget, params: { budget_inr: 15000 } }
          max_positions: 1
          session: { style: intraday }
        """
        .formatted(basis);
  }

  private static String equityStrategyWithPercentStop() {
    return """
        schema: strategy-schema/v1
        id: equity-percent-probe
        name: "Equity Percent Probe"
        version: 1.0.0
        universe:
          mode: explicit
          instruments:
            - { exchange: NSE, tradingsymbol: RELIANCE }
        timeframes: { primary: 1d }
        indicators:
          - { name: EMA, alias: ema_fast, timeframe: 1d, params: { period: 9 }, weight: 1.0 }
        entry_rules:
          direction: long
          gate:
            all:
              - "close > ema_fast"
          scoring: { threshold: 0.5 }
        exit_rules:
          - { type: stop_loss, params: { basis: percent, value: 8 } }
        risk:
          position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
          max_positions: 1
          session: { style: positional }
        """;
  }

  private static Stream<Path> fixtures(String kind) throws IOException, URISyntaxException {
    Path dir = Path.of(CorpusTest.class.getResource("/corpus/" + kind).toURI());
    try (Stream<Path> listing = Files.list(dir)) {
      return listing.filter(p -> p.toString().endsWith(".yaml")).sorted().toList().stream();
    }
  }

  private static Path fixture(String kind, String name) throws URISyntaxException {
    return Path.of(CorpusTest.class.getResource("/corpus/" + kind + "/" + name).toURI());
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  private static String expectHeader(String yaml, Path file) {
    List<String> lines = yaml.lines().toList();
    String first = lines.isEmpty() ? "" : lines.get(0);
    assertThat(first)
        .as("reject fixture %s must start with '# expect: <substring>'", file)
        .startsWith("# expect:");
    return first.substring("# expect:".length()).trim();
  }
}
