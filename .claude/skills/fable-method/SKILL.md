---
name: fable-method
description: Use at the START of any non-trivial ArthaYantra task — build, fix, investigate, tune, audit, batch. The house working method for decomposing hard tasks, verifying your own work, and deciding what to do next. Follow it regardless of which model you are; this is how the repo's history was built.
---

# fable-method

How work gets done in this repo. CLAUDE.md says what not to break; this skill says how to
move. Three biases: **evidence over recall**, **verification over confidence**, **the
ledger over your own sense of "what's next"**.

## 0. Orient before acting (five minutes, every task)

1. **Memory first** — `MEMORY.md` index → open the 1–3 topic files matching the task.
   They are pre-paid tuition (each trap in them cost a real CI cycle or a live incident).
2. **The forward ledger** — `docs/superpowers/plans/2026-07-02-remaining-items.md` is the
   ONE queue of open work. Audit findings: `docs/audits/*-full-codebase-audit.md`
   (§12 findings, §13 fix-log). Never invent a backlog when one exists.
3. **Authority docs** — design set under `docs/design/`; master plan §17/§18 override
   §1–§16. For strategy work the doctrine doc (Minervini plan, Manas CONSOLIDATED,
   oipulse study) outranks your intuition about what a trading rule "should" do.
4. **Verify before building** — grep for an existing implementation BEFORE writing one.
   3 of 10 items in one 2026-07-04 batch were already built or moot. "Does this already
   exist?" is step one of every feature; "is this already fixed?" is step one of every bug.

## 1. Decompose

- Slice into **PR-sized units**: one concern, one branch, one squash-merge. A "batch" is
  several PRs, not one mega-PR. Every unit gets a written verify check before you start
  ("test X reproduces the bug, then passes" — not "fix the bug").
- **State assumptions out loud** in the first message. If a design forks, gather evidence
  from code/DB first (read the index, the join, the caller — e.g. the F2 pyramiding fork
  was settled by reading `uq_paper_positions_open` + the order→position close-join), then
  ask ONE sharp question with a recommendation. Never ask what the code can answer.
- Sequence **cheap-and-certain before expensive-and-uncertain**. Independent units may run
  as parallel worktree agents — but their branches base on spawn-time main: **rebase before
  push or the squash-merge reverts other work** (cost a real revert once).
- Hard bug? **Reproduce first** (failing test or a live query showing the wrong row), then
  fix, then watch the same probe go green.

## 2. Classify — the merge-policy tier decides everything downstream

| Tier | What falls in it | Action |
|---|---|---|
| **clean** | correctness, ops, tests, docs, default-OFF flags, tooling | build → PR → auto-merge on CI-green, deploy, live-verify |
| **HOLD** | anything changing an owner-facing number: exit doctrine, parity surfaces, backtest methodology, live P&L behaviour, gate thresholds | build fully + adversarial-review → PR → **leave OPEN** for the owner (precedent: #594) |
| **owner-gated** | doctrine/product decisions, arming a flag that changes live behaviour, spending money | don't start; present options + a recommendation, park it, take the next ledger item |

When unsure which tier, it's HOLD. Arming/un-arming an existing flag on the owner's
explicit ask is clean (see [arm-flag]).

## 3. Verify your own work — the ladder (skip no rung that applies)

1. **Build + tests with `-am`** ([build-service]) — a bare `-pl` run embeds stale libs and
   produces phantom failures ("unknown indicator" errors) or phantom passes.
2. **Goldens byte-identical** for any engine/replay/signal change — new fields ride the
   non-serialized side-channel (CLAUDE.md "Extend engine records parity-safely").
3. **Adversarial review** ([adversarial-review]) — mandatory for parity, money, exit
   doctrine, migrations; 2 reviewers minimum, more for keystones.
4. **CI green** — the fresh-stack 2-core runner exposes what local can't. Known-flaky:
   ci-e2e signals/ws-reconnect on non-signals changes is not a merge blocker.
5. **Deploy + live-verify** — merged ≠ done. Done = the change observed live: a log line,
   a DB row, an endpoint response ([live-verify]). The stale-jar trap means a deploy that
   "succeeded" can still run old code — verify behaviour, not exit codes.
6. **Scheduled future verification** when the proof arrives later — a 20:05 batch gets a
   20:22 durable check ([daily-ops]); never claim an outcome that hasn't happened yet.

**The surprising-result rule:** a number too good or too bad means suspect the harness
before the strategy. Armed-gate backtests showing ~0 trades = data artifact; derived-history
OI is muted by design; Minervini RS-CAGR "dropping" 43→34.6% was a bug-fix *correction*.
Cross-check determinism: an unchanged sim re-runs **to the decimal** — if the control
variant moved, the sim changed, not the market.

## 4. Decide what to do next

- **Ledger discipline**: mark the finished item DONE (PR# + SHA) in the remaining-items
  ledger BEFORE picking the next. Pick from the ledger, not from whim.
- **Closeout ritual** per shipped item: ledger → doc-of-record (dated file under
  `docs/strategies/` or `docs/signal-analysis/`) → memory topic file + `MEMORY.md` hook →
  `PHASE_GATES.md` currency when a frontier closes.
- **Out-of-scope findings** → a spawn_task chip with a self-contained prompt. Never
  scope-creep the current PR.
- **Blocked on the owner** → park with options + recommendation, take the next item.
- **End-of-turn check**: if your last paragraph is a plan or a promise, either do it now
  or schedule it durably. "I'll verify tomorrow" without a scheduled task is a dropped ball.

## 5. Honesty rules (non-negotiable)

- Failing tests are reported with output, not smoothed over. Skipped steps are named.
- Costs are recorded ("cost 2 CI cycles") — future sessions learn from them.
- Every number cites its source (backtest run date, DB query, PR). Backtest claims carry
  their caveats (survivorship, derived OI, capacity-bound slots) in the same sentence.
- Never print secrets: owner password, PHC hashes, Kite/Upstox tokens, `.env` contents.
