package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ H20(2): REPLAY SIZES UNGRADED WHILE LIVE DOES NOT. This pins the gap so it cannot change by
 * accident, in either direction.
 *
 * <p><b>The divergence.</b> Live applies an E8 confluence-graded size multiplier before lot-rounding
 * (SignalEngine → the grading helper → PaperEmissionGuard). {@code ReplayEngine.size()} calls
 * {@code PositionSizer} directly with no multiplier, so a backtest sizes UP TO 2× what live would
 * have taken on the same signal — which flatters every absolute P&amp;L number it reports.
 *
 * <p><b>Why this is pinned rather than fixed</b> (owner ruling 2026-08-29). Porting the multiplier
 * requires its INPUTS, and replay does not have them: the OI-imbalance and VIX inputs appear ZERO
 * times in this service, and the confluence aggregate comes from the OI gate, which is systematically
 * MUTED on derived history (Dow and IV degrade to NEUTRAL). Applying it there would size every
 * historical backtest near the 0.50 floor for a DATA-FIDELITY reason rather than a market one — not
 * "matching live", just differently wrong.
 *
 * <p><b>What this test is really for.</b> The gap is otherwise invisible in code: nothing in
 * backtest-service applies grading, so a reader cannot tell "deliberately ungraded" from "nobody
 * thought about it". This test is that difference. If someone later ports the multiplier it fails and
 * forces them to read the ruling and delete it ON PURPOSE — which is what should happen, since that
 * port re-bases historical backtest numbers the owner has already looked at.
 *
 * <p>Deliberately a SOURCE-LEVEL assertion. A behavioural test would need a graded expectation to
 * compare against, and there is none to construct — the whole point is that the inputs do not exist
 * here. The absence IS the invariant.
 */
class ReplaySizesUngradedTest {

  private static final Path SERVICE_SRC = Path.of("src", "main", "java");

  /**
   * The live grading API, named in pieces so this file does not trip its own assertion.
   *
   * <p>⚠️ This indirection is not cleverness for its own sake. The first cut searched raw source and
   * failed immediately — on the explanatory comment in {@code BacktestRunner} that NAMES the grading
   * API while explaining why this service does not use it. A raw grep cannot tell "applies the API"
   * from "documents why it is absent", and the documentation is the thing we WANT there.
   */
  private static final String GRADER = "Scalper" + "Sizing";

  private static final String MULTIPLIER = "size" + "Multiplier";

  @Test
  void backtestServiceNeverAppliesTheLiveGradingApi() throws IOException {
    List<String> offenders;
    try (Stream<Path> java = Files.walk(SERVICE_SRC)) {
      offenders =
          java.filter(p -> p.toString().endsWith(".java"))
              .filter(p -> appliesGrading(read(p)))
              .map(Path::toString)
              .toList();
    }

    assertThat(offenders)
        .as(
            "backtest-service must not apply the live E8 size grading. If this fails because the"
                + " multiplier was deliberately ported, DELETE this test and re-base the historical"
                + " backtest numbers on purpose — do not silence it. See ledger H20 part 2: the"
                + " grading inputs do not exist in this service and are MUTED on derived history, so"
                + " a port sizes history near the 0.50 floor for a data-fidelity reason rather than a"
                + " market one.")
        .isEmpty();
  }

  /**
   * The other half, and the reason the search above is not merely trivia: {@code ReplayEngine} really
   * does size through the ungraded path. If it ever grows a multiplier argument this stops matching
   * and the divergence has changed without anyone updating the ruling.
   */
  @Test
  void replayEngineSizesThroughTheUngradedPositionSizer() {
    String body =
        read(SERVICE_SRC.resolve(
            Path.of("in", "arthayantra", "backtest", "replay", "ReplayEngine.java")));

    assertThat(stripComments(body))
        .as("ReplayEngine must size via PositionSizer directly — the ungraded path")
        .contains("PositionSizer.size(");
  }

  /** CODE only: prose explaining the absence must not count as applying it. */
  private static boolean appliesGrading(String java) {
    String code = stripComments(java);
    return code.contains(GRADER) || code.contains(MULTIPLIER);
  }

  /**
   * Removes block and line comments so the assertion judges CODE, not prose.
   *
   * <p>Deliberately simple: it does not understand comment markers inside string literals. That is
   * acceptable because a false POSITIVE fails loudly and is trivially diagnosed, while a false
   * negative would let a real port through silently. Fail in the loud direction.
   */
  private static String stripComments(String java) {
    return java.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\n]*", " ");
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new IllegalStateException("unreadable: " + p, e);
    }
  }
}
