"""OpenAPI contract diff-gate (register §9-1). The optimizer-service OpenAPI was committed to
contracts/ once but never regenerated or diff-checked, so a route/param/response-shape change could
drift the live API away from the committed contract silently. This pins the current app.openapi() to
the committed snapshot; a drift fails the test with the re-capture command — the same discipline the
Java services get from ContractCaptureTest, run inside the existing pytest CI (no new workflow)."""

import json
import os
from pathlib import Path

from app.main import app

# contracts/ is at the repo root: services/optimizer-service/tests/<this> -> parents[3] = root.
CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "optimizer-service.openapi.json"


def _current() -> str:
    return json.dumps(app.openapi(), indent=2, sort_keys=True) + "\n"


def test_openapi_matches_committed_contract():
    if os.environ.get("CONTRACTS_CAPTURE"):
        # newline="\n": *.json is pinned eol=lf (.gitattributes) — write LF verbatim, not Windows
        # CRLF (which would churn the diff + break the cross-platform compare).
        CONTRACT.write_text(_current(), encoding="utf-8", newline="\n")
        return
    assert CONTRACT.exists(), f"missing contract snapshot: {CONTRACT}"
    assert _current() == CONTRACT.read_text(encoding="utf-8"), (
        "optimizer-service OpenAPI drifted from the committed contract. If the change is intended, "
        "re-capture with:  CONTRACTS_CAPTURE=1 python -m pytest tests/test_openapi_contract.py  "
        "and commit contracts/optimizer-service.openapi.json"
    )
