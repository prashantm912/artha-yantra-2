---
name: live-verify
description: Use when verifying or diagnosing the LIVE ArthaYantra stack — confirming a deploy took effect, checking a "feed looks dead" report, inspecting live DB rows, reading batch/engine logs, or thread-dumping a stalled service. The read-mostly live diagnosis toolkit.
---

# live-verify

The toolkit for observing live behaviour. Default posture: **read-only** — SELECTs,
GETs, `docker logs`. Anything mutating goes through [ship-a-change]/[arm-flag].

## API access — two doors

1. **Gateway (auth)** — `http://localhost:8080`, login + XSRF per [run-artha-yantra].
   From PowerShell 5.1 use `Invoke-WebRequest -UseBasicParsing`.
2. **Socat sidecars (no auth, loopback, dev-tools profile)** — the fast path for
   internal reads and owner-approved internal ops:
   - market-data → `http://127.0.0.1:8081` (candles, screener, OI, health)
   - strategy-signal → `http://127.0.0.1:8082` (strategies, signals, paper, rejections)
   - bring up if absent: `docker compose -f deploy/docker-compose.yml --env-file .env --profile dev-tools up -d mds-publish sss-publish`
   - Remember: a new `/api/v1/<x>` prefix ALSO needs the edge-gateway route allowlist,
     or the gateway serves SPA index.html — only live-verify catches that.

## Health endpoints — check these BEFORE hand-digging

```bash
curl -s http://127.0.0.1:8081/api/v1/market/health/data          # per-token tick/bar divergence + capture freshness
curl -s http://127.0.0.1:8082/api/v1/signal-rejections/dot-health # per-dot gate-input liveness
docker ps --format "table {{.Names}}\t{{.Status}}" | grep ay-     # container health
```

## Live DB

```bash
docker exec ay-timescaledb psql -U artha -d artha -c "<SQL>"      # live (mock: -d artha_mock)
```
- **IST trap:** in-container `now()`/`::date` is UTC. Bound `signals.generated_at` /
  candle `bucket` by explicit `+05:30` ISO bounds, never `::date = CURRENT_DATE`.
- Bound every hypertable scan to a window; no unbounded scans mid-session.
- Published versions: `strategy_versions.status='published'` is **lowercase**.
- Signals hold only FIRING bars; every block is in `strategy.signal_rejections`.
  Zero rejections during market hours = real problem; zero off-hours = normal.

## Logs + JVM

```bash
docker logs ay-strategy-signal-service --since 30m 2>&1 | grep -i <pattern>
docker exec ay-<svc> sh -c 'kill -3 1'    # thread dump → lands in docker logs (no jstack in slim image)
```
A "stalled" service that is RUNNABLE on a PG socket read with queries turning over is
I/O contention (often the nightly pg_dump), not a hang — don't restart it.

## Known non-alarms (don't "fix" these)

- `NIFTY-FUT-CONT` max bar = backfill end — the continuous future is replay-only by
  design; LIVE signals ride the dated front contract (re-resolved ~08:40 IST).
- Kite token expires 06:00 IST → "Ticker: DISCONNECTED" pre-open until owner re-logins.
- Market-data 404s outside market hours for quote/chain endpoints.
- Swing paper positions don't tick intraday — funnel equities aren't in the live feed;
  they settle on the daily batch's close.

## Deploy verification (the stale-jar trap)

A compose rebuild that COPYs a stale `target/*.jar` "succeeds" and runs old code. Verify
**behaviour**, not exit codes: a log line unique to the new code, a new endpoint
responding, or a DB row the change writes. If in doubt, rebuild the artifact first and
compare `docker inspect --format '{{.Created}}'` of image vs jar mtime.

## Never

Print secrets (owner password, PHC hashes, tokens, `.env` contents) · `docker kill` ·
restart services on pattern-match suspicion — evidence first (thread dump, health, logs).
