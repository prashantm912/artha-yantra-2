---
name: claude-review
description: Structured cross-vendor review of Codex-built changes by an Opus subagent — the mirror of codex-code-review, same checklist + verdict tags + convergence loop, so the reviewer is always the OPPOSITE vendor from the builder
---

# Claude Review

The Anthropic-side reviewer, for when the **builder was Codex**. It gives Codex-built code the same
*structured* other-vendor review loop that Claude-built code gets from `codex-code-review` — closing the
"Codex reviews Codex" gap where the only cross-vendor eyes were the Architect's audit. An Opus subagent
reads the diff against the SAME `.claude/skills/codex/checklist.md` and returns the same
`APPROVED`/`REQUEST_CHANGES`/`NEEDS_REWORK` verdict.

**Routing (ROUTING.md, the router rule):** reviewer vendor = **opposite of the builder vendor**.
- Builder = **Codex** (`codex-build`) → review with **this skill** (Opus subagent).
- Builder = **Claude/Opus** (`ship-a-change`, Architect-direct, or `delegated-ship`'s Opus subagent) →
  review with **`codex-code-review`** (Codex).

The Architect (also Claude) still audits the receipt vs the real artifact ON TOP — this skill is the
structured review loop, the audit is the final gate.

## Run it (the Architect drives; the reviewer is a subagent)

1. **Spawn the reviewer** — Agent tool, `model: "opus"`, a read-only reviewer type
   (`general-purpose`, or `timescale-domain-reviewer` / `ui-a11y-reviewer` for those surfaces). It is a
   FRESH context that did not build the change, and a different vendor from Codex → genuine cross-vendor.
   Prompt it with:
   > Review the uncommitted change. Read the diff with `git -C <worktree> status -s` and
   > `git -C <worktree> diff HEAD` (omit `-C` for the main repo). Judge it ONLY against
   > `.claude/skills/codex/checklist.md` — the single source of truth for criteria, severity, and the
   > approval gate — plus `CLAUDE.md` conventions and any change-area memory traps pasted below. Cite
   > `file:line` for every finding; tag severity from the checklist; prefer one-line fixes. The testing
   > gate (lint/typecheck/affected tests) was run by the requester — summary below; if it failed, or new
   > logic has no tests and no rationale, return REQUEST_CHANGES. You are the OPPOSITE-vendor reviewer of
   > Codex-built code; find what a fresh Anthropic model would catch that the OpenAI builder + its own
   > review thread might share a blind spot on. End with exactly one tag on its own line:
   > APPROVED / REQUEST_CHANGES / NEEDS_REWORK. `$GATE_SUMMARY  <pasted traps>`

2. **Parse the trailing tag** of the subagent's final message:
   - `APPROVED` → hand back to the Architect audit. Promotion follows the normal tiered policy
     (Architect decides; owner approval for money/arming/HOLD) — this skill never merges.
   - `REQUEST_CHANGES` → surface verbatim; the Architect reads each `file:line`, fixes legitimate
     findings, pushes back on wrong ones; re-run the testing gate.
   - `NEEDS_REWORK` → surface before mass-editing (a firewall/boundary violation).

3. **Iterate to convergence (threaded).** Continue the SAME subagent with `SendMessage` (its context is
   retained — the mirror of `codex exec resume`): "Re-review after these fixes: <notes on what changed,
   what you pushed back on>." Loop until `APPROVED`. Cap 5 rounds; surface leftovers to the Architect.

## Notes

- **checklist.md is shared** — both reviewers judge against the identical criteria, so a Codex review and
  a Claude review are comparable and the gate is consistent.
- **Cross-vendor is real here:** Opus reviewer (Anthropic) ≠ Codex builder (OpenAI). The Architect being
  Claude doesn't weaken it — the Architect didn't build the change; Codex did.
- **Availability:** Opus subagents have no OpenAI-capacity dependency, so this path is always up (it IS the
  fallback reviewer when Codex is down, per ROUTING.md).
- Skip for trivial/docs-only changes (say so instead of spawning). Money/parity → pair with
  `adversarial-review` and the Architect's own Golden+Parity rerun.
