#!/usr/bin/env python3
"""Watch a running (or finished) Codex session live.

The codex harness already streams every event to
`.claude/skills/codex-*/state/<target>.<hash>.events.ndjson` while the run is in
flight — narration, every shell command, exit codes, and the final verdict. Until
now we only ever read the tail *after* the wrapper exited, which is why a long
review looked like a black box for ten minutes.

Usage:
    python tools/codex-watch.py                 # list sessions, newest first
    python tools/codex-watch.py ca-purge-r4     # print the session so far
    python tools/codex-watch.py ca-purge-r4 -f  # follow it live
    python tools/codex-watch.py -f              # follow the newest session

Reads with encoding='utf-8', errors='replace' on purpose: the stream is UTF-8,
but a naive read on Windows picks cp1252 and turns every apostrophe into mojibake.
"""

import argparse
import json
import os
import sys
import time

STATE_DIRS = [
    os.path.join(".claude", "skills", "codex-code-review", "state"),
    os.path.join(".claude", "skills", "codex-build", "state"),
    os.path.join(".claude", "skills", "codex-plan-review", "state"),
    os.path.join(".claude", "skills", "codex-ask", "state"),
]

SUFFIX = ".events.ndjson"


def sessions():
    """Every events file across the skill state dirs, newest first."""
    out = []
    for d in STATE_DIRS:
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            if name.endswith(SUFFIX):
                path = os.path.join(d, name)
                target = name[: -len(SUFFIX)].rsplit(".", 1)[0]
                out.append((os.path.getmtime(path), target, path))
    return sorted(out, reverse=True)


def shorten(command):
    """Strip the PowerShell wrapper so the interesting part of the line survives.

    Every sandboxed command arrives as
    `"C:\\...\\powershell.exe" -Command '<the bit you actually want>'`, which is
    ~70 characters of prefix — enough to push the real command past any sane
    truncation width and make every line look identical.
    """
    cmd = " ".join(command.split())
    marker = "-Command "
    idx = cmd.find(marker)
    if idx != -1:
        cmd = cmd[idx + len(marker) :].lstrip("\"'")
    return cmd[:150]


def render(event):
    """One event -> one printable line, or None to skip."""
    kind = event.get("type")
    if kind == "thread.started":
        return f"--- thread {event.get('thread_id', '?')}"
    if kind == "turn.started":
        return "--- turn started"
    if kind not in ("item.started", "item.completed"):
        return None

    item = event.get("item", {})
    itype = item.get("type")

    # Narration: what the agent believes it is doing. The most useful line by far.
    if itype == "agent_message" and kind == "item.completed":
        return "\n>> " + (item.get("text") or "").strip()

    # Shell work: show it on START so a long command is visible while it runs,
    # then only report the exit code if it is non-zero.
    if itype == "command_execution":
        if kind == "item.started":
            return "   $ " + shorten(item.get("command") or "")
        code = item.get("exit_code")
        return f"   ! exit {code}" if code not in (0, None) else None

    if itype == "error" and kind == "item.completed":
        return "\n!! ERROR: " + (item.get("message") or "").strip()

    return None


def stream(path, follow):
    printed_bytes = 0
    idle = 0
    while True:
        with open(path, encoding="utf-8", errors="replace") as fh:
            fh.seek(printed_bytes)
            chunk = fh.read()
            printed_bytes = fh.tell()
        wrote = False
        for line in chunk.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            out = render(event)
            if out:
                print(out, flush=True)
                wrote = True
        if not follow:
            return
        idle = 0 if wrote else idle + 1
        # The wrapper writes the receipt and stops; 60 quiet polls (~2 min) means done.
        if idle > 60:
            print("\n--- no output for ~2 min; session is idle or finished", flush=True)
            return
        time.sleep(2)


def main():
    # The stream is UTF-8; a default cp1252 stdout on Windows turns every curly
    # apostrophe in the agent's narration into a replacement char.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description="Watch a Codex session live.")
    ap.add_argument("target", nargs="?", help="session target (prefix match)")
    ap.add_argument("-f", "--follow", action="store_true", help="follow live")
    args = ap.parse_args()

    found = sessions()
    if not found:
        print("no codex sessions found (run this from the repo root)", file=sys.stderr)
        return 1

    if not args.target and not args.follow:
        print(f"{'AGE':>8}  TARGET")
        now = time.time()
        for mtime, target, _ in found[:20]:
            mins = int((now - mtime) // 60)
            age = f"{mins}m" if mins < 120 else f"{mins // 60}h"
            print(f"{age:>8}  {target}")
        return 0

    if args.target:
        hits = [s for s in found if s[1].startswith(args.target)]
        if not hits:
            print(f"no session matching {args.target!r}", file=sys.stderr)
            return 1
    else:
        hits = found

    _, target, path = hits[0]
    print(f"=== {target}\n=== {path}\n", flush=True)
    stream(path, args.follow)
    return 0


if __name__ == "__main__":
    sys.exit(main())
