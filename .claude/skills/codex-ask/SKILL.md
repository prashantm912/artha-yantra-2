---
name: codex-ask
description: Grounded cross-vendor second opinion from Codex on any question — architecture, debugging, a conclusion to red-team — advisory, never gating
---

> # ⚠️ DO NOT SPEND A RATIONED SLOT ON AN ADVISORY ASK (owner, 2026-08-15)
> Under the **$20/month tier** the budget is reserved for tier-gated pre-merge review. `codex-ask` is
> advisory and never gating, so it is the first thing to cut. Use a fresh Opus subagent for a second
> opinion, or skip it. Full rules: `.claude/skills/codex/ROUTING.md`.

# Codex Ask

Free-form second opinion from Codex CLI on any matter — an architecture call, a debugging hypothesis,
a conclusion you are about to present. Codex answers from inside the repository (read-only), so its
opinion is grounded in the actual code, not an excerpt. Threaded per topic for multi-round discussion.
Pairs with `adversarial-review`.

**Advisory, not authoritative.** No verdict tags, nothing gated. Treat the reply as one colleague's
input: agreement is weak evidence; **disagreement is a strong signal** to surface to the owner.

State: `.claude/skills/codex-ask/state/<topic-label>.{thread,review.txt,events.ndjson}`. Shared scripts
in `.claude/skills/codex/scripts/`. Export first:

```bash
export STATE_DIR=".claude/skills/codex-ask/state"
```

## Arguments

- `<topic-label>` — short kebab-case label (the state key), e.g. `gateway-oom-fix`, `flaky-signals-spec`.
  Auto: start if no thread, resume if one exists.
- `<question>` — trailing text. Include your own draft position when you have one
  ("Here's my recommendation: … red-team it").
- `reset <topic-label>` / `show <topic-label>`.

## Execution

1. **Start**:
   ```bash
   bash .claude/skills/codex/scripts/start.sh \
       --prompt-file .claude/skills/codex-ask/prompts/ask.tpl \
       <topic-label> "<question — include your draft position and ask for disagreement>"
   ```
2. **Follow up** (counterpoint / new evidence, same thread):
   ```bash
   bash .claude/skills/codex/scripts/resume.sh \
       --prompt-file .claude/skills/codex-ask/prompts/followup.tpl \
       <topic-label> "<follow-up>"
   ```
3. **Reset** / **Show** via the shared scripts.

## When to use

- Second opinion on a design/architecture decision before it hardens.
- Root-cause help when genuinely stuck — fresh eyes, different blind spots.
- Red-team a memo or recommendation before presenting it to the owner.

## When NOT to use

- Questions that need the OWNER's preference/judgment — ask the owner, not Codex.
- Trivial lookups settled by reading the code yourself — every ask costs a Codex run.
- As a gate — never block or approve work on the answer; that's what `codex-plan-review` /
  `codex-code-review` (with verdict tags) are for.

## Notes

- `--sandbox read-only`. Model/effort from `_common.sh` (gpt-5.6-sol, xhigh); override per run.
- Surface Codex's answer to the owner verbatim when it disagrees with your position — the
  disagreement is the valuable output.
- **Model unavailable?** `.claude/skills/codex/ROUTING.md` — ask is never load-bearing: use an Opus
  subagent for the second opinion, or skip.
