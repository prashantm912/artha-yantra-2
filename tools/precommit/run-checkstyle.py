#!/usr/bin/env python3
"""Pre-commit launcher for the reactor Checkstyle gate (Phase 4 / COMMON 10.6).

Runs `mvnw checkstyle:check` across the reactor whenever staged Java files are
committed — the fast local half of the lint story; CI stage 1 runs the full
build. Cross-platform: picks mvnw.cmd on Windows, ./mvnw elsewhere.
"""
import os
import subprocess
import sys


def main() -> int:
    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    if os.name == "nt":
        cmd = [os.path.join(repo_root, "mvnw.cmd")]
    else:
        cmd = [os.path.join(repo_root, "mvnw")]
    cmd += ["-ntp", "-q", "checkstyle:check"]
    result = subprocess.run(cmd, cwd=repo_root)
    if result.returncode != 0:
        print("checkstyle violations — fix before committing (config/checkstyle/checkstyle.xml)")
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
