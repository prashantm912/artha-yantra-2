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


def _nonempty(value):
    return bool(value) if isinstance(value, (dict, list)) else value is not None


def _discloses_key_information(node, schemas: dict) -> bool:
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
    if "$ref" in node:
        return True                                     # delegates disclosure to the target
    for keyword in ("properties", "patternProperties", "required", "dependentSchemas",
                    "dependentRequired", "allOf", "anyOf", "oneOf", "enum"):
        if _nonempty(node.get(keyword)):
            return True
    if "const" in node:
        return True
    for keyword in ("additionalProperties", "unevaluatedProperties"):
        if keyword in node and _value_schema_says_something(node[keyword], schemas):
            return True
    if "propertyNames" in node and _narrows_names(node["propertyNames"]):
        return True
    # RECURSE, do not merely detect PRESENCE. These three carried the old validation model:
    # `not: {"type":"string"}` and `if`/`then` over scalar facets restrict the instance but publish
    # no key names, so a client is still untyped and the diff gate still blind. Ask the SUBSCHEMA
    # the same disclosure question, defaulting to non-disclosing when it answers no.
    if "not" in node and _discloses_key_information(node["not"], schemas):
        return True
    if "if" in node and ("then" in node or "else" in node):      # a lone `if` has no effect
        return (_discloses_key_information(node.get("then"), schemas)
                or _discloses_key_information(node.get("else"), schemas))
    return False


def _value_schema_says_something(schema, schemas: dict, seen=()) -> bool:
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
        if not ref.startswith(COMPONENT_PREFIX):
            return False
        name = ref[len(COMPONENT_PREFIX):]
        if name in seen or name not in schemas:
            return False                                # unresolvable, or a reference cycle
        return _value_schema_says_something(schemas[name], schemas, seen + (name,))
    return "type" in schema or _discloses_key_information(schema, schemas)


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

def _is_open(node, schemas: dict = {}) -> bool:
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
    return not _discloses_key_information(node, schemas)


def _walk(schemas: dict, node: Any, where: str, pointer: str, found: set, refs: set) -> None:
    if node is None:
        return
    if not isinstance(node, dict):
        # PREDICATE-CORRECT IS NOT WALKER-CORRECT. _is_open already judged the boolean `true`
        # schema correctly and its unit case passed, while this branch dropped it before
        # publication ever saw it. Evaluate here rather than bailing on "not a dict".
        if _is_open(node, schemas):
            found.add(where + pointer)
        return
    ref = node.get("$ref")
    if ref is not None:
        if not ref.startswith(COMPONENT_PREFIX):
            return
        name = ref[len(COMPONENT_PREFIX) :]
        # A reference to a component that IS an open object makes THIS SITE the opacity, not the
        # component: freezing the name once would authorise unlimited further uses of it.
        if _is_open(schemas.get(name), schemas):
            found.add(f"{where}{pointer} -> {name}")
        else:
            refs.add(name)
        return
    if _is_open(node, schemas):
        found.add(where + pointer)
        return
    for key, value in (node.get("properties") or {}).items():
        _walk(schemas, value, where, f"{pointer}.{key}", found, refs)
    if "items" in node:                                  # dict OR boolean `true`
        _walk(schemas, node["items"], where, f"{pointer}[]", found, refs)
    additional = node.get("additionalProperties")
    if isinstance(additional, dict) and additional:
        _walk(schemas, additional, where, pointer + "{}", found, refs)
    for keyword in ("allOf", "oneOf", "anyOf"):
        for index, value in enumerate(node.get(keyword) or []):
            _walk(schemas, value, where, f"{pointer}/{keyword}/{index}", found, refs)


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
                        _walk(schemas, media["schema"], where, "", found, roots)

    seen: set = set()
    queue = list(roots)
    while queue:
        name = queue.pop()
        if name in seen:
            continue
        seen.add(name)
        refs: set = set()
        _walk(schemas, schemas.get(name), f"#{name}", "", found, refs)
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
