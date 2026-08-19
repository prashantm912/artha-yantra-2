package in.arthayantra.strategysignal.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The options-digest context sweep names its underlyings as free text, and a name market-data does
 * not recognise fails in the QUIETEST possible way.
 *
 * <p>⚠️ Why this exists (chip task_ffefe53e). The configured value was a bare {@code NIFTY} from the
 * day the feature shipped. {@code GET /api/v1/market/context/options-digest} answers only for the
 * canonical instrument key, so it returned 404 — and {@link ContextClient} deliberately fail-softs a
 * 404 to an empty {@code Optional} at DEBUG, because the endpoint legitimately has no digest for
 * some underlyings. So the miss produced no error, no WARN and no row: {@code strategy.insights}
 * held FOUR underlying-scoped CONTEXT_SHIFT rows across the entire history of the feature and every
 * one was {@code BSE:SENSEX}. Measured live 2026-08-19: {@code name=NIFTY} → 404,
 * {@code name=NIFTY 50} → 200.
 *
 * <p><b>What this asserts, and why not the obvious thing.</b> The value was wrong in TWO places —
 * {@code application.yml} and {@code InsightProperties.DEFAULT_UNDERLYINGS} — so a test that the two
 * copies AGREE would have passed against the defect: they agreed, and were both wrong. This instead
 * ties the consumer to the PRODUCER's vocabulary: every configured underlying must be a name
 * market-data's own config uses for the same instruments. That is the check that fails on a bare
 * {@code NIFTY}.
 *
 * <p>⚠️ Parsed with plain string operations, deliberately. The first cut used regex and could not
 * survive being written through a shell heredoc — {@code \\s} collapsed to {@code \s}, an invalid
 * Java escape, and checkstyle failed to PARSE the file. Same trap cost a test its first attempt on
 * 2026-08-18; there is nothing here a regex buys.
 */
class ContextUnderlyingNamesTest {

  private static final String SS_YML = "services/strategy-signal-service/src/main/resources/application.yml";
  private static final String MD_YML = "services/market-data-service/src/main/resources/application.yml";

  /**
   * Surefire runs with the MODULE as its working directory, not the repo root — the same reason
   * {@code OperatingWindowTest} carries this helper. It FAILS rather than skipping: a source-walking
   * test that quietly finds no file is a guard that checks nothing.
   */
  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
      if (Files.exists(dir.resolve("deploy/docker-compose.yml"))) {
        return dir;
      }
    }
    throw new IllegalStateException(
        "could not locate the repo root from " + Paths.get("").toAbsolutePath());
  }

  private static final String CANONICAL_KEY = "snapshot-underlyings:";
  private static final String CONFIGURED_KEY = "underlyings:";

  /** market-data's F&amp;O index vocabulary — the canonical spelling of every index we can digest. */
  private static Set<String> canonicalUnderlyings() throws IOException {
    String line = firstLineContaining(MD_YML, CANONICAL_KEY);
    assertThat(line)
        .as(
            "market-data no longer declares %s — this test cannot source the canonical vocabulary"
                + " and would otherwise pass vacuously",
            CANONICAL_KEY)
        .isNotNull();
    Set<String> out = new LinkedHashSet<>();
    for (String s : line.substring(line.indexOf(CANONICAL_KEY) + CANONICAL_KEY.length()).split(",")) {
      if (!s.isBlank()) {
        out.add(s.trim());
      }
    }
    assertThat(out).as("the canonical set parsed empty").isNotEmpty();
    return out;
  }

  /** The {@code artha.insights.context.underlyings} list as spelled in application.yml. */
  private static List<String> configuredUnderlyings() throws IOException {
    String line = firstLineContaining(SS_YML, CONFIGURED_KEY + " [");
    assertThat(line)
        .as("artha.insights.context.underlyings not found as an inline list in application.yml")
        .isNotNull();
    String inside = line.substring(line.indexOf('[') + 1, line.indexOf(']'));
    List<String> out = new ArrayList<>();
    for (String s : inside.split(",")) {
      if (!s.isBlank()) {
        out.add(s.trim());
      }
    }
    return out;
  }

  /** First line containing the needle, or null. Comments after the value are left in place. */
  private static String firstLineContaining(String file, String needle) throws IOException {
    for (String line : Files.readAllLines(repoRoot().resolve(file), StandardCharsets.UTF_8)) {
      if (line.contains(needle)) {
        return stripComment(line);
      }
    }
    return null;
  }

  private static String stripComment(String line) {
    int hash = line.indexOf('#');
    return hash < 0 ? line : line.substring(0, hash);
  }

  @Test
  @DisplayName("every configured context underlying is a name market-data can actually resolve")
  void configuredUnderlyingsAreCanonicalInstrumentNames() throws IOException {
    Set<String> canonical = canonicalUnderlyings();
    List<String> configured = configuredUnderlyings();

    assertThat(configured).as("an empty list makes every assertion here vacuous").isNotEmpty();
    for (String underlying : configured) {
      assertThat(canonical)
          .as(
              "'%s' is not a canonical instrument name. The options-digest endpoint answers only for"
                  + " the canonical key, and ContextClient fail-softs a 404 to empty at DEBUG — so a"
                  + " non-canonical name here is a PERMANENTLY SILENT miss, not a visible failure",
              underlying)
          .contains(underlying);
    }
  }

  @Test
  @DisplayName("the code default carries the same canonical names as application.yml")
  void theCodeDefaultDoesNotDriftFromTheYaml() throws IOException {
    // Not sufficient on its own — both copies were wrong together — but it is what stops the yml
    // being fixed while InsightProperties keeps the stale value for whoever runs without the yml.
    assertThat(InsightProperties.Context.defaults().underlyings())
        .containsExactlyElementsOf(configuredUnderlyings());
  }
}
