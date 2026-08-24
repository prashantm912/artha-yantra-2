---
name: codex-build
description: Delegate a planned build to Codex CLI in an isolated worktree, with our receipt contract baked into the prompt and a persistent thread for multi-phase plans
---

> # ⚠️ DO NOT SPEND A SLOT ON BUILDING (owner, 2026-08-15)
> Under the **$20/month tier** the scarce Codex budget is reserved for **review**, where a second
> vendor is irreplaceable. Building is not — an Opus subagent via `delegated-ship` builds to the same
> receipt contract at no Codex cost. **Route builds there.** Full rules:
> `.claude/skills/codex/ROUTING.md`.

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

   **…and parse the receipt's FIRST line — the BRIEF verdict (task_587984d1).** `build.tpl` STEP 0
   makes the builder verify the brief's factual claims against the CURRENT tree before writing code:
   - `BRIEF-CONFIRMED` → proceed.
   - `BRIEF-CORRECTED` → **read the corrections.** The builder implemented against the CODE, not your
     brief. Fold them back into the chip/ledger row so the next reader doesn't inherit the same wrong
     premise. This is the common case and it is a success.
   - `BRIEF-INVALID` → **STOP. Do not push the build through.** Re-file the chip with what is actually
     true (the `task_a86f2d17` → `task_9059519d` shape: mark the old row "DO NOT REBUILD FROM THIS ROW").
   - **Verdict missing** → the receipt is incomplete; resume the thread and ask for it. Do not proceed
     on an unverified brief.

   **Why this gate exists and why it cannot be moved later:** every other gate in the lane — sol,
   `claude-review`, your own audit — judges **CODE against BRIEF**. Nothing else judges **BRIEF against
   CODE**. So a factually wrong brief produces well-tested, green-on-every-gate work on a false
   premise, and no downstream reviewer can catch it *by construction*, because the code genuinely does
   match the brief. Measured on the 2026-07-17 first full pipeline run: **4 of 5 briefs were wrong**
   (chips filed weeks earlier; the code moved underneath them), and `task_a86f2d17` burned a whole
   build before review killed its premise. Verifying chips during recon is a HABIT — habits get skipped
   under time pressure and on autonomous runs, i.e. exactly when nothing else is watching.

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

## Batched delegation (large multi-part plans)

A one-shot delegation of a large plan produces a diff too big to review well, and lets luna drift for
many checkboxes before you catch it. For a plan of **more than ~4 checkboxes (or a diff likely >~300
lines)**, delegate it in **batches** on the SAME persistent thread — implement a few checkboxes, review
the delta, feed your corrections forward, then request the next. Context + conventions compound across
turns; the sol refine, cross-vendor review, and Architect audit still run **ONCE at the very end**,
never per batch. (Adapted from `PiLastDigit/TRIP-workflow`'s batched codex-implement, 2026-07-15.)

**Small plans (≤3–4 low-risk checkboxes) skip this — one-shot them via the Execution flow above.**

### 1. Split the plan into batches
- A batch is the **smallest set of checkboxes that leaves the tree green** (compiles + lints). Never
  split an interface from its implementation + wiring; never span a phase boundary.
- Target a reviewable diff — roughly **≤300 changed lines**. A checkbox that alone exceeds this is its own batch.
- **Size by risk:** novel / architectural / parity / money / security work → small batches (down to one
  checkbox); mechanical, repetitive work → larger batches.
- **Filter out non-Codex items FIRST** — checkboxes needing owner input, numbers, credentials, arming,
  or ops actions are YOURS; resolve them with the owner before/between batches, never delegate them.

### 2. Delegate batch by batch (one luna thread)
Start the first batch with `build.tpl` (Execution step 2), scoping the trailing text to it:
`"Implement only: <batch-1 checkboxes>.  <pasted memory traps>"`. Each next batch **resumes the same
thread** (`continue.tpl`) carrying your review corrections as `--notes` (now wired into the prompt as a
binding-conventions block):
```bash
bash .claude/skills/codex/scripts/resume.sh \
    --prompt-file .claude/skills/codex-build/prompts/continue.tpl \
    --notes "<what you fixed after the last batch + why; conventions binding from now on>" \
    --cd <worktree> \
    <target> "Now implement only: <next batch checkboxes>"
```
Parse the DRAFT tag as usual (`IMPLEMENTATION_PARTIAL` → resume for the remainder first).

### 3. Review each batch's DELTA (git-index checkpoint)
Between batches — this is where batching pays off:
1. **Review the delta only:** in the worktree, `git status -s && git diff` shows *just this batch*
   because prior reviewed batches are staged. Judge it against the plan, the parity firewall, Modulith
   boundaries, and the change-area conventions.
2. **Fix problems directly yourself** — do NOT ping-pong fixes back to luna. What you fixed + why
   becomes the `--notes` of the next resume (binding for the rest of the thread).
3. **Micro-gate:** run only the touched module's lint + typecheck/build (the full testing gate waits
   for the end). Fix failures now.
4. **Checkpoint:** `git add -A` in the worktree — stages the reviewed batch so the next `git diff`
   starts clean. NO commits (the Architect still owns the single commit at the end).
5. Verify the checkboxes luna ticked match what the diff actually contains.

**Adapt as you go:** clean batch → grow the next; heavy corrections → shrink the next + spell out the
fix pattern in the notes. If luna ignores notes or repeats a corrected mistake late in a long thread,
`reset` at the next batch boundary — the plan + a summary note rebuilds context.

### 4. Final pass, then the normal gates ONCE
After the last batch, read the **full feature diff** (`git diff HEAD` in the worktree) to catch
cross-batch drift — duplicated helpers, divergent naming, dead code from course corrections; fix
directly. Then run the pipeline's end exactly as one-shot mode: **sol refine (once, step 5) → hand to
the Architect → testing gate → `claude-review` cross-vendor → receipt audit → tiered promotion.** The
between-batch reviews replace none of these — they just make the diff that reaches them clean. Our
cross-vendor final gate is the edge TRIP's Codex-only lane lacks; keep it.

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
