#!/usr/bin/env python3
"""Claude Code PostToolUse hook: run Prettier on edited frontend-react files.

Best-effort and non-blocking — if Prettier is not installed or errors, it exits
0 silently so it can never interrupt a Claude turn. Only touches files under
frontend-react/ with a front-end extension.
"""
import json
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return
    ti = payload.get("tool_input") or {}
    path = ti.get("file_path")
    if not path:
        return
    abspath = path if os.path.isabs(path) else os.path.join(REPO_ROOT, path)
    abspath = os.path.abspath(abspath)
    rel = os.path.relpath(abspath, REPO_ROOT).replace("\\", "/")

    if not rel.startswith("frontend-react/"):
        return
    if not re.search(r"\.(tsx?|jsx?|css|json)$", rel):
        return

    fe = os.path.join(REPO_ROOT, "frontend-react")

    # frontend-react formats via ESLint, NOT Prettier — there is no Prettier config, so running
    # Prettier with its defaults mangles the hand-formatted single-quote/LF source (double-quotes,
    # CRLF, narrow wrap). Only run when a Prettier config is actually present (Angular's frontend-ui
    # shipped one); otherwise this hook is a no-op. Re-activates automatically if a config is added.
    cfg_names = (
        ".prettierrc", ".prettierrc.json", ".prettierrc.yaml", ".prettierrc.yml",
        ".prettierrc.js", ".prettierrc.cjs", ".prettierrc.mjs",
        "prettier.config.js", "prettier.config.cjs", "prettier.config.mjs",
    )
    if not any(os.path.exists(os.path.join(fe, n)) for n in cfg_names):
        return  # no Prettier config — ESLint owns formatting here; skip silently

    prettier = os.path.join(fe, "node_modules", ".bin", "prettier" + (".cmd" if os.name == "nt" else ""))
    if not os.path.exists(prettier):
        return  # prettier not installed in this checkout — skip silently

    try:
        subprocess.run([prettier, "--write", abspath], cwd=fe, timeout=30, capture_output=True)
    except Exception:
        pass


if __name__ == "__main__":
    main()
