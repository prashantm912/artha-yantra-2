---
name: ship-a-change
description: Use whenever landing ANY code or docs change in ArthaYantra — the full lifecycle from branch to live-verified deploy (branch → build → test → review → PR → CI → squash-merge → deploy → live-verify → closeout). Use for "implement X", "fix Y", "ship this", or after finishing local edits.
---

# ship-a-change

The end-to-end lifecycle every change follows. Classify the change's tier first
([fable-method] §2) — it decides whether the PR auto-merges or HOLDs.

## 1. Branch

```bash
git checkout main && git pull && git checkout -b feat/<scope>-<slug>   # or fix/ chore/ docs/
```
- **Never push to main.** Squash-merge only. One concern per branch.
- Worktree-agent branches base on spawn-time main — **rebase onto origin/main before push**.
- `git reset --hard origin/main` wipes uncommitted TRACKED edits — stash in-flight work first.

## 2. Build + test (authoritative = full reactor)

Use [build-service] for the exact Maven invocation (cached mvn + Windows-ROOT truststore).
Non-negotiables:
- **`-am` always** — bare `-pl` embeds stale libs (phantom "unknown indicator" failures).
- Tests are `*Test`/`*IntegrationTest` — `*IT` classes are silently skipped (no failsafe).
- ITs share one singleton DB with **no cleanup**: unique slug+name per test method.
- Frontend: `npm run lint` + `npm run test:ci` + `npm run build` (the verify trio).
- Engine/replay/exit changes: goldens must stay **byte-identical**; run
  GoldenDeterminismTest + BacktestParityTest; new fields ride the side-channel.
- Parity/money/doctrine surfaces: run [adversarial-review] BEFORE opening the PR.

## 3. Commit + PR

- Conventional Commits, scope = service/lib (`feat(strategy-signal): …`). Multi-line
  messages via `git commit -F -` with a bash heredoc (PS here-strings corrupt subjects).
- End the message with the `Co-Authored-By:` trailer your system prompt specifies.
- `*.json` is pinned `eol=lf` — after touching `.gitattributes`, `git add --renormalize`.
- `gh pr create` with: what/why, tier (clean vs HOLD), test evidence, review outcome.

## 4. CI → merge

```bash
gh pr checks <n> --watch    # 3-shard ci-java + ci-contracts + ci-e2e
```
- **clean tier**: on green, `gh pr merge <n> --squash --admin` (solo-owner repo,
  enforce_admins off by design). Delete the branch. **Then verify `git log origin/main -1`
  equals the PR's mergeCommit before building/deploying** — `merge && pull` races the
  remote (a stale pull once deployed a migration-less "healthy" service).
- **HOLD tier**: leave the PR OPEN with a "HOLD for owner review" note; move on.
- e2e fail? DISCRIMINATE before acting: `gh run view <id> --log-failed | grep -oE
  "✘ +[0-9]+ tests/[a-z-]+\.spec\.ts:[0-9]+" | sort -u`. Known flake pair =
  signals.spec.ts:38 + ws-reconnect.spec.ts:23. Reachability test: can THIS diff touch
  the failing spec's surface? Unreachable → admin-merge once every other gate is green;
  signals/WS-adjacent → rerun-to-green. A <60s e2e death = infra, read the log first.
  (~15 identical flake signatures in one night, 2026-07-10/11 — the procedure held.)
- New service? It needs its own CI matrix shard or its tests never run.
- Contract drift: new endpoints/params re-capture via `-Dcontracts.capture=true` then
  `npx openapi-typescript@7`; every endpoint returns a **typed record, never Map** —
  MapReturnRatchetTest fails the shard otherwise.

## 5. Deploy the changed service(s)

```powershell
# artifact FIRST (Dockerfiles COPY pre-built target/*.jar and frontend dist/ — the stale-jar trap)
# (build per [build-service]; frontend: cd frontend-react; npm run build)
$env:ARTHA_DB_NAME = 'artha'; $env:ARTHA_REDIS_DB = '0'    # live (mock: artha_mock / 1)
docker compose -f deploy\docker-compose.yml --env-file .env build <svc>
docker compose -f deploy\docker-compose.yml --env-file .env up -d <svc>
```
- Never bare `docker compose` without `--env-file .env` + the two env vars — unset vars
  drift sibling services onto the wrong DB.
- **Migration in the change?** `up -d --force-recreate flyway-init` FIRST (`up -d <svc>`
  treats the exited one-shot as satisfied), then DB-probe the new object
  (`to_regclass`/information_schema) — a healthy container + "up to date" flyway log do
  NOT prove the migration applied.
- Frontend after deploy: hard-reload (Ctrl+Shift+R) — cached chunks render the old UI.
- Deploy from the **merged main checkout**, not the feature branch.

## 6. Live-verify (merged ≠ done)

Observe the change in live behaviour — a log line, DB row, or endpoint response
([live-verify] has the toolkit). If a scheduled batch is the first real exercise of the
change, schedule a durable post-batch check ([daily-ops]) instead of claiming success.

## 7. Closeout

Ledger DONE (PR#+SHA) → doc-of-record if numbers changed → memory topic + MEMORY.md hook →
PHASE_GATES.md if a frontier closed. Then pick the next ledger item.
