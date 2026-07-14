---
name: codex-build
description: Delegate a planned build to Codex CLI in an isolated worktree, with our receipt contract baked into the prompt and a persistent thread for multi-phase plans
---

# Codex Build

Delegate implementation of a plan/brief to Codex CLI — the skill-based replacement for hand-typing a
`codex exec` brief each run. Codex edits the working tree, runs the touched module's lint/build, and
returns a **receipt** (files + labeled claims + mandatory open-doubts). One persistent thread per
target, so multi-phase plans delegate phase-by-phase with context retained.

**Delegation contract (unchanged):** Codex never commits, pushes, versions, touches `.env`/secrets,
or edits applied migrations. The Architect owns self-review, fixes, the testing gate, merge, and
deploy. This skill is the executable form of the `codex-builder-lane` memory.

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

4. **Parse the trailing tag** of the receipt:
   - `IMPLEMENTATION_COMPLETE` → hand back to the Architect: audit the RECEIPT against the real diff
     (read the diff, spot-rerun tests, verify citations), then testing gate → `codex-code-review` →
     merge/deploy. Fixes are the Architect's job — do NOT ping-pong fixes to Codex.
   - `IMPLEMENTATION_PARTIAL` → read the receipt; resume for the remainder, or finish small leftovers
     during self-review.

5. **Reset** / **Show** via the shared scripts.

## Notes

- `--bypass` = `--dangerously-bypass-approvals-and-sandbox` — Codex runs unsandboxed (needs maven +
  node). It still must not commit/push/deploy per the contract; the Architect keeps those.
- Model/effort from `_common.sh` (build → gpt-5.6-sol, xhigh); override per run via `CODEX_MODEL` /
  `CODEX_EFFORT`. Reviews run a SEPARATE thread (writer ≠ reviewer preserved).
- A fresh worktree may not be a trusted Codex project dir; if `--cd` resets to the main repo, add the
  worktree to trust or run from inside it. Verify on first use (see the spike plan Phase 5).
- Audit depth is tiered by risk (CLAUDE.md delegation model): docs/mechanical = diff read;
  engine/money/parity = full verify-ladder rerun by the Architect, never delegated.
