"""Fixture-pinned regression tests for openapi_relabel_30.py's downgrade passes.

Run: python3 -m pytest .github/scripts/test_openapi_relabel_30.py -q
(needs openapi-spec-validator; installed by the CI step immediately before this one runs)

Every shape here was measured against a REAL committed spec, not invented:
  - the four Java specs' bare `$ref`+null anyOf (backtest/edge-gateway/market-data/strategy-signal)
  - strategy-signal's nullable type array (`stopLoss`/`target`, @Schema(types=...))
  - optimizer-service's 134 nullable anyOf nodes (96 titled primitive, 33 titled
    object+additionalProperties, 2 bare $ref, 2 titled $ref, 1 titled empty-schema)
  - margin-service's 7 nullable anyOf nodes (6 titled primitive incl. a 3-branch genuine union
    alongside nullability, 1 bare primitive on `/health`)

This file tests the pure downgrade_* functions directly (isolated dict fixtures) plus one
end-to-end check per real non-Java spec (import main() is avoided; the CLI is invoked as a
subprocess exactly as CI does, so a fixture bug in the functions and a wiring bug in main() are
both caught).
"""

import copy
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).with_name("openapi_relabel_30.py")
REPO_ROOT = SCRIPT.parents[2]

sys.path.insert(0, str(SCRIPT.parent))
import openapi_relabel_30 as relabel  # noqa: E402


def _is_valid_30(components):
    """Real assertion, not a trace: run the actual OpenAPI-3.0 validator relabel_30.py uses,
    wrapping the given `components` fragment in the minimal skeleton the OAS 3.0 document schema
    requires (`info`, `paths`) so validation failures are about the FRAGMENT under test, not
    about a test helper that forgot the boilerplate."""
    from openapi_spec_validator import validate

    doc = {
        "openapi": "3.0.1",
        "info": {"title": "fixture", "version": "1.0.0"},
        "paths": {},
        "components": copy.deepcopy(components),
    }
    validate(doc)  # raises on invalid; a bare call is the pass condition


def _permits_null(schema):
    """SEMANTIC check, deliberately separate from `_is_valid_30`: does this schema fragment, as it
    will appear in the throwaway 3.0 diff copy, actually allow a null value? `_is_valid_30` only
    proves the document is syntactically well-formed 3.0 - it says nothing about whether a
    converted node still means what the original 3.1 node meant, which is exactly the gap a real
    review round caught (a parent-level `nullable: true` beside `anyOf` with no sibling `type` is
    valid 3.0 syntax that OAS 3.0's own rule says does NOTHING - "nullable adds null to the allowed
    type ... only if type is explicitly defined within the SAME Schema Object"). Encodes that one
    rule plus plain `anyOf` semantics (valid if it matches ANY branch) - not a general JSON-Schema
    validator, just the one property every downgrade in this file must preserve."""
    if not isinstance(schema, dict):
        return False
    if "anyOf" in schema:
        return any(_permits_null(b) for b in schema["anyOf"])
    if "type" not in schema:
        return True  # unconstrained (pydantic's Optional[Any] -> {}) - null already fits, no flag needed
    return isinstance(schema["type"], str) and schema.get("nullable") is True


def test_permits_null_catches_the_parent_level_nullable_mistake():
    """Regression pin for the exact defect a review round caught before merge: `nullable: true`
    sitting beside `anyOf` with NO sibling `type` is syntactically valid 3.0 (passes
    `_is_valid_30`) but semantically permits nothing extra. If this test ever goes green for the
    wrong reason, `_permits_null` itself is broken."""
    wrong = {"anyOf": [{"type": "number"}, {"type": "string"}], "nullable": True}
    assert not _permits_null(wrong)
    right = {"anyOf": [{"type": "number", "nullable": True}, {"type": "string"}]}
    assert _permits_null(right)


# ---- downgrade_nullable_type_arrays (pre-existing; pinned so a future edit cannot regress it) --


def test_type_array_nullable_converts():
    node = {"type": ["number", "null"], "title": "StopLoss"}
    relabel.downgrade_nullable_type_arrays(node)
    assert node == {"type": "number", "nullable": True, "title": "StopLoss"}
    _is_valid_30({"schemas": {"X": {"properties": {"f": node}, "type": "object"}}})
    assert _permits_null(node)


def test_type_array_genuine_union_left_alone():
    node = {"type": ["string", "integer"]}
    before = copy.deepcopy(node)
    relabel.downgrade_nullable_type_arrays(node)
    assert node == before  # untouched; scan()/validator refuses it downstream, not this pass


# ---- downgrade_nullable_ref_anyof (pre-existing bare form + the new title-tolerant widening) ---


def test_ref_anyof_bare_converts():
    """Every Java spec's shape: {"anyOf": [{"$ref": ...}, {"type": "null"}]}, no siblings."""
    node = {"anyOf": [{"$ref": "#/components/schemas/Foo"}, {"type": "null"}]}
    relabel.downgrade_nullable_ref_anyof(node)
    assert node == {"$ref": "#/components/schemas/Foo"}


def test_ref_anyof_titled_converts():
    """optimizer-service's requestBody-wrapper shape: title sibling, title is dropped along with
    nullability (openapi-diff is blind to both; keeping title beside a hoisted $ref would recreate
    the '$ref with sibling keys' hazard scan() exists to refuse)."""
    node = {"anyOf": [{"$ref": "#/components/schemas/ProbeRequest"}, {"type": "null"}], "title": "Body"}
    relabel.downgrade_nullable_ref_anyof(node)
    assert node == {"$ref": "#/components/schemas/ProbeRequest"}


def test_ref_anyof_with_other_sibling_refuses():
    """Only `title` is tolerated. A description sibling (or anything else) must NOT silently
    convert - fall through untouched so the validator/scan() backstop can refuse it."""
    node = {"anyOf": [{"$ref": "#/components/schemas/Foo"}, {"type": "null"}], "description": "x"}
    before = copy.deepcopy(node)
    relabel.downgrade_nullable_ref_anyof(node)
    assert node == before


def test_ref_anyof_never_descends_into_composition():
    """Regression for the review-caught hole: a nullable $ref nested inside a oneOf branch must
    not be silently rewritten (oneOf: [anyOf:[$ref,null], string] -> oneOf:[$ref,string] is a
    VALID-3.0 silent semantic change the validator could never catch)."""
    node = {"oneOf": [{"anyOf": [{"$ref": "#/components/schemas/Foo"}, {"type": "null"}]}, {"type": "string"}]}
    before = copy.deepcopy(node)
    relabel.downgrade_nullable_ref_anyof(node)
    assert node == before


# ---- downgrade_nullable_primitive_anyof (NEW) ---------------------------------------------------


def test_primitive_anyof_bare_converts():
    """margin-service's /health: additionalProperties is a bare nullable primitive anyOf, no
    title anywhere on the node itself."""
    node = {"anyOf": [{"type": "string"}, {"type": "null"}]}
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == {"type": "string", "nullable": True}
    _is_valid_30(
        {"schemas": {"X": {"type": "object", "additionalProperties": node, "title": "Response"}}}
    )
    assert _permits_null(node)


def test_primitive_anyof_titled_converts():
    """margin-service's LegIn.expiry / PositionIn.strike / optimizer's 96 titled primitive
    fields: `title` sibling preserved untouched, only `anyOf` is replaced."""
    node = {"anyOf": [{"type": "number"}, {"type": "null"}], "title": "Strike"}
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == {"title": "Strike", "type": "number", "nullable": True}
    _is_valid_30({"schemas": {"X": {"properties": {"f": node}, "type": "object"}}})
    assert _permits_null(node)


def test_object_additionalproperties_anyof_converts():
    """optimizer-service's 33 nullable map-type fields: the surviving branch is an object schema
    carrying its own `additionalProperties`, not a bare `{"type": ...}` - every one of that
    branch's own keys must be hoisted, not just `type`."""
    node = {
        "anyOf": [
            {"type": "object", "additionalProperties": {"type": "number"}},
            {"type": "null"},
        ],
        "title": "Weights",
    }
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == {
        "title": "Weights",
        "type": "object",
        "additionalProperties": {"type": "number"},
        "nullable": True,
    }
    _is_valid_30({"schemas": {"X": {"properties": {"f": node}, "type": "object"}}})
    assert _permits_null(node)


def test_empty_schema_anyof_converts():
    """optimizer-service's GateResult.value: pydantic's Optional[Any] -> anyOf: [{}, null]. The
    non-null branch is the empty (unconstrained) schema; hoisting it is a no-op besides
    `nullable: true`, and the validator must still accept the result (no `type` key at all)."""
    node = {"anyOf": [{}, {"type": "null"}], "title": "Value"}
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == {"title": "Value", "nullable": True}
    _is_valid_30({"schemas": {"X": {"properties": {"f": node}, "type": "object"}}})
    assert _permits_null(node)


def test_multi_branch_union_alongside_null_converts():
    """margin-service's SizeRequest.stop: number | pattern-string | None - a GENUINE union that
    ALSO carries nullability. 3.0 has no single-type spelling for this, and a parent-level
    `nullable: true` beside `anyOf` (no sibling `type`) is a NO-OP under OAS 3.0's own rule
    (nullable only takes effect beside an explicit `type` in the SAME schema object) - caught in
    review before this ever reached a committed spec; `_is_valid_30` alone cannot catch it since
    it only checks document SYNTAX. The correct, lossless spelling puts `nullable: true` on ONE
    RETAINED TYPED BRANCH: a null instance then matches that branch (type + nullable == "type or
    null"), so it matches the anyOf as a whole. Confirmed both syntactically (real validator) and
    SEMANTICALLY (`_permits_null`, which encodes the exact OAS 3.0 rule above)."""
    node = {
        "anyOf": [
            {"type": "number"},
            {"type": "string", "pattern": r"^\d+$"},
            {"type": "null"},
        ],
        "title": "Stop",
    }
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == {
        "title": "Stop",
        "anyOf": [
            {"type": "number", "nullable": True},
            {"type": "string", "pattern": r"^\d+$"},
        ],
    }
    _is_valid_30({"schemas": {"X": {"properties": {"f": node}, "type": "object"}}})
    assert _permits_null(node)


def test_multi_branch_with_no_typed_retained_branch_refuses():
    """If NO retained branch carries `type`, there is nowhere sound to hang `nullable` - this must
    be left COMPLETELY untouched (not guessed at) so the leftover `type: "null"` sibling falls
    through to the validator/scan() backstop and the gate fails closed rather than silently
    mangling. Synthetic (not observed in any real spec today); pins the refusal path so a future
    edit cannot quietly start guessing here."""
    node = {"anyOf": [{}, {"pattern": r"^x$"}, {"type": "null"}], "title": "Odd"}
    before = copy.deepcopy(node)
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == before


def test_non_nullable_union_left_alone():
    """margin-service's SizeRequest.capital: number | pattern-string, NO null branch at all - this
    is already valid OAS 3.0 syntax (anyOf without a null branch needs no conversion) and must be
    left completely untouched."""
    node = {
        "anyOf": [{"type": "number"}, {"type": "string", "pattern": r"^\d+$"}],
        "title": "Capital",
    }
    before = copy.deepcopy(node)
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == before
    _is_valid_30({"schemas": {"X": {"properties": {"f": node}, "type": "object"}}})
    assert not _permits_null(node)  # correctly so - the ORIGINAL never permitted null either


def test_primitive_anyof_never_touches_ref_branch():
    """A $ref mixed into the surviving branches must NEVER be absorbed by this pass - that is
    downgrade_nullable_ref_anyof's narrower job (with its own sibling-keys care). Not observed in
    any real spec today; pinned as an invariant so the two passes cannot silently overlap."""
    node = {"anyOf": [{"$ref": "#/components/schemas/Foo"}, {"type": "null"}], "title": "Thing"}
    before = copy.deepcopy(node)
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == before  # downgrade_nullable_ref_anyof (run first in main()) owns this shape


def test_primitive_anyof_with_ref_in_multi_branch_refuses():
    """A $ref alongside OTHER non-null branches (not just paired with null) must also fall
    through untouched - the same invariant for the >1-remaining-branch path."""
    node = {
        "anyOf": [
            {"$ref": "#/components/schemas/Foo"},
            {"type": "string"},
            {"type": "null"},
        ],
        "title": "Mixed",
    }
    before = copy.deepcopy(node)
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == before


def test_primitive_anyof_never_descends_into_composition():
    """Same non-descent invariant as downgrade_nullable_ref_anyof, for the same documented
    reason: recursing into a composition branch after converting the outer node can silently
    absorb a nested union the validator would otherwise catch."""
    node = {"oneOf": [{"anyOf": [{"type": "string"}, {"type": "null"}]}, {"type": "integer"}]}
    before = copy.deepcopy(node)
    relabel.downgrade_nullable_primitive_anyof(node)
    assert node == before


# ---- End-to-end: the real committed specs, via the actual CLI (matches what CI runs) -----------


def _run_relabel(src, dst):
    result = subprocess.run(
        [sys.executable, str(SCRIPT), str(src), str(dst), "3.0.1"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return result.returncode, result.stdout


def test_optimizer_service_now_relabels(tmp_path):
    src = REPO_ROOT / "contracts" / "optimizer-service.openapi.json"
    rc, out = _run_relabel(src, tmp_path / "out.json")
    assert rc == 0, out


def test_margin_service_now_relabels(tmp_path):
    src = REPO_ROOT / "contracts" / "margin-service.openapi.json"
    rc, out = _run_relabel(src, tmp_path / "out.json")
    assert rc == 0, out


def test_java_specs_still_relabel(tmp_path):
    for svc in (
        "backtest-service",
        "edge-gateway",
        "market-data-service",
        "strategy-signal-service",
    ):
        src = REPO_ROOT / "contracts" / f"{svc}.openapi.json"
        rc, out = _run_relabel(src, tmp_path / f"{svc}.json")
        assert rc == 0, "%s: %s" % (svc, out)
