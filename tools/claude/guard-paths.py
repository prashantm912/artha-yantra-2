#!/usr/bin/env python3
"""Claude Code PreToolUse guard for ArthaYantra.

Reads the hook payload on stdin and emits a permission decision on stdout:
  * DENY edits/writes to secrets (.env, deploy/secrets/*, deploy/dev-certs/*.pem).
  * ASK before editing an already-existing versioned Flyway migration (applied
    migrations are checksum-locked; the right move is a new suffix-versioned file).
  * ALLOW everything else (silent exit 0).

Anchored to the repo root via __file__ so it is independent of the hook's cwd.
"""
import json
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def load_payload():
    try:
        return json.load(sys.stdin)
    except Exception:
        return {}


def target_path(payload):
    ti = payload.get("tool_input") or {}
    return ti.get("file_path") or ti.get("notebook_path") or ti.get("path")


def decide(rel, abspath):
    # --- secrets: hard deny -------------------------------------------------
    secret_patterns = [
        r"^\.env$",
        r"^\.env\.(?!example$).+",                       # .env.* except .env.example
        r"^deploy/secrets/(?!\.gitkeep$|README\.md$).+",  # any real secret file
        r"^deploy/dev-certs/.+\.(pem|cer)$",
    ]
    for pat in secret_patterns:
        if re.match(pat, rel, re.IGNORECASE):
            return (
                "deny",
                f"Blocked: '{rel}' holds secrets/credentials (gitignored). Claude must "
                "not read or write secret material — edit it yourself if it truly needs changing.",
            )

    # --- existing versioned Flyway migration: ask --------------------------
    if re.match(r"^deploy/flyway/[^/]+/V[0-9].*\.sql$", rel, re.IGNORECASE) and os.path.exists(abspath):
        return (
            "ask",
            f"'{rel}' is an existing versioned Flyway migration. Applied migrations are "
            "checksum-locked — editing one breaks `flyway validate` / flyway-init. Prefer a "
            "NEW suffix-versioned migration (see the /new-migration skill). Edit anyway?",
        )

    return ("allow", None)


def main():
    payload = load_payload()
    path = target_path(payload)
    if not path:
        sys.exit(0)
    abspath = path if os.path.isabs(path) else os.path.join(REPO_ROOT, path)
    abspath = os.path.abspath(abspath)
    rel = os.path.relpath(abspath, REPO_ROOT).replace("\\", "/")

    decision, reason = decide(rel, abspath)
    if decision == "allow":
        sys.exit(0)

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": reason,
        }
    }))
    sys.exit(0)


if __name__ == "__main__":
    main()
