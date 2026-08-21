# Model routing — single source of truth

Who runs on what, and what to do when a model is unavailable. The skills reference THIS table;
never hardcode a fallback decision inline in a brief or an ad-hoc command.

## ⚠️ CODEX IS RATIONED, NOT RETIRED (owner decision, 2026-08-15)

The owner is moving to the **$20/month tier** (down from $100). Codex is no longer the default
reviewer for everything — it is a **scarce, budget-gated resource spent only where a second vendor
has demonstrably earned its keep**. The `codex-*` skills stay enabled; what changes is *when* you
are allowed to spend a slot.

### The ration rule

| Change class | Reviewer | Waits for a slot? |
| --- | --- | --- |
| **money · parity · exit doctrine · migrations · live engine** | **Codex, PRE-MERGE** (`codex-code-review`) | **YES — hold the item** |
| a plan meeting the size gate (HOLD / migration / money / >~3 files) | **Codex** (`codex-plan-review`) — cheapest leverage in the pipeline | yes, if a slot is free |
| everything else (clean tier, docs, mechanical, FE slices) | `claude-review` (Opus, fresh thread, distinct lens) | never |

**Pre-merge or not at all.** A Codex slot spent on already-merged code buys an *audit*, not a gate,
and the two defects that justify this whole mechanism were caught **before** merge (the 2026-07-25
`premium_pct` one-bar force-exit; Opus catching that sol's own fix would have re-issued the F10
outage). Never spend a scarce slot reviewing what already shipped — if you want a post-hoc sweep,
label it an audit so nobody mistakes it for a gate.

**Holding is now a schedule, not a stall.** The old rule — *prefer to hold a parity/money item
rather than ship it with both builder and reviewer degraded* — becomes practical under a cadence.
A money/parity item sits with `Cross-vendor review: PENDING (awaiting rationed Codex slot)` and a red
`verdict` check, which is the DESIGN, not a failure.

⚠️ **A hold has a deadline: two missed slots.** If an item waits through two intended slots, ship it
with a same-vendor round and record the degradation. The failure mode to avoid is the worst of both —
delayed shipping *and* no review — which is what happens when a queue has no expiry and nobody keeps it.

### Budget — MEASURE IT, do not assume it

⚠️ **Nobody knows what the $20 tier actually allows.** The plan above assumes roughly a handful of
real reviews per month; that is an assumption, not a measurement. **On the first review after the
2026-08-20 reset, record what it consumed against the allowance** and write the number into memory
topic `codex-builder-lane`. If the real budget turns out to be one or two reviews a month, the
"hold money items for the slot" rule becomes a throughput problem and the tier table above must
narrow — better to learn that before it is load-bearing.

### What this costs, stated plainly

Most changes now get a **same-vendor** review. That is weaker than what it replaced, and the loss
must be written into the verdict line rather than left to imply what it used to. Buy back what
diversity you can: fresh thread always, a DISTINCT lens per reviewer, more than one reviewer when a
change can fail in more than one way, and optionally seed the round with local-model candidates.

⚠️ **Local models do NOT restore it.** Seven models scored **0/2** as reviewers. A local model may
GENERATE candidates for a human-grade reviewer to judge; it may never be the second opinion.

## The table (owner revision 2026-08-15)

| Role | Primary | Fallback |
| --- | --- | --- |
| **Main loop (Architect)** | **Opus 5** — orchestrator + FINAL GATE; never builds substantive code | all skills are main-loop-model-agnostic; another model in the seat still owes every gate below |
| **Planner** | **Fable 5** — Agent tool, `model: "fable"` | **Opus 5** writes the plan itself on a capacity error (don't stall the item) |
| **Builder** | **Opus subagent** (`model: "opus"`, `isolation: "worktree"`) for parity / money / exit doctrine / migrations / the live engine | **Sonnet 5** subagent for MECHANICAL work only (docs, tests, renames, config, FE slices). Never degrade a money or parity path to Sonnet to save tokens |
| **Code review** | **money/parity/migration/live-engine → Codex** (`codex-code-review`, rationed slot, PRE-merge) · **everything else → Opus subagent, FRESH thread, distinct lens** | no slot free → hold the item, or after two missed slots ship same-vendor and record it |
| **Plan review** | **Codex** (`codex-plan-review`) if a slot is free — cheapest leverage | **Opus subagent, fresh thread** (same-vendor, record the loss) |
| **Advisory ask** | `codex-ask` — ⚠️ never spend a rationed slot on this | Opus subagent, or skip; ask is never load-bearing |
| **Candidate generation** (pre-review) | `qwen3.8:27b` local, `candgen.py` — see `local-model` skill | optional; skip it, never let it gate |
| **Log / CI / dump digestion** | `qwen3.5:9b` local (CI, psql) · `qwen3.8:27b` (service logs) | read the raw source yourself |
| **Recon / Explore** | Claude Explore agents | — |

**When does the Planner stage run?** Only for real items: **HOLD tier, migrations, money/parity
surfaces, or >~3 files / multi-PR.** A 1-file chip's plan costs more than the chip — go straight to
a self-contained brief. This is a size gate, not an optional step.

## Review router — writer ≠ reviewer, and buy back diversity where you can

Every **non-trivial** change gets a *structured* review loop; trivial/docs-only changes skip it (say
so instead of running a round).

**First decide WHICH reviewer** by the ration rule at the top: money / parity / exit doctrine /
migrations / live engine earn a Codex slot pre-merge; everything else gets `claude-review`. For the
same-vendor majority, the diversity has to come from somewhere else — three things, in order of value:

1. **Fresh thread, always.** The reviewer must not have seen the build conversation. This is the
   minimum and it is not optional — it is now the *only* structural separation left.
2. **Give the reviewer a distinct LENS**, not a generic "review this". When a change can fail in
   more than one way, run more than one reviewer with different lenses (correctness / money-path /
   does-the-test-actually-detect / operational-blast-radius). Perspective diversity is the closest
   available substitute for vendor diversity.
3. **Feed it candidates from the local generator** (`candgen.py`, q3.8, no-discard prompt). It is a
   different model family and it surfaced a real defect at rank 1 once. It also duplicates itself
   heavily and has found nothing a good builder had not already flagged — a confirmation net, not a
   discovery engine. **Never let it gate anything.**

| Change class (NOT who built it — the tier decides now) | Review with | Note |
| --- | --- | --- |
| money · parity · exit doctrine · migrations · live engine | **`codex-code-review`** (rationed slot, pre-merge) | genuine cross-vendor ✓ |
| clean tier, mechanical, FE slices, ops/alert code | **`claude-review`** (Opus, fresh thread, distinct lens) | same-vendor — record it |
| trivial / docs-only | none | say so instead of running a round |

Reviewers judge against `.claude/skills/codex/checklist.md` and emit
`APPROVED`/`REQUEST_CHANGES`/`NEEDS_REWORK`. The checklist stays here (path unchanged) so nothing
else has to move; it is vendor-neutral and always was.

**Verdict line, PR body.** The literal string `Cross-vendor review:` is the greppable anchor
`ci-review-verdict.yml` matches — keep it EXACTLY. Three honest forms now:
```
Cross-vendor review: APPROVED — gpt-5.6-sol (OpenAI)                      # a rationed slot was spent: genuine cross-vendor
Cross-vendor review: PENDING (awaiting rationed Codex slot)               # money/parity item on hold — red verdict is the DESIGN
Cross-vendor review: SKIPPED (clean tier — same-vendor Opus review on a fresh thread, cross-vendor not spent)
```
⚠️ Never write `APPROVED — <an Anthropic model> (Anthropic)` and let it read as a cross-vendor pass.

**Canonical order for any change (one sequence everywhere):** orient + classify tier → *(plan, if the
item meets the size gate)* → *(optional: local candidate pass)* → build → testing gate **run by the
Architect, not the builder** → review loop on a fresh thread (converge to `APPROVED`) → **Architect
receipt audit = the final gate** → tiered promotion (owner approval for money/arming/HOLD) → deploy +
live-verify → ledger.

**The review and the audit are two gates. Never merge them into one.** This mattered when every
reviewer was a second vendor; it matters MORE for the same-vendor majority. "The orchestrator reviews it
itself" is only ever *audit on top of a review round*. Proven again 2026-08-15: a same-vendor
fresh-thread round on PR #1376 found two Majors — a pre-open reserve that was a start gate only, and
a test asserting a constructor annotation rather than the behaviour — neither of which the build,
its tests, or the Architect's own reading had caught.

## Invariants that survive ANY routing

- The **receipt contract** (labeled claims + mandatory open-doubts) and the **audit** are
  model-independent — an Opus builder owes the same receipt Codex used to.
- **EVERY brief opens with STEP 0**: verify the premise before writing anything; reporting the
  premise is wrong is a SUCCESSFUL outcome. Proven again 2026-08-15 — a builder refuted the
  Architect's own proposed design (a door projection that could never fire, because a missed cron
  seeds nothing) and was right.
- **Writer ≠ reviewer** minimum = a different thread. A different VENDOR is now rationed to the
  tiers above; wherever it was not spent, say so in the review record rather than dropping it
  silently.
- The **Architect keeps merge/deploy/ledger/memory** no matter which model built or reviewed.
- Parity/money changes gate on a byte-identical Golden+Parity rerun by the Architect — never on any
  builder's or reviewer's say-so, and never on a local model's output at all.
- **Local models never merge, deploy, verdict, or decide.** See the `local-model` skill for the full
  gate list; the red-proof gate on any generated test is mandatory, because a local model has
  already produced a 4/4-GREEN suite that detected neither of two planted bugs.

## Spending a slot, and keeping the queue

1. **Classify the item's tier first** (the ration table at the top). Clean tier never queues.
2. If it earns a slot, open the PR with `Cross-vendor review: PENDING (awaiting rationed Codex slot)`
   — the red `verdict` check is the design while it waits.
3. Run `codex-code-review` (or `codex-plan-review`) through the harness — **never a hand-rolled
   `codex exec`**; the harness is where the sandbox decision lives (`start.sh` passes `--sandbox`
   explicitly, default read-only).
4. **Record what it cost** against the monthly allowance in memory topic `codex-builder-lane`. This
   is how the budget stops being an assumption.
5. Two missed slots → ship with a same-vendor round and record the degradation. Do not let the
   queue grow without expiry.

⚠️ **Never spend a slot on:** docs, mechanical work, an advisory `codex-ask`, or anything already
merged. The first three do not need a second vendor; the fourth is an audit wearing a gate's clothes.

## If Codex is unavailable mid-slot

Availability is detected at FAILURE time, never by a preflight probe — call it and fall back when it
errors.

1. **At capacity**: the harness auto-retries `$CODEX_MODEL` → `$CODEX_FALLBACK_MODELS` and echoes
   which model served. Transient — retry after 10–30 min.
2. **Quota exhausted** (the `$20` ceiling, or an `at capacity` that persists): this is the expected
   steady state near month end. Hold the item to the next cycle, or take the two-missed-slots exit.
3. **CLI down entirely** (missing, auth broken, hangs): same-vendor round now, record the loss.
4. **Died MID-review with output on disk**: a failed resume leaves the PREVIOUS round's
   `review.txt` in place, unchanged — reading it yields a complete, confident review **of the wrong
   revision**. Capture the file's mtime BEFORE the run and compare after; `PRE_MTIME == POST_MTIME`
   means no review happened, whatever the file contains. The wrapper exits 0 even when codex fails,
   so the exit code proves nothing either.

## Turning Codex fully off, or fully back on

Nothing is destructive. The harness scripts, `checklist.md` and the four `codex-*` skills are intact
and ENABLED — they simply must not be invoked outside the ration rule.

- **Fully off**: add a DISABLED banner to each `codex-*/SKILL.md` and route every tier to
  `claude-review`.
- **Back to unrationed** (a higher tier restored): delete the ration section above and revert this
  file to the 2026-07-25 revision — git history has it. The checklist and receipt contract never
  changed, so Codex slots straight back in as the opposite vendor on every change.
