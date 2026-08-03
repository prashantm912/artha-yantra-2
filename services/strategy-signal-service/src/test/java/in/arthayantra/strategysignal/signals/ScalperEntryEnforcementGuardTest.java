package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Architecture guard for the #960 option-side ENTRY-enforcement invariant (maintainability follow-up).
 *
 * <p>{@code ScalperConfluenceGate} holds the LIVE-vs-BACKTEST parity guard by METHOD IDENTITY, not the
 * type system: the ENTRY form {@code evaluateWithDiagnostic(...)} ENFORCES the strategy's declared
 * {@code option_types} against the VWAP-derived side (a {@code -pe}-only slug cannot OPEN a CE), while
 * the bare {@code evaluate(...)} decision read does NOT — because the E9 D4 confluence-flip EXIT oracle
 * ({@code SignalEngine.confluenceFlipExit}) must report the TRUE market side, INCLUDING the one the
 * strategy will not ENTER, or a held single-side position could never detect a protective flip against
 * it (silently disabling a stop-loss on the scalpers).
 *
 * <p>That split is safe ONLY while every {@code SignalEngine} path that OPENS a position routes through
 * the enforcing {@code evaluateWithDiagnostic}. This test pins it structurally: the bare, non-enforcing
 * {@code evaluate(...)} may be called from EXACTLY ONE place in {@code SignalEngine} — {@code
 * confluenceFlipExit} (the exit oracle). A future entry path that reaches for the bare {@code
 * evaluate(...)} — re-opening the {@code -pe}-slug-enters-CE parity hole #960 closed — turns THIS test
 * red. The two BEHAVIOURAL halves of the contract are pinned separately by {@code
 * ScalperConfluenceGateTest} ({@code optionSideConstraintRailEnforcesDeclaredOptionTypesAgainstTheVwapSide}
 * proves the entry form enforces; {@code flipExitOracleEvaluateSeesTheTrueSideEvenWhenOptionTypesExcludesIt}
 * proves the bare oracle does not).
 */
class ScalperEntryEnforcementGuardTest {

  private static final String SIGNAL_ENGINE =
      "src/main/java/in/arthayantra/strategysignal/signals/SignalEngine.java";
  // The non-enforcing ORACLE read. The trailing '(' is load-bearing: it excludes the enforcing
  // evaluateWithDiagnostic( (which reads "evaluate" + "WithDiagnostic(", never "evaluateOracle(").
  //
  // NOTE (V056 exit-oracle shadow): the exit oracle now calls `evaluateOracle(...)` — the SAME
  // evaluateInternal invocation with enforceOptionSide=false that the bare `evaluate(...)` always
  // made, differing only in that it returns the Result instead of discarding the diagnostic it had
  // already built. The invariant this test guards is UNCHANGED in substance: exactly one
  // NON-ENFORCING gate read in the engine, and it lives in the protective exit oracle. Both
  // non-enforcing spellings are counted, so reverting to the bare form, or adding either one on a
  // new path, still turns this red.
  private static final String BARE_EVALUATE = "scalperGate.get().evaluate(";
  private static final String ORACLE_EVALUATE = "scalperGate.get().evaluateOracle(";
  private static final String ENFORCING_ENTRY = "scalperGate.get().evaluateWithDiagnostic(";

  @Test
  void theOnlyBareNonEnforcingGateReadInSignalEngineIsTheFlipExitOracle() throws IOException {
    String src = Files.readString(moduleRoot().resolve(SIGNAL_ENGINE));

    // The ENTRY paths (where the live engine opens positions) must use the ENFORCING form.
    assertThat(count(src, ENFORCING_ENTRY))
        .as("SignalEngine ENTRY paths must call the enforcing evaluateWithDiagnostic(...)")
        .isGreaterThanOrEqualTo(1);

    // There is exactly ONE non-enforcing gate read in the whole engine, counting BOTH spellings...
    int nonEnforcing = count(src, BARE_EVALUATE) + count(src, ORACLE_EVALUATE);
    assertThat(nonEnforcing)
        .withFailMessage(
            "a NEW non-enforcing scalperGate read (evaluate(...) or evaluateOracle(...)) appeared in"
                + " SignalEngine — it SKIPS the #960 option-side ENTRY guard. If this is an entry-emit"
                + " path it re-opens the -pe-slug-enters-CE parity hole; route it through the enforcing"
                + " evaluateWithDiagnostic(...). Only confluenceFlipExit (the protective EXIT oracle,"
                + " which MUST see the opposite side) may read a non-enforcing form. Found %s"
                + " occurrence(s).",
            nonEnforcing)
        .isEqualTo(1);

    // ...and it lives inside confluenceFlipExit — the protective EXIT oracle, never an entry path.
    String flipExitBody = methodBody(src, "private boolean confluenceFlipExit(");
    assertThat(flipExitBody)
        .as("the single non-enforcing gate read must be confluenceFlipExit's exit-oracle read")
        .satisfiesAnyOf(
            body -> assertThat(body).contains(BARE_EVALUATE),
            body -> assertThat(body).contains(ORACLE_EVALUATE));
  }

  /** A method's source from its declaration to the next 2-space-indented class member. */
  private static String methodBody(String src, String declaration) {
    int start = src.indexOf(declaration);
    assertThat(start).as("declaration present in SignalEngine: " + declaration).isNotNegative();
    int next = src.indexOf("\n  private ", start + declaration.length());
    return next < 0 ? src.substring(start) : src.substring(start, next);
  }

  private static int count(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
      n++;
    }
    return n;
  }

  /** Module dir — surefire's cwd is the module, but tolerate a repo-root run too (per RailPolicyTableTest). */
  private static Path moduleRoot() {
    Path cwd = Path.of("").toAbsolutePath();
    if (Files.exists(cwd.resolve(SIGNAL_ENGINE))) {
      return cwd;
    }
    Path fromRepoRoot = cwd.resolve("services/strategy-signal-service");
    assertThat(fromRepoRoot.resolve(SIGNAL_ENGINE)).as("SignalEngine source locatable").exists();
    return fromRepoRoot;
  }
}
