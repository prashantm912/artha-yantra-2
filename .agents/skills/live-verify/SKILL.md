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
curl -s http://127.0.0.1:8081/api/v1/market/health/ingest        # per-source EOD ingest coverage (A4/A5/A11, #699) — also /data-ops/ingest-health page
curl -s http://127.0.0.1:8082/api/v1/signal-rejections/dot-health # per-dot gate-input liveness
docker ps --format "table {{.Names}}\t{{.Status}}" | grep ay-     # container health
```

New-code counters (registration proves the deploy took): `docker exec ay-<svc> sh -c
'wget -qO- http://localhost:<port>/actuator/prometheus' | grep ay_` — e.g.
`ay_signal_partial_bucket_mismatch_total` (#683), `ay_paper_{bracket_starved,settle_refused,
stale_settle}_total` (#694), `ay_paper_recon_*` (#701), `ay_ingest_coverage_gap_total` (#689).
Slim images lack curl; optimizer smoke via `docker exec ay-optimizer-service python -c
"import urllib.request; ..."` — find the internal port via `docker inspect --format
'{{json .Config.Cmd}}'`, not by guessing. **After ANY migration deploy: DB-probe the new
object (`to_regclass`) — healthy + flyway "up to date" prove nothing.**

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

### ⚠️ GATE — answer this BEFORE any log pull, every time

**Do you already know the string you are looking for?**

- **YES → `grep` it. Never a model.** Exact, complete, no fabrication risk. A targeted grep for
  `kite` / `swing` / `effect` BEATS a digest and substituting one makes the result worse, not
  cheaper. Most log pulls in this runbook are this case.
- **NO — you are reading to find out what happened → PRE-FILTER, then digest.** Never paste a raw
  `docker logs` pull.

⚠️ **PRE-FILTERING IS MANDATORY, NOT AN OPTIMISATION — measured end-to-end 2026-08-17 on this box
during a live session.** Feeding a raw 90-minute pull (56 k chars ≈ 14 k tokens) to the 9b **never
finished: killed at 25+ minutes.** `num_ctx` auto-expands to 16384 and the model drops to 14 % CPU /
86 % GPU. Strip to the message field and bound the window first, and the same job takes **191 s**:

```bash
docker logs ay-<svc> --since 30m 2>&1 \
  | grep -oE '"message":"[^"]{0,160}' | sed 's/^"message":"//' | tail -120 > $SP/log.txt   # 56k -> 19k chars
python .claude/skills/local-model/scripts/run.py qwen3.5:9b $SP/prompt.txt $SP/out.txt 900 false
```

**Budget it at ~3 minutes, not "instant."** Measured 191 s / 9.7 tok/s — **not** the 43 tok/s in
the `local-model` skill, because that figure was taken on an idle box and this one was
running the live stack. Under load, expect a quarter of the documented throughput.

⚠️ **OLLAMA SERIALISES PER MODEL, AND A QUEUED CALL IS INDISTINGUISHABLE FROM A SLOW ONE.** A second
`run.py` launched while the first was still going sat behind it and timed out at 9 min having done
nothing. That timeout nearly became a recorded "the digest lane is unusable" verdict — a confounded
measurement, the same failure this repo keeps paying for. **Check `ollama ps` and `tasklist //FI
"IMAGENAME eq python.exe"` before timing anything.**

The prompt MUST name the six categories or the 9b drops the batch summary (measured 3/5 terse →
5/5 structure-marked): *service lifecycle / load health / actions taken / risk refusals /
warnings-aggregated / batch tally.* Read the `local-model` skill's `PROMPTING.md` before writing it.
⚠️ It is a **Claude-tree-only** skill (`.claude/skills/local-model/`) — an agent running from
`.agents/skills/` has the scripts on disk but not that guidance, so quote the rules rather than
assuming the reader can open them.

⚠️ **Then read the RAW lines the summary points at, and cite those. A summary is a POINTER, NEVER
EVIDENCE** — never quote one in a ledger entry, a PR body, or a claim to the owner. **Measured the
same run, and this is why:** the digest correctly surfaced a signal the operator had missed (`#203`,
`24,354.00`, composite `0.9877` — all three verified exactly right against `strategy.signals`) and
described it as **"successfully executed"** when its status was **`EXPIRED`**, i.e. never taken.
Right id, right price, **wrong outcome.** It also duplicated a category heading and produced
speculative operator advice under a prompt that said *"Do not speculate."* **That is the lane
working as designed** — it points you at the row; you read the row.

**Why this gate exists:** on 2026-08-17 a full live-diagnosis session ran ~8 `docker logs` pulls,
merged three PRs, and made **zero** local-model calls while both models sat installed and idle. The
first pull alone put ~40 full ECS JSON lines with repeated Java stack traces into context raw.
Nothing at the point of use said to reach for the models, and a principle three files away loses to
momentum every time.

**Same gate applies to psql** — but the threshold bites far less often: bound the query and
`select` named columns instead. A 1–20 row result is CHEAPER read raw than digested. The real waste
is `select *` on a row carrying a fat JSON column (measured: `swing_batch_runs.dropped_by_cap`
dumped hundreds of unused symbol/rank pairs). **Narrow the query first; digest only what is still
wide after that.**

⚠️ **Guessing a column name costs a round-trip and a local model cannot save you** — it does not
know the schema either. Six queries failed this way in one session. Query
`information_schema.columns` FIRST:

```bash
docker exec ay-timescaledb psql -U artha -d artha -t -c "select string_agg(column_name,', ' order by ordinal_position) from information_schema.columns where table_schema='strategy' and table_name='<t>';"
```

```bash
docker logs ay-strategy-signal-service --since 30m 2>&1 | grep -i <pattern>   # known string: grep, don't digest
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
