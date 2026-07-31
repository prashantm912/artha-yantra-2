# AGENTS.md — Codex builder guidance for ArthaYantra

**Read `CLAUDE.md` in this directory FIRST — it is the full project authority**
(build/test traps, DB/migration rules, parity doctrine, compose rules, git rules).
Everything there applies to you with the substitutions and constraints below. This file is
deliberately thin: `CLAUDE.md` is the single source of truth; do not duplicate it here.
The invariants a REVIEWER will judge your diff against are `.claude/skills/codex/checklist.md`
— self-check against it before calling a build done. Model routing + fallbacks:
`.claude/skills/codex/ROUTING.md`. (`CODEX_SETUP.md` is the HISTORICAL 2026-07-13 spike record —
where it disagrees with this file, ROUTING.md, or the skills, they win.)

## Substitutions (CLAUDE.md is written for Claude Code — adjust these)

- In the "Delegation model" section, ignore the TOOL mechanics (Agent tool / SendMessage /
  model tables — Claude-only machinery), but the **receipt contract there binds you**
  (ROUTING.md says so explicitly): claims carry EVIDENCE (file:line / SQL+result / log line),
  each labeled computed | sourced | recalled | assumed, and a `recalled` label on a
  load-bearing claim belongs in open-doubts, not the claims list.
- Ignore the `guard-paths.py` PreToolUse-hook paragraph (a Claude Code hook that does not
  run for you). Cwd rule: when your session runs in a WORKTREE, every build/test command runs
  in a subshell pinned there — `(cd <worktree> && ./mvnw.cmd …)` — NEVER a bare command that
  inherits a drifted cwd: that builds the MAIN checkout and reports BUILD SUCCESS for code you
  did not write (cost a false gate 2026-07-26, CLAUDE.md "persisted cwd" trap).
- Skills live in `.claude/skills/` — readable runbooks (start with `fable-method` for the
  working method; `write-tests` for the test harness rules; plus `build-service`,
  `new-migration`, `ship-a-change`, `adversarial-review`, `hotfix`, …). You may READ them for
  procedure; you cannot "invoke" them as tools.
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
- Migrations: only the number your brief reserves; a NEW file, never an in-place edit. If the
  brief reserves none, take the NEXT FREE number in that schema's lineage on current `origin/main`
  and say so in the receipt — numbers deploy in version order; a collision strands every later
  migration (renumber the stranded one HIGHER, never `outOfOrder=true`).
- Run the full verify ladder your brief specifies (builds always full-reactor `-am`;
  tests named `*Test`/`*IntegrationTest` — `*IT` is silently skipped; engine changes need
  byte-identical goldens via `GoldenDeterminismTest` + `BacktestParityTest`).
- End every task by writing the receipt file your brief names
  (`docs/handoffs/<date>-<slug>-receipt.md`) — diff summary + PR URL, real test output,
  claims labeled computed | sourced | recalled | assumed, and a mandatory **open-doubts**
  section — then open the PR with `gh pr create` and **leave it OPEN** (the Architect
  merges). The PR body MUST include the line
  `Cross-vendor review: PENDING (Architect runs the opposite-vendor round before merge)` —
  the `ci-review-verdict` check reads the body and is EXPECTED to be red until the Architect
  resolves that line after the review round; a red `verdict` at open is normal, not your bug.
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
