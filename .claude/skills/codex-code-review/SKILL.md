---
name: codex-code-review
description: Iterative cross-vendor Codex review of an uncommitted diff against our invariant checklist, with a persistent thread that converges across rounds
---

# Codex Code Review

Cross-vendor (GPT) review of uncommitted changes via Codex CLI, threaded so multi-round review
converges with retained context instead of re-briefing from scratch. **Use this for CLAUDE/OPUS-built
changes** (`ship-a-change`, Architect-direct, `delegated-ship`) — the opposite vendor. For
**Codex-built** changes use `claude-review` instead (the review router in ROUTING.md keeps the reviewer
the opposite vendor of the builder). Codex reads the diff
(`git status -s` / `git diff HEAD`) in a **read-only** sandbox and judges it against
`.claude/skills/codex/checklist.md` (our load-bearing invariants). Runs in `delegated-ship` AFTER a
builder returns and BEFORE the Architect audit — it makes the audit lighter, it does not replace the
parity ladder, verify-ladder, or the Architect's merge/deploy authority.

State: `.claude/skills/codex-code-review/state/<sanitized-target>.{thread,review.txt,events.ndjson}`.
Shared scripts live in `.claude/skills/codex/scripts/`. Always export the state dir first:

```bash
export STATE_DIR=".claude/skills/codex-code-review/state"
```

## Arguments

- `<target>` — auto: start if no thread, resume if one exists. A plan path
  (`docs/superpowers/plans/*.md`, `docs/handoffs/*.md`) or a free-form label for unplanned work.
- `<worktree>` — pass `--cd <worktree>` when the diff lives in a builder worktree (not the current
  repo). Omit to review the current repo's diff. If `--cd` hits an untrusted-dir reset, fall back to
  the inline-diff mode (Diff Visibility below).
- `reset <target>` / `show <target>` — drop state / display last review.

## Execution

1. **Parse** `$ARGUMENTS`: action (`reset`/`show`/auto) + target (+ optional worktree).

2. **Auto** — try start first (exit 2 = thread exists → resume):
   - **Start**: `bash .claude/skills/codex/scripts/start.sh --prompt-file .claude/skills/codex-code-review/prompts/start.tpl [--cd <worktree>] <target> "$GATE_SUMMARY  <pasted change-area memory traps>"`
   - **Resume**: `bash .claude/skills/codex/scripts/resume.sh --prompt-file .claude/skills/codex-code-review/prompts/resume.tpl --notes "Fixed X. Pushed back on Y because Z." [--cd <worktree>] <target> "$GATE_SUMMARY"`

   `$GATE_SUMMARY` = the requester's testing-gate line (`lint: clean | typecheck: clean | tests: N passed (M new)`).

3. **Reset**: `bash .claude/skills/codex/scripts/reset.sh [--cd <worktree>] <target>` · **Show**:
   `.../show.sh [--cd <worktree>] <target>`. **A worktree session is keyed by its `--cd`** — every
   state operation (resume, synthesize, reset, show) must pass the SAME `--cd` or it targets a
   different (usually nonexistent) session.

4. **Parse the trailing tag**:
   - `APPROVED` → synthesize (below), then hand back to the Architect audit.
   - `REQUEST_CHANGES` → surface verbatim; read each `file:line`, fix legitimate findings directly,
     push back on incorrect ones; re-run the testing gate; resume.
   - `NEEDS_REWORK` → surface to the requester before mass-editing (a firewall/boundary violation).

5. **Cap at 5 rounds** (or as directed). Surface remaining findings and let the Architect decide.

## Synthesize (after multi-round convergence)

Skip if it converged on turn 1 (the state file already holds the full review). Otherwise (pass the
same `--cd <worktree>` the session was started with, if any):

```bash
bash .claude/skills/codex/scripts/resume.sh \
    --prompt-file .claude/skills/codex-code-review/prompts/synthesize.tpl \
    [--cd <worktree>] <target> "Today's date is YYYY-MM-DD"
```

Outputs `PROMOTION_READY`. The consolidated review is the record the Architect audits against.

## Diff Visibility (inline fallback)

If `--cd` resets to the trusted repo (a fresh worktree isn't a trusted project dir), or `git diff`
returns nothing in the sandbox, pass the diff inline as extra context:
`DIFF="$(git -C <worktree> diff --stat HEAD; echo '---'; git -C <worktree> diff HEAD)"` and append it
to the start/resume prompt text.

## Notes

- `--sandbox read-only`. Safe to invoke autonomously (no writes, no commits).
- Model/effort from `.claude/skills/codex/scripts/_common.sh` (reviews → gpt-5.6-sol, effort xhigh);
  override per run via `CODEX_MODEL` / `CODEX_EFFORT`.
- Thread ids are per-target (absolute-path keyed) — concurrent worktree reviews never collide.
- Surface reviews verbatim; keep edits scoped to findings; reset the thread only if context is
  genuinely confused. The testing gate must be green before `APPROVED`.
- Skip for trivial/docs-only changes (state that instead of running a round).
- **Foreground-timeout trap:** a big-diff review at `xhigh` can exceed the Bash tool's 5-min
  foreground cap and get KILLED mid-exec (no thread written). Run reviews via `run_in_background`,
  and/or `CODEX_EFFORT=medium` for large diffs.
- **Killed-wrapper salvage:** if the wrapper dies AFTER codex ran (timeout, crash, script edited
  mid-run), the review/receipt (`-o`) and events file usually survived — salvage the thread id and
  write it back, then resume normally:
  `jq -r 'select(.type=="thread.started").thread_id' <events.ndjson> | head -1 > <key>.thread`
- **Never edit a harness script while a run is in flight** — bash reads scripts incrementally; an
  edit shifts offsets under the running interpreter and crashes it mid-run (hit live 2026-07-14).
- **Model unavailable?** `.claude/skills/codex/ROUTING.md`. At-capacity auto-retries the chain in
  the harness; codex down entirely → Opus subagent on a FRESH thread (writer≠reviewer holds via the
  separate thread; cross-vendor is lost — say so in the review record).

## Loop shape

```
turn 1: start.sh  -> REQUEST_CHANGES (Critical: A; Major: B C)
        fix A B C, re-run gate
turn 2: resume.sh -> REQUEST_CHANGES (A B addressed; Minor: C partial)
        fix C
turn 3: resume.sh -> APPROVED -> synthesize -> Architect audit -> merge
```
