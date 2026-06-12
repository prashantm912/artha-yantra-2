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
