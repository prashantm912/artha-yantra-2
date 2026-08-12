"""API-surface contract diff-gate (register §9-1). The Java services' /v3/api-docs are
snapshot + diff-gated by ContractCaptureTest; the FastAPI services weren't. This gates the
version-STABLE surface of the OpenAPI — each operation's method + path, its parameter names,
whether it has a request body, and its response status codes — NOT the full pydantic-serialized
schema, whose internal representation churns across Python / fastapi / pydantic versions (a
raw-spec pin false-fails in CI: it runs 3.12, dev may run 3.14). The surface comes from the route
decorators, so it is identical across versions and still catches the drift that matters: an
added / removed / renamed route or param, or a changed response-code set. Runs in the pytest CI."""

import json
import os
from pathlib import Path

import fastapi
from fastapi.testclient import TestClient

from app.main import app

_HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}

# contracts/ is at the repo root: services/optimizer-service/tests/<this> -> parents[3] = root.
CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"
CONTRACT = CONTRACTS / "optimizer-service.api-surface.json"
# The FastAPI-native full dump (STAGE_D deliverable). Deliberately NOT asserted — its bytes churn
# across Python / fastapi / pydantic versions, which is the whole reason the coarse surface above
# exists — but it IS refreshed by the same CONTRACTS_CAPTURE run, so re-capturing can no longer
# update one artifact and silently leave the other stale.
FULL_SPEC = CONTRACTS / "optimizer-service.openapi.json"
LOCKFILE = Path(__file__).resolve().parents[1] / "requirements-dev.lock"


def _pinned_fastapi_version() -> str:
    """The exact fastapi version requirements-dev.lock hash-pins for CI, parsed directly from the
    lockfile - never hand-copied here, so this check can never itself drift from the pin it
    verifies. Mirrors margin-service's identical guard (task_ms-spec's sibling chain)."""
    for line in LOCKFILE.read_text(encoding="utf-8").splitlines():
        if line.startswith("fastapi=="):
            return line[len("fastapi==") :].split()[0].rstrip("\\")
    raise AssertionError(f"no fastapi== pin found in {LOCKFILE}")


def test_running_under_the_pinned_fastapi_version():
    """Runs FIRST (file order) because `test_component_property_names_match_committed_spec` below
    depends on it: LIVE and COMMITTED are only comparable at the property level if both were
    generated under the SAME fastapi version, or fastapi's OWN generated schemas (this service does
    not use the stock HTTPValidationError/ValidationError shape today - see
    test_every_documented_422_is_the_shared_error_envelope below - but a future dependency could
    reintroduce one) can disagree between environments for reasons that have nothing to do with
    application code. margin-service hit exactly this (fastapi 0.115.6 CI-pinned vs 0.136.3 local
    ambient); mirrored here defensively rather than assuming this service is permanently immune."""
    assert fastapi.__version__ == _pinned_fastapi_version(), (
        f"running fastapi {fastapi.__version__}, but requirements-dev.lock pins "
        f"{_pinned_fastapi_version()} (the version CI installs). "
        "test_component_property_names_match_committed_spec below - and any CONTRACTS_CAPTURE=1 "
        "re-dump - are only trustworthy under the PINNED version. Run tests and re-capture "
        "through a venv built from the lockfile, never the ambient interpreter:\n"
        "  uv venv --python 3.12 .venv-pinned\n"
        "  uv pip install --python .venv-pinned --require-hashes -r requirements-dev.lock\n"
        "  CONTRACTS_CAPTURE=1 .venv-pinned/Scripts/python.exe -m pytest "
        "tests/test_openapi_contract.py"
    )


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
        # indent=2, insertion order, ensure_ascii — matches how the committed dump was written, so
        # a re-capture diffs only what actually changed.
        FULL_SPEC.write_text(
            json.dumps(app.openapi(), indent=2) + "\n", encoding="utf-8", newline="\n"
        )
        return
    assert CONTRACT.exists(), f"missing contract snapshot: {CONTRACT}"
    assert _surface() == CONTRACT.read_text(encoding="utf-8"), (
        "optimizer-service API surface (method/path, params, response codes) drifted from the "
        "committed contract. If intended, re-capture with:  CONTRACTS_CAPTURE=1 python -m pytest "
        "tests/test_openapi_contract.py  and commit contracts/optimizer-service.api-surface.json"
    )


def _component_property_keys(spec: dict) -> dict:
    """Property-NAME projection per schema component - deliberately narrower than a raw-spec pin
    (see the module docstring on why that churns across Python/fastapi/pydantic versions), but a
    property's NAME is stable across all of them; only the JSON-Schema keyword spelling of its
    TYPE varies (e.g. anyOf branch shape/ordering/title casing). This catches precisely the class
    of break the route surface above and ci-contracts' removed-component-name gate cannot: a
    response-field RENAME that changes no route and no component KEY (e.g. ExpireResult.expired ->
    expiredCount) - measured to pass every other optimizer-service gate silently before this test
    existed."""
    schemas = spec.get("components", {}).get("schemas", {})
    return {name: sorted(schema.get("properties", {}).keys()) for name, schema in schemas.items()}


def test_component_property_names_match_committed_spec():
    """LIVE app vs the COMMITTED contracts/optimizer-service.openapi.json - the artifact
    ci-contracts.yml's breaking gate uses as this service's branch-side comparison input (it has no
    Maven-style fresh-capture artifact of its own). If code renames a field and this test is not
    re-run with CONTRACTS_CAPTURE=1, the committed spec goes stale and the breaking gate would
    silently compare two identical (stale) documents across the merge base - this test is what
    forces the re-capture BEFORE that can happen. Only trustworthy under the pinned fastapi
    version - see test_running_under_the_pinned_fastapi_version above, which runs first."""
    assert FULL_SPEC.exists(), f"missing committed spec: {FULL_SPEC}"
    committed = json.loads(FULL_SPEC.read_text(encoding="utf-8"))
    assert _component_property_keys(app.openapi()) == _component_property_keys(committed), (
        "optimizer-service's LIVE component property names differ from the committed "
        f"{FULL_SPEC.name} - a field was added, removed, or renamed in code without "
        "re-capturing the spec. Re-capture with:  CONTRACTS_CAPTURE=1 python -m pytest "
        "tests/test_openapi_contract.py  and commit the result. If this fails with NO code "
        "change, first check test_running_under_the_pinned_fastapi_version - a version mismatch "
        "is the far more likely cause."
    )


def _documented_422_refs() -> set[str | None]:
    """Every 422 response schema `$ref` the published spec advertises, service-wide."""
    spec = app.openapi()
    refs: set[str | None] = set()
    for item in spec.get("paths", {}).values():
        for method, operation in item.items():
            if method.lower() not in _HTTP_METHODS:
                continue
            response = (operation.get("responses") or {}).get("422")
            if response is not None:
                refs.add(response["content"]["application/json"]["schema"].get("$ref"))
    return refs


def test_every_documented_422_is_the_shared_error_envelope():
    """The 422 body is enveloped at runtime, so the SPEC must say so — FastAPI's stock
    ``HTTPValidationError`` (``{"detail": [...]}``) would be a documented-but-FALSE response shape,
    the 'contract lie' class the 2026-07-31 E2E sweep hunted and found zero of across 262 paths.
    The coarse surface snapshot above cannot see this: it records response-code KEYS, never their
    schemas. Asserted service-wide so a route added later cannot quietly reintroduce the stock
    shape, and the orphaned components are asserted GONE — leaving them would keep advertising a
    body nothing returns."""
    assert _documented_422_refs() == {"#/components/schemas/ErrorEnvelope"}
    schemas = app.openapi()["components"]["schemas"]
    assert "HTTPValidationError" not in schemas
    assert "ValidationError" not in schemas


def test_documented_422_schema_matches_the_runtime_body():
    """Spec vs RUNTIME, not spec vs itself: drive a REAL validation failure and assert the body
    returned is exactly what the published schema promises. Without this half, the spec and the
    handler can drift apart again the next time either one changes."""
    schema = app.openapi()["components"]["schemas"]["ErrorEnvelope"]
    body = TestClient(app).get("/api/v1/optimizations/jobs/not-a-uuid").json()
    assert set(body) == set(schema["properties"])
    assert set(schema["required"]) == set(body)  # every documented key is ALWAYS written
    assert schema["properties"]["details"]["type"] == "object"
    assert isinstance(body["details"], dict)
