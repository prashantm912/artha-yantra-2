package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.config.GateNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.eval.BarValues;
import in.arthayantra.strategyschema.CanonicalJson;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Pins the invariant that makes {@link SwingBatchEngine}'s symbol-only lot keying HARMLESS: every
 * strategy within one swing family must resolve the SAME exit decision.
 *
 * <p><b>Why this is a test and not a comment.</b> {@code SwingBatchEngine.openLotsBySymbol:562-570}
 * groups open ENTRY anchors by {@code tradingsymbol} ALONE — no strategy dimension. The exit pass
 * ({@code exitPass:766-800}) then picks {@code oldestLot(lots)} across the WHOLE per-symbol group,
 * evaluates {@link in.arthayantra.strategyengine.eval.ExitEvaluator} with THAT lot's definition,
 * closes the shared paper position and expires EVERY lot in the group ({@code :1052-1054}).
 *
 * <p><b>⚠️ The collapse keys on {@code strategy_version_id}, NOT on strategy — and this test compares
 * STRATEGIES. Read that gap before trusting a green run.</b> Each anchor is stamped with
 * {@code strat.versionId()} ({@code :713}), and {@code AnchorResolution.resolve:351-357} falls
 * through to {@code adoptVersion:365-408}, which exit-manages a superseded anchor with <i>that
 * version's own frozen config</i>. So the two lots that collide need not belong to two strategies —
 * <b>two versions of ONE strategy collide identically</b>, and the swing seeders AUTO-PUBLISH on any
 * bundled-YAML change ({@code ManasAroraStrategySeeder:104-118}, unlike the scalper seeder which only
 * drafts). The reachable shape:
 *
 * <pre>
 *   lot 1 opens under manas-arora-vcp v1.0.0
 *   owner tunes arm_pct 9 → 6 in BOTH Manas YAMLs   → this test stays GREEN (they still agree)
 *   deploy; seeder auto-publishes v1.0.1
 *   lot 2 adds under v1.0.1
 *   oldestLot = lot 1 @ v1.0.0 → BOTH lots exit on the OLD 9% arm, both expired
 * </pre>
 *
 * <p>That is not exotic: 15 live Minervini anchors currently resolve through <b>6 version ids</b>
 * against 4 published strategies. A unit test cannot reach it — the version rows live in the database
 * — so it is recorded here and in the findings doc's §8 rather than silently uncovered. What this
 * test DOES bound is the cross-strategy axis: a family whose members disagree at one point in time.
 *
 * <p>Both shapes — two strategies, or two versions of one — are currently unreachable, because both
 * need a second lot on a held symbol: Minervini's {@code pyramid()} returns the
 * {@code PyramidPolicy.NONE} literal (compile-time), and Manas's is gated behind
 * {@code artha.manas-arora.pyramid.enabled}, deployed {@code false}. Identical exit rules are the
 * SECOND safety net, the one that makes the collapse harmless rather than merely unreachable. It is
 * a data coincidence today, held by nothing: a single YAML edit removes it silently, and the flag
 * that removes the first net has been armed before (F2 #612). Full reachability analysis:
 * {@code docs/signal-analysis/2026-08-04-swing-symbol-key-reachability.md}.
 *
 * <p><b>Population — deliberately DISCOVERED, not listed.</b> A hardcoded family list is exactly how
 * the invariant would decay, so this scans the classpath for every bundled {@code *-strategies/*.yaml},
 * keeps the {@code swing}-session ones, and groups them by {@code universe.mode} — the same key the
 * engine isolates families on ({@code loadPublishedSwingStrategies:1147}, {@code adoptVersion:374}).
 * A new family added as a new resource directory is covered with no edit here, and
 * {@link #everySwingDoctrineHasOneDiscoveredBundledFamily()} fails if a new {@link SwingDoctrine} bean
 * appears whose family this scan did NOT find.
 *
 * <p><b>What this does NOT cover, stated so nobody over-reads a green run.</b> It asserts the BUNDLED
 * YAML at ONE point in time, so it is blind on two axes:
 *
 * <ol>
 *   <li><b>The VERSION axis (the one that matters — see above).</b> Two lots resolving to different
 *       {@code strategy_version_id}s of the SAME strategy diverge exactly as two strategies would,
 *       and editing every family member together — the well-behaved thing to do — keeps this test
 *       green while creating precisely that divergence across time. Only a check over
 *       {@code strategy_versions} rows can see it.
 *   <li><b>The republish-LAG axis.</b> A config change is a silent no-op until republished, so a
 *       published version can carry exit rules this test never sees.
 * </ol>
 *
 * <p>The two point in OPPOSITE directions and should not be conflated: (2) makes the guard
 * conservative (it reds on a YAML nobody is running yet), while (1) makes it permissive (it passes a
 * divergence that is live). Covering either needs a live-data check, not a unit test. What this guard
 * buys is catching a same-instant cross-strategy edit in the PR that makes it, which is where it is
 * cheapest to catch.
 */
class SwingFamilyExitDoctrineTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Every bundled strategy doc lives in a {@code <family>-strategies/} classpath directory. */
  private static final String BUNDLED_STRATEGY_GLOB = "classpath*:*-strategies/*.yaml";

  private static final String WHY =
      """

      WHY IDENTICAL EXIT RULES ARE LOAD-BEARING (do not just "fix the diff" — read this first):

        SwingBatchEngine.openLotsBySymbol:562-570 groups a family's open lots by tradingsymbol
        ALONE, with no strategy dimension. exitPass:766-800 drives the exit off oldestLot(lots),
        using ONLY that lot's definition, then closes the shared paper position and expires EVERY
        lot (:1052-1054).

        Consequence: when two lots of one family share a symbol, the younger lot is exited by the
        OLDER lot's rules. Identical exit rules are what makes that harmless.

        Note the collision is per strategy_version_id, not per strategy (:713 stamps the version;
        adoptVersion:365-408 exit-manages a superseded anchor with that version's frozen config).
        Two versions of ONE strategy collide the same way — so editing every family member
        together keeps THIS test green while still diverging lot-1 from lot-2 across a republish.
        This test bounds the cross-strategy axis only; the version axis needs a DB check.

        The collapse is unreachable today (Minervini pyramid=NONE at compile time; Manas gated by
        artha.manas-arora.pyramid.enabled=false) — but that flag has been armed before, and 41.9%
        of Manas screen days offer a candidate eligible for BOTH its strategies.

      IF YOU INTEND THIS DIVERGENCE, the symbol-only keying must be fixed FIRST — see
      docs/signal-analysis/2026-08-04-swing-symbol-key-reachability.md sections 6 and 8.
      """;

  /** One bundled strategy doc, reduced to what the exit decision actually depends on. */
  private record SwingStrategyDoc(String slug, String family, String exitFingerprint) {}

  @Test
  void everyStrategyInOneSwingFamilyResolvesTheSameExitDecision() throws IOException {
    Map<String, List<SwingStrategyDoc>> families = discoverSwingFamilies();

    assertThat(families)
        .as(
            "the classpath scan (%s) found no bundled swing strategies at all — the guard would pass"
                + " vacuously, which is worse than failing",
            BUNDLED_STRATEGY_GLOB)
        .isNotEmpty();

    for (Map.Entry<String, List<SwingStrategyDoc>> family : families.entrySet()) {
      List<SwingStrategyDoc> members = family.getValue();
      if (members.size() < 2) {
        continue; // a single-strategy family has no coincidence to protect
      }
      SwingStrategyDoc reference = members.get(0);
      for (SwingStrategyDoc member : members.subList(1, members.size())) {
        assertThat(member.exitFingerprint())
            .as(
                "swing family '%s': '%s' and '%s' must resolve the SAME exit decision.%s",
                family.getKey(), reference.slug(), member.slug(), WHY)
            .isEqualTo(reference.exitFingerprint());
      }
    }
  }

  /**
   * The anti-decay half: a new swing family must not be able to appear without this guard covering
   * it. Every concrete {@link SwingDoctrine} component IS a swing family by construction (the engine
   * loads strategies per doctrine), so the doctrines are the authority on WHICH families exist.
   *
   * <p>Compares the family NAMES, not just how many there are. Counting alone lets two simultaneous
   * changes cancel — a new doctrine whose YAML sits outside {@code *-strategies/}, plus an existing
   * directory splitting into two {@code universe.mode}s — which is exactly the decay this assertion
   * exists to prevent.
   */
  @Test
  void everySwingDoctrineHasOneDiscoveredBundledFamily() throws IOException {
    Set<String> discovered = discoverSwingFamilies().keySet();
    Map<String, String> doctrines = swingDoctrineFamilies(); // universeMode -> declaring class

    assertThat(discovered)
        .as(
            "the SwingDoctrine components declare families %s but the classpath scan found %s."
                + " A swing family the scan misses is SILENTLY EXEMPT from"
                + " everyStrategyInOneSwingFamilyResolvesTheSameExitDecision — the exact way this"
                + " guard would decay. Either bundle that family's docs under <family>-strategies/"
                + " on the classpath, or widen %s to reach them.%s",
            doctrines, discovered, BUNDLED_STRATEGY_GLOB, WHY)
        .containsExactlyInAnyOrderElementsOf(doctrines.keySet());
  }

  // ---- discovery ------------------------------------------------------------------------------

  /** Bundled swing strategy docs grouped by {@code universe.mode} — the engine's family key. */
  private static Map<String, List<SwingStrategyDoc>> discoverSwingFamilies() throws IOException {
    Map<String, List<SwingStrategyDoc>> families = new TreeMap<>();
    for (Resource resource :
        new PathMatchingResourcePatternResolver().getResources(BUNDLED_STRATEGY_GLOB)) {
      String body;
      try (InputStream in = resource.getInputStream()) {
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      JsonNode config = StrategyDocuments.parse(body).config();
      if (!"swing".equals(config.path("risk").path("session").path("style").asText())) {
        continue; // scalpers are driven per-anchor by SignalEngine — no per-symbol lot grouping
      }
      families
          .computeIfAbsent(config.path("universe").path("mode").asText(), k -> new ArrayList<>())
          .add(
              new SwingStrategyDoc(
                  config.path("id").asText(),
                  config.path("universe").path("mode").asText(),
                  exitFingerprint(config)));
    }
    families.values().forEach(m -> m.sort(Comparator.comparing(SwingStrategyDoc::slug)));
    return families;
  }

  /**
   * The {@code universe.mode} each concrete {@code @Component SwingDoctrine} owns, mapped to its
   * declaring class.
   *
   * <p>Read without a Spring context: {@code CALLS_REAL_METHODS} over an Objenesis-constructed
   * instance runs the real {@code universeMode()} while bypassing the {@code @Value}-injected
   * constructor. Sound because {@code universeMode()} is a family DESCRIPTOR — both implementations
   * return a bare literal and touch no field. A future doctrine that computes it from injected state
   * would return null here and fail the assertion loudly rather than silently narrowing coverage.
   */
  private static Map<String, String> swingDoctrineFamilies() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
    Map<String, String> found = new TreeMap<>();
    for (var bean : scanner.findCandidateComponents("in.arthayantra.strategysignal")) {
      Class<?> type;
      try {
        type = Class.forName(bean.getBeanClassName());
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("scanned bean not loadable: " + bean, e);
      }
      if (!SwingDoctrine.class.isAssignableFrom(type)) {
        continue;
      }
      String mode =
          ((SwingDoctrine) Mockito.mock(type, Mockito.CALLS_REAL_METHODS)).universeMode();
      assertThat(mode)
          .as(
              "%s.universeMode() read as null — it is expected to return a bare literal so this"
                  + " guard can enumerate families without a Spring context. If it now derives from"
                  + " injected state, this cross-check needs a real context instead.",
              type.getSimpleName())
          .isNotNull();
      found.put(mode, type.getSimpleName());
    }
    return found;
  }

  // ---- the fingerprint ------------------------------------------------------------------------

  /**
   * Canonical JSON of everything the exit decision reads: the {@code exit_rules} array in order
   * (precedence is significant — {@code ExitEvaluator} returns the first rule that fires), plus the
   * declaration of every indicator those rules resolve an operand through.
   *
   * <p><b>The operand half is not optional, and it has TWO indirections — missing either one makes
   * this guard pass a family whose exits genuinely differ.</b>
   *
   * <ol>
   *   <li><b>{@code params.alias}</b> — Minervini's trail is
   *       {@code {trailing_stop, basis: indicator, alias: sma50}}, textually identical across all four
   *       strategies while the exit LEVEL is whatever each declares {@code sma50} to be
   *       ({@code ExitEvaluator#indicatorLevel} → {@code bank.valueAt(alias)}).
   *   <li><b>Operands named INSIDE a {@code signal_exit} rule STRING</b> — {@code
   *       ExitEvaluator#signalExit:715-732} compiles {@code params.rule} with {@code
   *       StrategyCompiler.compileLeafText} and evaluates it against the bank, so
   *       {@code crossunder(ema20, ema50)} resolves {@code ema50} exactly like an alias field would.
   *       Two strategies with that identical rule string and DIFFERENT {@code ema50} declarations
   *       produce different exits.
   * </ol>
   *
   * <p>Operands are extracted by compiling the rule with the ENGINE'S OWN parser and walking the
   * resulting {@link GateNode}, so this cannot drift from what the evaluator actually resolves.
   *
   * <p>Only the fields {@code IndicatorBank} computes a VALUE from are included ({@code name},
   * {@code timeframe}, {@code params}, {@code instrument}). {@code weight} and {@code normalize} are
   * deliberately excluded: they feed entry SCORING, never an exit level, so including them would red
   * this guard on a legitimate entry-only tune.
   *
   * <p><b>Built-in operands are skipped via {@link BarValues#isBuiltin}</b> — the engine's own
   * predicate, not a copy. Both resolution sites test it FIRST ({@code GateEvaluator:116},
   * {@code ExitEvaluator.indicatorLevel:686}), so {@code close}/{@code volume}/{@code vwap} shadow
   * any same-named declaration and folding such a declaration in could only invent a divergence the
   * engine ignores. Reusing the predicate means this cannot drift if the builtin set grows.
   *
   * <p>Only TWO indirections exist, and that is measured rather than assumed: {@code ExitEvaluator}
   * reads 19 distinct exit-rule param keys ({@code activate_at, alias, arm_pct, atr_basis,
   * atr_period, basis, breakeven_floor, cap_pct, fast_bars, fast_pct, max_bars, max_holding_days,
   * min_volume, parabolic_dist_pct, parabolic_ma, rule, tiers, trail_by, value}). Every one except
   * {@code alias} and {@code rule} is consumed as an in-place numeric or string literal —
   * {@code tiers} reads only {@code profit_pct}/{@code qty_pct}, {@code atr_basis} is compared to
   * {@code "rolling"}, {@code trail_by}/{@code activate_at} go through {@code decimal(...)} — so
   * none of them can name an indicator.
   */
  private static String exitFingerprint(JsonNode config) {
    JsonNode exitRules = config.path("exit_rules");

    Set<String> operands = new TreeSet<>(); // sorted → the fingerprint is order-stable
    for (JsonNode rule : exitRules) {
      JsonNode alias = rule.path("params").path("alias");
      if (alias.isTextual()) {
        operands.add(alias.asText());
      }
      JsonNode ruleText = rule.path("params").path("rule");
      if (ruleText.isTextual()) {
        collectOperands(StrategyCompiler.compileLeafText(ruleText.asText()), operands);
      }
    }

    ObjectNode operandIndicators = MAPPER.createObjectNode();
    for (String operand : operands) {
      if (BarValues.isBuiltin(operand)) {
        continue; // resolves via bank.builtin and shadows any same-named declaration
      }
      ObjectNode declaration = MAPPER.createObjectNode();
      for (JsonNode indicator : config.path("indicators")) {
        if (operand.equals(indicator.path("alias").asText())) {
          declaration.set("name", indicator.path("name"));
          declaration.set("timeframe", indicator.path("timeframe"));
          declaration.set("params", indicator.path("params"));
          declaration.set("instrument", indicator.path("instrument"));
          break;
        }
      }
      operandIndicators.set(operand, declaration);
    }

    ObjectNode fingerprint = MAPPER.createObjectNode();
    fingerprint.set("exit_rules", exitRules);
    fingerprint.set("exit_rule_operands", operandIndicators);
    // CanonicalJson (not toPrettyString): key order follows the YAML as authored, so two semantically
    // identical param maps written in a different order would otherwise fingerprint differently and
    // red this guard spuriously.
    return CanonicalJson.write(fingerprint);
  }

  /**
   * Every operand name a compiled gate/exit expression resolves against the bank.
   *
   * <p>No {@code default} branch on purpose: {@link GateNode} is sealed, so adding a variant makes
   * this switch fail to COMPILE rather than silently skip the new node's operands at runtime — the
   * same anti-decay reasoning as the discovered family population.
   */
  private static void collectOperands(GateNode node, Set<String> into) {
    switch (node) {
      case GateNode.All all -> all.children().forEach(c -> collectOperands(c, into));
      case GateNode.Any any -> any.children().forEach(c -> collectOperands(c, into));
      case GateNode.Not not -> collectOperands(not.child(), into);
      case GateNode.Crossover cross -> {
        into.add(cross.fast());
        into.add(cross.slow());
      }
      case GateNode.Crossunder cross -> {
        into.add(cross.fast());
        into.add(cross.slow());
      }
      case GateNode.Expression expression -> {
        into.add(expression.left());
        if (expression.rightOperand() != null) {
          into.add(expression.rightOperand()); // null ⇒ a numeric literal, nothing to resolve
        }
      }
    }
  }
}
