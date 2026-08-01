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

# Every unconstrained open object the optimizer is allowed to publish, named one by one. The
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
    # would mean patching a third-party mount. Same exemption as optimizer-service carries.
    "GET /metrics 200",
}


def _says_nothing(value):
    """A schema that says nothing at all: boolean true, or the empty schema."""
    return value is True or (isinstance(value, dict) and not value)


def _nonempty(value):
    return bool(value) if isinstance(value, (dict, list)) else value is not None


def _discloses_key_information(node: dict) -> bool:
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
    if "$ref" in node:
        return True                                     # delegates disclosure to the target
    for keyword in ("properties", "patternProperties", "required", "dependentSchemas",
                    "dependentRequired", "allOf", "anyOf", "oneOf", "enum"):
        if _nonempty(node.get(keyword)):
            return True
    if "const" in node:
        return True
    for keyword in ("additionalProperties", "unevaluatedProperties", "propertyNames"):
        value = node.get(keyword)
        if value is not None and not _says_nothing(value):
            return True                                 # pins the value or key-name shape
    if "not" in node and node["not"] is not False:
        return True                                     # `not: false` is the identity
    return "if" in node and ("then" in node or "else" in node)   # a lone `if` has no effect


def _is_open(node) -> bool:
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
    return not _discloses_key_information(node)


def _walk(schemas: dict, node: Any, where: str, pointer: str, found: set, refs: set) -> None:
    if not isinstance(node, dict):
        return
    ref = node.get("$ref")
    if ref is not None:
        if not ref.startswith(COMPONENT_PREFIX):
            return
        name = ref[len(COMPONENT_PREFIX) :]
        # A reference to a component that IS an open object makes THIS SITE the opacity, not the
        # component: freezing the name once would authorise unlimited further uses of it.
        if _is_open(schemas.get(name)):
            found.add(f"{where}{pointer} -> {name}")
        else:
            refs.add(name)
        return
    if _is_open(node):
        found.add(where + pointer)
        return
    for key, value in (node.get("properties") or {}).items():
        _walk(schemas, value, where, f"{pointer}.{key}", found, refs)
    if isinstance(node.get("items"), dict):
        _walk(schemas, node["items"], where, f"{pointer}[]", found, refs)
    additional = node.get("additionalProperties")
    if isinstance(additional, dict) and additional:
        _walk(schemas, additional, where, pointer + "{}", found, refs)
    for keyword in ("allOf", "oneOf", "anyOf"):
        for index, value in enumerate(node.get(keyword) or []):
            _walk(schemas, value, where, f"{pointer}/{keyword}/{index}", found, refs)


def _open_objects() -> set:
    """Every unconstrained open object reachable from a RESPONSE, as location strings.

    Two passes: response schemas inline, then the transitive closure of the components those
    responses ``$ref``. Request-only components are never seeded, so they never appear.
    """
    spec = app.openapi()
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
        {"propertyNames": {"pattern": "^x"}},
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
