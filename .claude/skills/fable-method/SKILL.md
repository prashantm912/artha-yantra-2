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
- Sequence **cheap-and-certain before expensive-and-uncertain** — EXCEPT when one unknown
  can invalidate downstream units (a design fork, an unverified "does the data even
  exist?"): spike the **load-bearing unknown first**, even when it is the expensive one.
  Three cheap units built on a wrong assumption are three reverts. Independent units may
  run as parallel worktree agents — but their branches base on spawn-time main: **rebase
  before push or the squash-merge reverts other work** (cost a real revert once).
- Hard bug? **Reproduce first** (failing test or a live query showing the wrong row), then
  fix, then watch the same probe go green.
- **Two-strikes rule:** an approach that fails twice the SAME way is exhausted — change
  strategy (different tool, different decomposition, different data source), never re-run
  with cosmetic variation. Three near-identical failing commands in a transcript is the
  tell you are already past the limit.

## 2. Classify — the merge-policy tier decides everything downstream

| Tier | What falls in it | Action |
|---|---|---|
| **clean** | correctness, ops, tests, docs, default-OFF flags, tooling | build → PR → auto-merge on CI-green, deploy, live-verify |
| **HOLD** | anything changing an owner-facing number: exit doctrine, parity surfaces, backtest methodology, live P&L behaviour, gate thresholds | build fully + adversarial-review → PR → **leave OPEN** for the owner (precedent: #594) |
| **owner-gated** | doctrine/product decisions, arming a flag that changes live behaviour, spending money | don't start; present options + a recommendation, park it, take the next ledger item |

When unsure which tier, it's HOLD. Arming/un-arming an existing flag on the owner's
explicit ask is clean (see [arm-flag]).

## 2a. Plan the item — but only if it earns a plan (owner revision 2026-07-25)

**Trigger (any one):** HOLD tier · a migration · a money/parity/exit-doctrine surface · >~3 files
or multi-PR. Below that bar a written plan costs more than the item — go straight to a
self-contained builder brief.

When triggered, the plan is written by a **Fable 5 subagent** (Agent tool, `model: "fable"`; the main
loop writes it itself on a capacity error — don't stall the item). The point is a *different
reasoning style applied before code exists*: decomposition, design forks resolved against real
code/DB evidence, and the "is this already built?" check that has killed whole items. A plan that
meets the bar then goes through a plan-review round — `codex-plan-review` if a rationed slot is free
(reviewing a PLAN is the cheapest leverage in the pipeline: one call, saves a whole build), else an
Opus subagent on a FRESH thread, which is same-vendor — give it a distinct lens and say so.

A plan is DONE when it names, per unit: the files/seams it touches, the verify check that must go
from red to green, the parity/golden exposure, the migration number if any, and the open questions
it could NOT settle from code. Anything the plan leaves open becomes the Architect's to settle
BEFORE briefing a builder — never handed down as an unresolved fork.

Routing for the stages after this one (planner / builder / reviewer + every fallback) lives in ONE
place: `.claude/skills/codex/ROUTING.md`. Never hardcode a model choice in a brief.

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

**The measurement rules (2026-08-15 — a whole day of wrong conclusions bought these):**

- ⚠️ **n=1 is not a measurement.** A single pass or a single fail tells you nothing about a
  non-deterministic system. Both directions bit in one day: one lucky run carried a capability claim
  into two merged ledger entries, and the ×5 rerun that corrected it was ITSELF wrong. Run it five
  times or do not state a rate.
- ⚠️ **Before recording a FAILURE, re-read your own prompt / query / filter.** If the answer you
  wanted requires something you never supplied, **the probe is broken, not the subject.** Measured:
  a model scored 0/5 for not flagging a row whose significance was nowhere in the data, under a
  prompt that also said "do not speculate"; given the rule it scores 5/5. Same family as
  `series='EQ'` hiding every BE-series symbol. **These fail in the ALARMING direction, which is why
  they survive review — nobody argues with a negative result.**
- ⚠️ **Write the rubric down BEFORE generating or querying anything**, and check the input supplies
  everything the rubric grades. A rubric you hold privately is not a test; it is a guess about what
  the subject will volunteer.
- ⚠️ **A confident finding from a reviewer is not evidence.** Verify its sharpest claim yourself.
  Measured the same day: an independent re-read confirmed one Major, while measuring the cold-start
  latency a second Major's severity assumed (6–13 s, not the minutes supposed) downgraded it.

**The surprising-result rule:** a number too good or too bad means suspect the harness
before the strategy. Armed-gate backtests showing ~0 trades = data artifact; derived-history
OI is muted by design; Minervini RS-CAGR "dropping" 43→34.6% was a bug-fix *correction*.
Cross-check determinism: an unchanged sim re-runs **to the decimal** — if the control
variant moved, the sim changed, not the market.

## 3b. Token discipline — the levers, and the one that back-fired

Context is the cost line, not turns. A long conversation is expensive because of what got PASTED
into it, not how many times you spoke. Ranked by measured value:

1. **Digest raw output before it enters context** — the single biggest lever. A docker-logs dump
   8,400 → 320 tokens, a CI failure log 6,800 → 218, ≈**96% reduction** via a local model
   ([local-model]). ⚠️ Then read the raw lines the summary points at; never cite the summary as
   evidence. Measured reliability is per-lane, not global: CI logs 5/5, psql 5/5, service logs 5/5
   on q3.8 but **3/5 on the 9b**.
2. **Never paste a raw log, dump, or file wholesale into your own context** when a citation does the
   job. `file:line`, a SQL query plus its result rows, one decisive log line. This is the same
   discipline the honesty rules already demand for claims — it just happens to be the cheapest one.
3. **Fan out reading to subagents.** A subagent's context is separate; only its conclusion returns.
   Use it whenever answering means sweeping many files ([Explore]) — you keep the finding, not the
   file dumps.
4. **Batch same-surface items into ONE plan/build/review/PR.** N items pay one review round instead
   of N. ⚠️ **Only on the SAME surface.** A mixed-surface batch makes the review round harder rather
   than cheaper, and a revert takes the innocent items with it. Risk-size the batch (novel / parity /
   money → small; mechanical → large) — the batching mechanics are in [delegated-ship].
5. **Don't re-derive what the conversation already established.** Re-reading a file you have already
   read, or re-litigating a decision the owner already made, is pure cost.

⚠️ **The lever that MEASURED NEGATIVE — read this before reaching for a cheap generator.** Local
code generation looked like an obvious saving and was not: brief ~500 tokens + reading the output
~700 + **two mandatory red-proof cycles ~600** ≈ **1,800 tokens for a test suite that caught neither
planted bug**, versus ~1,600 to write it correctly by hand. The generation was nearly free; the
**verification needed to trust it was not, and it cannot be skipped, because the failure mode is a
passing suite.**

**The general rule that falls out: a cheap producer plus a mandatory verifier is only cheap if the
verification is cheap.** Before adopting any "this will save tokens" step, price the verification it
forces, not the step itself. Where verification is genuinely free — running a generated SQL and
diffing its rows, or reading a commit message against a diff you were reading anyway — the saving is
real. Where it costs two Maven cycles, it is not.

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
- Label every material claim by how you know it: **computed** (derived this session),
  **sourced** (file:line / SQL+result / doc read now), **recalled** (memory/training,
  unverified here), **assumed** (filled in to proceed). Recalled + load-bearing → verify
  now or downgrade to a labeled guess. Contamination: a conclusion computed from an
  ASSUMED input stays ASSUMED — one arithmetic step never launders a guess into a fact.
- Never print secrets: owner password, PHC hashes, Kite/Upstox tokens, `.env` contents.

## 6. Report verdict-first

Owner-facing output (session analyses, backtest verdicts, run reports, PR bodies) opens
with the answer, not the journey:

1. **First sentence = the verdict** — the number, ship/no-ship, the recommendation — with
   its claim label attached ("holds — computed from paper_trades" beats "should hold").
   Cannot write that sentence? The work is not done — back to §1.
2. Then the 2–3 reasons that carried the weight. Considerations that did not move the
   verdict go last or nowhere; process narration is an appendix, not an opening.
3. **Weld the caveat to the verdict**: "safe to arm — AFTER the V031 probe lands" in ONE
   sentence, never a note three paragraphs down. Skimmed reports act on sentence one.
4. "It depends" must fork: "X if A, Y if B" names the deciding variable; "there are
   tradeoffs on both sides" is not a verdict.
