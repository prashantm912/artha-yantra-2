package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * Pins the SHIPPED DEFAULT of the entry-side coverage gate, and the three places that have to agree
 * on it.
 *
 * <p>⚠️ Why this file exists: the gate's mode tests all pass the mode EXPLICITLY, so flipping the
 * production default from {@code OBSERVE_ONLY} to {@code ARMED} left the entire suite GREEN —
 * measured, while red-proofing the flag. The default is the single fact the owner's merge decision
 * rests on ("ship it inert"), and nothing tested it. A gate that ships armed by accident is exactly
 * the failure this flag was introduced to prevent.
 *
 * <p>It also pins the name chain. An {@code application.yml} placeholder whose name does not match
 * the compose passthrough silently swallows the override and falls back to the YAML default with no
 * error (#653) — so "we set the env var" would be true and inert at the same time.
 */
class SwingCoverageGateDefaultTest {

  private static final String SHIPPED_DEFAULT = "OBSERVE_ONLY";
  private static final String ENV_NAME = "ARTHA_SIGNALS_SWING_COVERAGE_GATE_MODE";

  @Test
  @DisplayName("the @Value default on the wired constructor is OBSERVE_ONLY")
  void theInjectedDefaultShipsInert() {
    String expression = null;
    for (Constructor<?> ctor : SwingBatchEngine.class.getDeclaredConstructors()) {
      for (Parameter p : ctor.getParameters()) {
        Value v = p.getAnnotation(Value.class);
        if (v != null && v.value().contains("swing-coverage-gate.mode")) {
          expression = v.value();
        }
      }
    }
    assertThat(expression)
        .as("no constructor parameter carries the swing-coverage-gate.mode @Value — did it move?")
        .isNotNull();

    // "${artha.signals.swing-coverage-gate.mode:OBSERVE_ONLY}" -> OBSERVE_ONLY
    int colon = expression.lastIndexOf(':');
    assertThat(colon).as("the placeholder must carry an explicit default").isGreaterThan(0);
    String fallback = expression.substring(colon + 1, expression.length() - 1);

    assertThat(fallback)
        .as(
            "the gate SHIPS INERT (owner decision 2026-08-11). Arming is an env change, never a"
                + " default change — flip this and every swing entry starts being refused on"
                + " deploy, with no PR that obviously says so")
        .isEqualTo(SHIPPED_DEFAULT);
  }

  @Test
  @DisplayName("application.yml and the compose passthrough agree on the name and the default")
  void thePropertyChainIsWiredEndToEnd() throws IOException {
    String yml =
        Files.readString(
            repoRoot().resolve("services/strategy-signal-service/src/main/resources/application.yml"),
            StandardCharsets.UTF_8);
    assertThat(yml)
        .as("application.yml must read the env var by its EXACT name, with the inert default")
        .contains("mode: ${" + ENV_NAME + ":" + SHIPPED_DEFAULT + "}");

    String compose =
        Files.readString(repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8);
    assertThat(compose)
        .as(
            "compose must pass %s through, or the env var is silently swallowed and the YAML"
                + " default wins with no error (#653)",
            ENV_NAME)
        .contains(ENV_NAME + ": ${" + ENV_NAME + ":-" + SHIPPED_DEFAULT + "}");
  }

  /** Walks up from the working directory to the repo root. FAILS rather than skipping if absent. */
  private static Path repoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
      if (Files.exists(dir.resolve("deploy/docker-compose.yml"))) {
        return dir;
      }
    }
    return fail(
        "could not locate the repo root from "
            + Paths.get("").toAbsolutePath()
            + " — this test must FAIL rather than skip, or it becomes a guard that checks nothing");
  }
}
