#!/usr/bin/env python3
"""Ledger-consistency sweep — mechanical contradiction finder for the planning docs.

Why this exists (2026-07-30, one session): the ledger claimed "the only remaining
startable in #9 is 03" NINETEEN DAYS after that item shipped as row B17/PR #712 — the
contradiction sat ~690 lines away IN THE SAME FILE, and both session pickup sheets had
copied it forward, so a run picking "the clean-autonomous pick" would have built
already-shipped work. The same evening, chip task_76d8f2a4's premise turned out to be
resolved by a republish two days earlier, and three findings-doc "promotions" had never
landed as ledger rows. A human reads the queue top-down and trusts it; this script
cross-greps the claims instead.

It is an AID, not a prover: it prints REVIEW lines for a human (or the post-market
routine) to judge, and always exits 0. False positives are acceptable at low volume;
the failure mode it kills is a false STARTABLE, which costs a whole work lane.

Checks:
  A. task_ chip ids marked OPEN-ish on one line and CLOSED-ish on another.
  B. Pickup-sheet actionable bullets whose distinctive kebab-slugs or item ids appear
     in a DONE/CLOSED ledger row (the #9-03 class).
  C. Findings-doc promotion claims ("promoted as G16" etc.) whose target id never
     appears in the ledger (the dangling-promotion class).

Usage:
  python tools/ledger-consistency-check.py            # repo defaults
  python tools/ledger-consistency-check.py --pickup docs/signal-analysis/X.md
                                                      # override for testing
"""

from __future__ import annotations

import sys as _sys

# Windows consoles default stdout to cp1252; the docs these scan are UTF-8 (arrows, section
# marks). Reconfigure here so the ROUTINE does not have to remember PYTHONIOENCODING — this
# exact trap cost two debugging rounds on 2026-07-29/30.
if hasattr(_sys.stdout, "reconfigure"):
    _sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import argparse
import glob
import io
import os
import re
import sys

LEDGER_DEFAULT = "docs/superpowers/plans/2026-07-02-remaining-items.md"

OPENISH = re.compile(r"\b(OPEN|PENDING|IN-?\s?PROGRESS|startable|not started|carry[- ]forward)\b", re.I)
CLOSEDISH = re.compile(r"\b(DONE|CLOSED|SHIPPED|RESOLVED|MERGED|superseded)\b|~~")
TASK_ID = re.compile(r"task_[0-9a-f]{8}")
ITEM_ID = re.compile(r"\b([GT]\d{1,2})\b")
# Hyphenated tokens >= MIN_SLUG_LEN chars. The threshold matters: an earlier >=2-hyphen
# version would have MISSED "trial-metrics" and with it the exact #9-03 case that motivated
# this tool (caught during the tool's own red-proof). One hyphen + length beats hyphen count.
SLUG = re.compile(r"\b([a-z][a-z0-9]+(?:-[a-z0-9]+)+)\b")
MIN_SLUG_LEN = 10
PROMOTE = re.compile(r"promot", re.I)

# generic slugs that appear everywhere and identify nothing
SLUG_STOPLIST = {
    "next-session-pickup", "session-findings", "remaining-items", "docs-signal-analysis",
    "cross-vendor-review", "owner-gated", "post-market", "signal-analysis", "market-data",
    "strategy-signal", "backtest-service", "edge-gateway", "paper-positions", "exit-doctrine",
}

ROW_ID = re.compile(r"^\s*\|\s*~{0,2}\**([A-Za-z0-9_#§-]{1,24})\**~{0,2}\s*\|")


def row_id_of(line: str) -> str | None:
    m = ROW_ID.match(line)
    return m.group(1) if m else None


def read(path: str) -> list[str]:
    with io.open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def newest(pattern: str, n: int = 1) -> list[str]:
    return sorted(glob.glob(pattern))[-n:]


def check_task_ids(ledger_lines: list[str], pickup_lines: list[str], out: list[str]) -> None:
    """A: a chip id that reads OPEN in one place and CLOSED in another."""
    state: dict[str, dict[str, list[str]]] = {}
    for src, lines in (("ledger", ledger_lines), ("pickup", pickup_lines)):
        for ln in lines:
            for tid in TASK_ID.findall(ln):
                slot = state.setdefault(tid, {"open": [], "closed": []})
                if CLOSEDISH.search(ln):
                    slot["closed"].append(f"{src}: {ln.strip()[:150]}")
                elif OPENISH.search(ln):
                    slot["open"].append(f"{src}: {ln.strip()[:150]}")
    for tid, slot in sorted(state.items()):
        if slot["open"] and slot["closed"]:
            out.append(f"REVIEW[A] {tid} reads OPEN and CLOSED in different places:")
            out.append(f"    open   -> {slot['open'][0]}")
            out.append(f"    closed -> {slot['closed'][0]}")


def check_pickup_vs_done(ledger_lines: list[str], pickup_lines: list[str], out: list[str]) -> None:
    """B: an actionable pickup bullet whose slug/id sits in a DONE ledger row."""
    done_rows = [ln for ln in ledger_lines if CLOSEDISH.search(ln)]
    for ln in pickup_lines:
        stripped = ln.strip()
        if not stripped.startswith("-") or CLOSEDISH.search(ln):
            continue  # only un-struck, still-actionable bullets
        slugs = {s for s in SLUG.findall(ln.lower())
                 if len(s) >= MIN_SLUG_LEN and s not in SLUG_STOPLIST}
        ids = set(ITEM_ID.findall(ln))
        for key in sorted(slugs | ids):
            if key in ids:
                # an item id only counts when it is the DONE row's OWN id — a mere mention
                # inside another row's cell (G11 inside G1's prose) is noise, and noise here
                # could talk a routine out of a genuinely open owner-gated item
                hits = [d for d in done_rows if row_id_of(d) == key]
            else:
                hits = [d for d in done_rows if key in d.lower()]
            if hits:
                out.append(f"REVIEW[B] pickup bullet mentions '{key}' which appears in a DONE ledger row:")
                out.append(f"    pickup -> {stripped[:150]}")
                out.append(f"    done   -> {hits[0].strip()[:150]}")
                break  # one hit per bullet is enough for review


def check_dangling_promotions(ledger_text: str, findings_files: list[str], out: list[str]) -> None:
    """C: a findings doc says an item was promoted, but the ledger never gained it."""
    for path in findings_files:
        for ln in read(path):
            if not PROMOTE.search(ln):
                continue
            for iid in set(ITEM_ID.findall(ln)):
                if iid not in ledger_text:
                    out.append(f"REVIEW[C] {os.path.basename(path)} claims a promotion of {iid} "
                               f"but the ledger never mentions it:")
                    out.append(f"    {ln.strip()[:170]}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ledger", default=LEDGER_DEFAULT)
    ap.add_argument("--pickup", default=None, help="pickup sheet (default: newest by name)")
    ap.add_argument("--findings", type=int, default=5, help="how many recent findings docs to scan")
    args = ap.parse_args()

    ledger_lines = read(args.ledger)
    ledger_text = "\n".join(ledger_lines)
    pickup_paths = [args.pickup] if args.pickup else newest("docs/signal-analysis/*next-session-pickup.md")
    pickup_lines: list[str] = []
    for p in pickup_paths:
        pickup_lines += read(p)
    findings_files = newest("docs/signal-analysis/*session-findings.md", args.findings)

    out: list[str] = []
    check_task_ids(ledger_lines, pickup_lines, out)
    check_pickup_vs_done(ledger_lines, pickup_lines, out)
    check_dangling_promotions(ledger_text, findings_files, out)

    print(f"ledger-consistency-check: ledger={args.ledger} pickup={','.join(pickup_paths) or '-'} "
          f"findings={len(findings_files)}")
    if out:
        print(f"{sum(1 for l in out if l.startswith('REVIEW'))} item(s) need review:")
        print("\n".join(out))
    else:
        print("no contradictions found")
    return 0  # an aid, not a gate


if __name__ == "__main__":
    sys.exit(main())
