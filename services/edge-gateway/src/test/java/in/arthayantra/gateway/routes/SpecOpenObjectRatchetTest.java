package in.arthayantra.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Contract-surface ratchet, SPEC side: no published response may contain an <b>unconstrained open
 * object</b> outside a named, frozen exemption. Sibling of {@link MapReturnRatchetTest}, which asks
 * a related question of the controller SOURCE. Both are kept deliberately — see <i>why two</i>.
 *
 * <p>Unconstrained open object = a schema that declares {@code type: object} with no {@code
 * properties}, or carries {@code additionalProperties: {}} / {@code true}, or is the EMPTY schema
 * <code>{}</code> — the last being the worst case, "any JSON at all", which is how springdoc
 * renders a Jackson {@code JsonNode} or a bare {@code Object}. A consumer reading any of them
 * learns nothing: every key is legal, no key is promised, and ci-contracts' breaking gate has
 * nothing to diff — so adding, renaming or removing a key inside it is a silent wire change. That
 * is the entire reason a ratchet exists on this surface at all.
 *
 * <p>NOT flagged, deliberately: {@code additionalProperties: <schema>} (e.g. {@code
 * Map<String, List<WatchlistView>>} → {@code additionalProperties: {type: array, items: $ref}}).
 * The KEYS are open but the value shape is enumerated, so a consumer can still type what it reads
 * and a value-shape break still diffs. {@link MapReturnRatchetTest} does not count that shape
 * either; the two instruments agree on this boundary.
 *
 * <h2>Why two instruments, rather than replacing the regex</h2>
 *
 * <p>They read different artifacts and therefore fail in DISJOINT ways; neither subsumes the other,
 * and that is measured on today's tree, not asserted.
 *
 * <ul>
 *   <li><b>{@link MapReturnRatchetTest} reads SOURCE.</b> It fires the moment an opaque handler is
 *       written, with no dependence on anyone having re-run the capture. Its failure mode is
 *       under-matching: the pattern leaked twice on 2026-08-01 alone (every {@code
 *       ResponseEntity}-wrapped Map was uncounted until #1188; a fully-qualified {@code
 *       java.util.Map} until #1191). Both fixes were correct and both left the class open —
 *       MEASURED against #1191's widened pattern, a tab after {@code public}, a return type wrapped
 *       inside the generic, and a doubled {@code Mono<ResponseEntity<Map<…>>>} all still score zero
 *       matches, while all three publish {@code additionalProperties: {}} and fail HERE.
 *   <li><b>This test reads the COMMITTED CAPTURE.</b> Formatting cannot defeat it — it sees what
 *       springdoc actually published. But it is only as fresh as the last {@code
 *       -Dcontracts.capture=true}: a branch that adds an opaque handler and never re-captures is
 *       GREEN here and RED there.
 * </ul>
 *
 * <p>The decisive evidence is edge-gateway itself: it sits at the regex floor of <b>zero</b> and
 * still publishes two unconstrained open objects — {@code POST /api/v1/auth/login 200} (a {@code
 * Mono<ResponseEntity<Object>>}, a shape no {@code Map} pattern can ever match) and {@code
 * #SystemStatus.jobs} (a {@code Map<String, Object>} FIELD inside the typed {@code SystemStatus}
 * record, invisible by construction — it survived the very conversion that drove the count to 0).
 * Converting a handler to a record lowers the source count, which reads as progress, while the
 * opacity simply moves inside the record. Only this side sees that.
 *
 * <p>Cost of keeping both: a conversion updates two places — one integer there, one named line
 * here. The named line is the cheaper of the two to review, and because this side asserts an exact
 * SET, a stale exemption fails LOUDLY instead of silently widening the allowance (the
 * {@code isLessThanOrEqualTo} over-statement hole recorded against the count-based instrument
 * cannot exist here). Deleting the regex would trade a leak we can see for a window we cannot.
 *
 * <h2>⚠️ This test's OWN blind spot</h2>
 *
 * <p><b>It sees only what the capture produced, and only as of the last commit of it.</b> Three
 * consequences, none of them shared with the source grep:
 *
 * <ol>
 *   <li>A handler excluded from the spec entirely — wrong profile, missing {@code @*Mapping},
 *       {@code @Hidden}, a controller springdoc never scans — is INVISIBLE here. The source grep
 *       would still have seen the text.
 *   <li>A branch that adds an opaque handler without re-capturing is green here until the capture
 *       is refreshed. ci-contracts warns on that drift; it does not fail on it.
 *   <li>Only response schemas and the components REACHABLE from them are walked. Request bodies are
 *       out of scope on purpose: this ratchet is about what we PUBLISH, not what we accept.
 * </ol>
 *
 * <p>Neither instrument offers total coverage, and no future edit should describe either as if it
 * did.
 *
 * <p>Pure-file test, same shape as {@link MapReturnRatchetTest} and {@link
 * GatewayRouteAllowlistTest}: walks {@code contracts/} from the repo root, rides the ci-java
 * strategy-gateway shard, no containers.
 */
class SpecOpenObjectRatchetTest {

  private static final String SPEC_SUFFIX = ".openapi.json";
  private static final String COMPONENT_PREFIX = "#/components/schemas/";

  /**
   * Every unconstrained open object this platform is allowed to publish, named one by one so each
   * exemption is reviewable rather than hidden inside a count. The assertion is exact-set equality:
   * a NEW open object fails, and an exemption that no longer exists ALSO fails, so converting a
   * handler forces its line to be deleted here.
   *
   * <p>Location grammar: {@code METHOD /path <status>} for an open object at a response schema's
   * root, suffixed by {@code .field} / {@code []} / <code>{}</code> for one nested inside it;
   * {@code #ComponentName.field} for one inside a component schema reachable from some response.
   */
  private static final Map<String, Set<String>> FROZEN_OPEN_OBJECTS =
      Map.of(
          "edge-gateway",
              Set.of(
                  // login answers 204 with NO body on success and an ErrorResponse at 401/429
                  // (AuthController:63-95). springdoc renders the handler's
                  // Mono<ResponseEntity<Object>> as a bare `type: object` under 200; there is no
                  // honest single shape to declare for a response that has no body.
                  "POST /api/v1/auth/login 200",
                  // backtest's jobs rollup, read as raw JSON off the Redis `jobs:summary` key and
                  // re-emitted verbatim (SystemStatusController:99-104). Opaque BY CONSTRUCTION —
                  // this service does not own the shape. Invisible to MapReturnRatchetTest: the
                  // handler returns the typed SystemStatus record and the Map is a field in it.
                  "#SystemStatus.jobs",
                  // COMMON 8.3 free-form error-detail bag; the whole point is arbitrary context.
                  "#ErrorResponse.details"),
          "market-data-service",
              Set.of(
                  // The six OptionsAnalytics handlers + WatchlistController.create are the
                  // MapReturnRatchetTest floor of 7, mirrored here. #1190 (OPEN at the time of
                  // writing) converts five of them; when it lands, the stale-exemption assertion
                  // will name those five and they get deleted from this list. The two that remain
                  // are DELIBERATE STOPS of the HeroZeroPremium kind — oiExpiry emits 3 keys empty
                  // / 4 populated and openHighStrategy 3 / 5, so a record would add the missing
                  // keys as nulls to the empty response: a wire change on a live OI page, not a
                  // refactor.
                  "GET /api/v1/market/options/multiple-oi 200",
                  "GET /api/v1/market/options/oi-analysis 200",
                  "GET /api/v1/market/options/oi-analysis/strike-series 200",
                  "GET /api/v1/market/options/oi-expiry 200",
                  "GET /api/v1/market/options/open-high-strategy 200",
                  "GET /api/v1/market/options/options-chart 200",
                  "POST /api/v1/watchlists 200",
                  // Reachable from responses via DeepSwingRunResult. See #JsonNode below.
                  "#JsonNode",
                  "#ErrorResponse.details"),
          "strategy-signal-service",
              Set.of(
                  // Its four response-level open objects were converted by #1191 and their
                  // exemptions deleted here — the stale-exemption assertion named all four the
                  // moment this branch rebased onto that merge, which is the mechanism working.
                  // Reachable from responses via SignalDto / StrategyDetail / RejectionRow /
                  // OpeningSignal / Insight. See #JsonNode below.
                  "#JsonNode",
                  "#ErrorResponse.details"),
          "backtest-service",
              Set.of(
                  // DELIBERATE STOP: genuinely polymorphic — the empty path emits 5 keys, the
                  // populated path 16, so one record would add 11 null keys to the empty response.
                  // That is a wire change, not a retyping.
                  "GET /api/v1/backtests/{backtestId}/hero-zero-premium 200",
                  // DELIBERATE STOP: conditional for the same reason — its empty path emits fewer
                  // keys than its populated path.
                  "GET /api/v1/backtests/{backtestId}/oi-attribution 200",
                  // springdoc renders a Jackson JsonNode as the EMPTY schema {} — "any JSON at
                  // all", strictly looser than additionalProperties:{}. It is the wire form of
                  // user-authored strategy config, saved-view filters and score breakdowns, which
                  // have no fixed shape by construction; it is also returned directly by
                  // MonteCarloController.montecarlo and the folds endpoint. A THIRD class
                  // MapReturnRatchetTest cannot see: there is no `Map` in the source text at all.
                  "#JsonNode",
                  // `Object defaultValue` (IndicatorSeriesService:85) — an indicator parameter
                  // default that is legitimately any scalar type.
                  "#ParamMeta.defaultValue",
                  "#ErrorResponse.details"));

  /**
   * Specs deliberately NOT ratcheted, declared so the omission is a line a reviewer sees rather than
   * a service nobody noticed was skipped — the failure mode ci-contracts.yml records against its own
   * five hardcoded service lists, where a new service appeared in none of them and nothing failed.
   */
  private static final Map<String, String> OUT_OF_SCOPE =
      Map.of(
          "optimizer-service",
          "Python/FastAPI, outside the Java build. Its ~40 open objects are pydantic `dict`"
              + " parameter bags (searchSpace / objectiveSpec / params / scorecard) that are"
              + " inherently free-form — a hyperparameter dict has no shape to declare — so an"
              + " allowlist would be maintenance tax with no signal. Its spec is also hand-dumped"
              + " (no target/ capture artifact, per ci-contracts.yml), so it would churn on"
              + " somebody else's PR. Ratchet it only if the models stop being free-form.");

  @Test
  void noResponsePublishesAnUnfrozenOpenObject() throws IOException {
    Path contracts = findRepoRoot().resolve("contracts");
    ObjectMapper mapper = new ObjectMapper();
    for (Map.Entry<String, Set<String>> frozen : FROZEN_OPEN_OBJECTS.entrySet()) {
      Path specFile = contracts.resolve(frozen.getKey() + SPEC_SUFFIX);
      Set<String> found = openObjectsIn(mapper.readTree(Files.readString(specFile)));

      Set<String> unfrozen = new TreeSet<>(found);
      unfrozen.removeAll(frozen.getValue());
      assertThat(unfrozen)
          .withFailMessage(
              "%s publishes %d unconstrained open object(s) with no frozen exemption: %s."
                  + " A response typed `object` with no properties (or additionalProperties {})"
                  + " promises the consumer NOTHING and is invisible to the breaking-diff gate —"
                  + " return a typed record. If the shape is genuinely polymorphic or not owned by"
                  + " this service, add the location to FROZEN_OPEN_OBJECTS with the reason.",
              frozen.getKey(), unfrozen.size(), unfrozen)
          .isEmpty();

      Set<String> stale = new TreeSet<>(frozen.getValue());
      stale.removeAll(found);
      assertThat(stale)
          .withFailMessage(
              "%s has %d frozen exemption(s) that no longer exist in the captured spec: %s."
                  + " Progress — delete those lines from FROZEN_OPEN_OBJECTS. Leaving them would"
                  + " silently re-authorise the same location later.",
              frozen.getKey(), stale.size(), stale)
          .isEmpty();
    }
  }

  @Test
  void everyCommittedSpecIsEitherRatchetedOrDeclaredOutOfScope() throws IOException {
    Set<String> onDisk = new TreeSet<>();
    try (Stream<Path> files = Files.list(findRepoRoot().resolve("contracts"))) {
      files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(SPEC_SUFFIX))
          .map(n -> n.substring(0, n.length() - SPEC_SUFFIX.length()))
          .forEach(onDisk::add);
    }
    Set<String> accounted = new TreeSet<>(FROZEN_OPEN_OBJECTS.keySet());
    accounted.addAll(OUT_OF_SCOPE.keySet());
    assertThat(onDisk)
        .withFailMessage(
            "contracts/ holds specs for %s but this ratchet accounts for %s. A service that is in"
                + " neither map is not checked, and a check that never looked is indistinguishable"
                + " from a check that passed — add it to FROZEN_OPEN_OBJECTS, or to OUT_OF_SCOPE"
                + " with the reason.",
            onDisk, accounted)
        .isEqualTo(accounted);
  }

  /**
   * Every unconstrained open object reachable from a RESPONSE, as location strings. Two passes:
   * response schemas inline, then the transitive closure of the components those responses $ref.
   * Request-only components are never seeded, so they never appear.
   */
  private static Set<String> openObjectsIn(JsonNode spec) {
    Set<String> found = new TreeSet<>();
    Set<String> componentRoots = new TreeSet<>();

    for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
        for (Map.Entry<String, JsonNode> response :
            method.getValue().path("responses").properties()) {
          String where =
              method.getKey().toUpperCase(Locale.ROOT)
                  + " "
                  + path.getKey()
                  + " "
                  + response.getKey();
          for (JsonNode media : response.getValue().path("content")) {
            walk(media.get("schema"), where, "", found, componentRoots);
          }
        }
      }
    }

    JsonNode schemas = spec.path("components").path("schemas");
    Set<String> visited = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(componentRoots);
    while (!queue.isEmpty()) {
      String name = queue.pop();
      if (!visited.add(name)) {
        continue;
      }
      Set<String> refs = new TreeSet<>();
      walk(schemas.get(name), "#" + name, "", found, refs);
      refs.stream().filter(r -> !visited.contains(r)).forEach(queue::push);
    }
    return found;
  }

  private static void walk(
      JsonNode node, String where, String pointer, Set<String> found, Set<String> refs) {
    if (node == null || !node.isObject()) {
      return;
    }
    JsonNode ref = node.get("$ref");
    if (ref != null) {
      if (ref.asText().startsWith(COMPONENT_PREFIX)) {
        refs.add(ref.asText().substring(COMPONENT_PREFIX.length()));
      }
      return;
    }
    if (isOpenObject(node)) {
      found.add(where + pointer);
      return;
    }
    JsonNode properties = node.get("properties");
    if (properties != null) {
      for (Map.Entry<String, JsonNode> property : properties.properties()) {
        walk(property.getValue(), where, pointer + "." + property.getKey(), found, refs);
      }
    }
    walk(node.get("items"), where, pointer + "[]", found, refs);
    JsonNode additional = node.get("additionalProperties");
    if (additional != null && additional.isObject() && !additional.isEmpty()) {
      walk(additional, where, pointer + "{}", found, refs);
    }
    for (String keyword : List.of("allOf", "oneOf", "anyOf")) {
      JsonNode composed = node.get(keyword);
      if (composed == null) {
        continue;
      }
      for (int i = 0; i < composed.size(); i++) {
        walk(composed.get(i), where, pointer + "/" + keyword + "/" + i, found, refs);
      }
    }
  }

  /**
   * True when the schema constrains nothing about an object's contents: no {@code properties}, no
   * composition, and {@code additionalProperties} either absent (with {@code type: object}), {@code
   * true}, or the empty schema. A 3.1 nullability union ({@code type: ["object","null"]}) still
   * counts as declaring an object.
   */
  private static boolean isOpenObject(JsonNode node) {
    if (node.has("$ref")
        || node.has("allOf")
        || node.has("oneOf")
        || node.has("anyOf")
        || node.has("items")) {
      return false;
    }
    JsonNode properties = node.get("properties");
    if (properties != null && !properties.isEmpty()) {
      return false;
    }
    JsonNode type = node.get("type");
    boolean declaresObject =
        type != null
            && (type.isArray()
                ? stream(type).anyMatch(t -> "object".equals(t.asText()))
                : "object".equals(type.asText()));
    if (type != null && !declaresObject) {
      return false;
    }
    JsonNode additional = node.get("additionalProperties");
    boolean unconstrained =
        additional == null
            || (additional.isBoolean() && additional.booleanValue())
            || (additional.isObject() && additional.isEmpty());
    if (!unconstrained) {
      return false;
    }
    return node.isEmpty() || additional != null || declaresObject;
  }

  private static Stream<JsonNode> stream(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false);
  }

  private static Path findRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("contracts"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repo root (contracts/ dir) above the module dir").isNotNull();
    return dir;
  }
}
