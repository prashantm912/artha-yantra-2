package in.arthayantra.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * <h2>Why this asks DISCLOSURE rather than validation</h2>
 *
 * <p>FIVE times a check on this surface was beaten by a shape nobody listed: the source regex could
 * not see a {@code Map} field inside a typed record; this test's first cut aliased every use of a
 * component to one name; its second called {@code {"title": "x"}} constrained; its third asked
 * whether a keyword was PRESENT rather than whether it CONSTRAINED; and its fourth hardcoded 19
 * keywords as "always constraining", ten of which are not ({@code not: false}, {@code
 * propertyNames: true}, a lone {@code if}/{@code then}/{@code else}, {@code discriminator}, {@code
 * pattern: ""}, a bare {@code maxContains}, {@code contains} neutralised by {@code minContains: 0},
 * and a {@code $ref} to the boolean {@code true} schema).
 *
 * <p>Every one of those was an attempt to decide VALIDATION semantics — "does keyword K with value
 * V restrict the instance" — by table lookup. That is re-implementing a JSON Schema validator
 * badly, and it does not converge: the spec has ~40 keywords whose effects depend on their value,
 * on sibling keywords, and on the instance type.
 *
 * <p><b>The ratchet never needed validation semantics.</b> It exists because a response that
 * publishes no key information is invisible to ci-contracts' breaking-diff gate and generates an
 * untyped client. That is a STRUCTURAL question about the document — does it disclose the keys? —
 * with a short closed answer, and it makes the whole numeric/string/array facet family irrelevant
 * BY CONSTRUCTION rather than by classification. All ten counterexamples above fall out of the
 * definition without being listed; see {@link #disclosesKeyInformation}.
 *
 * <p>Measured before adoption: the disclosure predicate produces the IDENTICAL location set to the
 * keyword one on all committed specs, so this is a change of reasoning, not of verdict. A JSON
 * Schema VALIDATOR was also prototyped and rejected — it answers "accepts any object", which is a
 * different question: {@code ErrorResponse} enumerates three keys but declares no {@code required},
 * so a validator calls it open while the diff gate and the generated client both see its shape
 * perfectly well.
 *
 * <p>⚠️ <b>WHAT THE UNCHANGED COUNTS DO AND DO NOT PROVE.</b> Four successive rounds have reported
 * "identical location sets on all six specs" and that streak reads as more reassuring than it is.
 * It is REGRESSION evidence — no shape these six specs actually contain changed verdict — and
 * nothing more. <b>None of the frozen data exercises the adversarial forms these rounds were
 * about</b>: no committed spec contains a boolean schema, a {@code not}, a conditional, a
 * {@code propertyNames}, or a universal {@code pattern}. Every genuine defect since round 3 was
 * found by review or by a hand-built case, never by the counts moving. Treat the regression cases
 * and {@link #theWalkerReportsOpenObjectsAtEveryLocationForm} as the evidence that the classifier is
 * right; treat the counts only as evidence that nothing already-frozen broke.
 *
 * <p>Population census, stated because the count depends on the rule: every distinct schema node
 * this check EVALUATES, deduplicated by (spec, location), a component body counted ONCE per spec
 * however many sites reference it — including a {@code $ref} target judged at a reference site and
 * therefore never walked as a component. Across all five specs, the nodes declaring no {@code
 * type}, by exact key set — <b>four key sets, 169 nodes, of which two key sets are unconstrained</b>:
 *
 * <pre>
 *   121  ["anyOf", "title"]   discloses (a non-empty anyOf pins the shape)
 *    38  ["anyOf"]            discloses
 *     7  {}                   OPEN
 *     3  ["title"]            OPEN — annotation-only, all three in optimizer (pydantic `Any`)
 * </pre>
 *
 * <p>⚠️ The fails-safe property — an unrecognised keyword reads OPEN and cannot be silenced by an
 * inert neighbour — is ASSERTED by {@link #anUnknownKeywordIsNeverSilencedByAnInertKnownOne}, not
 * claimed in prose. It was claimed in prose once and was false in 18 combinations.
 *
 * <h2>⚠️ Predicate-correct is not walker-correct</h2>
 *
 * <p>They are SEPARATE properties, and this check shipped a version where only the first held. The
 * boolean {@code true} schema was judged correctly by {@link #isOpenObject} from the day it was
 * added and its unit case passed — while {@link #walk} bailed on "not an object" before publication
 * was ever consulted, so a {@code true} at a response root, under {@code items}, as a property or in
 * a composition branch was reported NOWHERE. No predicate case can catch that.
 * {@link #theWalkerReportsOpenObjectsAtEveryLocationForm} exists for exactly this class and asserts
 * every location form end-to-end.
 *
 * <p>The same round found the reframe had not propagated into three branches that still answered the
 * OLD validation question by PRESENCE: {@code not}, the {@code if}/{@code then}/{@code else}
 * conditional, and {@code propertyNames}. {@code not: {"type":"string"}} restricts the instance but
 * publishes no key names; a conditional over scalar facets likewise; and object keys are already
 * strings, so {@code propertyNames: {"type":"string"}} narrows nothing at all. Each now asks its
 * SUBSCHEMA the same disclosure question recursively, defaulting to non-disclosing. <b>When the
 * question changes, sweep every branch that answered the old one — a new definition does not
 * propagate by itself.</b>
 *
 * <p>The round after that made the same point from the other side: {@link #narrowsNames} had
 * REBUILT LOCALLY the very validation-semantics classifier the reframe removed globally, and six of
 * its nine members were wrong. The reframe did not fail — a corner of the old model survived inside
 * it. <b>Removing a bad abstraction is not finished until you check you have not re-created a small
 * one in the gap it left.</b>
 *
 * <p>And the round after THAT found the predicate/walker split a THIRD time, in {@code
 * unevaluatedProperties} — classified since the day it was added, descended by nothing. Two point
 * fixes had each produced the next instance, so the answer stopped being a fix and became a
 * STRUCTURE: descent is now DATA ({@link #DESCENT}) that the walker iterates, the classification is
 * asserted EXHAUSTIVE against the format's subschema-bearing keywords, and
 * {@link #theWalkerDescendsEverySchemaBearingKeyword} plants an open schema under every declared
 * keyword and demands it be reported. All three historical defects were replayed against it and all
 * three redden. <b>When the same defect shape recurs, stop fixing instances and assert the
 * property.</b>
 *
 * <p>⚠️ THE RECURRING SHAPE, named because it has now cost more rounds than any actual logic bug:
 * <b>a guard's existence keeps being mistaken for its efficacy.</b> Five instances so far — a
 * predicate the walker never called (×3), an invariant asserted against its own hand-list, a
 * comparison that defaulted to the value it was checking, a {@code --check} that enumerated which
 * fields to compare and named one of seven, and two reference forms documented as loud failures
 * and read by nothing. The countermeasure that works is mechanical and is now applied to every
 * claim here: <b>construct the state the guard says it catches, and watch it redden.</b> Three
 * separate fixes in this file were themselves found that way, by red-proofing rather than by
 * review. The related habit: prefer DEFAULT-COMPARE with declared exclusions over
 * default-ignore with declared inclusions — enumerating what to check has lost every time.
 *
 * <p>⚠️ And then the SOURCING itself was drawn one layer too shallow: the derivation scanned the
 * dialect's VOCABULARIES but not the dialect's own default metaschema, so the compatibility layer
 * ({@code definitions}, schema-valued {@code dependencies}, {@code $recursiveRef}) was missing and a
 * schema using {@code required} plus a legacy {@code dependencies} entry reported NOTHING. The
 * bounded claim written alongside it described a FUTURE-dialect freshness risk while the artifact
 * was incomplete for the CURRENT one — <b>a bound is only honest if it names the risk that is
 * actually live.</b> In the same round: {@code $dynamicRef} and {@code $recursiveRef} were named in
 * a comment inside the {@code $ref} branch and read by nothing (a bare one read open by accident,
 * so only a CLOSING SIBLING exposed it), and the dialect guard was asserted on the committed specs
 * — the artifact that cannot drift — while the Python halves scan a LIVE {@code app.openapi()} that
 * can. A guard on the wrong artifact is the same shape as a predicate the walker never calls.
 *
 * <p>⚠️ And the round before THAT found the flaw one level up: the exhaustiveness assertion compared
 * the classification against a HAND-WRITTEN inventory, so it inherited that list's blind spots —
 * a keyword could be missing from BOTH and the equality still passed. It was: {@code $defs} and
 * {@code contentSchema} were absent from both, and {@code $ref} was wrongly present. Measured, with
 * the inventory as the only variable: the historical classification PASSES against the hand list
 * and FAILS against the derived one. The inventory is now generated from the published 2020-12
 * vocabulary meta-schemas. <b>An invariant asserted against a list someone wrote is only as good as
 * the list; source it, or state the bound.</b>
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
                  // ⚠️ oi-expiry and open-high-strategy WERE frozen here as "deliberate stops of
                  // the HeroZeroPremium kind" and are GONE as of 2026-08-29 — both converted to
                  // records on an owner shape decision, so this service now freezes NO open response
                  // object at all. The wire change they were parked for (each ADDS a key to its
                  // EMPTY response: asOf, plus spot for open-high-strategy) was made deliberately
                  // and verified against every FE consumer first.
                  // ⚠️ THE LESSON, since it cost a CI cycle: this platform has TWO ratchets on the
                  // same fact and they must move TOGETHER. MapReturnRatchetTest counts Map-returning
                  // HANDLERS; this one checks the CAPTURED SPEC. The conversion lowered the handler
                  // count and left this list stale, and running MapReturnRatchetTest alone passed.
                  // Run the whole edge-gateway shard, not the one test you changed.
                  // The deep-swing report blob. See the JsonNode note in backtest-service below.
                  "#DeepSwingRunResult.report -> JsonNode",
                  "#ErrorResponse.details"),
          "strategy-signal-service",
              Set.of(
                  // Its four response-level open objects were converted by #1191 and their
                  // exemptions deleted here — the stale-exemption assertion named all four the
                  // moment this branch rebased onto that merge, which is the mechanism working.
                  //
                  // What remains is eleven JsonNode SITES, each its own line rather than a blanket
                  // `#JsonNode`. They are the persisted-JSONB passthroughs: score breakdowns, the
                  // per-family scalper/Minervini/ManasArora detail side-channels, the strategy
                  // config a user authored, and the rejection diagnostic.
                  "#Insight.evidence -> JsonNode",
                  "#Insight.priorityDetail/anyOf/0 -> JsonNode",
                  "#OpeningSignal.manasAroraDetail/anyOf/0 -> JsonNode",
                  "#OpeningSignal.minerviniDetail/anyOf/0 -> JsonNode",
                  "#OpeningSignal.scalperDetail/anyOf/0 -> JsonNode",
                  "#RejectionRow.diagnostic -> JsonNode",
                  // ⚠️ ARRIVED MID-REVIEW from V054 (F5 U3), and the ratchet caught it on a rebase
                  // rather than a human noticing: a NEW `JsonNode dataHealth` field on a record
                  // this list already covered. Under the blanket `#JsonNode` exemption this branch
                  // started with, it would have been absorbed in silence — the reference-site
                  // granularity is exactly what makes it a new line. Its shape is in fact known
                  // ({degraded, contextBearing, oiSuppressed, flags[]}, SignalRejectionRepository:
                  // 55), so this is typed-record DEBT rather than a polymorphic stop, recorded here
                  // rather than fixed because it is another change's surface.
                  "#RejectionRow.dataHealth/anyOf/0 -> JsonNode",
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
                  // ⚠️ hero-zero-premium and oi-attribution WERE frozen here as "deliberate stops"
                  // and are GONE as of 2026-08-29 — both converted to records on an owner shape
                  // decision, so backtest now freezes NO open response OBJECT (the JsonNode
                  // passthroughs below are a different, looser category and stay).
                  //
                  // ⚠️ THE LESSON, AND I LEARNED IT TWICE IN ONE DAY: this platform has TWO
                  // ratchets on the same fact — MapReturnRatchetTest counts Map-returning HANDLERS,
                  // this one checks the CAPTURED SPEC — and they must move TOGETHER. The
                  // market-data conversion lowered one and left the other stale; CI caught it. I
                  // then wrote exactly that warning into the market-data commit, converted
                  // backtest, and made the SAME omission. Writing a lesson down is not the same as
                  // applying it: when you lower one ratchet, grep for the other BEFORE pushing.
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
          "margin-service",
          "Ratcheted in its OWN suite — services/margin-service/tests/test_open_object_ratchet.py,"
              + " same predicate and grammar, against a live app.openapi(). Python/FastAPI, so the"
              + " source regex cannot read it either. It appeared here the moment #1199 committed"
              + " its spec, which is this assertion doing its job: a new service cannot arrive"
              + " unchecked, it can only arrive as a decision somebody writes down.",
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
            "{\"items\": true}",
            // ⚠️ THE TEN that the ALWAYS_CONSTRAINS list closed by fiat. Each discloses nothing
            // about the object's keys, and each now falls out of the definition rather than being
            // listed: `not: false` is the identity; `propertyNames: true` names no name; a lone
            // `if` has no branch to apply and lone `then`/`else` are ignored without it;
            // `discriminator` is OAS dispatch metadata; the empty regex matches every string;
            // `maxContains` does nothing without `contains`; and an adjacent `minContains: 0`
            // makes `contains` always pass.
            "{\"not\": false}",
            "{\"propertyNames\": true}",
            "{\"propertyNames\": {}}",
            "{\"if\": {\"type\": \"string\"}}",
            "{\"then\": {\"type\": \"string\"}}",
            "{\"else\": {\"type\": \"string\"}}",
            "{\"discriminator\": {\"propertyName\": \"kind\"}}",
            "{\"pattern\": \"\"}",
            "{\"maxContains\": 2}",
            "{\"contains\": {\"type\": \"string\"}, \"minContains\": 0}",
            // ⚠️ ROUND-7: these four still answered VALIDATION semantics. Each restricts the
            // instance while publishing no key names, so a client stays untyped and the diff gate
            // stays blind — and object keys are already strings, so a string `propertyNames`
            // narrows nothing at all.
            "{\"not\": {\"type\": \"string\"}}",
            "{\"if\": {\"type\": \"string\"}, \"then\": {\"maxLength\": 5}}",
            "{\"if\": {\"type\": \"string\"}, \"else\": {\"maximum\": 10}}",
            "{\"propertyNames\": {\"type\": \"string\"}}",
            // ⚠️ ROUND-9: narrowsNames had rebuilt the very classifier the disclosure reframe
            // removed, and six of its nine members were wrong in the false-CLOSED direction. Each
            // of these needed an evaluation the helper could not do, so each was a guess: a regex
            // may be universal; an unresolved $ref may point at `true`; anyOf/oneOf are
            // DISJUNCTIONS so one permissive branch admits every name; `not: false` is `true`; and
            // `if: false` makes a narrowing `then` unreachable.
            "{\"propertyNames\": {\"pattern\": \"\"}}",
            "{\"propertyNames\": {\"pattern\": \".*\"}}",
            "{\"propertyNames\": {\"pattern\": \"^x\"}}",
            "{\"propertyNames\": {\"$ref\": \"#/components/schemas/AlwaysTrue\"}}",
            "{\"propertyNames\": {\"anyOf\": [true, {\"pattern\": \"^x\"}]}}",
            "{\"propertyNames\": {\"oneOf\": [false, true]}}",
            "{\"propertyNames\": {\"not\": false}}",
            "{\"propertyNames\": {\"if\": false, \"then\": {\"pattern\": \"^x\"}}}",
            // Facets bound the SIZE of the object, never which keys it has, so a consumer still
            // cannot type the response — see the divergence note on disclosesKeyInformation.
            "{\"type\": \"object\", \"minProperties\": 1}",
            "{\"type\": \"object\", \"maxProperties\": 3}",
            "{\"maximum\": 5}",
            "{\"minLength\": 3}");
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
            "{\"type\": \"object\", \"required\": [\"a\"]}",
            "{\"patternProperties\": {\"^x\": {\"type\": \"string\"}}}",
            // PROVABLE narrowing, unconditional whatever the value: no legal name, exactly one,
            // a finite set, or an allOf CONJUNCTION containing one of those.
            "{\"propertyNames\": false}",
            "{\"propertyNames\": {\"const\": \"a\"}}",
            "{\"propertyNames\": {\"enum\": [\"a\", \"b\"]}}",
            "{\"propertyNames\": {\"allOf\": [{\"enum\": [\"a\"]}]}}",
            "{\"propertyNames\": {\"allOf\": [true, {\"const\": \"a\"}]}}",
            "{\"type\": \"object\", \"additionalProperties\": false}", // a CLOSED object
            "{\"not\": {\"required\": [\"a\"]}}",
            "{\"if\": {\"required\": [\"a\"]}, \"then\": {\"required\": [\"b\"]}}");
    for (String json : constrained) {
      assertThat(isOpenObject(mapper.readTree(json)))
          .withFailMessage("expected CONSTRAINED but the predicate said open: %s", json)
          .isFalse();
    }

    // Boolean schemas: `true` admits anything (the 10th counterexample, a $ref to it), `false`
    // admits nothing. Both were mishandled — the walk required an object node and closed both.
    assertThat(isOpenObject(mapper.readTree("true"))).isTrue();
    assertThat(isOpenObject(mapper.readTree("false"))).isFalse();
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

  /**
   * End-to-end through the WALKER, over a synthetic spec — because predicate-correct and
   * walker-correct are separate properties and this check has already shipped a case where only
   * the first held.
   *
   * <p>{@link #isOpenObject} judged the boolean {@code true} schema correctly from the day it was
   * added, and its unit case passed; but {@link #walk} bailed on "not an object" before publication
   * was ever consulted, so a {@code true} at a response root, under {@code items}, as a property or
   * in a composition branch was reported nowhere. A predicate case cannot catch that. Every
   * location form below is asserted at its exact pointer.
   */
  @Test
  void theWalkerReportsOpenObjectsAtEveryLocationForm() throws Exception {
    String spec =
        """
        {
          "paths": {
            "/direct":    {"get": {"responses": {"200": {"content": {"*/*": {"schema": true}}}}}},
            "/array":     {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"type": "array", "items": true}}}}}}},
            "/property":  {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"type": "object", "properties": {"payload": true}}}}}}}},
            "/composed":  {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"allOf": [true]}}}}}}},
            "/notstring": {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"not": {"type": "string"}}}}}}}},
            "/cond":      {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"if": {"type": "string"}, "then": {"maxLength": 5}}}}}}}},
            "/names":     {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"propertyNames": {"type": "string"}}}}}}}},
            "/refopen":   {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"$ref": "#/components/schemas/NotString"}}}}}}},
            "/typed":     {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"$ref": "#/components/schemas/Typed"}}}}}}},
            "/closed":    {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"type": "object", "additionalProperties": false}}}}}}},
            "/never":     {"get": {"responses": {"200": {"content": {"*/*": {"schema": false}}}}}},
            "/maptrue":   {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"type": "object", "additionalProperties":
                              {"$ref": "#/components/schemas/AlwaysTrue"}}}}}}}},
            "/maptyped":  {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                            {"type": "object", "additionalProperties":
                              {"$ref": "#/components/schemas/Typed"}}}}}}}}
          },
          "components": {"schemas": {
            "NotString": {"not": {"type": "string"}},
            "AlwaysTrue": true,
            "Typed": {"type": "object", "properties": {"a": {"type": "string"}}}
          }}
        }
        """;
    Set<String> found = openObjectsIn(new ObjectMapper().readTree(spec));

    assertThat(found)
        .containsExactlyInAnyOrder(
            "GET /direct 200", // a bare `true` as the response schema
            "GET /array 200[]", // `items: true`
            "GET /property 200.payload", // a `true`-valued property
            "GET /composed 200/allOf/0", // a `true` composition branch
            "GET /notstring 200", // `not` over a scalar type discloses no keys
            "GET /cond 200", // conditional over scalar facets discloses no keys
            "GET /names 200", // object keys are already strings
            "GET /refopen 200 -> NotString", // and the same through a $ref
            // the sibling sweep: a Map whose VALUE schema $refs the boolean `true` discloses
            // nothing, and counting the $ref on sight was the same unresolved-reference flaw
            "GET /maptrue 200");

    // negative controls: these must be reported NOWHERE. /maptyped is the one that proves the
    // $ref is RESOLVED rather than blanket-ignored — Map<String, SomeRecord> stays disclosed.
    assertThat(found).noneMatch(location -> location.contains("/typed"));
    assertThat(found).noneMatch(location -> location.contains("/closed"));
    assertThat(found).noneMatch(location -> location.contains("/never"));
    assertThat(found).noneMatch(location -> location.contains("/maptyped"));
  }

  /**
   * ⚠️ THE STRUCTURAL AGREEMENT ASSERTION — the fix for a CLASS, not an instance.
   *
   * <p>Three separate rounds found the same defect: the predicate consulted a subschema the walker
   * never descended, so the schema was classified correctly and then reported nowhere. Round 7 was
   * the boolean {@code true} schema, round 8 the {@code $ref} location, round 9 {@code
   * unevaluatedProperties} — which the predicate had handled since the round it was added while
   * every walker descended only {@code additionalProperties}. The {@code type} clause looked sound
   * purely because the additional-properties descent happened to find the nested open object
   * afterwards; the unevaluated path was silently blind.
   *
   * <p>Two point fixes each produced the next instance, so this asserts the property directly:
   * plant a KNOWN-OPEN schema (the boolean {@code true}, which also exercises the round-7 path)
   * under every keyword declared descendable, and require the walker to report it at exactly the
   * declared pointer. A keyword the walker fails to descend fails here by construction.
   */
  @Test
  void theWalkerDescendsEverySchemaBearingKeyword() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    for (Map.Entry<String, Descent> entry : DESCENT.entrySet()) {
      String keyword = entry.getKey();
      Descent descent = entry.getValue();
      ObjectNode schema = mapper.createObjectNode();
      // `required` forces the parent CLOSED, so the walker must actually descend to find anything.
      schema.putArray("required").add("x");
      String expected =
          switch (descent.container()) {
            case MAP -> {
              schema.putObject(keyword).put("k", true);
              yield descent.pointer().replace("{k}", "k");
            }
            case LIST -> {
              schema.putArray(keyword).add(true);
              yield descent.pointer().replace("{i}", "0");
            }
            case SCHEMA -> {
              schema.put(keyword, true);
              if (keyword.equals("then") || keyword.equals("else")) {
                schema.putObject("if").put("type", "string"); // inert without its condition
              }
              yield descent.pointer();
            }
          };
      ObjectNode spec = mapper.createObjectNode();
      spec.putObject("paths")
          .putObject("/p")
          .putObject("get")
          .putObject("responses")
          .putObject("200")
          .putObject("content")
          .putObject("*/*")
          .set("schema", schema);
      spec.putObject("components").putObject("schemas");

      assertThat(openObjectsIn(spec))
          .withFailMessage(
              "the predicate classifies `%s` but the walker never descends it, so an open schema"
                  + " under it is reported NOWHERE — the exact defect that shipped three times."
                  + " Expected [GET /p 200%s], got %s",
              keyword, expected, openObjectsIn(spec))
          .containsExactly("GET /p 200" + expected);
    }
  }

  /**
   * The classification must be EXHAUSTIVE, so a subschema-bearing keyword cannot arrive unnoticed:
   * every one is either descended or explicitly excused with a reason. Without this, the agreement
   * test above only proves the keywords somebody remembered to list.
   */
  @Test
  void everySubschemaKeywordIsClassified() throws IOException {
    Set<String> classified = new TreeSet<>(DESCENT.keySet());
    classified.addAll(NOT_DESCENDED.keySet());
    assertThat(classified)
        .withFailMessage(
            "a subschema-bearing keyword is neither descended nor excused: %s. Add it to DESCENT"
                + " with its pointer, or to NOT_DESCENDED with the reason it publishes no wire"
                + " location.",
            classified)
        .containsExactlyInAnyOrderElementsOf(derivedKeywords("subschemaBearing"));
    assertThat(DESCENT.keySet()).doesNotContainAnyElementsOf(NOT_DESCENDED.keySet());
  }

  /**
   * Every keyword in JSON Schema 2020-12 whose value contains subschema(s) — read from {@code
   * contracts/json-schema-2020-12-keywords.json}, which {@code tools/derive-json-schema-keywords.py}
   * DERIVES from the published vocabulary meta-schemas.
   *
   * <p>⚠️ It is read rather than typed because the hand-written version of this constant was
   * CIRCULAR: it existed to prove no keyword arrives unclassified, and compared the classification
   * against another list maintained by the same hand — so a keyword could be missing from both and
   * the equality still passed. It was: {@code $defs} and {@code contentSchema} were absent, and
   * {@code $ref} was wrongly present ({@code $ref} takes a URI STRING, not a subschema, which is
   * why reference handling is a separate axis with its own assertions below). An invariant asserted
   * against a hand-list inherits the hand-list's blind spots.
   *
   * <p><b>Bounded claim:</b> the derivation's dependency is a dev-machine package, so this is NOT
   * re-derived per CI run. Guaranteed: the list was mechanically derived from the normative
   * meta-schemas, and re-deriving is one command. NOT guaranteed: freshness against a future
   * dialect — which {@link #everyCommittedSpecUsesTheDialectTheInventoryWasDerivedFor} catches.
   */
  /** The committed, derived keyword artifact. */
  private static JsonNode keywordArtifact() throws IOException {
    return new ObjectMapper()
        .readTree(
            Files.readString(
                findRepoRoot().resolve("contracts/json-schema-2020-12-keywords.json")));
  }

  private static Set<String> derivedKeywords(String field) throws IOException {
    Set<String> keywords = new TreeSet<>();
    keywordArtifact().path(field).forEach(k -> keywords.add(k.asText()));
    assertThat(keywords).as("derived %s", field).isNotEmpty();
    return keywords;
  }

  /**
   * Every reference form the dialect declares is either FOLLOWED or explicitly excused. Without
   * this, {@code $dynamicRef} and {@code $recursiveRef} were mentioned in a comment inside the
   * {@code $ref} branch and read by nothing — a claimed loud failure that never fired.
   */
  @Test
  void everyReferenceCandidateIsClassified() throws IOException {
    Set<String> classified = new TreeSet<>(FOLLOWED_REFERENCE_FORMS);
    classified.addAll(NON_FOLLOWED_REFERENCE_FORMS.keySet());
    assertThat(classified)
        .withFailMessage(
            "a reference form the dialect declares is neither followed nor excused: %s", classified)
        .containsExactlyInAnyOrderElementsOf(derivedKeywords("referenceCandidates"));
  }

  /**
   * Each reference form paired with a CLOSING sibling. The sibling is the whole point: a bare
   * {@code $dynamicRef} read open by accident through the unknown-keyword default, so only a
   * disclosing sibling — which closes the parent and sends the walker into descent — exposes
   * whether the reference is read at all.
   */
  @Test
  void everyReferenceFormSurvivesAClosingSibling() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    for (String form : new TreeSet<>(FOLLOWED_REFERENCE_FORMS)) {
      String spec =
          ("""
           {"paths": {"/p": {"get": {"responses": {"200": {"content": {"*/*": {"schema":
             {"KEYWORD": "#/components/schemas/Missing", "required": ["x"]}}}}}}}},
            "components": {"schemas": {}}}
           """)
              .replace("KEYWORD", form);
      assertThat(openObjectsIn(mapper.readTree(spec)))
          .withFailMessage(
              "`%s` beside a closing sibling was reported NOWHERE — the reference form is not"
                  + " read at all, which is what a comment claiming a loud failure hid.",
              form)
          .isNotEmpty();
    }
  }


  /**
   * ⚠️ THE ONE RESOLVER. Every local reference in this file resolves here — the walker's {@code
   * $ref} branch, the closure, and {@link #valueSchemaSaysSomething}.
   *
   * <p>There used to be TWO. {@code valueSchemaSaysSomething} carried its own, which accepted only
   * {@code #/components/schemas/…}, so a map value pointing at {@code #/$defs/X} read as "says
   * nothing", the parent was reported open, and the walker returned before descending — and once
   * that wrong parent location was frozen, adding an open child inside the target produced no new
   * location. Reference-site absorption, through a fourth door. Making the second resolver smarter
   * would have left the divergence alive; there is now only one, and {@code #/components/schemas/X}
   * is simply a {@code #/} pointer like any other.
   */
  private static JsonNode resolveLocalSchema(JsonNode root, String reference) {
    return reference.startsWith("#/") ? resolvePointer(root, reference.substring(1)) : null;
  }

  /** Raw JSON-pointer walk; use {@link #resolveLocalSchema} rather than calling this directly. */
  private static JsonNode resolvePointer(JsonNode root, String pointer) {
    JsonNode current = root;
    for (String raw : pointer.split("/")) {
      if (raw.isEmpty()) {
        continue;
      }
      String token = raw.replace("~1", "/").replace("~0", "~");
      current = current.get(token);
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  /**
   * {@code $ref} does NOT replace its siblings at 2020-12 — it applies alongside them. Skipping
   * them meant a frozen reference site absorbed any opaque sibling for free, which is the
   * reference-site allowance hole this test closed once already, re-entering by a different door.
   */
  @Test
  void aReferenceDoesNotAbsorbItsOpaqueSiblings() throws Exception {
    String spec =
        """
        {
          "paths": {
            "/typed-target":  {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                                {"$ref": "#/components/schemas/Typed",
                                 "properties": {"payload": true}}}}}}}},
            "/open-target":   {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                                {"$ref": "#/components/schemas/Open",
                                 "properties": {"payload": true}}}}}}}},
            "/defs-ref":      {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                                {"$ref": "#/$defs/Open"}}}}}}},
            "/defs-typed":    {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                                {"$ref": "#/$defs/TypedWithOpenChild"}}}}}}},
            "/ap-defs":       {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                                {"type": "object", "additionalProperties":
                                  {"$ref": "#/$defs/TypedWithOpenChild"}}}}}}}},
            "/unev-defs":     {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                                {"type": "object", "unevaluatedProperties":
                                  {"$ref": "#/$defs/TypedWithOpenChild"}}}}}}}},
            "/external":      {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                                {"$ref": "https://example.test/x.json#/Foo"}}}}}}},
            "/dangling":      {"get": {"responses": {"200": {"content": {"*/*": {"schema":
                                {"$ref": "#/components/schemas/Missing"}}}}}}}
          },
          "$defs": {
            "Open": true,
            "TypedWithOpenChild": {"type": "object", "properties": {"payload": true}}
          },
          "components": {"schemas": {
            "Typed": {"type": "object", "properties": {"a": {"type": "string"}}},
            "Open": true
          }}
        }
        """;
    assertThat(openObjectsIn(new ObjectMapper().readTree(spec)))
        .containsExactlyInAnyOrder(
            // a TYPED target no longer hides an opaque sibling
            "GET /typed-target 200.payload",
            // an OPEN target reports the site AND the sibling — two distinct locations
            "GET /open-target 200 -> Open",
            "GET /open-target 200.payload",
            // a local pointer outside components/schemas is resolved, not ignored
            "GET /defs-ref 200 -> #/$defs/Open",
            // ⚠️ and a TYPED $defs target is TRAVERSED, not just classified at its root:
            // the open child is reported at its own location inside the target. BOTH map-value
            // forms must reach it — additionalProperties and unevaluatedProperties each used to
            // consult a second, component-only resolver that answered "says nothing" for a $defs
            // pointer, so the parent was reported open and the walker returned before descending.
            "#/$defs/TypedWithOpenChild.payload",
            // forms that cannot be resolved from the captured document fail LOUDLY
            "GET /external 200 -> UNRESOLVABLE https://example.test/x.json#/Foo",
            "GET /dangling 200 -> #/components/schemas/Missing");
  }

  /**
   * The derived inventory is pinned to one dialect and is not re-derived per CI run, so this is the
   * guard against it going stale: every committed spec must still declare an OpenAPI version whose
   * default JSON Schema dialect is the 2020-12 one the artifact was derived for.
   */
  @Test
  void everyCommittedSpecUsesTheDialectTheInventoryWasDerivedFor() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Path contracts = findRepoRoot().resolve("contracts");
    Set<String> services = new TreeSet<>(FROZEN_OPEN_OBJECTS.keySet());
    services.addAll(OUT_OF_SCOPE.keySet());
    for (String service : services) {
      JsonNode spec = mapper.readTree(Files.readString(contracts.resolve(service + SPEC_SUFFIX)));
      assertThat(spec.path("openapi").asText(""))
          .withFailMessage(
              "%s declares OpenAPI %s. The subschema-keyword inventory in"
                  + " contracts/json-schema-2020-12-keywords.json was derived for the 2020-12"
                  + " dialect that OpenAPI 3.1 implies; re-derive it with"
                  + " tools/derive-json-schema-keywords.py before moving off 3.1.",
              service, spec.path("openapi").asText(""))
          .startsWith("3.1");
      // ⚠️ An omitted `jsonSchemaDialect` does NOT mean raw 2020-12 — OAS 3.1.1 §4.8.24.1 says it
      // means the OAS BASE dialect, and all six contracts omit the field, so asserting they
      // declared 2020-12 asserted something the documents never said. Take the default FROM the
      // artifact and require the EFFECTIVE dialect to be one the inventory covers.
      JsonNode artifact = keywordArtifact();
      String effective =
          spec.path("jsonSchemaDialect")
              .asText(artifact.path("omittedJsonSchemaDialectMeans").asText());
      Set<String> applicable = new TreeSet<>();
      artifact.path("applicableDialects").forEach(d -> applicable.add(d.asText()));
      assertThat(applicable)
          .withFailMessage(
              "%s effectively uses dialect %s, which the keyword inventory does not cover (%s)."
                  + " Re-derive with tools/derive-json-schema-keywords.py.",
              service, effective, applicable)
          .contains(effective);
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
            walk(spec, schemas, media.get("schema"), where, "", found, componentRoots);
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
      // A closure entry is either a component NAME (walked as `#Name`, the frozen location form)
      // or a raw local POINTER such as `#/$defs/X`, which is its own location.
      boolean isPointer = name.startsWith("#/");
      JsonNode resolvedTarget =
          isPointer ? resolveLocalSchema(spec, name) : schemas.get(name);
      walk(spec, schemas, resolvedTarget, isPointer ? name : "#" + name, "", found, refs);
      refs.stream().filter(r -> !visited.contains(r)).forEach(queue::push);
    }
    return found;
  }

  private static void walk(
      JsonNode root,
      JsonNode schemas,
      JsonNode node,
      String where,
      String pointer,
      Set<String> found,
      Set<String> refs) {
    if (node == null) {
      return;
    }
    if (!node.isObject()) {
      // ⚠️ PREDICATE-CORRECT IS NOT WALKER-CORRECT. isOpenObject already judged the boolean `true`
      // schema correctly and its unit case passed, while this branch dropped it before publication
      // ever saw it — so a `true` sitting at a response root, under `items`, as a property or in a
      // composition branch was invisible. Evaluate here rather than bailing on "not an object".
      if (isOpenObject(node, root)) {
        found.add(where + pointer);
      }
      return;
    }
    String referenceKeyword =
        FOLLOWED_REFERENCE_FORMS.stream().filter(node::has).findFirst().orElse(null);
    JsonNode ref = referenceKeyword == null ? null : node.get(referenceKeyword);
    if (ref != null) {
      // A reference to a component that IS an open object (JsonNode -> `{}`) makes THIS SITE the
      // opacity, not the component: freezing the component name once would authorise unlimited
      // further uses of it. Freezing the site means each new exposure is a new line.
      String target = ref.asText();
      boolean dynamic = !referenceKeyword.equals("$ref");
      if (dynamic) {
        // $dynamicRef / $recursiveRef resolve against a RUNTIME dynamic scope, so a captured
        // document cannot say what they point at. Report unconditionally — see below for why
        // "it reads open anyway" was not good enough.
        found.add(where + pointer + " -> UNRESOLVABLE " + referenceKeyword + " " + target);
      } else if (target.startsWith(COMPONENT_PREFIX)) {
        String name = target.substring(COMPONENT_PREFIX.length());
        JsonNode component = resolveLocalSchema(root, target);
        if (component == null) {
          // A DANGLING component reference publishes a shape nothing can verify. Reporting it is
          // the fails-safe reading; the previous code seeded a closure entry that resolved to
          // nothing and moved on, which is the silent direction.
          found.add(where + pointer + " -> " + target);
        } else if (isOpenObject(component, root)) {
          found.add(where + pointer + " -> " + name);
        } else {
          refs.add(name);
        }
      } else if (target.startsWith("#/")) {
        // Any other LOCAL pointer (#/$defs/X, and anything else in-document) is resolved rather
        // than ignored — silently skipping it published an unchecked shape.
        JsonNode resolved = resolveLocalSchema(root, target);
        if (resolved == null || isOpenObject(resolved, root)) {
          found.add(where + pointer + " -> " + target);
        } else {
          // ⚠️ RESOLVE THEN KEEP WALKING. Classifying the target's ROOT and stopping meant a
          // TYPED `$defs` record containing an open field reported nothing — the third place
          // "resolve the reference" shipped without "and then traverse it" (after component
          // siblings and the dynamic forms). Local pointers ride the SAME closure the component
          // refs use, so its visited-set gives cycle protection for free.
          refs.add(target);
        }
      } else {
        // External URIs cannot be resolved from the captured document. FAIL LOUDLY rather than
        // treating an unverifiable target as safe.
        found.add(where + pointer + " -> UNRESOLVABLE " + target);
      }
      // ⚠️ $dynamicRef / $recursiveRef were previously read by NOTHING — both were named only in a
      // comment inside this branch, which tested `$ref` alone. A BARE one still read open through
      // the unknown-keyword default, which is exactly what made the gap invisible: add any
      // disclosing sibling (`required`), the parent closes, the walker descends, and the reference
      // disappears with no report at all. "It happens to read open" is not the same as "it is read".
      //
      // ⚠️ DO NOT RETURN. At 2020-12 `$ref` is an applicator ALONGSIDE its siblings, not a
      // replacement for them (that was draft-07). Returning here meant a frozen reference site
      // absorbed any opaque sibling for free — the reference-site allowance hole this test closed
      // once already, re-entering through a different door.
    } else if (isOpenObject(node, root)) {
      found.add(where + pointer);
      return;
    }
    // ⚠️ DESCENT IS DATA, NOT CODE — the walker ITERATES {@link #DESCENT} rather than restating it.
    // Three rounds running, the predicate consulted a subschema this method never descended
    // (boolean schemas, the $ref location, then unevaluatedProperties, which was classified at
    // :802 and walked nowhere). Two point fixes each produced the next instance. A keyword declared
    // descended can no longer be missed, because no branch hand-writes it.
    for (Map.Entry<String, Descent> entry : DESCENT.entrySet()) {
      JsonNode value = node.get(entry.getKey());
      if (value == null) {
        continue;
      }
      Descent descent = entry.getValue();
      switch (descent.container()) {
        case MAP -> {
          for (Map.Entry<String, JsonNode> child : value.properties()) {
            walk(
                root,
                schemas,
                child.getValue(),
                where,
                pointer + descent.pointer().replace("{k}", child.getKey()),
                found,
                refs);
          }
        }
        case LIST -> {
          for (int i = 0; i < value.size(); i++) {
            walk(
                root,
                schemas,
                value.get(i),
                where,
                pointer + descent.pointer().replace("{i}", String.valueOf(i)),
                found,
                refs);
          }
        }
        case SCHEMA ->
            walk(root, schemas, value, where, pointer + descent.pointer(), found, refs);
      }
    }
  }

  private enum Container {
    MAP,
    LIST,
    SCHEMA
  }

  /** How a subschema-bearing keyword is carried, and the pointer suffix its children get. */
  private record Descent(Container container, String pointer) {}

  /**
   * Every subschema-bearing keyword whose children can appear ON THE WIRE, and therefore MUST be
   * descended. The walker reads this; {@link #theWalkerDescendsEverySchemaBearingKeyword} proves
   * each entry actually reports, and {@link #everySubschemaKeywordIsClassified} proves the
   * classification is exhaustive so a new one cannot be quietly forgotten.
   */
  private static final Map<String, Descent> DESCENT =
      Map.ofEntries(
          Map.entry("properties", new Descent(Container.MAP, ".{k}")),
          Map.entry("patternProperties", new Descent(Container.MAP, ".~{k}")),
          Map.entry("dependentSchemas", new Descent(Container.MAP, "?{k}")),
          // 2020-12 still declares the legacy `dependencies`, whose map values are EITHER a
          // schema or a string array. The array values are skipped by walk()'s own node guard, so
          // the plain MAP descent is correct and the schema-valued entries are reached.
          Map.entry("dependencies", new Descent(Container.MAP, "?{k}")),
          Map.entry("additionalProperties", new Descent(Container.SCHEMA, "{}")),
          Map.entry("unevaluatedProperties", new Descent(Container.SCHEMA, "{*}")),
          Map.entry("items", new Descent(Container.SCHEMA, "[]")),
          Map.entry("contains", new Descent(Container.SCHEMA, "[?]")),
          Map.entry("unevaluatedItems", new Descent(Container.SCHEMA, "[*]")),
          Map.entry("then", new Descent(Container.SCHEMA, "/then")),
          Map.entry("else", new Descent(Container.SCHEMA, "/else")),
          Map.entry("allOf", new Descent(Container.LIST, "/allOf/{i}")),
          Map.entry("anyOf", new Descent(Container.LIST, "/anyOf/{i}")),
          Map.entry("oneOf", new Descent(Container.LIST, "/oneOf/{i}")),
          Map.entry("prefixItems", new Descent(Container.LIST, "/prefixItems/{i}")));

  /**
   * Reference keywords whose target this check FOLLOWS. The derived candidate set is {@code
   * referenceCandidates} in the keyword artifact; {@code $id} is the fourth and is deliberately
   * absent because it ESTABLISHES a base URI rather than pointing at a schema to inspect.
   * {@link #everyReferenceCandidateIsClassified} keeps that split honest, and
   * {@link #everyReferenceFormSurvivesAClosingSibling} proves each one is actually READ — which
   * {@code $dynamicRef} and {@code $recursiveRef} were not, despite a comment claiming they were.
   */
  private static final Set<String> FOLLOWED_REFERENCE_FORMS =
      Set.of("$ref", "$dynamicRef", "$recursiveRef");

  /** Reference candidates that are NOT pointers to follow, with the reason. */
  private static final Map<String, String> NON_FOLLOWED_REFERENCE_FORMS =
      Map.of("$id", "establishes a base URI for the schema; it does not point at another schema");

  /**
   * Subschema-bearing keywords deliberately NOT descended, each with the reason it publishes no
   * wire location.
   *
   * <p>⚠️ Why these can stay PROSE while {@code $ref} needed asserted behaviour. An accidental
   * descent into {@code propertyNames} / {@code not} / {@code if} would invent a location that does
   * not exist, and a wrong location fails LOUDLY — the exact-set assertion names it immediately.
   * {@code $ref} failed the other way: skipping its siblings reported one location too FEW,
   * silently, which is indistinguishable from correctness. <b>A judgement whose failure mode is
   * silent needs an assertion; one whose failure mode is loud can be argued in a comment.</b>
   */
  private static final Map<String, String> NOT_DESCENDED =
      Map.of(
          "propertyNames", "constrains key NAMES, which are strings; no object is published there",
          "not", "describes what the value is NOT; nothing is published at that position",
          "if", "a condition that is tested, never itself published (`then`/`else` ARE descended)",
          "$defs", "a REUSE container; its members are published only where something $refs them,"
              + " and that path is covered at the reference site",
          "definitions", "the pre-2019 spelling of $defs, still declared by the 2020-12"
              + " compatibility layer; a reuse container for the same reason",
          "contentSchema", "describes the decoded content of a STRING instance, not an object"
              + " published at this position (annotation-only at 2020-12)");



  /**
   * Does this node put ANY information about the object's KEYS into the published document?
   *
   * <p>⚠️ <b>This asks DISCLOSURE, not validation, and the distinction is the whole design.</b> Four
   * consecutive review rounds killed a keyword classifier that tried to decide "does keyword K with
   * value V restrict the instance" by table lookup. That is re-implementing a JSON Schema validator
   * badly — the spec has ~40 keywords with interactions a table cannot hold ({@code contains} +
   * {@code minContains: 0}, a lone {@code if} with no branch, {@code maxContains} without {@code
   * contains}, {@code not: false}) — and each round found more of them.
   *
   * <p>But the ratchet never needed validation semantics. It exists because a response that
   * publishes no key information is invisible to ci-contracts' breaking-diff gate and generates an
   * untyped client. That is a STRUCTURAL question about the document, and it has a short closed
   * answer: only the keywords below put key information on the wire. Every numeric, string and
   * array facet — {@code maximum}, {@code minLength}, {@code pattern}, {@code maxItems}, {@code
   * multipleOf}, {@code contains}, {@code maxContains}, {@code uniqueItems} — is irrelevant by
   * construction rather than by having been classified, and so are {@code format}, {@code
   * discriminator} and {@code nullable}. All ten counterexamples that killed the previous cut fall
   * out of this definition without being listed.
   *
   * <p>The default is NON-disclosing, so an unrecognised keyword can never close a schema: the
   * failure direction is a loud false OPEN, resolved by a reviewed exemption.
   *
   * <p>DELIBERATE, and a divergence from a reviewer ruling worth seeing: {@code minProperties} /
   * {@code maxProperties} do NOT disclose. They bound how MANY keys exist, never WHICH — a consumer
   * still cannot type the response and the diff gate still cannot see a rename. They are genuinely
   * constraining for VALIDATION, which is exactly why the reframing matters.
   */
  private static boolean disclosesKeyInformation(JsonNode node, JsonNode root) {
    if (!node.isObject()) {
      return false; // a missing branch, or a boolean schema: publishes no key names either way
    }
    if (FOLLOWED_REFERENCE_FORMS.stream().anyMatch(node::has)) {
      return true; // delegates disclosure to the target; resolved at the reference site
    }
    for (String keyword :
        List.of(
            "properties", // the key names themselves
            "patternProperties",
            "required", // names the keys that must be present
            "dependentSchemas",
            "dependentRequired",
            "allOf",
            "anyOf",
            "oneOf",
            "enum")) {
      if (nonEmpty(node.get(keyword))) {
        return true;
      }
    }
    if (node.has("const")) {
      return true;
    }
    for (String keyword : List.of("additionalProperties", "unevaluatedProperties")) {
      if (node.has(keyword)
          && valueSchemaSaysSomething(node.get(keyword), root, new HashSet<>())) {
        return true;
      }
    }
    if (node.has("propertyNames") && narrowsNames(node.get("propertyNames"))) {
      return true;
    }
    // ⚠️ RECURSE, do not merely detect PRESENCE. These three carried the old validation model:
    // `not: {"type":"string"}` and `if/then` over scalar facets restrict the instance but publish
    // no key names, so a client is still untyped and the diff gate still blind. Ask the SUBSCHEMA
    // the same disclosure question, defaulting to non-disclosing when it answers no.
    if (node.has("not") && disclosesKeyInformation(node.path("not"), root)) {
      return true;
    }
    if (node.has("if") && (node.has("then") || node.has("else"))) { // a lone `if` has no effect
      return disclosesKeyInformation(node.path("then"), root)
          || disclosesKeyInformation(node.path("else"), root);
    }
    return false;
  }

  /**
   * Does an {@code additionalProperties} / {@code unevaluatedProperties} subschema pin the VALUE
   * shape? {@code true} and <code>{}</code> say nothing; {@code false} says "no further keys", which
   * is a closed object and very much something.
   *
   * <p>A {@code $ref} is RESOLVED rather than counted as disclosure on sight — the same
   * unresolved-reference flaw {@link #narrowsNames} carried, swept from this sibling in the same
   * pass. {@code additionalProperties: {"$ref": X}} discloses iff X does, so a reference to the
   * boolean {@code true} schema is correctly nothing while {@code Map<String, SomeRecord>} stays
   * disclosed.
   *
   * <p>⚠️ Scope of that change, stated precisely because the first telling undersold it: for a
   * VALID RESOLVABLE reference it is location ACCURACY, not blindness — the old rule still reported
   * the opacity, at {@code …{} -> Name} rather than at the node root. But a MISSING, EXTERNAL or
   * directly-CYCLIC reference now fails LOUDLY where the old rule reported nothing at all, because
   * an unresolvable target says nothing and the node therefore reads open.
   */
  private static boolean valueSchemaSaysSomething(
      JsonNode schema, JsonNode root, Set<String> seen) {
    if (schema.isBoolean()) {
      return !schema.booleanValue();
    }
    if (!schema.isObject() || schema.isEmpty()) {
      return false;
    }
    JsonNode ref = schema.get("$ref");
    if (ref != null) {
      JsonNode target = resolveLocalSchema(root, ref.asText());
      if (target == null || !seen.add(ref.asText())) {
        return false; // unresolvable, external, or a reference cycle
      }
      return valueSchemaSaysSomething(target, root, seen);
    }
    return schema.has("type") || disclosesKeyInformation(schema, root);
  }

  /**
   * Does a {@code propertyNames} subschema narrow WHICH names are legal?
   *
   * <p>⚠️ <b>ONLY directly provable narrowing counts.</b> This helper is where the disclosure
   * reframe leaked: it replaced the global validation-semantics table and then rebuilt a small one
   * locally, and six of its nine members were wrong in the false-CLOSED direction — {@code pattern}
   * (universal patterns like {@code .*} narrow nothing), {@code $ref} (unresolved; a reference to
   * {@code true} narrows nothing), {@code anyOf} and {@code oneOf} (DISJUNCTIONS — one permissive
   * branch such as {@code [true, {"pattern":"^x"}]} accepts every name), {@code not} ({@code not:
   * false} ≡ {@code true}), and the conditional ({@code if: false} makes a narrowing {@code then}
   * unreachable). Each needed evaluation this helper could not do, so each was a guess.
   *
   * <p>What survives is unconditional regardless of value, which is what makes it not a classifier:
   * {@code false} permits no name at all, {@code const} permits exactly one (or none, for a
   * non-string constant), a non-empty {@code enum} permits a finite set, and {@code allOf} is a
   * CONJUNCTION so any provably-narrowing branch narrows the whole. Everything else defaults to
   * non-narrowing — the fails-safe direction, since the cost is a loud false OPEN.
   */
  private static boolean narrowsNames(JsonNode schema) {
    if (schema.isBoolean()) {
      return !schema.booleanValue(); // `false` = no legal names at all
    }
    if (!schema.isObject()) {
      return false;
    }
    if (schema.has("const") || nonEmpty(schema.get("enum"))) {
      return true;
    }
    for (JsonNode branch : schema.path("allOf")) {
      if (narrowsNames(branch)) {
        return true;
      }
    }
    return false;
  }

  private static boolean nonEmpty(JsonNode node) {
    return node != null && node.isContainerNode() && !node.isEmpty();
  }

  /**
   * True when the schema publishes nothing about the keys of the object it describes — the empty
   * schema <code>{}</code>, the boolean {@code true} schema, annotation-only such as {@code
   * {"title": "x"}}, {@code type: object} with nothing enumerated, or {@code additionalProperties}
   * of {@code true} / <code>{}</code>.
   */
  /** Standalone schemas (the regression cases) carry no components to resolve against. */
  private static boolean isOpenObject(JsonNode node) {
    return isOpenObject(node, JsonNodeFactory.instance.objectNode());
  }

  private static boolean isOpenObject(JsonNode node, JsonNode root) {
    if (node == null) {
      return false;
    }
    if (node.isBoolean()) {
      return node.booleanValue(); // the boolean TRUE schema admits anything; FALSE admits nothing
    }
    if (!node.isObject()) {
      return false;
    }
    List<String> types = declaredTypes(node);
    if (!types.isEmpty() && !types.contains("object")) {
      return false;
    }
    return !disclosesKeyInformation(node, root);
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
