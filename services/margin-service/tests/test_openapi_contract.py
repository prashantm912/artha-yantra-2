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
apart from each other. Coverage is PARTIAL, the same shape as optimizer-service's (see
contract_service_inventory.sh's NON_JAVA_SERVICES comment): the openapi-diff breaking-change gate
NEVER runs on margin-service — it loops over the JAVA service list only, categorically, regardless
of spec content. Only ci-contracts.yml's removed-component-name gate diffs this document across
the merge base, and only the COMPONENT KEY SET (e.g. whether "SizeResponse" exists at all) — never
a property or type inside it. Concretely: renaming SizeResponse.target to targetPrice changes
neither the route surface below nor any component key, so it passes every margin-service gate
silently — measured, not assumed, and permanent (not a gap a later PR closes on its own).
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
