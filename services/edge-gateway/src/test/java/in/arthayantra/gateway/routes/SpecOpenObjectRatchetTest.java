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
import java.util.function.Predicate;
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
 * <h2>Granularity: why a REFERENCE SITE, not a component name (and not a full expansion)</h2>
 *
 * <p>The first cut of this test froze component NAMES — one {@code #JsonNode} line per service —
 * and that reproduced the exact over-stated-allowance hole it was built to remove. Cross-vendor
 * review MEASURED it: adding a handler that returns {@code JsonNode} directly, and separately
 * adding a {@code JsonNode} field to an existing typed record, both re-captured with ZERO set
 * delta. One frozen line was silently authorising 40 response locations and every future one.
 * {@link MapReturnRatchetTest} cannot see a {@code JsonNode} either, so nothing else caught it.
 *
 * <p>The obvious repair — resolve every {@code $ref} per response and freeze every EXPOSED
 * location — closes it but does not survive contact with this spec set. Measured over the five
 * committed specs: <b>414 lines</b> (market-data alone 160, backtest 46, strategy-signal 112),
 * almost all of them {@code #ErrorResponse.details} re-reached through the {@code default} error
 * envelope of every single operation. Every new endpoint would add a line to the allowlist. That
 * is not a ratchet anybody maintains, and an unmaintained one gets bulk-edited green.
 *
 * <p>So the unit is <b>where opacity is WRITTEN INTO the schema graph</b>, not where it is
 * transitively reachable: an inline open object is frozen at the spot it is written, and a
 * {@code $ref} to a component that is itself open is frozen at the REFERENCING SITE. Same five
 * specs: <b>31 lines</b> for the four Java services. Both reviewer mutations now produce a delta —
 * a direct {@code JsonNode} response is a new site, and a new {@code JsonNode} field on a record is
 * a new site — while a new endpoint returning EXISTING types adds nothing.
 *
 * <p>RESIDUAL, stated because the boundary is a choice: exposing an already-frozen component's
 * already-frozen internal opacity through a NEW endpoint produces no delta. Adding an endpoint
 * that returns {@code RunResult} does not re-freeze {@code #RunResult.metrics}. That is deliberate
 * — it is the whole difference between 31 lines and 414 — and it is sound because no new opaque
 * DECISION was made: the shape was settled when the field was written. A new opaque decision
 * always writes a new site, and that is what this catches.
 *
 * <h2>What counts as "no constraint" — enumerated, not guessed</h2>
 *
 * <p>FOUR times now a check on this surface has been beaten by a shape nobody listed: the source
 * regex could not see a {@code Map} field inside a typed record; this test's first cut aliased
 * every use of a component to one name; its second called {@code {"title": "x"}} constrained
 * because it was not literally <code>{}</code>; and its third asked whether a constraining keyword
 * was PRESENT rather than whether it CONSTRAINED, so {@code {"properties": {}}} — and an unknown
 * keyword sitting beside any inert known one — read closed. Every one of those was an enumeration
 * of what to CATCH.
 *
 * <p>So the population is censused, not sampled. Population rule, stated because the count depends
 * on it: every distinct schema node this check EVALUATES, deduplicated by (spec, location), with a
 * component body counted ONCE per spec however many sites reference it — including a {@code $ref}
 * target judged at a reference site and therefore never walked as a component. Across all five
 * committed specs, the nodes declaring no {@code type}, by exact key set:
 *
 * <pre>
 *   121  ["anyOf", "title"]   constrained (a non-empty anyOf pins it)
 *    38  ["anyOf"]            constrained
 *     7  {}                   OPEN — already caught
 *     3  ["title"]            OPEN — the annotation-only miss, all three in optimizer (pydantic
 *                                    `Any`); the four unconstrained key sets total 169 nodes
 * </pre>
 *
 * <p>Two unconstrained key sets, nothing else. Shapes reasoned through beyond the census and
 * confirmed handled: annotation-only in any combination ({@code description} / {@code default} /
 * {@code example} / {@code deprecated} / bare {@code nullable} / {@code format}), any inert known
 * keyword, an {@code allOf}/{@code anyOf} branch that is itself open (recursed, reported at the
 * branch pointer), {@code items: {}} under {@code type: array} (reported at {@code []}), a
 * {@code Map} whose VALUE schema is open (reported at <code>{}</code>), and a {@code $ref} to an
 * annotation-only component (reported at the reference site — the same fix as {@code JsonNode}).
 *
 * <p>⚠️ The fails-safe property — an unrecognised keyword reads OPEN and cannot be silenced by an
 * inert neighbour — is now ASSERTED by {@link #anUnknownKeywordIsNeverSilencedByAnInertKnownOne}
 * over all 19 inert-capable keywords, not claimed in prose. It was claimed in prose once and was
 * false in 18 of them.
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
   * <p>Location grammar, three forms:
   *
   * <ul>
   *   <li>{@code METHOD /path <status>} — an open object written INLINE at a response schema's
   *       root, suffixed {@code .field} / {@code []} / <code>{}</code> when nested inside it.
   *   <li>{@code #ComponentName.field} — one written inline inside a component schema that some
   *       response reaches.
   *   <li>{@code <site> -> ComponentName} — a REFERENCE to a component that is itself an open
   *       object ({@code JsonNode} → the empty schema). The site is the location, never the
   *       component name; see the granularity note in the class javadoc for why.
   * </ul>
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
                  // DELIBERATE STOPS of the HeroZeroPremium kind, and the documented floor #1190
                  // drove this service down to: oiExpiry emits 3 keys empty / 4 populated and
                  // openHighStrategy 3 / 5, so a record would add the missing keys as nulls to the
                  // empty response — a wire change on a live OI page, not a refactor.
                  "GET /api/v1/market/options/oi-expiry 200",
                  "GET /api/v1/market/options/open-high-strategy 200",
                  // The deep-swing report blob. See the JsonNode note in backtest-service below.
                  "#DeepSwingRunResult.report -> JsonNode",
                  "#ErrorResponse.details"),
          "strategy-signal-service",
              Set.of(
                  // Its four response-level open objects were converted by #1191 and their
                  // exemptions deleted here — the stale-exemption assertion named all four the
                  // moment this branch rebased onto that merge, which is the mechanism working.
                  //
                  // What remains is ten JsonNode SITES, each its own line rather than one blanket
                  // `#JsonNode`. They are the persisted-JSONB passthroughs: score breakdowns, the
                  // per-family scalper/Minervini/ManasArora detail side-channels, the strategy
                  // config a user authored, and the rejection diagnostic.
                  "#Insight.evidence -> JsonNode",
                  "#Insight.priorityDetail/anyOf/0 -> JsonNode",
                  "#OpeningSignal.manasAroraDetail/anyOf/0 -> JsonNode",
                  "#OpeningSignal.minerviniDetail/anyOf/0 -> JsonNode",
                  "#OpeningSignal.scalperDetail/anyOf/0 -> JsonNode",
                  "#RejectionRow.diagnostic -> JsonNode",
                  // ⚠️ ARRIVED WITH #1191's conversion: RiskController's opaque Map became the
                  // typed RiskSettingRow, but its `value` stayed a JsonNode — the response is
                  // narrower, not closed. Under a blanket `#JsonNode` exemption this would have
                  // been absorbed silently; naming the site is what makes it visible.
                  "#RiskSettingRow.value -> JsonNode",
                  "#SignalDto.scalperDetail/anyOf/0 -> JsonNode",
                  "#SignalDto.scoreBreakdown -> JsonNode",
                  "#StrategyDetail.config -> JsonNode",
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
                  // all", strictly looser than additionalProperties:{}. These are the persisted
                  // JSONB passthroughs (curves, metrics, saved-view filters, trade contributions)
                  // plus two handlers that return a JsonNode directly. A class
                  // MapReturnRatchetTest cannot see: there is no `Map` in the source text at all.
                  "GET /api/v1/backtests/{backtestId}/folds 200 -> JsonNode",
                  "GET /api/v1/backtests/{backtestId}/montecarlo 200 -> JsonNode",
                  "#BacktestTradeItem.contributions/anyOf/0 -> JsonNode",
                  "#RunResult.benchmarkCurve -> JsonNode",
                  "#RunResult.drawdownCurve -> JsonNode",
                  "#RunResult.equityCurve -> JsonNode",
                  "#RunResult.metrics -> JsonNode",
                  "#SavedView.filter -> JsonNode",
                  "#Trace.sampleBreakdown -> JsonNode",
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
          "Ratcheted in its OWN suite instead — services/optimizer-service/tests/"
              + "test_open_object_ratchet.py, same rules, against a live app.openapi(). NOT"
              + " unratcheted, and NOT because its responses are inherently free-form: that was"
              + " this constant's original claim and cross-vendor review falsified it (api.py:55"
              + " `run` promises exactly {jobId, status}, api.py:79 `cancel` exactly {status} —"
              + " fixed shapes declared as open dicts). It moved rather than being listed here"
              + " because it is the one service with NO source-side instrument (the regex cannot"
              + " read Python) and a HAND-DUMPED spec, so reading its committed file would leave a"
              + " new dict[str, Any] endpoint unguarded until someone re-dumps. Generating the"
              + " document from code in its own suite removes that window entirely.");

  /**
   * Regression cases for the predicate itself, pinned in the language that owns it (the Python half
   * carries the mirror of this list). Every "constrains nothing" shape here was reachable from a
   * real response at some point in this check's short history, and the annotation-only group is the
   * third distinct way a schema turned out to be open after component-name aliasing and the source
   * regex's inability to see a field inside a typed record.
   */
  @Test
  void theOpenObjectPredicateRecognisesEveryUnconstrainedShape() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    List<String> open =
        List.of(
            "{}", // the empty schema — any JSON at all
            "{\"title\": \"Response\"}", // ⚠️ annotation-only: pydantic's bare `Any`
            "{\"description\": \"whatever the handler felt like\"}",
            "{\"title\": \"X\", \"description\": \"Y\", \"default\": null}",
            "{\"type\": \"object\"}",
            "{\"type\": [\"object\", \"null\"]}",
            "{\"type\": \"object\", \"additionalProperties\": {}}",
            "{\"type\": \"object\", \"additionalProperties\": true}",
            "{\"additionalProperties\": true}",
            "{\"nullable\": true}", // 3.0 nullability alone constrains nothing
            "{\"unknownFutureKeyword\": 1}", // fails-safe: unrecognised ⇒ treated as open
            // ⚠️ PRESENT BUT INERT. Every one of these read CONSTRAINED under the presence-based
            // predicate. `format` is here on the 3.1 reading: a format does not imply a type, and
            // 2020-12 makes it an annotation by default, so this still permits any object.
            "{\"type\": \"object\", \"properties\": {}}",
            "{\"properties\": {}}",
            "{\"required\": []}",
            "{\"minProperties\": 0}",
            "{\"patternProperties\": {}}",
            "{\"format\": \"uuid\"}",
            "{\"allOf\": []}",
            "{\"enum\": []}",
            "{\"uniqueItems\": false}",
            "{\"items\": true}");
    for (String json : open) {
      assertThat(isOpenObject(mapper.readTree(json)))
          .withFailMessage("expected OPEN but the predicate said constrained: %s", json)
          .isTrue();
    }

    List<String> constrained =
        List.of(
            "{\"type\": \"string\"}",
            "{\"type\": [\"string\", \"null\"]}",
            "{\"type\": \"array\", \"items\": {}}", // the ARRAY is not open; its item is, and the
            // walk recurses to report it at the `[]` pointer
            "{\"type\": \"object\", \"properties\": {\"a\": {\"type\": \"string\"}}}",
            "{\"type\": \"object\", \"additionalProperties\": {\"type\": \"string\"}}",
            "{\"$ref\": \"#/components/schemas/Foo\"}",
            "{\"allOf\": [{\"type\": \"object\"}]}",
            "{\"anyOf\": [{\"type\": \"string\"}]}",
            "{\"enum\": [\"a\", \"b\"]}",
            "{\"const\": 3}",
            "{\"title\": \"Bounded\", \"maxLength\": 5}",
            "{\"minProperties\": 1}",
            "{\"type\": \"object\", \"required\": [\"a\"]}");
    for (String json : constrained) {
      assertThat(isOpenObject(mapper.readTree(json)))
          .withFailMessage("expected CONSTRAINED but the predicate said open: %s", json)
          .isFalse();
    }
  }

  /**
   * The headline property, asserted rather than claimed: an unrecognised keyword must never be
   * silenced by an inert known one sitting beside it.
   *
   * <p>This is the test the previous round needed and did not have. "An unanticipated keyword reads
   * as OPEN and fails loudly" was written into the header as the justification for inverting the
   * lists, and it was false in all 18 of these combinations, because the check asked whether a
   * constraining keyword was PRESENT rather than whether it CONSTRAINED. Pair an unknown keyword
   * with {@code "properties": {}} and the schema went quiet.
   */
  @Test
  void anUnknownKeywordIsNeverSilencedByAnInertKnownOne() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, String> inert =
        Map.ofEntries(
            Map.entry("properties", "{}"),
            Map.entry("patternProperties", "{}"),
            Map.entry("dependentSchemas", "{}"),
            Map.entry("dependentRequired", "{}"),
            Map.entry("required", "[]"),
            Map.entry("allOf", "[]"),
            Map.entry("anyOf", "[]"),
            Map.entry("oneOf", "[]"),
            Map.entry("enum", "[]"),
            Map.entry("prefixItems", "[]"),
            Map.entry("minProperties", "0"),
            Map.entry("minLength", "0"),
            Map.entry("minItems", "0"),
            Map.entry("minContains", "0"),
            Map.entry("uniqueItems", "false"),
            Map.entry("additionalProperties", "true"),
            Map.entry("unevaluatedProperties", "true"),
            Map.entry("unevaluatedItems", "true"),
            Map.entry("items", "true"));
    for (Map.Entry<String, String> noop : inert.entrySet()) {
      String json =
          "{\"" + noop.getKey() + "\": " + noop.getValue() + ", \"unknownFutureKeyword\": 1}";
      assertThat(isOpenObject(mapper.readTree(json)))
          .withFailMessage(
              "an unknown keyword was silenced by an inert `%s` — the fails-safe property does not"
                  + " hold for: %s",
              noop.getKey(), json)
          .isTrue();
    }
  }

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
    JsonNode schemas = spec.path("components").path("schemas");
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
            walk(schemas, media.get("schema"), where, "", found, componentRoots);
          }
        }
      }
    }

    Set<String> visited = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(componentRoots);
    while (!queue.isEmpty()) {
      String name = queue.pop();
      if (!visited.add(name)) {
        continue;
      }
      Set<String> refs = new TreeSet<>();
      walk(schemas, schemas.get(name), "#" + name, "", found, refs);
      refs.stream().filter(r -> !visited.contains(r)).forEach(queue::push);
    }
    return found;
  }

  private static void walk(
      JsonNode schemas,
      JsonNode node,
      String where,
      String pointer,
      Set<String> found,
      Set<String> refs) {
    if (node == null || !node.isObject()) {
      return;
    }
    JsonNode ref = node.get("$ref");
    if (ref != null) {
      if (!ref.asText().startsWith(COMPONENT_PREFIX)) {
        return;
      }
      String name = ref.asText().substring(COMPONENT_PREFIX.length());
      JsonNode target = schemas.get(name);
      // A reference to a component that IS an open object (JsonNode -> `{}`) makes THIS SITE the
      // opacity, not the component: freezing the component name once would authorise unlimited
      // further uses of it. Freezing the site means each new exposure is a new line.
      if (target != null && isOpenObject(target)) {
        found.add(where + pointer + " -> " + name);
      } else {
        refs.add(name);
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
        walk(schemas, property.getValue(), where, pointer + "." + property.getKey(), found, refs);
      }
    }
    walk(schemas, node.get("items"), where, pointer + "[]", found, refs);
    JsonNode additional = node.get("additionalProperties");
    if (additional != null && additional.isObject() && !additional.isEmpty()) {
      walk(schemas, additional, where, pointer + "{}", found, refs);
    }
    for (String keyword : List.of("allOf", "oneOf", "anyOf")) {
      JsonNode composed = node.get(keyword);
      if (composed == null) {
        continue;
      }
      for (int i = 0; i < composed.size(); i++) {
        walk(schemas, composed.get(i), where, pointer + "/" + keyword + "/" + i, found, refs);
      }
    }
  }

  /** Keywords that constrain no matter what value they carry. */
  private static final Set<String> ALWAYS_CONSTRAINS =
      Set.of(
          "$ref",
          "const",
          "not",
          "propertyNames",
          "contains",
          "if",
          "then",
          "else",
          "discriminator",
          "pattern",
          "multipleOf",
          "maximum",
          "minimum",
          "exclusiveMaximum",
          "exclusiveMinimum",
          "maxLength",
          "maxItems",
          "maxContains",
          "maxProperties");

  private static final Predicate<JsonNode> EMPTY_CONTAINER =
      value -> value.isContainerNode() && value.isEmpty();
  private static final Predicate<JsonNode> ZERO = value -> value.isNumber() && value.asInt() == 0;
  private static final Predicate<JsonNode> PERMISSIVE =
      value ->
          (value.isBoolean() && value.booleanValue()) || (value.isObject() && value.isEmpty());

  /**
   * Keywords that constrain only when their VALUE is not a no-op, with the no-op test. An empty
   * {@code properties} map, an empty {@code required} list, {@code minProperties: 0} and friends
   * are all present-but-inert: they restrict nothing, so a schema carrying only those is as open as
   * <code>{}</code>.
   */
  private static final Map<String, Predicate<JsonNode>> VACUOUS_WHEN =
      Map.ofEntries(
          Map.entry("properties", EMPTY_CONTAINER),
          Map.entry("patternProperties", EMPTY_CONTAINER),
          Map.entry("dependentSchemas", EMPTY_CONTAINER),
          Map.entry("dependentRequired", EMPTY_CONTAINER),
          Map.entry("required", EMPTY_CONTAINER),
          Map.entry("allOf", EMPTY_CONTAINER),
          Map.entry("anyOf", EMPTY_CONTAINER),
          Map.entry("oneOf", EMPTY_CONTAINER),
          Map.entry("enum", EMPTY_CONTAINER),
          Map.entry("prefixItems", EMPTY_CONTAINER),
          Map.entry("minProperties", ZERO),
          Map.entry("minLength", ZERO),
          Map.entry("minItems", ZERO),
          Map.entry("minContains", ZERO),
          Map.entry("uniqueItems", value -> value.isBoolean() && !value.booleanValue()),
          Map.entry("additionalProperties", PERMISSIVE),
          Map.entry("unevaluatedProperties", PERMISSIVE),
          Map.entry("unevaluatedItems", PERMISSIVE),
          Map.entry("items", PERMISSIVE));

  /**
   * True when a keyword actually restricts the value, as opposed to merely being present.
   *
   * <p>⚠️ <b>The default is NON-constraining, and that is the whole fails-safe property.</b> A
   * keyword in neither table — an annotation ({@code title} / {@code description} / {@code default}
   * / {@code example} / {@code deprecated} / bare {@code nullable}), or one nobody here anticipated
   * — contributes nothing, so its schema reads OPEN and fails LOUDLY, resolved by a reviewed
   * exemption or one more entry here. The inverse (enumerate the annotations, treat everything else
   * as constraining) fails silently, and every hole this check has had was of that shape.
   *
   * <p>{@code format} is deliberately in NEITHER table: OpenAPI 3.1 states that a format does not
   * imply a type, and JSON Schema 2020-12 makes {@code format} an annotation by default, so
   * {@code {"format": "uuid"}} with no {@code type} still permits an arbitrary object. Calling it a
   * constraint was this check's fourth hole.
   */
  private static boolean constrains(String keyword, JsonNode value) {
    if (ALWAYS_CONSTRAINS.contains(keyword)) {
      return true;
    }
    Predicate<JsonNode> vacuous = VACUOUS_WHEN.get(keyword);
    return vacuous != null && !vacuous.test(value);
  }

  /**
   * True when the schema constrains nothing about an object's contents — i.e. every keyword it
   * carries is an annotation, unrecognised, or present with an inert value, and any declared type
   * admits an object.
   *
   * <p>This is VALUE-aware, not presence-aware, and the difference is load-bearing: the previous
   * cut asked only whether a constraining keyword was PRESENT, so {@code {"properties": {}}} read
   * as constrained, and — fatally for the fails-safe claim — pairing an unknown keyword with any
   * inert known one ({@code {"unknownFutureKeyword": 1, "properties": {}}}) went quiet. That was
   * measured across 18 distinct no-op keywords, all of which the presence check closed.
   *
   * <p>Shapes this recognises: the empty schema <code>{}</code>; annotation-only such as
   * {@code {"title": "x"}} (pydantic's rendering of a bare {@code Any}, and the miss that had the
   * optimizer ratchet reporting 43 locations of 46); {@code type: object} with nothing pinning its
   * contents; {@code additionalProperties} of {@code true} / <code>{}</code>; and any combination
   * of inert keywords.
   */
  private static boolean isOpenObject(JsonNode node) {
    if (!node.isObject()) {
      return false;
    }
    List<String> types = declaredTypes(node);
    if (!types.isEmpty() && !types.contains("object")) {
      return false;
    }
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      if (!"type".equals(field.getKey()) && constrains(field.getKey(), field.getValue())) {
        return false;
      }
    }
    return true;
  }

  /** The declared type(s); empty when absent, so an untyped schema is judged on its keywords. */
  private static List<String> declaredTypes(JsonNode node) {
    JsonNode type = node.get("type");
    if (type == null) {
      return List.of();
    }
    return type.isArray()
        ? stream(type).map(JsonNode::asText).toList()
        : List.of(type.asText());
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
