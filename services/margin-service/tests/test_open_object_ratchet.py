"""Open-object ratchet for margin-service's published surface — the same rules, predicate and
location grammar as edge-gateway's SpecOpenObjectRatchetTest and optimizer-service's ratchet.

No response may contain an UNCONSTRAINED OPEN OBJECT — one that publishes nothing about the keys of
the object it describes — outside a named frozen exemption. A consumer reading one learns nothing:
every key is legal, no key is promised, ci-contracts' breaking gate has nothing to diff, and the
generated client is untyped.

WHY THIS LIVES HERE rather than in the Java ratchet. margin-service is Python/FastAPI, so
MapReturnRatchetTest's source regex cannot read it at all; and #1199 committed its spec, so the Java
inventory assertion demanded it be either ratcheted or declared out of scope. Reading the committed
dump would leave a new open response unguarded until somebody re-dumped, so — exactly as for
optimizer-service — this generates the document from ``app.openapi()`` every run. No staleness
window, and it fails in the language of whoever broke it. It rides ci-margin, which is
path-filtered to precisely the changes that can introduce one.

WHAT COUNTS AS "NO CONSTRAINT" is a DISCLOSURE question, not a validation one — see
``_discloses_key_information``. The default is non-disclosing so an unrecognised keyword reads OPEN
and fails loudly, and ``test_an_unknown_keyword_is_never_silenced_by_an_inert_known_one`` asserts
that property rather than leaving it as a claim in this docstring.

BLIND SPOTS: this sees only what ``app.openapi()`` enumerates, so a route excluded from the schema
(``include_in_schema=False``) is invisible; and it is scoped to RESPONSES — request bodies are
deliberately out, since the question is what we PUBLISH.
"""

import json
import pathlib
from typing import Any

from app.main import app

COMPONENT_PREFIX = "#/components/schemas/"

# Every unconstrained open object margin-service is allowed to publish, named one by one. The
# assertion is exact-set equality in BOTH directions: a new open object fails, and an exemption
# that no longer exists also fails, so typing a response forces its line to be deleted.
FROZEN_OPEN_OBJECTS = {
    # DEBT, not doctrine. FastAPI's own HTTPValidationError detail carries `ctx` and `input` as
    # bare `Any` — pydantic's rendering of "whatever value failed validation", which genuinely has
    # no shape. They are framework-owned: closing them means overriding FastAPI's built-in 422
    # model, not editing this service.
    "#ValidationError.ctx",
    "#ValidationError.input",
    # prometheus-fastapi-instrumentator mounts /metrics with no declared response model (it serves
    # text/plain in practice). The empty schema is the instrumentator's, not ours, and typing it
    # would mean patching a third-party mount. optimizer-service carries the same exemption.
    "GET /metrics 200",
}


# Every subschema-bearing keyword whose children can appear ON THE WIRE, and therefore MUST be
# descended: (container kind, pointer suffix for its children). The walker READS this rather than
# restating it -- three rounds running, the predicate consulted a subschema the walker never
# descended (boolean schemas, the $ref location, then unevaluatedProperties, classified in the
# predicate and walked nowhere), and two point fixes each produced the next instance.
DESCENT = {
    "properties":            ("map",    ".{k}"),
    "patternProperties":     ("map",    ".~{k}"),
    "dependentSchemas":      ("map",    "?{k}"),
    # 2020-12 still declares the legacy `dependencies`, whose map values are EITHER a schema or a
    # string array. Array values are skipped by _walk's own node guard, so the plain map descent is
    # correct and the schema-valued entries are reached.
    "dependencies":          ("map",    "?{k}"),
    "additionalProperties":  ("schema", "{}"),
    "unevaluatedProperties": ("schema", "{*}"),
    "items":                 ("schema", "[]"),
    "contains":              ("schema", "[?]"),
    "unevaluatedItems":      ("schema", "[*]"),
    "then":                  ("schema", "/then"),
    "else":                  ("schema", "/else"),
    "allOf":                 ("list",   "/allOf/{i}"),
    "anyOf":                 ("list",   "/anyOf/{i}"),
    "oneOf":                 ("list",   "/oneOf/{i}"),
    "prefixItems":           ("list",   "/prefixItems/{i}"),
}

# Reference keywords whose target this check FOLLOWS. The derived candidate set is
# `referenceCandidates` in the keyword artifact; `$id` is the fourth and is deliberately absent
# because it ESTABLISHES a base URI rather than pointing at a schema to inspect.
FOLLOWED_REFERENCE_FORMS = ("$ref", "$dynamicRef", "$recursiveRef")

# Reference candidates that are NOT pointers to follow, with the reason.
NON_FOLLOWED_REFERENCE_FORMS = {
    "$id": "establishes a base URI for the schema; it does not point at another schema",
}

# Subschema-bearing keywords deliberately NOT descended, each with the reason it publishes no wire
# location.
# Why these can stay PROSE while `$ref` needed asserted behaviour: an accidental descent into
# propertyNames / not / if would invent a location that does not exist, and a wrong location fails
# LOUDLY -- the exact-set assertion names it immediately. `$ref` failed the other way: skipping its
# siblings reported one location too FEW, silently, which is indistinguishable from correctness. A
# judgement whose failure mode is silent needs an assertion; one whose failure mode is loud can be
# argued in a comment.
NOT_DESCENDED = {
    "propertyNames": "constrains key NAMES, which are strings; no object is published there",
    "not": "describes what the value is NOT; nothing is published at that position",
    "if": "a condition that is tested, never itself published (`then`/`else` ARE descended)",
    "$defs": "a REUSE container; its members are published only where something $refs them, and "
             "that path is covered at the reference site",
    "definitions": "the pre-2019 spelling of $defs, still declared by the 2020-12 compatibility "
                   "layer; a reuse container for the same reason",
    "contentSchema": "describes the decoded content of a STRING instance, not an object published "
                     "at this position (annotation-only at 2020-12)",
}

# Every keyword in JSON Schema 2020-12 whose value contains subschema(s), READ from
# contracts/json-schema-2020-12-keywords.json, which tools/derive-json-schema-keywords.py DERIVES
# from the published vocabulary meta-schemas.
#
# It is read rather than typed because the hand-written version was CIRCULAR: it existed to prove no
# keyword arrives unclassified, and compared the classification against another list maintained by
# the same hand -- so a keyword could be missing from both and the equality still passed. It was:
# `$defs` and `contentSchema` were absent and `$ref` was wrongly present ($ref takes a URI STRING,
# not a subschema, which is why reference handling is a separate axis with its own assertions).
#
# BOUNDED CLAIM: the derivation's dependency is a dev-machine package, so this is not re-derived per
# CI run. Guaranteed: mechanically derived from the normative meta-schemas, one command to redo. Not
# guaranteed: freshness against a future dialect.
_KEYWORD_ARTIFACT = (pathlib.Path(__file__).resolve().parents[3]
                     / "contracts" / "json-schema-2020-12-keywords.json")
SUBSCHEMA_BEARING_KEYWORDS = frozenset(
    json.loads(_KEYWORD_ARTIFACT.read_text(encoding="utf-8"))["subschemaBearing"])


def _nonempty(value):
    return bool(value) if isinstance(value, dict | list) else value is not None


def _discloses_key_information(node, root: dict) -> bool:
    """Does this node put ANY information about the object's KEYS into the published document?

    This asks DISCLOSURE, not validation, and the distinction is the whole design. Four consecutive
    review rounds killed a keyword classifier that tried to decide "does keyword K with value V
    restrict the instance" by table lookup. That is re-implementing a JSON Schema validator badly --
    the spec has ~40 keywords with interactions a table cannot hold (``contains`` + ``minContains:
    0``, a lone ``if`` with no branch, ``maxContains`` without ``contains``, ``not: false``) -- and
    each round found more of them.

    But the ratchet never needed validation semantics. It exists because a response that publishes
    no key information is invisible to the breaking-diff gate and generates an untyped client. That
    is a STRUCTURAL question about the document with a short closed answer: only the keywords below
    put key information on the wire. Every numeric / string / array facet is irrelevant by
    construction rather than by classification, and so are ``format``, ``discriminator`` and
    ``nullable``. All ten counterexamples that killed the previous cut fall out of this definition
    without being listed.

    The default is NON-disclosing, so an unrecognised keyword can never close a schema.

    DELIBERATE divergence from a reviewer ruling, stated so it is seen: ``minProperties`` /
    ``maxProperties`` do NOT disclose. They bound how MANY keys exist, never WHICH -- a consumer
    still cannot type the response and the diff gate still cannot see a rename. They are genuinely
    constraining for VALIDATION, which is exactly why the reframing matters.
    """
    if not isinstance(node, dict):
        return False        # a missing branch, or a boolean schema: publishes no key names either
    if any(k in node for k in FOLLOWED_REFERENCE_FORMS):
        return True                                     # delegates disclosure to the target
    for keyword in ("properties", "patternProperties", "required", "dependentSchemas",
                    "dependentRequired", "allOf", "anyOf", "oneOf", "enum"):
        if _nonempty(node.get(keyword)):
            return True
    if "const" in node:
        return True
    for keyword in ("additionalProperties", "unevaluatedProperties"):
        if keyword in node and _value_schema_says_something(node[keyword], root):
            return True
    if "propertyNames" in node and _narrows_names(node["propertyNames"]):
        return True
    # RECURSE, do not merely detect PRESENCE. These three carried the old validation model:
    # `not: {"type":"string"}` and `if`/`then` over scalar facets restrict the instance but publish
    # no key names, so a client is still untyped and the diff gate still blind. Ask the SUBSCHEMA
    # the same disclosure question, defaulting to non-disclosing when it answers no.
    if "not" in node and _discloses_key_information(node["not"], root):
        return True
    if "if" in node and ("then" in node or "else" in node):      # a lone `if` has no effect
        return (_discloses_key_information(node.get("then"), root)
                or _discloses_key_information(node.get("else"), root))
    return False


def _value_schema_says_something(schema, root: dict, seen=()) -> bool:
    """Does an additionalProperties / unevaluatedProperties subschema pin the VALUE shape?

    ``true`` and ``{}`` say nothing; ``false`` says "no further keys", which is a closed object and
    very much something.

    A ``$ref`` is RESOLVED rather than counted as disclosure on sight -- the same
    unresolved-reference flaw ``_narrows_names`` carried, swept from this sibling in the same pass.
    ``additionalProperties: {"$ref": X}`` discloses iff X does, so a reference to the boolean
    ``true`` schema is correctly nothing while ``Map[str, SomeRecord]`` stays disclosed.
    """
    if schema is True:
        return False
    if schema is False:
        return True
    if not isinstance(schema, dict) or not schema:
        return False
    ref = schema.get("$ref")
    if ref is not None:
        target = _resolve_local_schema(root, ref)
        if target is None or ref in seen:
            return False                    # unresolvable, external, or a reference cycle
        return _value_schema_says_something(target, root, seen + (ref,))
    return "type" in schema or _discloses_key_information(schema, root)


def _narrows_names(schema) -> bool:
    """Does a propertyNames subschema narrow WHICH names are legal?

    ONLY directly provable narrowing counts. This helper is where the disclosure reframe leaked: it
    replaced the global validation-semantics table and then rebuilt a small one locally, and six of
    its nine members were wrong in the false-CLOSED direction -- ``pattern`` (universal patterns
    like ``.*`` narrow nothing), ``$ref`` (unresolved; a reference to ``true`` narrows nothing),
    ``anyOf`` and ``oneOf`` (DISJUNCTIONS -- one permissive branch such as
    ``[true, {"pattern": "^x"}]`` accepts every name), ``not`` (``not: false`` is ``true``), and the
    conditional (``if: false`` makes a narrowing ``then`` unreachable). Each needed an evaluation
    this helper could not do, so each was a guess.

    What survives is unconditional regardless of value, which is what makes it not a classifier:
    ``false`` permits no name at all, ``const`` permits exactly one (or none, for a non-string
    constant), a non-empty ``enum`` permits a finite set, and ``allOf`` is a CONJUNCTION so any
    provably-narrowing branch narrows the whole. Everything else defaults to non-narrowing -- the
    fails-safe direction, since the cost is a loud false OPEN.
    """
    if schema is False:
        return True                                     # no legal names at all
    if not isinstance(schema, dict):
        return False                                    # `true`, and anything non-schema
    if "const" in schema or _nonempty(schema.get("enum")):
        return True
    return any(_narrows_names(b) for b in (schema.get("allOf") or []))

def _is_open(node, root: dict = {}) -> bool:
    """True when the schema publishes nothing about the keys of the object it describes.

    The empty schema ``{}``, the boolean ``true`` schema, annotation-only such as ``{"title":
    "x"}``, ``type: object`` with nothing enumerated, or a permissive ``additionalProperties``.
    """
    if node is True:
        return True                                     # the boolean TRUE schema admits anything
    if not isinstance(node, dict):
        return False                                    # includes boolean FALSE: admits nothing
    declared = node.get("type")
    types = declared if isinstance(declared, list) else ([declared] if declared else [])
    if types and "object" not in types:
        return False
    return not _discloses_key_information(node, root)



def _resolve_pointer(root, pointer: str):
    """Resolve a local JSON pointer (``/components/schemas/X``, ``/$defs/X``) or return None."""
    current = root
    for raw in pointer.split("/"):
        if not raw:
            continue
        token = raw.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or token not in current:
            return None
        current = current[token]
    return current



def _resolve_local_schema(root, reference: str):
    """THE ONE RESOLVER. Every local reference in this file resolves here.

    There used to be TWO. ``_value_schema_says_something`` carried its own, which accepted only
    ``#/components/schemas/...``, so a map value pointing at ``#/$defs/X`` read as "says nothing",
    the parent was reported open, and the walker returned before descending -- and once that wrong
    parent location was frozen, adding an open child inside the target produced no new location.
    Reference-site absorption, through a fourth door. Making the second resolver smarter would have
    left the divergence alive; there is now only one, and ``#/components/schemas/X`` is simply a
    ``#/`` pointer like any other.
    """
    return _resolve_pointer(root, reference[1:]) if reference.startswith("#/") else None


def _walk(root: dict, schemas: dict, node: Any, where: str, pointer: str,
          found: set, refs: set) -> None:
    if node is None:
        return
    if not isinstance(node, dict):
        # PREDICATE-CORRECT IS NOT WALKER-CORRECT. _is_open already judged the boolean `true`
        # schema correctly and its unit case passed, while this branch dropped it before
        # publication ever saw it. Evaluate here rather than bailing on "not a dict".
        if _is_open(node, root):
            found.add(where + pointer)
        return
    reference_keyword = next((k for k in FOLLOWED_REFERENCE_FORMS if k in node), None)
    ref = node.get(reference_keyword) if reference_keyword else None
    if ref is not None:
        # A reference to a component that IS an open object makes THIS SITE the opacity, not the
        # component: freezing the name once would authorise unlimited further uses of it.
        if reference_keyword != "$ref":
            # $dynamicRef / $recursiveRef resolve against a RUNTIME dynamic scope, so a captured
            # document cannot say what they point at. Previously NEITHER was read at all -- both
            # were named only in a comment inside the `$ref` branch. A BARE one still read open
            # through the unknown-keyword default, which is what made the gap invisible: add any
            # disclosing sibling and the parent closes, the walker descends, and the reference
            # disappears with no report. "It happens to read open" is not "it is read".
            found.add(f"{where}{pointer} -> UNRESOLVABLE {reference_keyword} {ref}")
        elif ref.startswith(COMPONENT_PREFIX):
            name = ref[len(COMPONENT_PREFIX) :]
            component = _resolve_local_schema(root, ref)
            if component is None:
                # A DANGLING reference publishes a shape nothing can verify: report it.
                found.add(f"{where}{pointer} -> {ref}")
            elif _is_open(component, root):
                found.add(f"{where}{pointer} -> {name}")
            else:
                refs.add(name)
        elif ref.startswith("#/"):
            # Any other LOCAL pointer (#/$defs/X and the rest) is resolved rather than ignored.
            resolved = _resolve_local_schema(root, ref)
            if resolved is None or _is_open(resolved, root):
                found.add(f"{where}{pointer} -> {ref}")
            else:
                # RESOLVE THEN KEEP WALKING. Classifying the target's ROOT and stopping meant a
                # TYPED `$defs` record containing an open field reported nothing -- the third place
                # "resolve the reference" shipped without "and then traverse it" (after component
                # siblings and the dynamic forms). Local pointers ride the SAME closure the
                # component refs use, so its seen-set gives cycle protection for free.
                refs.add(ref)
        else:
            # External URIs, $dynamicRef and $recursiveRef cannot be resolved from the captured
            # document. FAIL LOUDLY rather than treating an unverifiable target as safe.
            found.add(f"{where}{pointer} -> UNRESOLVABLE {ref}")
        # DO NOT RETURN. At 2020-12 `$ref` is an applicator ALONGSIDE its siblings, not a
        # replacement for them (that was draft-07). Returning here meant a frozen reference site
        # absorbed any opaque sibling for free -- the reference-site allowance hole this test
        # closed once already, re-entering through a different door.
    elif _is_open(node, root):
        found.add(where + pointer)
        return
    # DESCENT IS DATA, NOT CODE -- iterate the table rather than restating it, so a keyword
    # declared descended cannot be missed by a branch nobody wrote.
    for keyword, (kind, template) in DESCENT.items():
        if keyword not in node:
            continue
        value = node[keyword]
        if kind == "map" and isinstance(value, dict):
            for key, child in value.items():
                _walk(root, schemas, child, where, pointer + template.format(k=key), found, refs)
        elif kind == "list" and isinstance(value, list):
            for index, child in enumerate(value):
                _walk(root, schemas, child, where, pointer + template.format(i=index), found, refs)
        elif kind == "schema":
            _walk(root, schemas, value, where, pointer + template, found, refs)


def _open_objects() -> set:
    """Every open object the LIVE app publishes — generated from code, never a dump."""
    return _scan(app.openapi())


def _scan(spec: dict) -> set:
    """Every unconstrained open object reachable from a RESPONSE, as location strings.

    Two passes: response schemas inline, then the transitive closure of the components those
    responses ``$ref``. Request-only components are never seeded, so they never appear.
    """
    schemas = spec.get("components", {}).get("schemas", {})
    found: set = set()
    roots: set = set()

    for path, item in spec.get("paths", {}).items():
        for method, operation in item.items():
            if not isinstance(operation, dict) or "responses" not in operation:
                continue
            for code, response in operation["responses"].items():
                where = f"{method.upper()} {path} {code}"
                for media in (response.get("content") or {}).values():
                    if media.get("schema") is not None:
                        _walk(spec, schemas, media["schema"], where, "", found, roots)

    seen: set = set()
    queue = list(roots)
    while queue:
        name = queue.pop()
        if name in seen:
            continue
        seen.add(name)
        refs: set = set()
        # A closure entry is either a component NAME (walked as `#Name`, the frozen location form)
        # or a raw local POINTER such as `#/$defs/X`, which is its own location.
        is_pointer = name.startswith("#/")
        target = _resolve_local_schema(spec, name) if is_pointer else schemas.get(name)
        _walk(spec, schemas, target, name if is_pointer else f"#{name}", "", found, refs)
        queue.extend(refs - seen)
    return found


def test_the_open_object_predicate_recognises_every_unconstrained_shape():
    """Regression cases for the predicate itself — the mirror of the Java half's list.

    The annotation-only group is the third distinct way a schema turned out to be open, after
    component-name aliasing and the source regex's inability to see a field inside a typed record.
    """
    open_shapes = [
        {},                                                  # the empty schema — any JSON at all
        {"title": "Response"},                               # annotation-only: pydantic's `Any`
        {"description": "whatever the handler felt like"},
        {"title": "X", "description": "Y", "default": None},
        {"type": "object"},
        {"type": ["object", "null"]},
        {"type": "object", "additionalProperties": {}},
        {"type": "object", "additionalProperties": True},
        {"additionalProperties": True},
        {"nullable": True},                                  # 3.0 nullability alone constrains none
        {"unknownFutureKeyword": 1},                         # fails-safe: unrecognised => open
        # PRESENT BUT INERT. Every one of these read CONSTRAINED under the presence-based
        # predicate. `format` is here on the 3.1 reading: a format does not imply a type, and
        # 2020-12 makes it an annotation by default, so this still permits any object.
        {"type": "object", "properties": {}},
        {"properties": {}},
        {"required": []},
        {"minProperties": 0},
        {"patternProperties": {}},
        {"format": "uuid"},
        {"allOf": []},
        {"enum": []},
        {"uniqueItems": False},
        {"items": True},
        # THE TEN that the ALWAYS_CONSTRAINS list closed by fiat. Each discloses nothing about the
        # object's keys, and each now falls out of the definition rather than being listed:
        # `not: false` is the identity; `propertyNames: true` names no name; a lone `if` has no
        # branch to apply and lone `then`/`else` are ignored without it; `discriminator` is OAS
        # dispatch metadata; the empty regex matches every string; `maxContains` does nothing
        # without `contains`; and an adjacent `minContains: 0` makes `contains` always pass.
        {"not": False},
        {"propertyNames": True},
        {"propertyNames": {}},
        {"if": {"type": "string"}},
        {"then": {"type": "string"}},
        {"else": {"type": "string"}},
        {"discriminator": {"propertyName": "kind"}},
        {"pattern": ""},
        {"maxContains": 2},
        {"contains": {"type": "string"}, "minContains": 0},
        # ROUND-7: these still answered VALIDATION semantics. Each restricts the instance while
        # publishing no key names, so a client stays untyped and the diff gate stays blind -- and
        # object keys are already strings, so a string `propertyNames` narrows nothing at all.
        {"not": {"type": "string"}},
        {"if": {"type": "string"}, "then": {"maxLength": 5}},
        {"if": {"type": "string"}, "else": {"maximum": 10}},
        {"propertyNames": {"type": "string"}},
        # ROUND-9: _narrows_names had rebuilt the very classifier the disclosure reframe removed,
        # and six of its nine members were wrong in the false-CLOSED direction. Each of these
        # needed an evaluation the helper could not do, so each was a guess: a regex may be
        # universal; an unresolved $ref may point at `true`; anyOf/oneOf are DISJUNCTIONS so one
        # permissive branch admits every name; `not: false` is `true`; and `if: false` makes a
        # narrowing `then` unreachable.
        {"propertyNames": {"pattern": ""}},
        {"propertyNames": {"pattern": ".*"}},
        {"propertyNames": {"pattern": "^x"}},
        {"propertyNames": {"$ref": "#/components/schemas/AlwaysTrue"}},
        {"propertyNames": {"anyOf": [True, {"pattern": "^x"}]}},
        {"propertyNames": {"oneOf": [False, True]}},
        {"propertyNames": {"not": False}},
        {"propertyNames": {"if": False, "then": {"pattern": "^x"}}},
        # Facets bound the SIZE of the object, never which keys it has, so a consumer still cannot
        # type the response — see the divergence note on _discloses_key_information.
        {"type": "object", "minProperties": 1},
        {"type": "object", "maxProperties": 3},
        {"maximum": 5},
        {"minLength": 3},
        True,                                            # the boolean TRUE schema
    ]
    for shape in open_shapes:
        assert _is_open(shape), f"expected OPEN but the predicate said constrained: {shape}"

    constrained_shapes = [
        {"type": "string"},
        {"type": ["string", "null"]},
        # the ARRAY is not open; its item is, and the walk recurses to report it at the `[]` pointer
        {"type": "array", "items": {}},
        {"type": "object", "properties": {"a": {"type": "string"}}},
        {"type": "object", "additionalProperties": {"type": "string"}},
        {"$ref": "#/components/schemas/Foo"},
        {"allOf": [{"type": "object"}]},
        {"anyOf": [{"type": "string"}]},
        {"enum": ["a", "b"]},
        {"const": 3},
        {"type": "object", "required": ["a"]},
        {"patternProperties": {"^x": {"type": "string"}}},
        # PROVABLE narrowing, unconditional whatever the value.
        {"propertyNames": False},
        {"propertyNames": {"const": "a"}},
        {"propertyNames": {"enum": ["a", "b"]}},
        {"propertyNames": {"allOf": [{"enum": ["a"]}]}},
        {"propertyNames": {"allOf": [True, {"const": "a"}]}},
        {"type": "object", "additionalProperties": False},      # a CLOSED object
        {"not": {"required": ["a"]}},
        {"if": {"required": ["a"]}, "then": {"required": ["b"]}},
        False,                                                  # the boolean FALSE schema
    ]
    for shape in constrained_shapes:
        assert not _is_open(shape), f"expected CONSTRAINED but the predicate said open: {shape}"


def test_an_unknown_keyword_is_never_silenced_by_an_inert_known_one():
    """The headline property, asserted rather than claimed.

    This is the test the previous round needed and did not have. "An unanticipated keyword reads as
    OPEN and fails loudly" was written into both headers as the justification for inverting the
    lists, and it was false in all 18 of these combinations, because the check asked whether a
    constraining keyword was PRESENT rather than whether it CONSTRAINED. Pair an unknown keyword
    with ``"properties": {}`` and the schema went quiet.
    """
    inert = {
        "properties": {}, "patternProperties": {}, "dependentSchemas": {},
        "dependentRequired": {}, "required": [], "allOf": [], "anyOf": [], "oneOf": [],
        "enum": [], "prefixItems": [], "minProperties": 0, "minLength": 0, "minItems": 0,
        "minContains": 0, "uniqueItems": False, "additionalProperties": True,
        "unevaluatedProperties": True, "unevaluatedItems": True, "items": True,
    }
    for keyword, noop in inert.items():
        shape = {keyword: noop, "unknownFutureKeyword": 1}
        assert _is_open(shape), (
            f"an unknown keyword was silenced by an inert `{keyword}` — the fails-safe property "
            f"does not hold for: {shape}"
        )


def test_no_response_publishes_an_unfrozen_open_object():
    found = _open_objects()

    unfrozen = sorted(found - FROZEN_OPEN_OBJECTS)
    assert not unfrozen, (
        f"margin-service publishes {len(unfrozen)} unconstrained open object(s) with no frozen "
        f"exemption: {unfrozen}. A response typed `object` with no properties promises the "
        "consumer NOTHING and is invisible to the breaking-diff gate — declare a pydantic model. "
        "If the shape is genuinely free-form, add the location to FROZEN_OPEN_OBJECTS with the "
        "reason."
    )

    stale = sorted(FROZEN_OPEN_OBJECTS - found)
    assert not stale, (
        f"margin-service has {len(stale)} frozen exemption(s) that no longer exist in "
        f"app.openapi(): {stale}. Progress — delete those lines from FROZEN_OPEN_OBJECTS. Leaving "
        "them would silently re-authorise the same location later."
    )


def test_the_walker_reports_open_objects_at_every_location_form():
    """End-to-end through the WALKER, over a synthetic spec.

    Predicate-correct and walker-correct are separate properties, and this check has already
    shipped a case where only the first held: ``_is_open`` judged the boolean ``true`` schema
    correctly from the day it was added and its unit case passed, but ``_walk`` bailed on "not a
    dict" before publication was ever consulted -- so a ``true`` at a response root, under
    ``items``, as a property or in a composition branch was reported nowhere. A predicate case
    cannot catch that.
    """
    spec = {
        "paths": {
            "/direct": {"get": {"responses": {"200": {"content": {"*/*": {"schema": True}}}}}},
            "/array": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"type": "array", "items": True}}}}}}},
            "/property": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"type": "object", "properties": {"payload": True}}}}}}}},
            "/composed": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"allOf": [True]}}}}}}},
            "/notstring": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"not": {"type": "string"}}}}}}}},
            "/cond": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"if": {"type": "string"}, "then": {"maxLength": 5}}}}}}}},
            "/names": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"propertyNames": {"type": "string"}}}}}}}},
            "/refopen": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"$ref": "#/components/schemas/NotString"}}}}}}},
            "/typed": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"$ref": "#/components/schemas/Typed"}}}}}}},
            "/closed": {"get": {"responses": {"200": {"content": {"*/*": {
                "schema": {"type": "object", "additionalProperties": False}}}}}}},
            "/never": {"get": {"responses": {"200": {"content": {"*/*": {"schema": False}}}}}},
            "/maptrue": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "type": "object",
                "additionalProperties": {"$ref": "#/components/schemas/AlwaysTrue"}}}}}}}},
            "/maptyped": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "type": "object",
                "additionalProperties": {"$ref": "#/components/schemas/Typed"}}}}}}}},
        },
        "components": {"schemas": {
            "NotString": {"not": {"type": "string"}},
            "AlwaysTrue": True,
            "Typed": {"type": "object", "properties": {"a": {"type": "string"}}},
        }},
    }
    assert _scan(spec) == {
        "GET /direct 200",                  # a bare `true` as the response schema
        "GET /array 200[]",                 # items: true
        "GET /property 200.payload",        # a `true`-valued property
        "GET /composed 200/allOf/0",        # a `true` composition branch
        "GET /notstring 200",               # `not` over a scalar type discloses no keys
        "GET /cond 200",                    # conditional over scalar facets discloses no keys
        "GET /names 200",                   # object keys are already strings
        "GET /refopen 200 -> NotString",    # and the same through a $ref
        # the sibling sweep: a Map whose VALUE schema $refs the boolean `true` discloses nothing.
        # /maptyped is absent, which proves the $ref is RESOLVED rather than blanket-ignored.
        "GET /maptrue 200",
    }


def _spec_with(schema: dict) -> dict:
    return {"paths": {"/p": {"get": {"responses": {"200": {"content": {"*/*": {
        "schema": schema}}}}}}}, "components": {"schemas": {}}}


def test_the_walker_descends_every_schema_bearing_keyword():
    """THE STRUCTURAL AGREEMENT ASSERTION -- the fix for a CLASS, not an instance.

    Three separate rounds found the same defect: the predicate consulted a subschema the walker
    never descended, so the schema was classified correctly and then reported nowhere. Round 7 was
    the boolean ``true`` schema, round 8 the ``$ref`` location, round 9 ``unevaluatedProperties`` --
    which the predicate had handled since the round it was added while every walker descended only
    ``additionalProperties``. Two point fixes each produced the next instance, so this asserts the
    property directly: plant a KNOWN-OPEN schema (the boolean ``true``, which also exercises the
    round-7 path) under every keyword declared descendable and require the walker to report it at
    exactly the declared pointer.
    """
    for keyword, (kind, template) in DESCENT.items():
        # `required` forces the parent CLOSED, so the walker must descend to find anything.
        schema = {"required": ["x"]}
        if kind == "map":
            schema[keyword] = {"k": True}
            expected = template.format(k="k")
        elif kind == "list":
            schema[keyword] = [True]
            expected = template.format(i=0)
        else:
            schema[keyword] = True
            if keyword in ("then", "else"):
                schema["if"] = {"type": "string"}        # inert without its condition
            expected = template
        assert _scan(_spec_with(schema)) == {f"GET /p 200{expected}"}, (
            f"the predicate classifies `{keyword}` but the walker never descends it, so an open "
            f"schema under it is reported NOWHERE -- the exact defect that shipped three times. "
            f"Expected [GET /p 200{expected}], got {sorted(_scan(_spec_with(schema)))}"
        )


def test_every_subschema_keyword_is_classified():
    """The classification must be EXHAUSTIVE, so a subschema-bearing keyword cannot arrive
    unnoticed. Without this, the agreement test above only proves the keywords somebody remembered
    to list."""
    classified = set(DESCENT) | set(NOT_DESCENDED)
    assert classified == set(SUBSCHEMA_BEARING_KEYWORDS), (
        "a subschema-bearing keyword is neither descended nor excused: "
        f"{sorted(classified ^ set(SUBSCHEMA_BEARING_KEYWORDS))}. Add it to DESCENT with its "
        "pointer, or to NOT_DESCENDED with the reason it publishes no wire location."
    )
    assert not (set(DESCENT) & set(NOT_DESCENDED))


def test_a_reference_does_not_absorb_its_opaque_siblings():
    """``$ref`` does NOT replace its siblings at 2020-12 -- it applies alongside them.

    Skipping them meant a frozen reference site absorbed any opaque sibling for free, which is the
    reference-site allowance hole this ratchet closed once already, re-entering by a different door.
    """
    spec = {
        "paths": {
            "/typed-target": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "$ref": "#/components/schemas/Typed", "properties": {"payload": True}}}}}}}},
            "/open-target": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "$ref": "#/components/schemas/Open", "properties": {"payload": True}}}}}}}},
            "/defs-ref": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "$ref": "#/$defs/Open"}}}}}}},
            "/defs-typed": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "$ref": "#/$defs/TypedWithOpenChild"}}}}}}},
            "/ap-defs": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "type": "object",
                "additionalProperties": {"$ref": "#/$defs/TypedWithOpenChild"}}}}}}}},
            "/unev-defs": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "type": "object",
                "unevaluatedProperties": {"$ref": "#/$defs/TypedWithOpenChild"}}}}}}}},
            "/external": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "$ref": "https://example.test/x.json#/Foo"}}}}}}},
            "/dangling": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
                "$ref": "#/components/schemas/Missing"}}}}}}},
        },
        "$defs": {
            "Open": True,
            "TypedWithOpenChild": {"type": "object", "properties": {"payload": True}},
        },
        "components": {"schemas": {
            "Typed": {"type": "object", "properties": {"a": {"type": "string"}}},
            "Open": True,
        }},
    }
    assert _scan(spec) == {
        "GET /typed-target 200.payload",        # a TYPED target no longer hides an opaque sibling
        "GET /open-target 200 -> Open",         # an OPEN target reports the site AND the sibling
        "GET /open-target 200.payload",
        "GET /defs-ref 200 -> #/$defs/Open",    # a local pointer outside components is resolved
        # and a TYPED $defs target is TRAVERSED, not just classified at its root.
        # BOTH map-value forms must reach it -- additionalProperties and unevaluatedProperties each
        # used to consult a second, component-only resolver that answered "says nothing" for a
        # $defs pointer, so the parent was reported open and the walker returned before descending.
        "#/$defs/TypedWithOpenChild.payload",
        "GET /external 200 -> UNRESOLVABLE https://example.test/x.json#/Foo",
        "GET /dangling 200 -> #/components/schemas/Missing",
    }


def test_every_reference_candidate_is_classified():
    """Every reference form the dialect declares is either FOLLOWED or explicitly excused."""
    classified = set(FOLLOWED_REFERENCE_FORMS) | set(NON_FOLLOWED_REFERENCE_FORMS)
    derived = set(json.loads(_KEYWORD_ARTIFACT.read_text(encoding="utf-8"))["referenceCandidates"])
    assert classified == derived, (
        "a reference form the dialect declares is neither followed nor excused: "
        f"{sorted(classified ^ derived)}"
    )


def test_every_reference_form_survives_a_closing_sibling():
    """Each reference form paired with a CLOSING sibling.

    The sibling is the whole point: a bare ``$dynamicRef`` read open by accident through the
    unknown-keyword default, so only a disclosing sibling -- which closes the parent and sends the
    walker into descent -- exposes whether the reference is read at all.
    """
    for form in FOLLOWED_REFERENCE_FORMS:
        spec = {"paths": {"/p": {"get": {"responses": {"200": {"content": {"*/*": {"schema": {
            form: "#/components/schemas/Missing", "required": ["x"]}}}}}}}},
            "components": {"schemas": {}}}
        assert _scan(spec), (
            f"`{form}` beside a closing sibling was reported NOWHERE -- the reference form is not "
            "read at all, which is what a comment claiming a loud failure hid."
        )


def test_the_live_schema_uses_the_dialect_the_inventory_was_derived_for():
    """MAJOR 3: the guard belongs on the artifact that CAN drift.

    The Java half asserts the dialect of the COMMITTED specs -- but this suite scans the LIVE
    ``app.openapi()``, and nothing asserted its dialect. A FastAPI/Pydantic upgrade can move the
    live schema's dialect while the committed dump stays 3.1, leaving the only ratchet that runs
    against the live document using an inventory derived for the old one, silently. A guard on the
    wrong artifact is the same shape as a predicate the walker never calls.
    """
    spec = app.openapi()
    artifact = json.loads(_KEYWORD_ARTIFACT.read_text(encoding="utf-8"))
    assert spec.get("openapi", "").startswith("3.1"), (
        f"live schema declares OpenAPI {spec.get('openapi')!r}, but the keyword inventory's "
        f"omitted-dialect default ({artifact['omittedJsonSchemaDialectMeans']}) is the OpenAPI 3.1 "
        "rule. Re-derive with tools/derive-json-schema-keywords.py before moving off 3.1."
    )
    # An omitted `jsonSchemaDialect` does NOT mean raw 2020-12 -- OAS 3.1.1 §4.8.24.1 says it means
    # the OAS BASE dialect, and FastAPI emits no such field, so asserting equality with the raw
    # 2020-12 URI asserted something the document never said. Take the default FROM the artifact
    # and require the EFFECTIVE dialect to be one the inventory covers.
    effective = spec.get("jsonSchemaDialect", artifact["omittedJsonSchemaDialectMeans"])
    assert effective in artifact["applicableDialects"], (
        f"live schema effectively uses dialect {effective!r}, which the keyword inventory does not "
        f"cover ({artifact['applicableDialects']})."
    )
