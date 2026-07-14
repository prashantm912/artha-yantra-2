# AGENTS.md — Codex builder guidance for ArthaYantra

**Read `CLAUDE.md` in this directory FIRST — it is the full project authority**
(build/test traps, DB/migration rules, parity doctrine, compose rules, git rules).
Everything there applies to you with the substitutions and constraints below. This file is
deliberately thin: `CLAUDE.md` is the single source of truth; do not duplicate it here.
Workflow context (roles, handoff protocol, receipt shape): `CODEX_SETUP.md`.

## Substitutions (CLAUDE.md is written for Claude Code — adjust these)

- Ignore the "Delegation model" section (Agent tool / Opus subagents / SendMessage —
  Claude-only machinery). YOU are the builder; just execute your brief.
- Ignore the `guard-paths.py` PreToolUse-hook paragraph (a Claude Code hook that does not
  run for you). But DO keep your shell cwd at the repo root for consistency.
- Skills live in `.claude/skills/` — readable runbooks (`build-service`, `new-migration`,
  `ship-a-change`, `adversarial-review`, …). You may READ them for procedure; you cannot
  "invoke" them as tools.
- The "Bash tool is bash, not PowerShell" note: applies to you too if running in WSL; on
  native Windows PowerShell 5.1, use PS-safe syntax (no `&&` chains, no here-strings into
  git).
- Claude's `.claude/settings.json` hooks (path guard, frontend formatter) do NOT run for
  you — on frontend changes, run `npm run lint` yourself. The plain git pre-commit hooks
  (gitleaks, checkstyle) DO bind your commits.

## Your role (builder) — hard boundaries

You execute ONE brief at a time from `docs/handoffs/<date>-<slug>-brief.md`.

- Branch from fresh `origin/main`: `feat/|fix/|chore/|docs/<slug>`. Conventional Commits,
  scope = service/lib name. One brief = one branch = one PR.
- **NEVER:** push to `main`, merge PRs, deploy or run `docker compose up`, edit `.env` or
  any secret, edit an APPLIED flyway migration, `docker kill`, force-push, edit
  `docs/superpowers/plans/2026-07-02-remaining-items.md` (the ledger), or write to the
  live database. Read-only DB queries for verification are allowed.
- Migrations: only the number your brief reserves; a NEW file, never an in-place edit.
- Run the full verify ladder your brief specifies (builds always full-reactor `-am`;
  tests named `*Test`/`*IntegrationTest` — `*IT` is silently skipped; engine changes need
  byte-identical goldens via `GoldenDeterminismTest` + `BacktestParityTest`).
- End every task by writing the receipt file your brief names
  (`docs/handoffs/<date>-<slug>-receipt.md`) — diff summary + PR URL, real test output,
  claims labeled computed | sourced | recalled | assumed, and a mandatory **open-doubts**
  section — then open the PR with `gh pr create` and **leave it OPEN** (the Architect
  merges).
- End commit messages with: `Co-Authored-By: OpenAI Codex <noreply@openai.com>`
- If a brief conflicts with `CLAUDE.md`, STOP and write the conflict into the receipt's
  open-doubts instead of picking silently. Two failures of the same approach = stop and
  doubt (two-strikes rule).

## Mode exception — edit-only sessions (the `codex-build` skill lane)

The rules above describe **ship mode** (the `docs/handoffs/` brief lane): you branch, commit,
push, open the PR, and write a receipt FILE. A session whose prompt declares **edit-only mode**
(launched via `.claude/skills/codex-build/`) replaces the ship steps: do NOT branch, commit,
push, open a PR, or write a receipt file — you edit the working tree only, and your **final
message IS the receipt** (same shape: labeled claims + mandatory open-doubts). Every NEVER rule
above still applies unchanged. The prompt states the mode; if it doesn't, assume ship mode.
