# Spike Plan — Codex Skill Suite (skill-based, templated builder lane)

**Date:** 2026-07-14
**Status:** ✅ **BUILT AND IN PRODUCTION USE — this plan is HISTORICAL, not forward work.**
(Was "Phase 0 PASSED — build phases not yet started"; that line went stale once the suite shipped.)
All five skills exist and are the live lane: `.claude/skills/codex{,-ask,-build,-code-review,-plan-review}`,
shared harness `.claude/skills/codex/scripts/`, `checklist.md`, `ROUTING.md`.
**The authority is now the SKILLS themselves + the `codex-builder-lane` memory topic — not this plan.**
Read this only for the original design rationale.
**Proven end-to-end 2026-07-16** on [#874](https://github.com/prashantm912/artha-yantra-2/pull/874)
(F10 engine cold-start self-heal): luna DRAFT → sol REFINE (self-caught 3 issues incl. its own
convergence bug, and ran its own adversarial sub-agent fan-out) → Opus cross-vendor `claude-review`
(**REQUEST_CHANGES twice — caught 2 blockers a green 785-test suite did not**) → Architect audit + fixes
→ merge → live drill. That run is the strongest evidence the lane works as designed; it also confirmed
the receipt contract's value (luna's thin receipt was caught by reading the real diff, per the
audit-the-artifact-not-the-receipt rule).
**Origin:** analysis of [TRIP-workflow](https://github.com/PiLastDigit/TRIP-workflow) vs our Architect +
codex-builder-lane workflow. Superseded the earlier review-only framing (owner asked to skillify the
*whole* Codex lane, not just review).

---

## Goal

Convert our **ad-hoc codex-builder-lane** (Architect hand-types a brief + `codex exec --dangerously-bypass
--cd <worktree> "<prompt>"` per run, re-remembering the receipt shape from memory) into a **skill-based,
templated Codex harness suite** — a shared script layer + four skills, each with prompt templates tailored to
our conventions. Ports TRIP's `codex-implement` / `codex-code-review` / `codex-plan-review` / `codex-ask`
pattern, adapted to our worktree model, delegation contract, and parity firewall.

## Why (impact vs how we work now)

| Aspect | Now (on-the-fly) | After (skill-based) |
| --- | --- | --- |
| Brief assembly | Architect hand-writes each brief, re-recalls the receipt shape + NEVER-list from memory | template auto-injects receipt contract (diff + tests + claims labeled computed/sourced/recalled/assumed + open-doubts) + parity NEVER-list |
| Multi-round | stateless — re-brief from scratch each round (re-primes context, re-pastes traps, burns tokens) | `thread_id` per target; `resume` retains full context (Phase 0 proven) |
| Config | model/flags scattered in each command | model/effort in one `_common.sh`; prompts are versioned `.tpl` (reviewable, improvable) |
| Audit | ephemeral | durable `state/*.review.txt` + `events.ndjson` per target |
| The lane's runbook | lives in the `codex-builder-lane` memory (Architect must recall it) | becomes **executable** — the skill IS the runbook |

**Unchanged:** parity firewall (Golden+Parity byte-identical), verify-ladder, domain reviewers, and the
Architect's exclusive merge + deploy + ledger + memory authority. Codex still never merges/deploys.
**Blast radius:** new bash tooling + templates wrapping a Codex CLI we already call. Nothing in engine / money
/ live paths.

---

## Phase 0 — Feasibility gate ✅ PASSED (2026-07-14, verified live on this Windows machine)

| Check | Result |
| --- | --- |
| `codex exec resume <id>` exists | ✅ codex-cli **0.144.3** |
| Resume **retains thread context** | ✅ recalled the prior message's word — the entire payoff |
| `--sandbox read-only` on Windows | ✅ `rc=0` — review/ask/plan-review need no `--dangerously-bypass` |
| `--json` `thread.started` capture + `-o` output | ✅ captured `019f5ebc-…`, `-o` wrote final message |
| `jq` 1.8.1 / `realpath` / `readlink -f` | ✅ present in git-bash |
| Bonus: native `codex exec review --base <branch> --uncommitted` | ✅ one-shot review subcommand (may supply code-review round-1 for free) |

**Nuance:** `codex exec` logs *"Shell cwd was reset to repo root"* — for a worktree diff, pass `--cd <worktree>`
(verify it sticks) or use TRIP's inline-diff fallback.

---

## Target layout

```
.claude/skills/
  codex/                      # SHARED harness (one copy)
    scripts/  _common.sh  start.sh  resume.sh  reset.sh  show.sh
    checklist.md              # OUR invariants — single source of truth for reviews
  codex-build/                # <- codex-implement (delegate a build to Codex in a worktree)
    SKILL.md  prompts/ build.tpl  continue.tpl
  codex-code-review/          # read-only threaded review of an uncommitted diff
    SKILL.md  prompts/ start.tpl  resume.tpl  synthesize.tpl
  codex-plan-review/          # read-only threaded review of a plan doc, pre-build
    SKILL.md  prompts/ start.tpl  resume.tpl
  codex-ask/                  # read-only advisory second opinion (no gate)
    SKILL.md  prompts/ ask.tpl  followup.tpl
```

State (`*.thread`, `*.review.txt`, `*.events.ndjson`) lives per-skill under a scratchpad `STATE_DIR`, keyed by
absolute worktree path (`target_key` sanitizes `/`→`__` → parallel worktrees never collide). Not committed.

---

## Phase 1 — Shared harness (~2h)

Port TRIP's 5 scripts into `.claude/skills/codex/scripts/`. Adaptations:
- **`--cd <worktree>`** on `start.sh`/`resume.sh` (TRIP is single-tree). Verify the worktree cwd sticks; else
  inline-diff fallback.
- **`_common.sh` model defaults** → build flow `gpt-5.6-luna`?/`gpt-5.6-sol` (decide), review/ask flows
  `gpt-5.6-sol`, effort `xhigh`; keep `CODEX_MODEL`/`CODEX_EFFORT` overrides. (`resume` rejects
  `--sandbox`/`--color` — inherits from the start session; preserve that.)
- **Sandbox per flow:** read-only for review/plan-review/ask (Phase 0 proven). Build flow — see Decision D2.

## Phase 2 — `codex-build` skill (~3h, the biggest change)

Replaces hand-written briefs. `build.tpl` encodes **our delegation contract**, not TRIP's generic one:
- **Receipt shape** (mandatory): diff, test output, claims each labeled **computed/sourced/recalled/assumed**,
  and an **open-doubts** section (our proven regression-catcher).
- **NEVER-list**: don't touch frozen `GoldenSignalsJson.write()`; live SignalEngine never builds `SignalEvent`;
  typed records not `Map<String,Object>` (MapReturnRatchet); no `avgEntryPrice` breakeven fallback; etc.
- **Codex does NOT commit / merge / deploy / touch `.env` / edit applied migrations** — Architect owns all of
  that (matches TRIP's implement contract AND our delegation model).
- **Trap-paste slot** `{{EXTRA_PROMPT}}`: the Architect pastes the change-area memory traps (subagents/Codex
  get CLAUDE.md but never the memory files).
- Threaded: multi-phase plans delegate phase-by-phase via `continue.tpl` (context retained).
- Trailing tag `IMPLEMENTATION_COMPLETE` / `IMPLEMENTATION_PARTIAL`.

## Phase 3 — `codex-code-review` + `checklist.md` (~2h, highest review leverage)

- `checklist.md` = **our load-bearing invariants** (where our review beats generic TRIP): parity firewall,
  MapReturnRatchet, Modulith cycles (signals ↛ notifier/paper), IST/UTC time-key traps, gateway `Path=`
  allowlist, migration checksum-lock, `*IntegrationTest` naming, no-money-fallback rules. Prereqs point at
  **CLAUDE.md + change-area memory**, not a new ARCHI.md.
- Port `start.tpl`/`resume.tpl`/`synthesize.tpl`, severity tags + `APPROVED`/`REQUEST_CHANGES`/`NEEDS_REWORK`.
- Evaluate native `codex exec review --base main` as the round-1 engine (less template upkeep) + our resume
  loop for iteration.
- Wire into `delegated-ship`: runs **after a builder returns, before the Architect audit** (makes the audit
  lighter; replaces no safety gate).

## Phase 4 — `codex-plan-review` + `codex-ask` (~1.5h)

- `codex-plan-review`: read-only threaded review of a plan doc **before** we build — catches plan flaws
  pre-implementation. Hook into `fable-method` / plan authoring.
- `codex-ask`: read-only advisory second opinion, threaded, **no gate** — "red-team this conclusion / debug
  hypothesis." Cheap. Surfaces disagreement (the valuable output) verbatim. Hook into `adversarial-review`.

## Phase 5 — Validate + migrate the memory (~1.5h)

- End-to-end on one small real change: `codex-build` → `codex-code-review` loop → Architect audit → PR, in a
  worktree, with a second concurrent worktree to prove no state collision.
- Fold the executable parts of the `codex-builder-lane` memory into the skills; leave the outcome/trap log in
  memory, repoint it at the skills.

---

## Design decisions (recommend, owner may steer)

- **D1 — Codex commits, or Architect commits?** *Now:* Codex pushes its own worktree branch (parallel
  builders). *TRIP:* Codex edits only; requester commits. **Recommend: support both** — default to
  Codex-pushes for parallel/autonomous runs (our scaling win), expose an Architect-commits mode for
  high-stakes (parity/money) changes where the Architect must audit before anything is committed.
- **D2 — Build-flow sandbox.** `--sandbox workspace-write` blocks network + writes outside the worktree; our
  Java/Node builds need `~/.m2` + offline `mvn` + node. **Recommend: keep `--dangerously-bypass` for
  `codex-build`** (builders need the toolchain), read-only for the three review/ask skills. Phase-1 spot-check:
  does `workspace-write` + `sandbox_permissions` allow `~/.m2`? If yes, prefer it (safer) — else bypass.
- **D3 — Build-flow model.** TRIP splits impl (Luna) vs review (Sol). We currently run Sol for builds.
  **Recommend: keep Sol for `codex-build`** unless a Luna trial shows better build quality; reviews stay Sol
  (writer≠reviewer is preserved because it's a different thread, and can be a different model per D3).

## Risks (post Phase 0)

| # | Risk | Status / mitigation |
| --- | --- | --- |
| R1 | Codex lacks `exec resume` / `--sandbox` on Windows | **CLEARED (Phase 0)** |
| R2 | State collisions across parallel worktrees | key by absolute worktree path; state in scratchpad |
| R3 | Worktree cwd reset → wrong diff | verify `--cd`; inline-diff fallback |
| R4 | `workspace-write` breaks Java/Node builds | D2 — keep bypass for build flow |
| R5 | Weak checklist → noisy reviews | encode real invariants; skip trivial/docs changes |
| R6 | Suite drifts from the `codex-builder-lane` memory | Phase 5 folds memory into the skills, repoints |

## Effort

~2–3 dev-days for a builder (Phase 0 done). Additive — the skills wrap existing behavior; the ad-hoc path keeps
working until the skills prove out. Most value: the `codex-build` template (receipt contract codified) + the
`checklist.md` (our invariants).
