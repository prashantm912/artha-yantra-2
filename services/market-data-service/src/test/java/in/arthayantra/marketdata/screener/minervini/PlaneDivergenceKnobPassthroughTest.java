package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the #653 trap for {@link PlaneDivergenceProbe}'s knobs: a {@code @Value} default with no
 * compose passthrough is pinned to that default forever, and a {@code .env} edit silently does
 * nothing. There is no error and no log line — the knob simply is not tunable, which is worst for a
 * default-ON component nobody can then turn off without an image rebuild.
 *
 * <p>Relaxed binding is what connects the two, so the assertion is on the exact translated name:
 * {@code artha.minervini.plane-divergence.min-pct} ⇄ {@code
 * ARTHA_MINERVINI_PLANE_DIVERGENCE_MIN_PCT}. Both sides are derived here rather than written twice,
 * so renaming the property without renaming the passthrough fails this test.
 */
class PlaneDivergenceKnobPassthroughTest {

  private static final String PREFIX = "artha.minervini.plane-divergence.";
  private static final List<String> KNOBS = List.of("enabled", "min-pct", "lookback-days");

  @Test
  void everyKnobHasAMarketDataComposePassthrough() throws IOException {
    String compose = Files.readString(repoRoot().resolve("deploy/docker-compose.yml"));

    for (String knob : KNOBS) {
      String env = envName(PREFIX + knob);
      assertThat(compose)
          .as("compose passthrough for %s (#653: no passthrough = pinned to the @Value default)",
              PREFIX + knob)
          .contains(env + ": ${" + env + ":-");
    }
  }

  @Test
  void everyKnobIsDocumentedInEnvExample() throws IOException {
    String envExample = Files.readString(repoRoot().resolve(".env.example"));

    for (String knob : KNOBS) {
      assertThat(envExample).contains(envName(PREFIX + knob));
    }
  }

  /** Spring's {@code SystemEnvironmentPropertySource} translation, applied in the same direction. */
  private static String envName(String property) {
    return property.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_');
  }

  private static Path repoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("deploy/docker-compose.yml"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException("repo root not found from " + Path.of("").toAbsolutePath());
    }
    return p;
  }
}
