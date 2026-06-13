package in.arthayantra.strategysignal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Phase 21 unit slices: semver bumps, structured diff paths, seed-migration consistency. */
class RegistryUnitTest {

  @Test
  void semverBumpsAndOrdering() {
    assertThat(SemVer.parse("1.2.3").bump(SemVer.Bump.PATCH).toString()).isEqualTo("1.2.4");
    assertThat(SemVer.parse("1.2.3").bump(SemVer.Bump.MINOR).toString()).isEqualTo("1.3.0");
    assertThat(SemVer.parse("1.2.3").bump(SemVer.Bump.MAJOR).toString()).isEqualTo("2.0.0");
    assertThat(SemVer.Bump.of(null)).isEqualTo(SemVer.Bump.PATCH);
    assertThat(SemVer.Bump.of("minor")).isEqualTo(SemVer.Bump.MINOR);
    assertThat(SemVer.parse("1.10.0")).isGreaterThan(SemVer.parse("1.9.9"));
    assertThatThrownBy(() -> SemVer.parse("v1.2")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void diffUsesSelectorPathsAndSurvivesReordering() {
    JsonNode from =
        StrategyDocuments.parse(
                """
                schema: strategy-schema/v1
                id: diff-sample
                name: "Diff Sample"
                version: 1.0.0
                universe: { mode: explicit, instruments: [{ exchange: NSE, tradingsymbol: X }] }
                timeframes: { primary: 1m }
                indicators:
                  - { name: EMA, alias: fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
                  - { name: EMA, alias: slow, timeframe: 1m, params: { period: 21 }, weight: 1.0 }
                entry_rules:
                  direction: long
                  gate: { all: ["close > 1"] }
                  scoring: { threshold: 0.5 }
                exit_rules:
                  - { type: stop_loss, params: { basis: r_multiple, value: 1.5 } }
                risk:
                  position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
                  max_positions: 1
                  session: { style: intraday }
                """)
            .config();
    // reorder the indicators AND change slow's period AND drop the stop, add a time stop
    JsonNode to =
        StrategyDocuments.parse(
                """
                schema: strategy-schema/v1
                id: diff-sample
                name: "Diff Sample"
                version: 1.0.1
                universe: { mode: explicit, instruments: [{ exchange: NSE, tradingsymbol: X }] }
                timeframes: { primary: 1m }
                indicators:
                  - { name: EMA, alias: slow, timeframe: 1m, params: { period: 34 }, weight: 1.0 }
                  - { name: EMA, alias: fast, timeframe: 1m, params: { period: 9 }, weight: 1.0 }
                entry_rules:
                  direction: long
                  gate: { all: ["close > 1"] }
                  scoring: { threshold: 0.5 }
                exit_rules:
                  - { type: time_stop, params: { max_bars: 10 } }
                risk:
                  position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
                  max_positions: 1
                  session: { style: intraday }
                """)
            .config();

    List<ConfigDiff.Op> ops = ConfigDiff.diff(from, to);

    assertThat(ops)
        .anySatisfy(
            op -> {
              assertThat(op.path()).isEqualTo("indicators[alias=slow].params.period");
              assertThat(op.before()).isEqualTo("21");
              assertThat(op.after()).isEqualTo("34");
            });
    // reordering alone produces NO ops for the untouched fast indicator
    assertThat(ops).noneMatch(op -> op.path().startsWith("indicators[alias=fast]"));
    assertThat(ops)
        .anySatisfy(op -> assertThat(op.path()).isEqualTo("exit_rules[type=stop_loss]"));
    assertThat(ops)
        .anySatisfy(op -> assertThat(op.path()).isEqualTo("exit_rules[type=time_stop]"));
    assertThat(ConfigDiff.summary(ops)).contains("changed");
  }

  @Test
  void seedMigrationChecksumMatchesItsEmbeddedYaml() throws Exception {
    Path migration = locate("deploy/flyway/strategy/R__seed_sample_strategy.sql");
    String sql = Files.readString(migration, StandardCharsets.UTF_8);

    Matcher yaml = Pattern.compile("\\$yaml\\$(.*?)\\$yaml\\$", Pattern.DOTALL).matcher(sql);
    Matcher checksum = Pattern.compile("'([0-9a-f]{64})'").matcher(sql);
    assertThat(yaml.find()).isTrue();
    assertThat(checksum.find()).isTrue();

    StrategyDocuments.Parsed parsed = StrategyDocuments.parse(yaml.group(1));
    assertThat(parsed.checksum())
        .as("the seed migration's checksum must match its embedded YAML (D18)")
        .isEqualTo(checksum.group(1));
  }

  private static Path locate(String relative) {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(relative + " not found");
  }
}
