# Spike Plan — Persistent-Thread Codex Review Harness

**Date:** 2026-07-14
**Status:** Phase 0 (feasibility) PASSED — Phases 1–4 not yet built
**Origin:** analysis of [TRIP-workflow](https://github.com/PiLastDigit/TRIP-workflow) vs our Architect + codex-builder-lane workflow

---

## Goal

Port TRIP-workflow's **threaded Codex code-review loop** (`start → REQUEST_CHANGES → fix → resume → APPROVED`)
into our workflow as an **addition** to `delegated-ship`, adapted to our git-worktree builder model. It is the
one concrete mechanic TRIP is ahead of us on; everything else in TRIP we either already do better (worktree
isolation, typed memory, parity firewall, domain reviewers, receipt/audit contract) or deliberately reject
(ARCHI.md as a 4th arch source, SemVer/changelog/tutorial ceremony, in-tree `workspace-write` sandbox).

## Why (impact vs how we work now)

- **Now:** every Codex review/build is a **stateless one-shot** per brief. A second review round re-briefs from
  scratch — re-pastes memory traps, re-primes context, burns tokens — and leaves no durable audit trail.
- **After:** a `thread_id` per target holds full context across rounds → cheaper per round, tighter
  convergence, durable `state/*.review.txt` audit trail, model/effort config in one file.
- **Unchanged:** parity firewall (Golden+Parity byte-identical), verify-ladder, domain reviewers, and the
  Architect's exclusive merge + deploy + ledger + memory authority. The harness runs the Codex review loop
  **before** the Architect audit, making that audit lighter — it does not replace any safety gate.
- **Blast radius:** new bash tooling wrapping a Codex CLI we already call. Nothing touches engine / money /
  live paths.

## Scope discipline

**In:** code-review loop only (highest value).
**Out (rejected in analysis):** ARCHI.md, the plan-review loop (possible later follow-up), SemVer/changelog
ceremony, `TRIP-compact`, replacing our memory system, the in-tree `workspace-write` sandbox.

---

## Phase 0 — Feasibility gate ✅ PASSED (2026-07-14)

TRIP assumes a POSIX sandbox + a Codex CLI with `exec resume`. We run Codex on **Windows via git-bash**. All
kill-criteria were verified live on this machine:

| Check | Result |
| --- | --- |
| `codex exec resume <id>` subcommand exists | ✅ codex-cli **0.144.3** |
| Resume **retains thread context** | ✅ after `resume`, Codex recalled the prior message's word (the entire payoff) |
| `--sandbox read-only` works on Windows | ✅ `start_rc=0`, no error — reviewer needs **no** `--dangerously-bypass` (safer than the builder lane) |
| `--json` emits `thread.started` + thread_id capture | ✅ captured `019f5ebc-…` via `jq` |
| `-o/--output-last-message <FILE>` writes final message | ✅ |
| `jq` / `realpath` / `readlink -f` in git-bash | ✅ jq 1.8.1, coreutils present |
| **Bonus:** native `codex exec review --base <branch> --uncommitted` | ✅ exists — a one-shot review subcommand (see Phase 2 note) |

**One nuance (Phase-1 detail, not a blocker):** `codex exec` logs *"Shell cwd was reset to repo root"* — a
config/guard resets its cwd. For reviewing a **worktree's** diff, pass `--cd <worktree>` (verify it sticks) or
fall back to TRIP's inline-diff mode (`DIFF="$(git diff --stat HEAD; echo ---; git diff HEAD)"` as extra
context).

---

## Phase 1 — Port the harness scripts (~2h)

Copy TRIP's five files into `.claude/skills/codex-review/scripts/` (`start.sh`, `resume.sh`, `reset.sh`,
`show.sh`, `_common.sh`). Adaptations:

- **Add `--cd <worktree>`** to `start.sh`/`resume.sh` (TRIP is single-tree; we review a worktree diff). New
  required arg. Verify the worktree cwd sticks despite the cwd-reset log; else use the inline-diff fallback.
- **Sandbox** = `--sandbox read-only` for the reviewer (Phase 0 proved it works — do **not** carry the
  builder lane's `--dangerously-bypass` into the reviewer).
- **`_common.sh` model defaults** → our `gpt-5.6-sol`, effort `xhigh`; keep `CODEX_MODEL`/`CODEX_EFFORT`
  per-run overrides. (`resume` does NOT accept `--sandbox`/`--color` — inherits from the start session; keep
  that quirk.)
- **`STATE_DIR`** keyed by absolute worktree path (`target_key` already sanitizes `/`→`__`, so parallel
  worktrees don't collide); park state under the scratchpad, **not committed**.

## Phase 2 — Prompts + our checklist (~2h, highest leverage)

- Port `start.tpl` / `resume.tpl` (severity tags + trailing `APPROVED` / `REQUEST_CHANGES` / `NEEDS_REWORK`
  convergence tag).
- **Author `checklist.md` encoding OUR load-bearing invariants** — this is where our review beats generic
  TRIP: parity firewall (Golden+Parity byte-identical), MapReturnRatchet (typed records, never
  `Map<String,Object>`), Modulith import cycles (signals ↛ notifier/paper), IST/UTC time-key traps, gateway
  `Path=` allowlist, migration checksum-lock, IntegrationTest naming. Prereqs block points at **CLAUDE.md +
  the change-area memory traps** (our ARCHI.md equivalent), not a new doc.
- **Evaluate native `codex exec review` vs the ported prompt harness.** Native `exec review --base main`
  produces review content with zero prompt-template maintenance, but is one-shot; TRIP's value is the
  resume-thread multi-round loop. Decide: (a) native `review` for round 1 + our resume loop for iteration, or
  (b) fully ported prompts. Likely (a) — less to maintain.

## Phase 3 — Thin skill + wire into delegated-ship (~1h)

- `.claude/skills/codex-review/SKILL.md`: wraps `start → parse trailing tag → address findings → resume`,
  cap 5 rounds, surface reviews verbatim.
- Hook: `delegated-ship` calls it **after a builder returns, before the Architect audit**. It does NOT
  replace the parity firewall / verify-ladder / merge+deploy authority — all remain Architect-run.

## Phase 4 — Validate on a real diff (~1h)

Run the full loop against one trivial recent change end-to-end. Confirm: convergence to `APPROVED`, state
files written, resume retains context, and **no collision when a second worktree reviews concurrently**.

---

## Risks (post Phase 0)

| # | Risk | Status / mitigation |
| --- | --- | --- |
| R1 | Codex lacks `exec resume` / `--sandbox` on Windows | **CLEARED in Phase 0** |
| R2 | State collisions across parallel worktrees | key by absolute worktree path; state in scratchpad |
| R3 | Worktree cwd reset → reviewer sees wrong diff | verify `--cd`; inline-diff fallback exists |
| R4 | Weak checklist → noisy reviews | encode real invariants; skip trivial/docs changes |
| R5 | Extra latency per build cycle | scope to non-trivial changes only |

## Effort

~1 dev-day for a builder (Phase 0 done). Ships as an addition, not a replacement. Most value is in the
Phase-2 checklist encoding our invariants.
