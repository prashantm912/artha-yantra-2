#!/usr/bin/env python3
"""Pre-commit launcher for the gitleaks staged-secret scan (A.9).

Resolves the `gitleaks` binary even when it is not on the *current* shell's
PATH — Windows Smart App Control blocks the upstream golang pre-commit hook,
so we shell out to the system binary, but a freshly winget-installed gitleaks
only lands on the PATH of *new* shells. This launcher finds it via PATH, then
common winget/Homebrew/Linux locations, so `git commit` works from any shell.
"""
import glob
import os
import shutil
import subprocess
import sys


def find_gitleaks() -> str | None:
    onpath = shutil.which("gitleaks")
    if onpath:
        return onpath
    candidates = []
    localappdata = os.environ.get("LOCALAPPDATA", "")
    if localappdata:
        candidates += glob.glob(
            os.path.join(localappdata, "Microsoft", "WinGet", "Packages", "*itleaks*", "**", "gitleaks.exe"),
            recursive=True,
        )
        candidates.append(os.path.join(localappdata, "Microsoft", "WinGet", "Links", "gitleaks.exe"))
    candidates += ["/opt/homebrew/bin/gitleaks", "/usr/local/bin/gitleaks", "/usr/bin/gitleaks"]
    for path in candidates:
        if os.path.isfile(path):
            return path
    return None


def main() -> int:
    gitleaks = find_gitleaks()
    if not gitleaks:
        print(
            "gitleaks not found. Install it (Windows: `winget install Gitleaks.Gitleaks`,\n"
            "macOS: `brew install gitleaks`) and open a NEW shell, or add it to PATH.",
            file=sys.stderr,
        )
        return 1
    # pre-commit may invoke hooks from a transient cwd; gitleaks' git scan must
    # run inside the repo, so pin cwd to the repo root (tools/precommit/..).
    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    return subprocess.run(
        [gitleaks, "git", "--pre-commit", "--staged", "--redact"], cwd=repo_root
    ).returncode


if __name__ == "__main__":
    sys.exit(main())
