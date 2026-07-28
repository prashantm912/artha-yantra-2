---
name: codex-plan-review
description: Iterative cross-vendor Codex review of an implementation plan BEFORE build, against our invariants, with a persistent thread
---

# Codex Plan Review

Second-opinion review of a plan/brief via Codex CLI, before implementation, so design flaws are caught
in the plan (cheapest) not the diff. Read-only. Threaded for multi-round convergence. Pairs with
`fable-method` plan authoring — run it after a plan is drafted, before delegating the build.

State: `.claude/skills/codex-plan-review/state/<sanitized-path>.{thread,review.txt,events.ndjson}`.
Shared scripts in `.claude/skills/codex/scripts/`. Export first:

```bash
export STATE_DIR=".claude/skills/codex-plan-review/state"
```

## Arguments

- `<plan-path>` — auto: start if no thread, resume if one exists. Trailing text is extra context.
- `reset <plan-path>` / `show <plan-path>`.

## Execution

1. **Start**:
   ```bash
   bash .claude/skills/codex/scripts/start.sh \
       --prompt-file .claude/skills/codex-plan-review/prompts/start.tpl \
       <plan-path> "<pasted change-area memory traps>"
   ```
2. **Resume** after revising the plan:
   ```bash
   bash .claude/skills/codex/scripts/resume.sh \
       --prompt-file .claude/skills/codex-plan-review/prompts/resume.tpl \
       --notes "Fixed X. Pushed back on Y because Z." <plan-path>
   ```
3. **Reset** / **Show** via the shared scripts.

4. **Parse the trailing tag**:
   - `APPROVED` → the plan is sound and invariant-safe; proceed to build.
   - `REQUEST_CHANGES` → surface verbatim; fix legitimate gaps in the plan, push back on incorrect
     ones; resume.
   - `NEEDS_REWORK` → surface to the Architect — the approach needs a redesign.

5. **Cap at 5 rounds.**

## Notes

- ⚠️ **Every state op must run from the SAME cwd as `start.sh` — in practice the repo root.**
  `STATE_DIR` is exported RELATIVE by these instructions, and `_common.sh` canonicalizes it with
  `cd "$STATE_DIR" && pwd` — so it resolves against the INVOKING directory. Call `resume`/
  `synthesize`/`reset`/`show` from a git worktree and it resolves to THAT worktree's own
  `.claude/skills/.../state`, which EXISTS (`.claude` is tracked) and is empty — reporting
  `no session for <target>` while the real `.thread` sits in the root's state dir, minutes old.
  Hit twice on 2026-07-28, ~20 minutes apart. **It is NOT the per-target key**: for a label target
  `target_key` hashes the label string, identical from any cwd — the DIRECTORY moved, not the key.
  Subshell the worktree step so the harness call itself stays at the root:
  `(cd <worktree> && git diff origin/main...HEAD > "$STATE_DIR/<key>.diff")`.
  Since 2026-07-28 the error names where it looked and, when it can find the session in the main
  checkout, says so outright — but the discipline above is what avoids the round trip.

- `--sandbox read-only`. Safe to invoke autonomously.
- Model/effort from `_common.sh` (review → gpt-5.6-sol, xhigh); override via `CODEX_MODEL`/`CODEX_EFFORT`.
- Thread ids per-plan (absolute-path keyed) — concurrent reviews never collide.
- Skip for trivial plans (single-file, low-risk); run for anything touching engine/money/parity or a
  new module.
- **Model unavailable?** `.claude/skills/codex/ROUTING.md` — at-capacity auto-retries the chain;
  codex down → Opus subagent fresh thread (cross-vendor lost, say so) or defer the review.
