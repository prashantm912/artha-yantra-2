"""API-surface contract diff-gate (register §9-1). The Java services' /v3/api-docs are
snapshot + diff-gated by ContractCaptureTest; the FastAPI services weren't (margin-service had
no committed contract at all). This gates the version-STABLE surface of the OpenAPI — each
operation's method + path, its parameter names, whether it has a request body, and its response
status codes — NOT the full pydantic-serialized schema, whose internal representation churns
across Python / fastapi / pydantic versions (a raw-spec pin false-fails in CI: it runs 3.12, dev
may run 3.14). The surface comes from the route decorators, so it is identical across versions and
still catches the drift that matters: an added / removed / renamed route or param, or a changed
response-code set. Runs in the pytest CI (no new workflow)."""

import json
import os
from pathlib import Path

from app.main import app

_HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}

# contracts/ is at the repo root: services/margin-service/tests/<this> -> parents[3] = root.
CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "margin-service.api-surface.json"


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
        return
    assert CONTRACT.exists(), f"missing contract snapshot: {CONTRACT}"
    assert _surface() == CONTRACT.read_text(encoding="utf-8"), (
        "margin-service API surface (method/path, params, response codes) drifted from the "
        "committed contract. If intended, re-capture with:  CONTRACTS_CAPTURE=1 python -m pytest "
        "tests/test_openapi_contract.py  and commit contracts/margin-service.api-surface.json"
    )
