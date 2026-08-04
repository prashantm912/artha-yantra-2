package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategyschema.ValidationResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Couples the two modules that independently decide whether a scalper's only stop bounds it, and
 * that today agree only by coincidence of a shared {@code "options_of_underlying"} string literal:
 *
 * <ul>
 *   <li>{@code SemanticValidator.checkOptionsPlaneLevelBases} (#1284) — which level bases may be
 *       DECLARED on an options strategy, checked at save/publish.
 *   <li>{@link ScalperRisk#hasBoundingExit} — which of them count as the §0B bounding exit, checked
 *       at engine load, for exactly the same universe mode ({@code SignalEngine}'s scalper keying).
 * </ul>
 *
 * <p><b>Why this test exists rather than a comment.</b> {@code ScalperRiskTest} builds
 * {@code ExitRuleSpec} objects by hand, so it never touches validation and stays green however far
 * the two drift. Before #1284 that blindness was live: {@code percent} was refused by neither side,
 * so a scalper whose only stop was {@code {percent, 25}} loaded as BOUNDED with no enforceable stop
 * on either plane — ~25% of a ~24,000 index never fires intraday, and no premium bracket is built.
 * #1284 made that unreachable by refusing the basis upstream, but nothing tied the two decisions
 * together: widen the mode keying on either side, or relax the refusal for an unrelated reason, and
 * the §0B hole returns with every existing test still passing.
 *
 * <p>So every case here is built as a real config and driven through {@code
 * StrategyDocuments.validateTree} — the same entry {@code RegistryService} uses at publish — and
 * through {@code StrategyCompiler}. The only things this test supplies are the basis name, the
 * value, and the frozen expectation; both verdicts come from production code.
 */
class ScalperStopBasisCouplingTest {

  /** A real seeded scalper — an options_of_underlying config in exactly the shape that ships. */
  private static final String BASE_SCALPER = "/scalper-strategies/scalp-connect-the-dots-nifty.yaml";

  /**
   * The frozen joint verdict of the two modules for every basis the schema's {@code
   * levelParams.basis} enum admits. A cell moves only by a deliberate edit to one of them.
   */
  private record Verdict(boolean acceptedOnOptions, boolean countsAsBoundingExit) {}

  private static final Map<String, Verdict> FROZEN = frozen();

  private static Map<String, Verdict> frozen() {
    Map<String, Verdict> m = new LinkedHashMap<>();
    // Legal on the options plane (it NAMES that plane), but its enforcement path is the paper
    // bracket, which does not run when a signal is not taken into paper — so it cannot be the
    // §0B floor on its own.
    m.put("premium_pct", new Verdict(true, false));
    // REFUSED on the options plane (#1284): the name does not say which of the two planes it
    // means. Also no longer counted as a bound, so a widened refusal cannot reopen the hole.
    m.put("percent", new Verdict(false, false));
    // Index-side and unambiguous — the two bases that bound a scalper on their own.
    m.put("atr_multiple", new Verdict(true, true));
    m.put("index_points", new Verdict(true, true));
    // Legal, but derives its distance from ANOTHER stop's initial risk: not self-sufficient.
    m.put("r_multiple", new Verdict(true, false));
    return Map.copyOf(m);
  }

  /**
   * Two values three orders of magnitude apart, asserted to give the SAME verdict. This pins the
   * correction #1284's review made to the old {@code ENGINE_SIDE_STOP_BASES} javadoc: {@code
   * percent} is not "inert at index scale" — {@code ExitEvaluator.levelDistance} computes it and
   * {@code premium_pct} with one shared {@code entryPrice × value ÷ 100} arm, so whether a level
   * fires is a property of the VALUE ({@code {percent, 0.3}} on NIFTY is ~72 index points and
   * fires normally; {@code {percent, 25}} is ~6,000 and never does). Neither module may start
   * deciding on magnitude — that would be a rule the config author cannot see.
   */
  private static final List<Double> VALUES = List.of(0.3, 25.0);

  @Test
  void everyLevelBasisKeepsItsFrozenVerdictFromBothModules() throws IOException {
    ObjectNode base = baseScalperConfig();
    for (Map.Entry<String, Verdict> entry : FROZEN.entrySet()) {
      String basis = entry.getKey();
      Verdict expected = entry.getValue();
      for (double value : VALUES) {
        ObjectNode config = withSingleStop(base, basis, value);
        String where = basis + " (value=" + value + ")";

        ValidationResult result = StrategyDocuments.validateTree(config);
        assertThat(result.valid())
            .as(
                where
                    + ": SemanticValidator's verdict on an options_of_underlying config — errors="
                    + result.errors())
            .isEqualTo(expected.acceptedOnOptions());

        if (!expected.acceptedOnOptions()) {
          // A refusal only counts if it came from the PLANE check. Without this, a fixture that
          // broke for some unrelated reason would read as the safety rule working.
          assertThat(result.errors().stream().map(i -> i.path()))
              .as(where + ": must be refused BY the level-basis check, not by an unrelated rule")
              .contains("/exit_rules/0/params/basis");
        }

        assertThat(ScalperRisk.hasBoundingExit(StrategyCompiler.compile(config).exitRules()))
            .as(where + ": ScalperRisk's §0B bounding-exit verdict for the SAME config")
            .isEqualTo(expected.countsAsBoundingExit());
      }
    }
  }

  @Test
  void everyBasisCountedAsABoundIsOneValidationAcceptsOnAnOptionsStrategy() throws IOException {
    // ScalperRisk is consulted ONLY for options_of_underlying strategies, and only for configs
    // that already passed publish validation. A basis it counts as the §0B bound but that
    // validation refuses on that plane is a rule about configs which cannot exist — dead, and
    // actively misleading to the next editor about what bounds a scalper. Derived from the sweep
    // below, NOT from the frozen table, so it holds for any basis either side gains later.
    ObjectNode base = baseScalperConfig();
    List<String> countedButRefused = new ArrayList<>();
    for (String basis : new TreeSet<>(FROZEN.keySet())) {
      ObjectNode config = withSingleStop(base, basis, 25.0);
      boolean accepted = StrategyDocuments.validateTree(config).valid();
      boolean counted = ScalperRisk.hasBoundingExit(StrategyCompiler.compile(config).exitRules());
      if (counted && !accepted) {
        countedButRefused.add(basis);
      }
    }
    assertThat(countedButRefused)
        .as(
            "bases ScalperRisk.ENGINE_SIDE_STOP_BASES counts as a §0B bound that SemanticValidator"
                + " refuses on the options plane — the two modules have drifted apart")
        .isEmpty();
  }

  /** The seeded scalper config, parsed but NOT modified — the fixture's starting point. */
  private static ObjectNode baseScalperConfig() throws IOException {
    try (InputStream in = ScalperStopBasisCouplingTest.class.getResourceAsStream(BASE_SCALPER)) {
      assertThat(in).as("classpath resource " + BASE_SCALPER).isNotNull();
      JsonNode config = StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
      assertThat(config.path("universe").path("mode").asText())
          .as("the fixture must be options_of_underlying — both checks key on that literal")
          .isEqualTo("options_of_underlying");
      return (ObjectNode) config.deepCopy();
    }
  }

  /**
   * The base config with its exits replaced by a SINGLE {@code stop_loss} on the basis under test —
   * so whether the strategy is §0B-bounded rests entirely on that one rule. The seeded
   * {@code backtest.optimize} block tunes {@code exit_rules[type=time_stop].params.max_bars}, which
   * no longer resolves once the exits are replaced; left in place, {@code checkOptimizeParameters}
   * would refuse every case for a reason that has nothing to do with the basis. The
   * {@code atr_multiple} / {@code index_points} rows are the control: they are expected to VALIDATE,
   * so a fixture broken in some other way cannot pass as the plane check working.
   */
  private static ObjectNode withSingleStop(ObjectNode base, String basis, double value) {
    ObjectNode config = base.deepCopy();
    ObjectNode rule = config.objectNode();
    rule.put("type", "stop_loss");
    rule.putObject("params").put("basis", basis).put("value", value);
    ArrayNode exits = config.putArray("exit_rules");
    exits.add(rule);
    JsonNode backtest = config.path("backtest");
    if (backtest.isObject()) {
      ((ObjectNode) backtest).remove("optimize");
    }
    return config;
  }
}
