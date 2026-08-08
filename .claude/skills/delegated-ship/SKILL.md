---
name: delegated-ship
description: Use when shipping queue items via Opus builder subagents under the owner's delegation standing rule — the full delegated lifecycle (self-contained brief → receipt audit → review scaling → fix-rounds via SendMessage → rebase/CI/merge → deploy+probe → ledger flip). Use for autonomous multi-item runs or any "delegate the build" task; ship-a-change stays the single-session variant.
---

# delegated-ship

The delegated variant of [ship-a-change], proven over the 21-item overnight run
2026-07-10/11 (#683–#718: zero reverts, zero red merges, 3 HIGH-class defects caught
pre-merge). The main loop NEVER builds substantive code — it briefs, audits, reviews,
fixes small, merges, deploys, verifies, records. Outcome log + trap details: memory
topic `opus-delegation-standard`.

**Model routing (owner revision 2026-07-25):** the main loop is **Opus 5** (orchestrator + final
gate). The builder below is **Codex first** (`codex-build`); this Agent-subagent path is the
fallback — **Sonnet 5** for mechanical work, **Opus** whenever the surface is parity / money / exit
doctrine / migrations / the live engine. Items meeting the plan bar (HOLD, migration, money/parity,
>~3 files) get a **Fable 5** plan first ([fable-method] §2a). Full table: `.claude/skills/codex/ROUTING.md`.

## 1. Brief the builder (Agent tool, `model:"opus"` or `"sonnet"` per ROUTING, `isolation:"worktree"`)

A brief is SELF-CONTAINED — the builder gets CLAUDE.md but never memory. Every brief
carries, in this order:
1. **Goal** + the source-doc § that is the spec ("read it FIRST; it is the spec").
2. **Verify-before-building**: confirm the gap still exists on main; STOP-and-report
   if already fixed (3 of 10 items in one past batch were already built).
3. **Design constraints** — decide forks YOURSELF where you can; say "design already
   decided — do not redesign" when you have.
4. **Pasted memory traps** relevant to the surfaces touched (test naming, `-am`,
   singleton DB, Modulith rules, goldens/side-channel, #653 knob names, UTC/IST…).
5. **Worktree hygiene**: ALL git inside the worktree (`git status -sb` check — two
   builders polluted the shared checkout); never pipe a git command whose failure
   must stop a chain; commit locally, NEVER push/PR.
6. **Required receipt shape**: branch+SHA, full diff, verify tails, claims WITH
   file:line evidence, call-site/coverage tables where relevant, and a mandatory
   **open-doubts** section (builders' self-flagged doubts caught real regressions
   repeatedly — the field earns its keep).

Parallelism: 2 builders max concurrently (audit bandwidth is the constraint), only
across DIFFERENT services; same-file items run sequentially (B6/B7 conflicted anyway —
resolve rebase conflicts as a field UNION when both sides are additive).

A builder that stops mid-verify ("waiting for the build result") resumes cleanly with
one SendMessage nudge: "check the output, finish, full receipt."

**Large plan? Batch it** (the Opus-subagent analog of `codex-build`'s batched mode). A single brief
for a >~4-checkbox plan returns a diff too big to audit well and lets the builder drift before you
catch it. Instead delegate a risk-sized batch (smallest green set; novel/parity/money → small,
mechanical → large), audit its delta, then SendMessage the next batch with what you fixed + why as
binding conventions — same builder, context compounds. The review scaling + Architect audit (§3–§4)
still run ONCE on the final feature diff, never per batch.

## 2. Audit the receipt (never trust the summary)

Depth tiered by risk — docs/mechanical = diff read; engine/money/parity = full ladder:
- `git show <sha>` the ACTUAL diff; confirm scope (`git diff main.. --stat`).
- Spot-rerun the new tests + one adjacent regression suite YOURSELF in the builder's
  worktree (cached-maven invocation per [build-service]).
- Verify 2–3 load-bearing citations against the code.
- Check the shared checkout for strays: `git status -sb` on the MAIN checkout after
  every worktree receipt; repoint with `git branch -f <branch> <sha>` if polluted.
- Read the open-doubts hardest — resolve each one yourself (a one-grep answer like
  "does the funnel sort by RS?" is YOURS to close, not to ship as a doubt).

## 3. Scale the review to the tier (proven lens counts)

| Change class | Reviewers |
|---|---|
| parity-adjacent / live-eval semantics | 4 lenses (parity/golden, IST-time, concurrency, blast-radius) |
| scoped HOLD (money/exit path) | 2 lenses (domain semantics, tx/concurrency) |
| plain migration | 1 × timescale-domain-reviewer |
| FE page/component | 1 × ui-a11y-reviewer |
| alert-only ops code, docs | 0 extra — main-loop audit suffices |

**Cross-vendor gate (review router, `.claude/skills/codex/ROUTING.md`):** the builder here is an Opus
subagent (Anthropic), so for every non-trivial change ALSO run `codex-code-review` (Codex = the opposite
vendor) — the tier lenses above are same-vendor (Opus) and do not satisfy the cross-vendor requirement on
their own. It slots into ROUTING's canonical order (testing gate → cross-vendor review → Architect audit
= final gate → tiered promotion); the receipt-vs-diff check in §2 is a fast sanity pass, the Architect's
audit is the final gate after review. Codex down → record the same-vendor loss and lean on the lenses +
audit. Trivial/docs → skip.

Reviewers are REFUTERS with one lens each, spawned in ONE message. HOLD reviews must
trace **operational loops** (who retries, what state is already committed, which cron
runs once) — line-diff review missed the A3 strandings; loop-tracing found them.
Verify every finding in code yourself before acting: prescribed-fix findings ≤ ~10
lines land directly (then RERUN the verify — a main-loop edit broke a build once);
bigger fixes go BACK TO THE SAME BUILDER via SendMessage with mechanism + prescribed
fix, and the second receipt gets the same audit.

## 4. Ship (the race-proof chain)

From the builder's worktree: `git fetch origin main && git rebase origin/main` —
UNPIPED, then `git status -sb` (a `## HEAD (no branch)` line = conflicted rebase;
resolve, `git rebase --continue`, rerun that service's tests). Push, `gh pr create`
with tier + review tally + test evidence.

**The PR body MUST carry the review verdict in this EXACT greppable shape** (task_07199525):
```
Cross-vendor review: APPROVED | REQUEST_CHANGES (resolved) | NEEDS_REWORK (resolved) | SKIPPED (<reason>)
```
plus the reviewer's vendor/model. `SKIPPED (<reason>)` is FIRST-CLASS — ROUTING.md legitimately
allows skipping trivial/docs, and *a rule that cannot express the legitimate case gets ignored in
the illegitimate one*. The verdict lives ONLY in `state/*.review.txt`, which is **gitignored** and
dies with the worktree — **the PR body is the only durable record**. Verified 2026-07-17: of three
PRs shipped the same night through this lane, one carried the verdict, one carried a finding but no
verdict, and one carried nothing — that review is permanently lost. Do NOT write a separate review
doc (the PR IS the record; CLAUDE.md forbids proactive doc files).

CI: `gh pr checks <n> --watch`. On an e2e fail, ALWAYS discriminate before acting:
```bash
gh run view <run-id> --log-failed | grep -oE "✘ +[0-9]+ tests/[a-z-]+\.spec\.ts:[0-9]+" | sort -u
```
Known pair = signals.spec.ts:38 + ws-reconnect.spec.ts:23. Then the reachability
test: can THIS diff touch the failing spec's surface? (Read the spec's flow if
unsure — the take-flow uses /signals/{id}/taken, and the flake fails PRE-take.)
Signals/WS-adjacent diff → rerun-to-green; unreachable diff → rerun, then INVESTIGATE —
do NOT bypass the gate (⚠️ corrected 2026-08-04: the retracted text prescribed exactly that,
and it survived #1294 four lines above the #1252 note below. The flake pair was FIXED in #903,
so a red `e2e` now means a real failure). A <60s e2e death = infra (read the log; one was a runner
Maven-fetch), not specs. ci-optimizer/ci-margin are NO LONGER path-filtered (#1252,
2026-08-03): `optimizer-lint-test` + `margin-lint-test` are REQUIRED contexts and DO
appear in `gh pr checks` — read the rollup, not `gh run list` (corrected 2026-08-04; the
old "absence ≠ skipped" advice sent check-hunts to the wrong command). CI's ruff can be
newer than local.

Merge: `gh pr merge --squash`, then **verify `git log origin/main -1` equals
the PR's mergeCommit BEFORE building** (⚠️ `--admin` was REMOVED here 2026-08-04: it bypasses **all nine** required contexts, and `lock_branch` — the reason it was ever needed — was set false 2026-07-26. CLAUDE.md has said "merge normally; reaching for `--admin` means something is actually wrong" since then, but this runbook kept prescribing it, and it loads into every delegated builder session.) — `merge && pull` races the remote (a stale
pull once deployed a migration-less "healthy" service).

## 5. Deploy + probe (per service batch, not per item)

Batch consecutive merges touching the same services into ONE deploy round. Follow
[ship-a-change] §5 for the compose invocation. Two hard adds:
- Migration in the batch → `up -d --force-recreate flyway-init` FIRST, then **DB-probe
  the new object** (`to_regclass` / information_schema) — healthy + "up to date" log
  prove nothing.
- Live-verify one behavioural artifact per item where cheap (a counter at 0.0 on
  /actuator/prometheus, an endpoint smoke via in-container python — slim images lack
  curl/wget-to-localhost on the wrong port; find the port via `docker inspect`).

## 6. Closeout per item, not per session

Ledger row flipped (PR#+SHA+one-line outcome) BEFORE picking the next item; docs-only
ledger PRs merge normally, `--squash`, no bypass (⚠️ corrected 2026-08-04: they used to be told
to merge past the e2e flakes freely; those flakes were fixed in #903 and the `lock_branch` that
forced bypassing was lifted 2026-07-26). Out-of-scope findings → spawn_task
chips with self-contained prompts. Memory topic append when a pattern/trap is new.
End of run: fix-log entries in the source audit docs + a state note in the queue
header so a NEW session starts accurate.
