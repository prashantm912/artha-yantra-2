"""API-surface contract diff-gate (register §9-1). The Java services' /v3/api-docs are
snapshot + diff-gated by ContractCaptureTest; the FastAPI services weren't (margin-service had
no committed contract at all). This gates the version-STABLE surface of the OpenAPI — each
operation's method + path, its parameter names, whether it has a request body, and its response
status codes — NOT the full pydantic-serialized schema, whose internal representation churns
across Python / fastapi / pydantic versions (a raw-spec pin false-fails in CI: it runs 3.12, dev
may run 3.14). The surface comes from the route decorators, so it is identical across versions and
still catches the drift that matters: an added / removed / renamed route or param, or a changed
response-code set. Runs in the pytest CI (no new workflow).

margin-service now ALSO commits the full OpenAPI document (mirrors optimizer-service,
task_e2d15c21's pattern): this file captures both artifacts together so they can never drift
apart from each other. margin-service's breaking-change gate NOW RUNS (openapi_relabel_30.py
gained a converter for pydantic's nullable-anyOf idiom, and the breaking-gate loop now iterates
non-Java services too - see contract_service_inventory.sh's NON_JAVA_SERVICES comment), comparing
the merge base's committed spec against THIS branch's own committed spec (no Maven-style fresh
capture exists for Python). That comparison is only honest if the committed spec actually reflects
the current code - which is exactly what `test_component_property_names_match_committed_spec`
below exists to force: it fails LOUD if code renamed/added/removed a component property without
a `CONTRACTS_CAPTURE=1` re-run, so a forgotten re-dump can never let the breaking gate silently
compare two identical stale documents. Renaming SizeResponse.target to targetPrice is caught by
NEITHER the route surface below NOR ci-contracts.yml's removed-component-name gate (component KEY
set only, never a property inside it) - it is `test_component_property_names_match_committed_spec`
plus the breaking gate together that closes this, not either alone.
contracts/gen/margin-service.d.ts + tsc --strict are generated from this document too."""

import copy
import json
import os
from pathlib import Path

from app.main import app

_HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}

# contracts/ is at the repo root: services/margin-service/tests/<this> -> parents[3] = root.
CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"
CONTRACT = CONTRACTS / "margin-service.api-surface.json"
# The FastAPI-native full dump. Deliberately NOT asserted here — its bytes churn across
# Python / fastapi / pydantic versions, which is the whole reason the coarse surface above
# exists — but it IS refreshed by the same CONTRACTS_CAPTURE run, so re-capturing can no longer
# update one artifact and silently leave the other stale (see margin_spec_staleness.py, which
# hard-fails ci-contracts if the two ever disagree).
FULL_SPEC = CONTRACTS / "margin-service.openapi.json"


def _surface() -> str:
    spec = app.openapi()
    surface = {}
    for path, item in spec.get("paths", {}).items():
        for method, op in item.items():
            if method.lower() not in _HTTP_METHODS:
                continue  # skip path-level "parameters"/"summary" etc.
            surface[f"{method.upper()} {path}"] = {
                "params": sorted(p["name"] for p in op.get("parameters", [])),
                "requestBody": "requestBody" in op,
                "responses": sorted(op.get("responses", {}).keys()),
            }
    return json.dumps(surface, indent=2, sort_keys=True) + "\n"


def test_openapi_surface_matches_committed_contract():
    if os.environ.get("CONTRACTS_CAPTURE"):
        # newline="\n": *.json is pinned eol=lf (.gitattributes) — write LF verbatim, not Windows
        # CRLF (which would churn the diff + break the cross-platform compare).
        CONTRACT.write_text(_surface(), encoding="utf-8", newline="\n")
        # indent=2, insertion order, ensure_ascii — matches how the other services' committed
        # dumps are written, so a re-capture diffs only what actually changed.
        FULL_SPEC.write_text(
            json.dumps(app.openapi(), indent=2) + "\n", encoding="utf-8", newline="\n"
        )
        return
    assert CONTRACT.exists(), f"missing contract snapshot: {CONTRACT}"
    assert _surface() == CONTRACT.read_text(encoding="utf-8"), (
        "margin-service API surface (method/path, params, response codes) drifted from the "
        "committed contract. If intended, re-capture with:  CONTRACTS_CAPTURE=1 python -m pytest "
        "tests/test_openapi_contract.py  and commit BOTH contracts/margin-service.api-surface.json "
        "and contracts/margin-service.openapi.json (then regen contracts/gen/margin-service.d.ts "
        "via npx openapi-typescript@7)"
    )


# fastapi 0.115.6 (this repo's hash-locked CI pin) emits `{loc, msg, type}` for its own
# auto-generated validation-error detail schema; fastapi 0.136.3 (a newer local interpreter) adds
# `ctx` + `input`. STABLE_CORE is present in every version measured; KNOWN_OPTIONAL is the full
# vocabulary of version-added extras measured across that range - see
# `_validation_error_schema_name` for how these bound the structural check, never a name.
_VALIDATION_ERROR_STABLE_CORE = frozenset({"loc", "msg", "type"})
_VALIDATION_ERROR_KNOWN_OPTIONAL = frozenset({"ctx", "input"})


def _validation_error_schema_name(spec: dict) -> str | None:
    """Locate FastAPI's own generated validation-error detail schema by STRUCTURE, never by name -
    two exemption attempts already failed this way (caught in review, both reproduced): excluding
    any component NAMED `ValidationError`/`HTTPValidationError` let a same-named service-authored
    schema's real renames pass silently; excluding the property NAMES `ctx`/`input` GLOBALLY let a
    same-named service-authored PROPERTY's rename/add/remove pass silently in ANY schema. Neither
    a component name nor a property name is provenance.

    The actual provenance is FastAPI's own WIRING, walked structurally in three steps that must
    ALL hold, each checked, not assumed:
      1. Find a schema genuinely REFERENCED from a 422 response somewhere in `paths` - the real
         link FastAPI's default RequestValidationError handling creates, not an assumed name.
      2. That wrapper schema must have the EXACT shape FastAPI always emits for it: an object
         whose ONLY property is `detail`, an array of `$ref` to another schema.
      3. The referenced schema's own properties must fit the ENVELOPE measured across real
         fastapi versions: every one of `_VALIDATION_ERROR_STABLE_CORE` present, and nothing
         beyond `_VALIDATION_ERROR_STABLE_CORE | _VALIDATION_ERROR_KNOWN_OPTIONAL`.

    If any step fails, this returns None and NOTHING is exempted - fails CLOSED, the same
    philosophy openapi_relabel_30.py uses (refuse rather than guess). A schema reachable this way
    is trusted BECAUSE of how it is wired and shaped, not because of what it or its properties are
    called - a service-authored schema, however it is named, is exempted only if it is ACTUALLY
    playing this exact structural role for a real 422 response, which is not a name collision.
    """
    schemas = spec.get("components", {}).get("schemas", {})
    prefix = "#/components/schemas/"
    wrapper_names: set[str] = set()
    for item in spec.get("paths", {}).values():
        for operation in item.values():
            if not isinstance(operation, dict):
                continue
            response = (operation.get("responses") or {}).get("422")
            if response is None:
                continue
            ref = (
                response.get("content", {})
                .get("application/json", {})
                .get("schema", {})
                .get("$ref", "")
            )
            if ref.startswith(prefix):
                wrapper_names.add(ref[len(prefix) :])

    for wrapper_name in wrapper_names:
        wrapper = schemas.get(wrapper_name)
        if not isinstance(wrapper, dict):
            continue
        props = wrapper.get("properties", {})
        if set(props) != {"detail"}:
            continue
        detail = props["detail"]
        if detail.get("type") != "array":
            continue
        ref = detail.get("items", {}).get("$ref", "")
        if not ref.startswith(prefix):
            continue
        target_name = ref[len(prefix) :]
        target = schemas.get(target_name)
        if not isinstance(target, dict):
            continue
        target_props = set(target.get("properties", {}).keys())
        if _VALIDATION_ERROR_STABLE_CORE <= target_props <= (
            _VALIDATION_ERROR_STABLE_CORE | _VALIDATION_ERROR_KNOWN_OPTIONAL
        ):
            return target_name
    return None


def _component_property_keys(spec: dict) -> dict:
    """Property-NAME projection per schema component - deliberately narrower than a raw-spec pin
    (see the module docstring on why that churns across Python/fastapi/pydantic versions), but a
    property's NAME is stable across all of them EXCEPT inside the one schema
    `_validation_error_schema_name` structurally verifies as FastAPI's own - there, and ONLY
    there, `ctx`/`input` are dropped before comparing. This catches precisely the class of break
    the route surface above and ci-contracts' removed-component-name gate cannot: a response-field
    RENAME that changes no route and no component KEY (margin-service's SizeResponse.target ->
    targetPrice, e.g.) - measured to pass every other margin-service gate silently before this
    test existed. Every component is compared, by name, with no exclusion; the ONE structurally-
    verified schema still has every OTHER property (loc/msg/type) compared normally."""
    schemas = spec.get("components", {}).get("schemas", {})
    validation_error_name = _validation_error_schema_name(spec)
    result = {}
    for name, schema in schemas.items():
        keys = set(schema.get("properties", {}).keys())
        if name == validation_error_name:
            keys -= _VALIDATION_ERROR_KNOWN_OPTIONAL
        result[name] = sorted(keys)
    return result


def test_component_property_names_match_committed_spec():
    """LIVE app vs the COMMITTED contracts/margin-service.openapi.json - the artifact
    ci-contracts.yml's breaking gate uses as this service's branch-side comparison input (it has no
    Maven-style fresh-capture artifact of its own). If code renames a field and this test is not
    re-run with CONTRACTS_CAPTURE=1, the committed spec goes stale and the breaking gate would
    silently compare two identical (stale) documents across the merge base - this test is what
    forces the re-capture BEFORE that can happen."""
    assert FULL_SPEC.exists(), f"missing committed spec: {FULL_SPEC}"
    committed = json.loads(FULL_SPEC.read_text(encoding="utf-8"))
    assert _component_property_keys(app.openapi()) == _component_property_keys(committed), (
        "margin-service's LIVE component property names differ from the committed "
        f"{FULL_SPEC.name} - a field was added, removed, or renamed in code without "
        "re-capturing the spec. Re-capture with:  CONTRACTS_CAPTURE=1 python -m pytest "
        "tests/test_openapi_contract.py  and commit the result."
    )


def test_component_property_keys_catches_a_rename_under_a_reserved_schema_name():
    """Regression pin for round 1 of this hazard (caught + reproduced): an EARLIER version of
    `_component_property_keys` excluded any component NAMED `ValidationError` or
    `HTTPValidationError` wholesale, so a SERVICE-AUTHORED schema that happened to share either
    reserved name would have any real rename inside it pass silently - both projections came back
    `{}` regardless of content. This fixture has no `paths` at all, so
    `_validation_error_schema_name` correctly finds no 422 wiring and exempts NOTHING - a schema
    named "ValidationError" is compared like any other."""
    before = {"ValidationError": {"properties": {"reason": {"type": "string"}}}}
    after = {"ValidationError": {"properties": {"why": {"type": "string"}}}}
    committed = {"components": {"schemas": before}}
    live_renamed = {"components": {"schemas": after}}
    assert _component_property_keys(committed) != _component_property_keys(live_renamed)


def _spec_with_real_validation_error_wiring(foo_properties: dict) -> dict:
    """A REALISTIC spec: a 422 response genuinely wired to HTTPValidationError -> ValidationError
    (the shape `_validation_error_schema_name` structurally verifies), plus an UNRELATED,
    service-authored schema `Foo` carrying whatever properties the caller supplies - for proving
    the `ctx`/`input` exemption stays scoped to the ONE schema it structurally verifies, never to
    any schema sharing those property names elsewhere."""
    return {
        "paths": {
            "/x": {
                "post": {
                    "responses": {
                        "422": {
                            "content": {
                                "application/json": {
                                    "schema": {"$ref": "#/components/schemas/HTTPValidationError"}
                                }
                            }
                        }
                    }
                }
            }
        },
        "components": {
            "schemas": {
                "HTTPValidationError": {
                    "properties": {
                        "detail": {
                            "type": "array",
                            "items": {"$ref": "#/components/schemas/ValidationError"},
                        }
                    }
                },
                "ValidationError": {
                    "properties": {"loc": {}, "msg": {}, "type": {}, "ctx": {}, "input": {}}
                },
                "Foo": {"properties": foo_properties},
            }
        },
    }


def test_real_validation_error_ctx_input_churn_still_tolerated():
    """Sanity check for the structural fix: the ACTUAL fastapi-version drift this exemption
    exists for (ValidationError gaining/losing `ctx`/`input` between fastapi 0.115.6 and 0.136.3)
    must still be tolerated within the schema `_validation_error_schema_name` verifies - that is
    the whole point of the exemption existing at all."""
    committed = _spec_with_real_validation_error_wiring({"a": {}})
    live = copy.deepcopy(committed)
    live["components"]["schemas"]["ValidationError"] = {
        "properties": {"loc": {}, "msg": {}, "type": {}}
    }
    assert _component_property_keys(committed) == _component_property_keys(live)


def test_property_scoped_exemption_does_not_mask_an_unrelated_schema():
    """Regression pin for round 2 of this hazard (caught + reproduced): an EARLIER version of
    `_component_property_keys` dropped `ctx`/`input` GLOBALLY from every schema's projection, so a
    SERVICE-AUTHORED schema (here, `Foo`, unrelated to validation errors) that happened to use
    those exact property names had any rename/add/remove involving them pass silently too - the
    exemption must be scoped to the ONE schema structurally verified as FastAPI's own, never
    applied everywhere. All three misses the reviewer reproduced, in one fixture that also carries
    the REAL validation-error wiring (so the legitimate exemption is exercised at the same time,
    not merely absent)."""
    # 1. service-authored ctx -> input RENAME on Foo.
    committed = _spec_with_real_validation_error_wiring({"ctx": {"type": "string"}})
    live = _spec_with_real_validation_error_wiring({"input": {"type": "string"}})
    assert _component_property_keys(committed)["Foo"] != _component_property_keys(live)["Foo"]

    # 2. ADDED ctx on Foo.
    committed = _spec_with_real_validation_error_wiring({"a": {}})
    live = _spec_with_real_validation_error_wiring({"a": {}, "ctx": {"type": "string"}})
    assert _component_property_keys(committed)["Foo"] != _component_property_keys(live)["Foo"]

    # 3. REMOVED input from Foo.
    committed = _spec_with_real_validation_error_wiring({"a": {}, "input": {"type": "string"}})
    live = _spec_with_real_validation_error_wiring({"a": {}})
    assert _component_property_keys(committed)["Foo"] != _component_property_keys(live)["Foo"]
