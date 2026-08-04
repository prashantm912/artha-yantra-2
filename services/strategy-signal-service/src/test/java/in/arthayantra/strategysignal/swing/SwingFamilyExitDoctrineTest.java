package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * evaluates {@link in.arthayantra.strategyengine.eval.ExitEvaluator} with THAT lot's strategy
 * definition, closes the shared paper position and expires EVERY lot in the group
 * ({@code :1052-1054}). So if two strategies of one family ever hold one symbol, the younger lot is
 * exited by the OLDER strategy's rules.
 *
 * <p>That collapse is currently unreachable — Minervini's {@code pyramid()} returns the
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
 * <p><b>What this does NOT cover, stated so nobody over-reads a green run:</b> it asserts the BUNDLED
 * YAML, not the {@code strategy_versions} row the live engine actually executes. Those populations
 * legitimately differ — a config change is a silent no-op until republished, so a published version
 * can carry exit rules this test never sees. Covering that needs a live-data check, not a unit test;
 * this guard catches the edit at the moment it is made, in the PR that makes it, which is where it is
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
        ALONE, with no strategy dimension. exitPass:766-800 drives the exit off oldestLot(lots)
        across that whole group, using ONLY that lot's strategy definition, then closes the shared
        paper position and expires EVERY lot (:1052-1054).

        Consequence: if two strategies of one family ever hold the same symbol, the younger lot is
        exited by the OLDER strategy's rules. Identical exit rules are what makes that harmless.

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
   * loads strategies per doctrine), so the doctrine count is the authority on how many families
   * exist and the scan must find exactly that many.
   */
  @Test
  void everySwingDoctrineHasOneDiscoveredBundledFamily() throws IOException {
    Set<String> discovered = discoverSwingFamilies().keySet();
    List<String> doctrines = swingDoctrineComponents();

    assertThat(discovered)
        .as(
            "%d SwingDoctrine component(s) exist (%s) but the classpath scan found %d bundled swing"
                + " family/families (%s). A swing family with no bundled YAML directory is SILENTLY"
                + " EXEMPT from everyStrategyInOneSwingFamilyResolvesTheSameExitDecision — which is the"
                + " exact way this guard would decay. Either bundle the family's docs under"
                + " <family>-strategies/ on the classpath, or widen %s to reach them.%s",
            doctrines.size(), doctrines, discovered.size(), discovered, BUNDLED_STRATEGY_GLOB, WHY)
        .hasSameSizeAs(doctrines);
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

  /** Concrete {@code @Component} classes implementing {@link SwingDoctrine} — one per family. */
  private static List<String> swingDoctrineComponents() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
    List<String> found = new ArrayList<>();
    scanner
        .findCandidateComponents("in.arthayantra.strategysignal")
        .forEach(
            bean -> {
              try {
                Class<?> type = Class.forName(bean.getBeanClassName());
                if (SwingDoctrine.class.isAssignableFrom(type)) {
                  found.add(type.getSimpleName());
                }
              } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned bean not loadable: " + bean, e);
              }
            });
    found.sort(Comparator.naturalOrder());
    return found;
  }

  // ---- the fingerprint ------------------------------------------------------------------------

  /**
   * Canonical JSON of everything the exit decision reads: the {@code exit_rules} array in order
   * (precedence is significant — {@code ExitEvaluator} returns the first rule that fires), plus the
   * declaration of every indicator an exit rule resolves through an {@code alias}.
   *
   * <p>The alias half is not optional. Minervini's trail is
   * {@code {trailing_stop, basis: indicator, alias: sma50}} — textually identical across all four
   * strategies while the exit LEVEL is whatever each strategy declares {@code sma50} to be. Comparing
   * {@code exit_rules} alone would pass a family whose trail silently means SMA(50) in one strategy
   * and SMA(30) in another.
   *
   * <p>Only the fields {@code IndicatorBank} computes a VALUE from are included ({@code name},
   * {@code timeframe}, {@code params}, {@code instrument}). {@code weight} and {@code normalize} are
   * deliberately excluded: they feed entry SCORING, never an exit level, so including them would red
   * this guard on a legitimate entry-only tune.
   */
  private static String exitFingerprint(JsonNode config) {
    JsonNode exitRules = config.path("exit_rules");

    Set<String> aliases = new TreeSet<>(); // sorted → the fingerprint is order-stable
    exitRules.forEach(
        rule -> {
          JsonNode alias = rule.path("params").path("alias");
          if (alias.isTextual()) {
            aliases.add(alias.asText());
          }
        });

    ObjectNode aliasIndicators = MAPPER.createObjectNode();
    for (String alias : aliases) {
      ObjectNode declaration = MAPPER.createObjectNode();
      for (JsonNode indicator : config.path("indicators")) {
        if (alias.equals(indicator.path("alias").asText())) {
          declaration.set("name", indicator.path("name"));
          declaration.set("timeframe", indicator.path("timeframe"));
          declaration.set("params", indicator.path("params"));
          declaration.set("instrument", indicator.path("instrument"));
          break;
        }
      }
      aliasIndicators.set(alias, declaration);
    }

    ObjectNode fingerprint = MAPPER.createObjectNode();
    fingerprint.set("exit_rules", exitRules);
    fingerprint.set("exit_rule_aliases", aliasIndicators);
    return fingerprint.toPrettyString();
  }
}
