---
name: hotfix
description: Live-incident emergency fast-lane — snapshot evidence FIRST, minimal surgical fix, fast targeted verify, admin-merge, deploy + canary; no SemVer/tag ceremony, parity firewall still applies
---

# Hotfix

Express lane for a **genuine live incident**: feed dead, a service down/OOM-killed, a scheduled batch
missed, data corruption in flight. It trades the full `ship-a-change` ceremony for speed — but never
trades away the parity firewall or the Architect's merge/deploy authority.

**Gate first — is this really a hotfix?** A real crisis (production is broken or bleeding). NOT lazy
debugging or a "quick" feature — those go through `ship-a-change` / `codex-build`. If unsure, it's not
a hotfix.

## Steps

1. **Snapshot evidence BEFORE touching anything.** The hard-won lesson (#A13): a post-incident
   container recreate WIPED the only root-cause-discriminating logs. So first:
   ```bash
   docker logs ay-<svc> > <scratchpad>/incident-<svc>.log 2>&1
   ```
   Stalled JVM? thread-dump it before restart: `docker exec ay-<svc> sh -c 'kill -3 1'` (dump lands in
   `docker logs`). Capture `docker ps -a` (Exited(137)=OOM) + `docker stats --no-stream` if it's a
   resource incident. Only THEN mitigate.

2. **Diagnose (read-only) via `live-verify`.** Health endpoints (`/actuator/health`,
   `/api/v1/market/health/data`, `/signal-rejections/dot-health`), `PUBSUB NUMSUB <channel>`,
   `ticks:last`, per-source candle counts, DB rows by explicit `+05:30` bounds. Find the root cause,
   not just the symptom (known false-positives: FINNIFTY far-month DataHealthCanary noise).

3. **Stop the bleeding (optional runtime mitigation).** A reversible runtime guard to survive until the
   real fix lands is fine (e.g. `docker update --memory` to dodge an OOM, a kill-switch env flag) —
   but it reverts on the next `compose up`, so it is NOT the fix. Note it and continue.

4. **Branch + minimal surgical fix.** `git fetch origin && git checkout -b fix/<incident>-<slug>
   origin/main` (fetch first — a stale local `origin/main` ref is not a fresh base). Smallest
   change that resolves the root cause — no adjacent refactors. **The parity firewall still applies:** a
   money/parity/engine hotfix STILL needs `GoldenDeterminismTest` + `BacktestParityTest` byte-identical
   — "urgency" never licenses skipping the ladder.

5. **Fast targeted verify.** The touched module only, not the full suite: `bash -n` for scripts, the
   affected unit/IT, or the one probe that reproduces the failure. Enough to prove the fix + no
   regression in the blast radius.

6. **PR → admin-merge.** `gh pr create` (state the incident + fix + verify in the body), then
   `gh pr merge <#> --squash --admin` past unrelated e2e flakes (the change can't touch signals/WS).
   **Verify `git log origin/main -1` == the mergeCommit BEFORE deploying** (the pull-races-remote trap).

7. **Deploy** (Architect only). Host-build the artifact first, then rebuild+recreate the one service —
   full commands (copy-pasteable under pressure):
   ```bash
   ./mvnw -pl services/<svc> -am package -DskipTests          # or: (cd frontend-react && npm run build)
   ARTHA_DB_NAME=artha ARTHA_REDIS_DB=0 docker compose -f deploy/docker-compose.yml --env-file .env build <svc>
   ARTHA_DB_NAME=artha ARTHA_REDIS_DB=0 docker compose -f deploy/docker-compose.yml --env-file .env up -d <svc>
   ```
   Carries a migration? Force the one-shot first, then DB-probe the new object — a healthy container +
   "up to date" log do NOT prove it applied:
   ```bash
   ARTHA_DB_NAME=artha ARTHA_REDIS_DB=0 docker compose -f deploy/docker-compose.yml --env-file .env up -d --force-recreate flyway-init
   docker exec ay-timescaledb psql -U artha -d artha -tAc "select to_regclass('<schema>.<new_object>');"
   ```

8. **Post-deploy canary.** Probe the exact surface that failed: `docker ps` healthy, the failing
   endpoint returns, `docker stats` idles safely for a resource fix. Confirm recovery, don't assume it.

9. **Record.** Root cause + fix + any leftover into the right memory topic (`live-mode-findings` etc.);
   file a chip for deferred hardening (the permanent version of a runtime mitigation, added monitoring).

## Template

The edge-gateway OOM fix earlier this session is a clean run: `docker ps -a` caught Exited(137) →
root-caused to a 384m cap at 81% idle → runtime `docker update` guard → one-line compose fix → admin-merge
→ `up -d` → `docker stats` 51% canary → memory + chip. Reuse that shape.
