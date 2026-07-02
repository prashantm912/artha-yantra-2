package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The FailPolicy registry must stay in lockstep with the gate source (audit P2): every rail name
 * the gate records must have a declared {@link FailPolicy} in {@link RailPolicies}, and the
 * registry must carry no phantom entries. Adding a {@code diag.fails("new-rail", ...)} without a
 * registry line fails THIS test (source scan) — and also throws at runtime the first time the rail
 * is exercised ({@code RailPolicies.of}) — so a new rail cannot ship without declaring whether it
 * fails open or closed on missing data.
 */
class RailPolicyTableTest {

  private static final Pattern RAIL_LITERAL =
      Pattern.compile("(?:fails|failsBool|failsScore)\\(\\s*\"([a-z0-9-]+)\"");

  @Test
  void everyRailInTheGateHasADeclaredPolicyAndViceVersa() throws IOException {
    Set<String> inSource = railsDeclaredInGateSource();
    Set<String> inRegistry = new TreeSet<>(RailPolicies.all().keySet());

    assertThat(inSource).as("rails found in ScalperConfluenceGate source").isNotEmpty();

    Set<String> missing = new TreeSet<>(inSource);
    missing.removeAll(inRegistry);
    assertThat(missing)
        .withFailMessage(
            "these gate rails have NO declared FailPolicy — add each to RailPolicies with its"
                + " missing-data polarity (FAIL_OPEN degrades to pass, FAIL_CLOSED blocks): %s",
            missing)
        .isEmpty();

    Set<String> phantom = new TreeSet<>(inRegistry);
    phantom.removeAll(inSource);
    assertThat(phantom)
        .withFailMessage("RailPolicies declares rails the gate no longer records (remove): %s", phantom)
        .isEmpty();
  }

  @Test
  void everyDeclaredPolicyResolvesWithoutThrowing() {
    for (String rail : RailPolicies.all().keySet()) {
      assertThat(RailPolicies.of(rail)).isNotNull();
    }
  }

  private static Set<String> railsDeclaredInGateSource() throws IOException {
    Path src =
        findModuleRoot()
            .resolve(
                "src/main/java/in/arthayantra/strategysignal/scalper/ScalperConfluenceGate.java");
    Matcher m = RAIL_LITERAL.matcher(Files.readString(src));
    Set<String> rails = new TreeSet<>();
    while (m.find()) {
      rails.add(m.group(1));
    }
    return rails;
  }

  private static final String GATE =
      "src/main/java/in/arthayantra/strategysignal/scalper/ScalperConfluenceGate.java";

  /** The module dir — surefire's cwd is the module, but tolerate a repo-root run too. */
  private static Path findModuleRoot() {
    Path cwd = Path.of("").toAbsolutePath();
    if (Files.exists(cwd.resolve(GATE))) {
      return cwd;
    }
    Path fromRepoRoot = cwd.resolve("services/strategy-signal-service");
    assertThat(fromRepoRoot.resolve(GATE)).as("gate source locatable").exists();
    return fromRepoRoot;
  }
}
