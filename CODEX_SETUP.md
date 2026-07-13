# CODEX_SETUP.md — Claude (Architect) + OpenAI Codex (Builder/Executor)

**Status:** setup guide, written 2026-07-13 by Claude (Fable 5) at the owner's request.
**Scope:** how to wire the OpenAI Codex app/CLI into this repo so tasks are *initiated in
Claude* and *executed by Codex* (terminal, git, testing), with Claude keeping architecture,
review, merge, deploy, ledger, and memory.

> Honesty note up front: everything here about Codex flags/config keys is accurate as of
> Claude's knowledge cutoff (Jan 2026). Codex CLI moves fast — when a flag doesn't exist,
> trust `codex --help` over this file, and update this file.

---

## 1. Roles — who owns what

| Concern | Owner |
|---|---|
| Task selection (ledger §0), decomposition, design forks, tier classification (clean/HOLD/owner) | **Claude** |
| Writing the self-contained brief (goal, constraints, pasted memory traps, receipt shape) | **Claude** |
| Executing the build: branch, code, `mvnw`/`npm` builds, tests, commit, push, open PR | **Codex** |
| Receipt: diff + test output + labeled claims + open-doubts | **Codex** (produces) → **Claude** (audits) |
| Adversarial review on parity/money/migration surfaces | **Claude** (may use Codex as one extra independent reviewer lens) |
| Merge decisions, squash-merge, CI flake discrimination | **Claude** |
| Deploys, anything touching `.env`/secrets, flyway-init force + DB probes | **Claude** |
| Ledger flips, memory writes, owner communication | **Claude** |

This is deliberately the **same contract as the existing `delegated-ship` pipeline**
(CLAUDE.md "Delegation model") with Codex slotted into the *builder* seat. One brief/receipt
format for both builder kinds — Opus subagents and Codex — so audits are uniform.

**Terminology honesty:** the owner's framing is "Codex as Orchestrator." What works in
practice here is: Codex *executes* terminal/git/testing **per task**; **cross-task
orchestration** (sequencing the queue, auditing receipts, merging, deploying) stays with
Claude, because that's where this repo's proven guardrails live (skills, memory traps,
hooks, the verify ladder). Recommendation in §8.

---

## 2. Install Codex CLI (local — this is the right variant for this repo)

Why local CLI and not Codex Cloud: this stack is loopback-only (local Docker compose, live
TimescaleDB, Testcontainers ITs, gateway on localhost). Codex Cloud containers cannot reach
any of that, so cloud tasks could only do docs/pure-unit work. See §7C.

### Windows options (this machine is Windows 11 + Docker Desktop + PowerShell 5.1)

**Option A — WSL2 (officially recommended by OpenAI for Codex CLI):**
1. `wsl --install -d Ubuntu` (once), reboot if asked.
2. Docker Desktop → Settings → Resources → WSL integration → enable for Ubuntu
   (gives `docker` inside WSL — Testcontainers ITs work).
3. Inside WSL: install Node 22+ (`nvm install 22`), then `npm i -g @openai/codex`.
4. Work against the repo via `/mnt/c/Trading/ArthaYantra/artha-yantra-2`.
   - Caveat: `/mnt/c` file IO is slow for big Maven builds; acceptable, not great.
   - Do NOT make a second clone inside the WSL filesystem — two checkouts of a
     trunk-based repo invite divergence. One checkout, two shells.
5. Line endings: the repo pins `*.json eol=lf` in `.gitattributes`; WSL git respects it.
   Never re-normalize from WSL without checking `git status` first.

**Option B — native Windows (experimental but simpler; fine to start with):**
1. PowerShell: `npm i -g @openai/codex` (Node 22+ required).
2. Codex's sandboxing is weaker/experimental on native Windows — compensate by keeping
   approvals ON for anything outside the workspace (see config below).

Either way, verify:
```bash
codex --version
codex login            # ChatGPT-account sign-in (Plus/Pro/Team), or:
# setx OPENAI_API_KEY "..."   # API-key billing instead of ChatGPT plan
codex exec "reply with the single word ready"
```

---

## 3. Configure Codex for this repo

`~/.codex/config.toml` (create if absent). Suggested baseline:

```toml
# Model: leave default (Codex picks its current best coding model) or pin with -m per run.
approval_policy = "on-request"     # Codex asks before escalating outside the sandbox
sandbox_mode    = "workspace-write"

[sandbox_workspace_write]
network_access = true              # REQUIRED: Maven/npm dependency downloads, gh pushes
```

Notes that matter on this box:
- **Maven + AV TLS interception:** builds need
  `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (native Windows) — this is in
  CLAUDE.md/AGENTS.md; under WSL the AV interception generally doesn't apply, but the
  wrapper may re-download Maven the first time.
- **Codex reads `AGENTS.md` automatically** (repo root; also `~/.codex/AGENTS.md` global).
  That's the whole sharing mechanism — see §4.
- Codex CLI also supports per-run flags that override config:
  `codex exec --cd <dir> --sandbox workspace-write --full-auto "..."`.

---

## 4. Sharing CLAUDE.md with Codex — fix AGENTS.md (one source of truth)

Codex's convention file is **`AGENTS.md`** — the direct analogue of `CLAUDE.md`.

**Current state (found 2026-07-13): the existing root `AGENTS.md` is a corrupted mechanical
copy of CLAUDE.md.** A blind find/replace broke real paths — it says `.Codex/skills/` and
`tools/Codex/guard-paths.py` where the real paths are `.claude/skills/` and
`tools/claude/guard-paths.py` — and it retains Claude-only machinery (the Agent-tool
delegation section, PreToolUse hook trap) that either misleads Codex or tells it to use
tools it doesn't have.

**Fix: make `AGENTS.md` a thin pointer + Codex-role addendum.** Single source of truth
stays `CLAUDE.md` (no dual maintenance, no drift). Replace `AGENTS.md` wholesale with:

```markdown
# AGENTS.md — Codex builder guidance for ArthaYantra

**Read `CLAUDE.md` in this directory FIRST — it is the full project authority**
(build/test traps, DB/migration rules, parity doctrine, compose rules, git rules).
Everything there applies to you with these substitutions and constraints:

## Substitutions (CLAUDE.md is written for Claude Code — adjust these)
- Ignore the "Delegation model" section (Agent tool / Opus subagents / SendMessage —
  Claude-only machinery). YOU are the builder; just execute your brief.
- Ignore the `guard-paths.py` PreToolUse-hook paragraph (a Claude Code hook). But DO
  keep your shell cwd at the repo root for consistency.
- Skills live in `.claude/skills/` — they are readable runbooks (build-service,
  new-migration, ship-a-change). You may READ them for procedure; you cannot "invoke"
  them as tools.
- The "Bash tool is bash, not PowerShell" note: applies to you too if running in WSL;
  on native Windows PowerShell, use PS 5.1-safe syntax (no `&&` chains).

## Your role (builder) — hard boundaries
You execute ONE brief at a time from `docs/handoffs/<date>-<slug>-brief.md`.
- Branch from fresh `origin/main`: `feat/|fix/|chore/|docs/<slug>`. Conventional
  Commits, scope = service/lib name.
- NEVER: push to `main`, merge PRs, deploy/`docker compose up`, edit `.env` or any
  secret, edit an APPLIED flyway migration, `docker kill`, force-push, edit
  `docs/superpowers/plans/2026-07-02-remaining-items.md` (the ledger), or write to
  the live database. Read-only DB queries for verification are allowed.
- Migrations: only the number your brief reserves; new file, never an in-place edit.
- End every task by writing the receipt file your brief names
  (`docs/handoffs/<date>-<slug>-receipt.md`) and opening a PR with
  `gh pr create` (leave it OPEN — the Architect merges).
- End commit messages with: `Co-Authored-By: OpenAI Codex <noreply@openai.com>`
- If a brief conflicts with CLAUDE.md, STOP and write the conflict into the receipt's
  open-doubts instead of picking silently.
```

(Yes — commit that replacement via a normal `docs:` PR. It also fixes the broken paths.)

---

## 5. The handoff protocol (Claude → Codex)

Directory: **`docs/handoffs/`** (create on first use; gitignored? NO — commit briefs and
receipts; they are the audit trail, same spirit as the ledger).

- Brief: `docs/handoffs/YYYY-MM-DD-<slug>-brief.md` — written by Claude.
- Receipt: `docs/handoffs/YYYY-MM-DD-<slug>-receipt.md` — written by Codex.
- One brief = one branch = one PR (the repo's existing rule).

### Brief template (Claude fills this; paste-complete, no external context assumed)

```markdown
# Brief: <slug>
Date: YYYY-MM-DD · Architect: Claude · Builder: Codex
Ledger row: <row id / chip id> · Tier: clean | HOLD
Branch: feat/<slug> (from fresh origin/main @ <sha>)
Reserved migration number: <none | e.g. backtest V019 — use EXACTLY this>

## Goal (one paragraph)
<What must exist when done. The verifiable outcome, not the activity.>

## Scope — files in play
<explicit file list / packages; anything outside this list = stop and doubt>

## Constraints & memory traps (pasted, not referenced)
<Claude pastes the relevant CLAUDE.md/memory-trap bullets verbatim — e.g. -am builds,
*IT naming, goldens byte-identical, IST +05:30 filters, {items} envelope, typed records
never Map, eol=lf. Codex has AGENTS.md but NOT Claude's memory files.>

## Design decisions already made (do not relitigate)
<e.g. "UPPERCASE is canonical", "side-channel field, never serialized">

## Verify ladder (run ALL, paste outputs into receipt)
1. Build: MVN=$(ls ~/.m2/wrapper/dists/apache-maven-*/*/bin/mvn | head -1);
   MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" "$MVN" -pl services/<svc> -am test
2. <parity/golden commands if engine-adjacent: GoldenDeterminismTest, BacktestParityTest>
3. <frontend trio if FE touched: npm run lint && npm run test:ci && npm run build>
4. gh pr create --base main --head <branch> --title "<conventional title>" --body "<...>"
   — leave the PR OPEN.

## Receipt shape (mandatory sections)
- Diff summary (files + line counts) and the PR URL
- Test output (the actual `Tests run:` lines, not "tests pass")
- Claims WITH evidence (file:line / command+output), each labeled
  computed | sourced | recalled | assumed
- **Open-doubts** (mandatory, even if "none" — say why none)

## Stop conditions (write a doubt and halt instead of improvising)
- A test that fails for a reason outside Scope
- Any need to touch .env, deploy, the ledger, or an applied migration
- Two failures of the same approach (two-strikes rule)
```

### Receipt audit (Claude, before merging)
Same as the delegated-ship rule: audit the receipt **against the artifact** — read the
actual diff, spot-rerun at least one test, verify one citation. Depth tiered:
docs/mechanical = diff read; engine/money/parity/migration = full verify-ladder rerun +
adversarial review. Small fixes: Claude lands directly. Big ones: re-brief Codex on the
same branch.

---

## 6. Can Claude trigger Codex directly? — YES, two mechanisms (no plugin needed)

There is **no official "Codex plugin for Claude Code"**. There doesn't need to be — Codex
CLI is scriptable and Claude Code can run shell commands and register MCP servers. Both of
these work today:

### Mechanism 1 (recommended): Claude shells out to `codex exec` (headless)
Claude runs, via its Bash tool (in the background, like any builder):

```bash
git worktree add ../codex-<slug> origin/main   # isolation, same as Opus worktree builders
codex exec \
  --cd ../codex-<slug> \
  --sandbox workspace-write \
  "Read docs/handoffs/2026-07-XX-<slug>-brief.md and execute it exactly. \
   Write the receipt file it names before finishing." \
  > docs/handoffs/2026-07-XX-<slug>-codex.log 2>&1
```

- Output/receipt land in files Claude then audits — the same receipt loop as today.
- Parallelism: one worktree per concurrent Codex task; **rebase before push** (worktree
  branches base on spawn-time main — existing repo rule).
- Approvals: `codex exec` is non-interactive; anything the sandbox blocks fails visibly
  in the log rather than hanging. Keep `workspace-write` + network on (Maven).
- This needs zero new infrastructure. It is the closest analogue to the current
  `Agent(model:"opus")` call, with Codex as the engine.

### Mechanism 2: Codex as an MCP server inside Claude Code
Codex CLI ships an (experimental) MCP server mode. Register it once:

```bash
claude mcp add codex -- codex mcp-server
```

Then Codex appears to Claude as a callable tool (start task / continue task), output
returned in-session. Cleaner conversationally, but: the mode is experimental, long builds
can hit tool-call timeouts, and logs are less durable than Mechanism 1's file-based
receipts. Try it as a convenience layer AFTER Mechanism 1 is proven.

### The reverse direction also exists (for completeness)
Codex can consume MCP servers (`[mcp_servers]` in config.toml), and could even call a
Claude-based MCP tool — not needed for this workflow; noted so nobody rediscovers it as
"the missing integration."

---

## 7. Workflow variants — when to use which

**A. Manual relay (default when the owner is at the desk)**
1. Owner asks Claude for the next item → Claude writes the brief, commits it (docs PR or
   directly on the task branch).
2. Owner opens a terminal: `codex "Read docs/handoffs/<brief> and execute it."`
   (interactive TUI — owner watches/approves Codex's commands live).
3. Codex pushes branch + opens PR + writes receipt.
4. Claude audits receipt → review → merge → deploy → ledger.
   *Best control; two tools, one human relay.*

**B. Claude-triggered (autonomous; the "one item at a time" loop without owner relay)**
Same as A but step 2 is Claude running Mechanism-1 `codex exec` in the background and
auditing the receipt when it lands. Owner only sees the finished PR + Claude's audit.
*Use once a few manual runs have calibrated trust.*

**C. Codex Cloud (ChatGPT web/app) — narrow lane only**
Cloud sandboxes cannot reach the local stack: no Docker compose, no live Timescale, no
Testcontainers (ITs are the backbone of this repo's verification), no loopback gateway.
Usable for: pure-docs PRs, isolated pure-unit refactors, research spikes. If used, the
environment setup script must install Java 21 + Node 22 and skip ITs explicitly
(`-DskipTests` builds only) — and the PR must say its tests were NOT run.
*Not recommended as a primary lane for this repo.*

---

## 8. Recommendation (Claude's, candid)

1. **Don't move cross-task orchestration to Codex.** The queue discipline, receipt
   audits, tier rules, memory traps, adversarial review, deploy probes — all live on the
   Claude side and are proven across ~100 merged PRs. Moving the conductor role would
   rebuild all of that inside Codex for zero capability gain.
2. **Do use Codex as a builder lane.** Concretely:
   - clean-tier, well-specified queue items (mechanical/parallel work) → Codex via
     Mechanism 1;
   - HOLD-tier / parity / money / migrations → keep on the existing Opus-subagent
     pipeline (or build with Codex but ALWAYS full Claude adversarial review before the
     PR is presented).
3. **Use Codex as a cross-vendor reviewer on keystones.** A second model family catches
   different defect classes. One `codex exec "adversarially review PR #NNN's diff for
   <lens>"` per keystone is cheap and additive to the existing review routine.
4. **Sequence to adopt:** (1) fix AGENTS.md (§4) → (2) install + login (§2–3) → (3) two
   or three MANUAL handoffs (Workflow A) to calibrate → (4) turn on Claude-triggered
   `codex exec` (Workflow B) → (5) optionally add the MCP registration (Mechanism 2).
5. **What would make me change this advice:** if the owner wants to *initiate* work from
   the ChatGPT app on mobile, Codex Cloud + GitHub PRs becomes the entry point and
   Claude's role shifts to reviewing incoming PRs — workable, but the local-stack
   verification gap (no ITs, no live probes) means every cloud PR still needs a local
   verify pass before merge. The desk stays in the loop either way.

---

## 9. Guardrails recap (both agents, one list)

- Trunk-based; squash-merge only; **nobody pushes `main`** — Codex opens PRs, Claude merges.
- `.env`/secrets/deploys/`flyway-init`/DB writes/ledger/memory = **Claude only**.
- Applied migrations are checksum-locked — corrections are NEW migrations.
- Pre-commit hooks (gitleaks, checkstyle) bind Codex commits too — they're plain git hooks.
- Claude's `.claude/settings.json` hooks (guard-paths, format-frontend) do NOT run for
  Codex — Codex must run `npm run lint` itself on FE changes.
- Every Codex PR carries a receipt file; no receipt → no merge.
- Kill switch: Codex misbehaving = close its PR, delete the branch/worktree. It has no
  standing access to anything irreversible.
