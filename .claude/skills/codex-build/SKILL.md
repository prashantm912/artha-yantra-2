---
name: codex-build
description: Delegate a planned build to Codex CLI in an isolated worktree, with our receipt contract baked into the prompt and a persistent thread for multi-phase plans
---

# Codex Build

Delegate implementation of a plan/brief to Codex CLI — the skill-based replacement for hand-typing a
`codex exec` brief each run. Codex edits the working tree, runs the touched module's lint/build, and
returns a **receipt** (files + labeled claims + mandatory open-doubts). One persistent thread per
target, so multi-phase plans delegate phase-by-phase with context retained.

**Two builder modes exist (D1) — this skill is EDIT-ONLY mode:**
- **Ship mode** (the `docs/handoffs/` brief lane, per `AGENTS.md`): Codex branches, commits, pushes,
  opens the PR, writes a receipt file. Default for queue items / parallel autonomous runs.
- **Edit-only mode** (THIS skill): Codex edits the worktree only — no branch/commit/push/PR; the
  final message is the receipt. The prompt declares the mode (`AGENTS.md` has the matching
  exception, so Codex gets ONE consistent contract). Use for interactive phase-by-phase work and
  high-stakes (parity/money) changes where the Architect must audit before anything is committed.

In both modes Codex never merges, deploys, touches `.env`/secrets, or edits applied migrations.
The sol refine pass fixes issues in luna's draft (Codex-side); once the change is HANDED to the
Architect, the Architect owns fixes, the testing gate, commit (edit-only), merge, and deploy — no
ping-ponging fixes back to Codex after handoff. This skill is the executable form of the
`codex-builder-lane` memory.

State: `.claude/skills/codex-build/state/<sanitized-target>.{thread,review.txt,events.ndjson}`
(`review.txt` holds Codex's receipt). Shared scripts in `.claude/skills/codex/scripts/`. Export first:

```bash
export STATE_DIR=".claude/skills/codex-build/state"
```

## Arguments

- `<target>` — auto: start if no thread, resume if one exists. A plan/brief path or a free-form label.
- `--cd <worktree>` — the builder worktree (`git worktree add C:/Trading/codex-wt-<slug> -b feat/<slug> origin/main`).
  Build runs there, isolated from the main checkout and other parallel builders.
- Trailing instructions — scope control, e.g. `"Implement Phase 1 only"`.
- `reset <target>` / `show <target>`.

## Execution

1. **Prepare** a worktree off fresh `origin/main`; paste the change-area memory traps into the
   trailing text (Codex has `CLAUDE.md` but NOT the memory files).

2. **Start** (D2: build needs the full toolchain — `~/.m2` + node — so `--bypass`, not read-only):
   ```bash
   bash .claude/skills/codex/scripts/start.sh \
       --prompt-file .claude/skills/codex-build/prompts/build.tpl \
       --bypass --cd <worktree> \
       <target> "Implement Phase 1 only.  <pasted memory traps>"
   ```

3. **Resume** (next phase / more scope, same thread):
   ```bash
   bash .claude/skills/codex/scripts/resume.sh \
       --prompt-file .claude/skills/codex-build/prompts/continue.tpl \
       --cd <worktree> \
       <target> "Now implement Phase 2"
   ```

   The `start`/`resume` runs use gpt-5.6-luna (fast + cheap DRAFT — set by `_common.sh`).

4. **Parse the DRAFT's trailing tag:**
   - `IMPLEMENTATION_PARTIAL` → resume for the remainder before refining.
   - `IMPLEMENTATION_COMPLETE` → go to the sol refine pass (step 5).

5. **Sol review+fix pass (a more careful model refines luna's draft, still Codex-side).** A FRESH sol
   thread (`KEY_SUFFIX=_refine` gives the SAME `<target>` a distinct thread — pass the ORIGINAL target,
   NOT a suffixed one, so the prompt still resolves the plan) reads luna's uncommitted diff, fixes issues
   directly, and re-runs the touched module's lint/build. `CODEX_FALLBACK_MODELS=""` so an at-capacity
   sol does not pointlessly retry sol — the Architect finishes the fix instead:
   ```bash
   KEY_SUFFIX=_refine CODEX_MODEL=gpt-5.6-sol CODEX_FALLBACK_MODELS="" \
     bash .claude/skills/codex/scripts/start.sh \
       --prompt-file .claude/skills/codex-build/prompts/refine.tpl \
       --bypass --cd <worktree> \
       <target> "<the draft's report + change-area memory traps>"
   ```
   Parse the tag: `REFINE_COMPLETE` → step 6. `REFINE_BLOCKED` → surface to the Architect (a
   firewall/boundary problem needing redesign). This is the luna≠sol writer-vs-reviewer split — sol
   both reviews AND fixes here (it has write access), unlike the read-only `claude-review`/
   `codex-code-review` loops.

6. **Hand to the Architect** (unchanged — "rest same"), in the canonical order (ROUTING.md): the
   Architect runs the **testing gate → `claude-review`** (builder was Codex, so the cross-vendor review
   loop is Opus per the review router — the THIRD perspective after luna+sol) → **receipt audit = the
   final gate** (read the diff, spot-rerun tests, verify citations) → tiered promotion (Architect
   decides; owner approval for money/arming/HOLD; never an auto-merge). Post-handoff, fixes are the
   Architect's job — do NOT ping-pong fixes back to Codex.

7. **Reset** / **Show** via the shared scripts. `reset <target>` (pass the same `--cd`) clears the WHOLE
   pipeline for that target — both the luna draft thread and the sol `_refine` thread — so the next run
   starts clean.

## Notes

- `--bypass` = `--dangerously-bypass-approvals-and-sandbox` — Codex runs unsandboxed (needs maven +
  node). It still must not commit/push/deploy per the contract; the Architect keeps those.
- Model/effort from `_common.sh` (draft → gpt-5.6-luna, refine → gpt-5.6-sol, xhigh); override per run
  via `CODEX_MODEL` / `CODEX_EFFORT`. The refine runs a SEPARATE thread and a different model than the
  draft (luna ≠ sol) — writer ≠ reviewer preserved on both axes.
- A fresh worktree may not be a trusted Codex project dir; if `--cd` resets to the main repo, add the
  worktree to trust or run from inside it. Verify on first use (see the spike plan Phase 5).
- Audit depth is tiered by risk (CLAUDE.md delegation model): docs/mechanical = diff read;
  engine/money/parity = full verify-ladder rerun by the Architect, never delegated.

## Ops notes (builds run LONG)

- **Always launch via a background run** — a build easily exceeds the Bash tool's 5-min foreground
  cap; a killed wrapper strands the remote thread with no local `.thread` file.
- **Killed-wrapper salvage:** the receipt (`-o`) + events file usually survived — recover the
  thread and resume normally:
  `jq -r 'select(.type=="thread.started").thread_id' <events.ndjson> | head -1 > <key>.thread`
- **Never edit a harness script while a run is in flight** — bash reads scripts incrementally; the
  edit crashes the running interpreter mid-run (hit live 2026-07-14).

## Model unavailable?

Routing lives in `.claude/skills/codex/ROUTING.md` — follow it, don't improvise. Short form: an
at-capacity error auto-retries the OTHER tier inside the harness (draft luna → sol, review sol → luna;
refine has no auto-fallback); the WHOLE chain down →
retry later or go cross-vendor NOW (autonomous runs don't stall): Opus subagent, `model: "opus"`,
`isolation: "worktree"`, the SAME brief content + receipt contract. Codex died mid-build with files
on disk → salvage the worktree, the Architect finishes verify/commit personally (proven #817).
