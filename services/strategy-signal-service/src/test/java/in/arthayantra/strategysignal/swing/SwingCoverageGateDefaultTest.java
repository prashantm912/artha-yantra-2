package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * Pins the SHIPPED DEFAULT of the coverage gate — ONE mode governing BOTH halves, entry and exit
 * (the exit half was left ungated in the first cut and probed, logged, recorded and PAGED in every
 * mode; fixed 2026-08-14) — and the three places that have to agree on it.
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
  private static final String PROPERTY = "artha.signals.swing-coverage-gate.mode";

  @Test
  @DisplayName("the @Value default on the wired constructor is OBSERVE_ONLY")
  void theInjectedDefaultShipsInert() {
    String expression = null;
    for (Constructor<?> ctor : SwingBatchEngine.class.getDeclaredConstructors()) {
      for (Parameter p : ctor.getParameters()) {
        Value v = p.getAnnotation(Value.class);
        if (v != null && v.value().contains("swing-coverage-gate")) {
          expression = v.value();
        }
      }
    }
    assertThat(expression)
        .as("no constructor parameter carries the swing-coverage-gate @Value — did it move?")
        .isNotNull();

    // ⚠️ EXACT, not a substring match on the default (cross-vendor review Minor). A substring
    // assertion stays green when the PROPERTY PREFIX is mistyped — "artha.signal." for
    // "artha.signals." still ends in the right suffix — and a mistyped prefix means the YAML value
    // is never read at all, which is the same silent inertness this whole file exists to prevent.
    assertThat(expression)
        .as(
            "the gate SHIPS INERT (owner decision 2026-08-11). Arming is an env change, never a"
                + " default change — flip this and every swing entry starts being refused on"
                + " deploy, with no PR that obviously says so")
        .isEqualTo("${" + PROPERTY + ":" + SHIPPED_DEFAULT + "}");
  }

  @Test
  @DisplayName("application.yml and the compose passthrough agree on the name and the default")
  void thePropertyChainIsWiredEndToEnd() throws IOException {
    String yml =
        Files.readString(
            repoRoot().resolve("services/strategy-signal-service/src/main/resources/application.yml"),
            StandardCharsets.UTF_8);
    // ⚠️ Scoped to the `swing-coverage-gate:` key, not the whole file (review round 2): the exact
    // `mode:` line living under a DIFFERENT property hierarchy would otherwise pass, and a mode read
    // from the wrong prefix is never read at all.
    int gate = yml.indexOf("swing-coverage-gate:");
    assertThat(gate).as("application.yml has no swing-coverage-gate block").isGreaterThan(0);
    String gateBlock = yml.substring(gate, Math.min(yml.length(), gate + 400));
    assertThat(gateBlock)
        .as("application.yml must read the env var by its EXACT name, with the inert default")
        .contains("mode: ${" + ENV_NAME + ":" + SHIPPED_DEFAULT + "}");

    // ⚠️ `${VAR-DEFAULT}`, NOT `${VAR:-DEFAULT}` (cross-vendor review, round 2). The colon form
    // substitutes for an EMPTY value as well as an unset one, so Compose would replace an explicitly
    // blank env var with OBSERVE_ONLY before Java ever saw it — making anInvalidExplicitModeFailsClosed
    // assert a blank case that production CANNOT REACH. Without the colon, unset still defaults and an
    // explicit blank reaches the parser and refuses the boot, which is the behaviour that test claims.
    // ⚠️ SCOPED to the strategy-signal-service block (cross-vendor review Minor). A whole-file
    // search passes when the passthrough sits under the WRONG service — where it is inert for this
    // one — which is indistinguishable from it being absent.
    List<String> block =
        serviceBlock(
            Files.readAllLines(
                repoRoot().resolve("deploy/docker-compose.yml"), StandardCharsets.UTF_8),
            "strategy-signal-service");
    assertThat(block)
        .as(
            "compose must pass %s through ON strategy-signal-service, or the env var is silently"
                + " swallowed and the YAML default wins with no error (#653)",
            ENV_NAME)
        .anySatisfy(
            line ->
                assertThat(line.trim())
                    .isEqualTo(ENV_NAME + ": ${" + ENV_NAME + "-" + SHIPPED_DEFAULT + "}"));
  }

  @Test
  @DisplayName("an explicitly-set invalid mode REFUSES THE BOOT rather than running inert")
  void anInvalidExplicitModeFailsClosed() {
    // Cross-vendor review Critical. The first cut fell back to OBSERVE_ONLY on an unparseable value,
    // which fails OPEN: an operator writing ARMD for ARMED gets a silently inert gate and every
    // coverage-unsafe entry keeps being admitted, with a warn line as the only trace. ABSENT and
    // INVALID are different — absent takes the shipped default, invalid refuses to start.
    assertThatThrownBy(() -> engineWithMode("ARMD"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ARMD")
        .hasMessageContaining("DISABLED / OBSERVE_ONLY / ARMED");

    // Blank counts as EXPLICIT: @Value's fallback only applies when the property is MISSING, so an
    // empty string means somebody set it to nothing.
    assertThatThrownBy(() -> engineWithMode("")).isInstanceOf(IllegalStateException.class);

    // The three valid spellings must all construct, case-insensitively.
    assertThatCode(() -> engineWithMode("ARMED")).doesNotThrowAnyException();
    assertThatCode(() -> engineWithMode("observe_only")).doesNotThrowAnyException();
    assertThatCode(() -> engineWithMode(" DISABLED ")).doesNotThrowAnyException();
  }

  private static SwingBatchEngine engineWithMode(String mode) {
    return new SwingBatchEngine(
        null, null, null, null, null, java.util.Optional.empty(), null, null, null, null, null,
        mode);
  }

  /** The named service's own YAML lines: from its 2-space key to the next key at that indent. */
  private static List<String> serviceBlock(List<String> compose, String service) {
    int from = compose.indexOf("  " + service + ":");
    if (from < 0) {
      fail("no '  " + service + ":' block in deploy/docker-compose.yml — did it get renamed?");
    }
    int to = compose.size();
    for (int i = from + 1; i < compose.size(); i++) {
      String line = compose.get(i);
      if (line.startsWith("  ") && !line.startsWith("   ") && line.trim().endsWith(":")) {
        to = i;
        break;
      }
    }
    return compose.subList(from + 1, to);
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
