---
name: claude-review
description: The structured code-review round for non-trivial changes OUTSIDE the money/parity tiers — an Opus subagent on a fresh thread judging the diff against the shared checklist, returning APPROVED/REQUEST_CHANGES/NEEDS_REWORK and converging over rounds. Same-vendor, so run it with a distinct lens; money/parity/migration/live-engine take a rationed Codex slot instead.
---

# Claude Review

**The review round.** An Opus subagent on a FRESH thread reads the diff against
`.claude/skills/codex/checklist.md` and returns `APPROVED` / `REQUEST_CHANGES` / `NEEDS_REWORK`.
The Architect audits the receipt against the real artifact ON TOP — **this skill is the review, the
audit is the final gate, and they are never the same gate.**

## ⚠️ Read this before running it: are you even the right reviewer?

Codex was **rationed** on 2026-08-15 ($20/mo tier), not retired. Pick by TIER first
(`.claude/skills/codex/ROUTING.md`):

- **money · parity · exit doctrine · migrations · live engine** → **NOT this skill.** Those earn a
  rationed `codex-code-review` slot, PRE-merge. If no slot is free, HOLD the item
  (`Cross-vendor review: PENDING (awaiting rationed Codex slot)`); only after **two missed slots**
  does it fall back here, and then the degradation gets recorded.
- **everything else** → this skill.

For everything routed here the review is **same-vendor**, and that is weaker than the two-vendor gate
it replaces: the 2026-07-25 cross-vendor round caught a live Critical (`premium_pct` exits resolved
against the INDEX entry price) that CI structurally could not, and a later round caught a foreign
hunk the Architect had already read past in audit.

**Do not let "reviewed" imply what it used to.** The verdict line says so explicitly:
```
Cross-vendor review: SKIPPED (clean tier — same-vendor Opus review on a fresh thread, cross-vendor not spent)
```

**Buy back what diversity you can** — in order of value:
1. **Fresh thread, no exceptions.** The reviewer must not have seen the build conversation. On this
   path it is the ONLY structural separation between writer and reviewer.
2. **Give it a distinct LENS.** A generic "review this" from the same vendor that built it is the
   weakest possible round. When a change can fail in more than one way, run more than one reviewer:
   correctness · money-path · does-the-test-actually-detect · operational blast radius.
3. **Optionally seed it with local candidates** — `candgen.py` (q3.8, no-discard prompt) is a
   different model family and once surfaced a real defect at rank 1. It duplicates itself heavily
   and has found nothing a good builder had not already flagged. A confirmation net; **never a gate**.
   Seven local models scored 0/2 as reviewers — they generate, they do not judge. See `local-model`.

It still works, and well: a same-vendor fresh-thread round on PR #1376 (2026-08-15) found two
Majors — a pre-open reserve that was a start gate only, and a test that asserted a constructor
annotation rather than the behaviour it claimed to pin — none of which the build, its tests, or the
Architect's own reading had caught. Same-vendor is a real gate; it is just not the one it replaces.

## Run it (the Architect drives; the reviewer is a subagent)

1. **Spawn the reviewer** — Agent tool, `model: "opus"`, a read-only reviewer type
   (`general-purpose`, or `timescale-domain-reviewer` / `ui-a11y-reviewer` for those surfaces).
   Prompt it with:
   > Review the uncommitted change. Read the diff with `git -C <worktree> status -s` and
   > `git -C <worktree> diff HEAD` (omit `-C` for the main repo). Judge it ONLY against
   > `.claude/skills/codex/checklist.md` — the single source of truth for criteria, severity, and the
   > approval gate — plus `CLAUDE.md` conventions and any change-area memory traps pasted below. Cite
   > `file:line` for every finding; tag severity from the checklist; prefer one-line fixes. The testing
   > gate (lint/typecheck/affected tests) was run by the requester — summary below; if it failed, or new
   > logic has no tests and no rationale, return REQUEST_CHANGES.
   >
   > ⚠️ You are the SAME vendor as the builder, so the usual cross-vendor safety net is gone. Assume
   > you share its blind spots and compensate: for each new assertion ask "could this test pass
   > against the pre-fix code?", and for each claim in the receipt ask "what would falsify this?".
   > **Your lens for this review is: `<LENS>`.**
   >
   > End with exactly one tag on its own line: APPROVED / REQUEST_CHANGES / NEEDS_REWORK.
   > `$GATE_SUMMARY  <pasted traps>`

   Always begin the brief with a **STEP 0**: verify the brief's premise against the code first, and
   say so if it is wrong — that is a successful outcome, not a failure.

2. **Parse the trailing tag** of the subagent's final message:
   - `APPROVED` → hand back to the Architect audit. Promotion follows the normal tiered policy
     (Architect decides; owner approval for money/arming/HOLD) — this skill never merges.
   - `REQUEST_CHANGES` → surface verbatim; the Architect reads each `file:line`, fixes legitimate
     findings, pushes back on wrong ones; re-run the testing gate.
   - `NEEDS_REWORK` → surface before mass-editing (a firewall/boundary violation).

3. **Iterate to convergence (threaded).** Continue the SAME subagent with `SendMessage` (its context
   is retained): "Re-review after these fixes: <what changed, what you pushed back on>." Loop until
   `APPROVED`. Cap 5 rounds; surface leftovers to the Architect.

## Notes

- **Verify the reviewer's sharpest claim yourself.** It is same-vendor now; a confident finding is
  not evidence. On #1376 the Architect independently re-read the single line the top finding rested
  on before acting, and separately DOWNGRADED a Major after measuring the cold-start latency its
  severity assumed (6–13 s, not the minutes the reviewer supposed).
- `checklist.md` stays at `.claude/skills/codex/checklist.md` — the path is historical, the content
  is vendor-neutral and always was.
- **Availability:** Opus subagents have no external-vendor dependency, so this path is always up.
- Skip for trivial/docs-only changes (say so instead of spawning). Money/parity → pair with
  `adversarial-review` and the Architect's own Golden+Parity rerun.
