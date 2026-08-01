#!/usr/bin/env python3
"""Fail when contracts/margin-service.openapi.json has rotted away from the service's code.

margin-service is Python (FastAPI), so — like optimizer-service (task_e2d15c21) — it has no
`target/contracts/*.json` Maven artifact and cannot ride ci-contracts' two Java loops (the capture
reactor + the openapi-diff breaking gate; the latter also refuses margin-service's spec outright —
see contract_service_inventory.sh's NON_JAVA_SERVICES comment). This script is the ONLY structural
protection its committed full dump gets, so it must be required, not a warning.

WHY THIS COMPARES TWO COMMITTED FILES RATHER THAN THE CODE. It is one half of a two-step chain:

    app routes --(step A)--> api-surface.json --(step B, THIS SCRIPT)--> openapi.json

Step A is services/margin-service/tests/test_openapi_contract.py run against the live app; step B
is this script. BOTH must run in ci-contracts' required `contracts` job — step A gated on a
`margin` path classification because it needs the service's Python dependencies installed, step B
unconditionally because it needs nothing but stdlib json. Delegating step A to ci-margin.yml alone
would NOT be sufficient: that workflow is `paths:`-filtered and not a required context, so a route
change touching neither committed artifact would pass every required check with the two snapshots
agreeing with each other while both lagged the code.

WHY THE SURFACE AND NOT THE BYTES. A byte diff of a FastAPI dump false-fails across toolchains: CI
runs Python 3.12 / fastapi 0.115, dev may run 3.14 / fastapi 0.136, and pydantic's serialized
representation churns between them. The surface below (method + path, parameter NAMES,
requestBody presence, response-code set) comes from the route decorators, so it is identical
across those versions.

This is deliberately a near-duplicate of optimizer_spec_staleness.py rather than a shared,
parametrized module: each script is a handful of hardcoded paths for exactly one service, the same
shape optimizer_spec_staleness.py already used before this file existed, and keeping them separate
means a change to one service's projection can never silently reach the other's.

The projection MUST mirror `_surface()` in services/margin-service/tests/test_openapi_contract.py
(that file is the source of truth for the shape). That is the ONE way this gate can produce a false
RED — change `_surface()`, re-capture, and every operation "differs" — so a divergence is detected
explicitly in main() and reported as a sync instruction rather than as contract drift. The check is
two-sided (every entry's key set must EQUAL ours): a one-sided "unexpected keys" test would miss a
REMOVED field and let it resurface later as permanent, misleading "drift". It cannot produce a false
GREEN: any shape that is not exactly ours is an error, never a pass.
"""

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DUMP = REPO / "contracts" / "margin-service.openapi.json"
SURFACE = REPO / "contracts" / "margin-service.api-surface.json"

_HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}


def surface_of(spec):
    """Mirror of test_openapi_contract.py::_surface, over a spec dict instead of the live app."""
    out = {}
    for path, item in (spec.get("paths") or {}).items():
        for method, op in item.items():
            if method.lower() not in _HTTP_METHODS:
                continue  # path-level "parameters"/"summary" etc.
            out["%s %s" % (method.upper(), path)] = {
                "params": sorted(p["name"] for p in op.get("parameters", [])),
                "requestBody": "requestBody" in op,
                "responses": sorted(op.get("responses", {}).keys()),
            }
    return out


def main():
    for artifact in (DUMP, SURFACE):
        if not artifact.exists():
            # Fail CLOSED: a missing artifact means the check cannot answer its question, and
            # "cannot answer" must never read as "no drift".
            print("::error::%s is missing - margin-service staleness cannot be checked." % artifact.name)
            return 1

    dump = surface_of(json.loads(DUMP.read_text(encoding="utf-8")))
    surface = json.loads(SURFACE.read_text(encoding="utf-8"))

    # The only way this gate can go falsely RED: someone changes _surface()'s shape and
    # re-captures, leaving this projection a version behind so EVERY operation "differs".
    # Name that case instead of reporting it as contract drift. EVERY entry's key set must EQUAL
    # ours - checking only for UNEXPECTED keys is one-sided and would let a REMOVED field (or a
    # per-operation shape mismatch) through, to resurface later as permanent, misleading "drift".
    ours = {"params", "requestBody", "responses"}
    unexpected = set()
    absent = set()
    for value in surface.values():
        keys = set(value)
        unexpected |= keys - ours
        absent |= ours - keys
    if unexpected or absent:
        print(
            "::error::contracts/margin-service.api-surface.json does not have the shape this "
            "script projects. _surface() in services/margin-service/tests/test_openapi_contract.py "
            "is the source of truth for the shape and has changed - mirror it in surface_of() here. "
            "This is a projection mismatch, NOT contract drift."
        )
        if unexpected:
            print("  fields the surface carries but this script does not project: %s"
                  % ", ".join(sorted(unexpected)))
        if absent:
            print("  fields this script projects but the surface no longer carries: %s"
                  % ", ".join(sorted(absent)))
        return 1

    missing = sorted(set(surface) - set(dump))
    extra = sorted(set(dump) - set(surface))
    changed = sorted(op for op in set(dump) & set(surface) if dump[op] != surface[op])

    if not (missing or extra or changed):
        print("contracts/margin-service.openapi.json is current (%d operations)." % len(dump))
        return 0

    print(
        "::error::contracts/margin-service.openapi.json drifts from the code. It advertises "
        "%d operations; the app serves %d (per the api-surface snapshot, which pytest asserts "
        "against the live app). Re-capture BOTH with:  cd services/margin-service && "
        "CONTRACTS_CAPTURE=1 python -m pytest tests/test_openapi_contract.py  then regenerate "
        "contracts/gen/margin-service.d.ts (npx openapi-typescript@7)." % (len(dump), len(surface))
    )
    for op in missing:
        print("  served by the app but ABSENT from the dump: %s" % op)
    for op in extra:
        print("  in the dump but NO LONGER served: %s" % op)
    for op in changed:
        print("  params/body/response-codes differ: %s" % op)
        print("      dump:    %s" % json.dumps(dump[op], sort_keys=True))
        print("      surface: %s" % json.dumps(surface[op], sort_keys=True))
    return 1


if __name__ == "__main__":
    sys.exit(main())
