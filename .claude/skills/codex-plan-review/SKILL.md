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

- `--sandbox read-only`. Safe to invoke autonomously.
- Model/effort from `_common.sh` (review → gpt-5.6-sol, xhigh); override via `CODEX_MODEL`/`CODEX_EFFORT`.
- Thread ids per-plan (absolute-path keyed) — concurrent reviews never collide.
- Skip for trivial plans (single-file, low-risk); run for anything touching engine/money/parity or a
  new module.
