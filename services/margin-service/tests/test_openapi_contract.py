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


def _component_property_keys(spec: dict) -> dict:
    """Property-NAME projection per schema component - deliberately narrower than a raw-spec pin
    (see the module docstring on why that churns across Python/fastapi/pydantic versions), but a
    property's NAME is stable across all of them; only the JSON-Schema keyword spelling of its
    TYPE varies (e.g. anyOf branch shape/ordering/title casing). This catches precisely the class
    of break the route surface above and ci-contracts' removed-component-name gate cannot: a
    response-field RENAME that changes no route and no component KEY (margin-service's
    SizeResponse.target -> targetPrice, e.g.) - measured to pass every other margin-service gate
    silently before this test existed."""
    schemas = spec.get("components", {}).get("schemas", {})
    return {name: sorted(schema.get("properties", {}).keys()) for name, schema in schemas.items()}


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
