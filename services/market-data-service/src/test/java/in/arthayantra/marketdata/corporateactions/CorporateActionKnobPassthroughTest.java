package in.arthayantra.marketdata.corporateactions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the #653 trap for {@link CorporateActionJob}'s knobs — and it was not hypothetical here.
 *
 * <p>{@code artha.corporate-actions.symbols} has been read by the job since it was written
 * ({@code CorporateActionJob}'s {@code symbolOverride}), but the key existed in NO yml and had NO
 * compose passthrough. It was therefore unsettable on a deployed stack: an operator arming a
 * two-symbol canary by putting {@code ARTHA_CORPORATE_ACTIONS_SYMBOLS} in {@code .env} would have
 * got no error, no log line, and a sweep over the ENTIRE active equity universe.
 *
 * <p>That matters more than the usual instance of this trap because of what the sweep does: the
 * remediation it triggers OOM-crashed live Postgres three times (A14), which is why the whole job
 * sat disarmed. Found 2026-08-10 while arming the canary that V057 made safe — the knob was the
 * thing standing between "two symbols" and "248".
 *
 * <p>Both sides are DERIVED from the property name rather than written twice, so renaming a property
 * without renaming its passthrough fails this test rather than silently unwiring the knob.
 */
class CorporateActionKnobPassthroughTest {

  private static final String PREFIX = "artha.corporate-actions.";

  /**
   * Every knob an operator is expected to reach from {@code .env}.
   *
   * <p>{@code symbols} is the canary allowlist; {@code enabled} is the A14 kill switch;
   * {@code rebuild-retry-cooldown-days} bounds the V057 retry. The re-backfill depth knobs
   * ({@code rebackfill-days-1m} / {@code -1d}) are deliberately NOT here — they are already wired
   * and are covered by their own passthroughs.
   */
  private static final List<String> KNOBS =
      List.of("enabled", "symbols", "rebuild-retry-cooldown-days");

  @Test
  void everyKnobHasAMarketDataComposePassthrough() throws IOException {
    String compose = Files.readString(repoRoot().resolve("deploy/docker-compose.yml"));

    for (String knob : KNOBS) {
      String env = envName(PREFIX + knob);
      assertThat(compose)
          .as(
              "compose passthrough for %s (#653: no passthrough means the knob is pinned to its"
                  + " default and a .env edit does nothing, silently)",
              PREFIX + knob)
          .contains(env + ": ${" + env + ":-");
    }
  }

  /**
   * …and the yml must actually carry the placeholder, or the compose variable reaches the container
   * and is never read. Both halves are needed: this is the half that was missing for {@code
   * symbols}, and having only the compose side would look correct in a `docker inspect`.
   */
  @Test
  void everyKnobIsReadFromTheEnvironmentInApplicationYml() throws IOException {
    String yml =
        Files.readString(
            repoRoot().resolve("services/market-data-service/src/main/resources/application.yml"));

    for (String knob : KNOBS) {
      assertThat(yml)
          .as("application.yml placeholder for %s", PREFIX + knob)
          .contains("${" + envName(PREFIX + knob) + ":");
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
