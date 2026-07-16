# Handoffs — the Codex SHIP-mode brief ↔ receipt lane

**This directory is the lane, not an archive.** It is referenced by `CLAUDE.md`, `AGENTS.md` and the
`codex-build` / `codex-code-review` skills. It being empty of live files means no handoff is in
flight — that is the normal resting state, not a missing directory.

## What lands here

The **SHIP-mode** builder lane (D1): the Architect writes `<date>-<slug>-brief.md`, Codex builds,
commits, pushes, opens the PR, and writes `<date>-<slug>-receipt.md` back here (per `AGENTS.md`).

The other mode — **EDIT-ONLY** (`.claude/skills/codex-build`) — writes **no file here**: Codex edits
the worktree, its final message IS the receipt, and the Architect commits after audit. Most recent
work uses that mode, which is why this lane is quiet.

## Lifecycle

A brief/receipt pair is **completed** once its PR is merged. Completed pairs move to
[`archive/`](archive/) so the lane shows only what is in flight. Archived pairs are kept for
provenance — they are the durable record of what was asked, what was delivered, and what the builder
self-flagged in open-doubts.

**Archived 2026-07-16:** the 24 files (12 pairs) from the 2026-07-13/07-14 D4 waves — every
corresponding PR is merged (#814, #817, #820, #823, #826, #829, #832, #839, …).

## Authority

- Lane contract + the two builder modes: `CLAUDE.md` (Codex skill suite section) and `AGENTS.md`.
- Model routing / outage fallback: `.claude/skills/codex/ROUTING.md`.
- Invariants every build is judged against: `.claude/skills/codex/checklist.md`.
