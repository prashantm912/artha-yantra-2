# Stage B — Manual Testing Guide (Market Data Spine, Phases 9–17)

Everything Stage B built, testable by hand from a PowerShell prompt at the
repo root (`C:\Trading\ArthaYantra\artha-yantra-2`). The whole pass takes
~45–60 minutes in **mock mode with zero Kite credentials** — that is itself a
Stage-B acceptance criterion. A short live-mode appendix covers the parts that
need real Kite keys.

> **Machine notes (carried from Stage A):**
> - Maven: `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` must be in
>   the environment (TLS-intercepting AV).
> - `.env` Argon2id hash: every `$` escaped as `$$`.
> - **New in Stage B:** if you run `docker compose -f deploy/docker-compose.yml ...`
>   directly, always add `--env-file .env` — without it compose blanks
>   `ARTHA_OWNER_PASSWORD_HASH` and login breaks. `.\ay.ps1` always passes it.

---

## 0. Bring-up + login (5 min)

```powershell
.\ay.ps1 up dev-tools
.\ay.ps1 status
```

**PASS when:** all containers `(healthy)`; `ay-flyway-init` `Exited (0)`.
First run now also creates `deploy/secrets/artha_master_key` (random) and
empty `kite_api_key` / `kite_api_secret` placeholders — mock mode never reads
them.

Verify the marketdata migrations reached V007:

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "select version, description from marketdata.flyway_schema_history order by installed_rank"
```

**PASS when:** the list ends `... 005 kite session, 006 options chain
snapshots, 006.1 roll events, 006.2 corporate action events, 007 watchlists`.

Login (Git-Bash/WSL syntax; the cookie jar + CSRF token are reused
everywhere below — POST/DELETE calls need the `X-XSRF-TOKEN` header):

```bash
curl -s -c /tmp/ay.txt -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"owner","password":"<your owner password>"}' -i | head -3
XSRF=$(grep XSRF-TOKEN /tmp/ay.txt | awk '{print $NF}')
```

**PASS when:** `204 No Content` + `SESSION` cookie. (5 failed logins/min
locks you out briefly — wait 60 s if you typo.)

---

## 1. Phase 9 — Instrument master (5 min)

```bash
# trigger a sync (202 + jobId), then status
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/instruments/sync
curl -s -b /tmp/ay.txt http://127.0.0.1:8080/api/v1/instruments/sync/status
# list, ranked search, point lookup
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/instruments?limit=3'
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/instruments/search?q=RELI&limit=3'
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/instruments/NSE/RELIANCE'
# the NFO ladder behind the options chain
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/instruments/NIFTY%2050/expiries'
```

**PASS when:** sync status flips to `OK` with per-exchange counts (~970 rows);
search ranks `RELIANCE` first; expiries include `2026-06-16`; unknown symbol
returns the `NOT_FOUND_INSTRUMENT` envelope. A second sync POST while one runs
returns `409 CONFLICT_SYNC_RUNNING`.

## 2. Phase 9A — Contract-spec history (2 min)

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "select tradingsymbol, as_of_date, change_type, lot_size from marketdata.contract_spec_history limit 5"
```

**PASS when:** FIRST_SEEN rows exist for the synced derivatives (the diff step
accrues CHANGED rows only when a future dump alters lot/tick — nothing to
force by hand in mock).

## 3. Phase 10 — 1m bars + continuous aggregates (5 min)

The mock feed ticks 24×7, so bars accrue for the streamed instruments
regardless of IST session time:

```bash
sleep 70   # let at least one full minute close
curl -s -b /tmp/ay.txt "http://127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=RELIANCE&interval=1m&from=$(date -u -d '-10 min' +%Y-%m-%dT%H:%M:00Z)&to=$(date -u +%Y-%m-%dT%H:%M:00Z)" | head -c 400
```

**PASS when:** items show string-decimal OHLC, `source: MOCK` (live-built
bars), IST `+05:30` buckets. Watch a bar publish on the bus:

```powershell
docker exec ay-redis redis-cli --timeout 70 subscribe "candles.1m.NSE.RELIANCE"
```

**PASS when:** one JSON bar arrives within ~65 s.

## 4. Phase 11 — Cache-first history + rate limiting (5 min)

```bash
# COLD read of a past session hour (fetched from the mock "Kite", upserted)
time curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=TCS&interval=1m&from=2026-02-03T09:15:00%2B05:30&to=2026-02-03T10:15:00%2B05:30&limit=1' | head -c 200
# WARM read — pure cache hit, visibly faster, still 60 bars
time curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=TCS&interval=1m&from=2026-02-03T09:15:00%2B05:30&to=2026-02-03T10:15:00%2B05:30&limit=1' | head -c 200
# mid-intervals come from continuous aggregates
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=TCS&interval=5m&from=2026-02-03T09:15:00%2B05:30&to=2026-02-03T10:15:00%2B05:30&limit=2'
# 1w is rolled locally, never fetched; refreshing it is refused
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/market/candles/refresh -H 'Content-Type: application/json' -d '{"exchange":"NSE","tradingsymbol":"TCS","interval":"1w","from":"2026-02-01T00:00:00+05:30","to":"2026-03-01T00:00:00+05:30"}'
# forced refresh of a real interval → 202 + jobId
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/market/candles/refresh -H 'Content-Type: application/json' -d '{"exchange":"NSE","tradingsymbol":"TCS","interval":"1m","from":"2026-02-03T09:15:00+05:30","to":"2026-02-03T10:15:00+05:30"}'
```

**PASS when:** cold ≫ warm latency; `total: 60`; 5m returns 12 buckets;
1w refresh → `400 VALIDATION_INTERVAL_UNSUPPORTED`; 1m refresh → `202`.
Metrics:

```powershell
docker exec ay-market-data-service wget -qO- http://127.0.0.1:8081/actuator/prometheus | Select-String 'ay_candle_cache|ay_kite_rate_limiter_saturation'
```

**PASS when:** `ay_candle_cache_requests_total{result="hit"}` grew on the warm
read and the saturation gauge exists.

## 5. Phase 12 — Kite session lifecycle (5 min)

```bash
curl -s -b /tmp/ay.txt http://127.0.0.1:8080/api/v1/auth/kite/status
curl -s -b /tmp/ay.txt http://127.0.0.1:8080/api/v1/auth/kite/login-url
```

**PASS when:** status is `connected: true, profile: MOCK, tickerState: MOCK`
and login-url answers `503 NOT_CONFIGURED` (the ritual does not exist in
mock). Secrets hygiene (a Stage-B PASS criterion):

```powershell
docker inspect ay-market-data-service --format '{{json .Config.Env}}'   # no secret VALUES anywhere
docker exec ay-market-data-service ls /run/secrets/                     # 4 secret FILES mounted
```

**PASS when:** env shows only file paths; `/run/secrets/` lists
`postgres_password kite_api_key kite_api_secret artha_master_key`.

## 6. Phase 13 — Subscription registry (5 min)

```bash
curl -s -b /tmp/ay.txt http://127.0.0.1:8080/api/v1/market/subscriptions
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/market/subscriptions -H 'Content-Type: application/json' -d '{"exchange":"NSE","tradingsymbol":"RELIANCE","mode":"full"}'
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X DELETE 'http://127.0.0.1:8080/api/v1/market/subscriptions?exchange=NSE&tradingsymbol=RELIANCE'
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/market/subscriptions -H 'Content-Type: application/json' -d '{"exchange":"NSE","tradingsymbol":"NOPE","mode":"ltp"}'
```

**PASS when:** the pinned set lists `NIFTY 50`, `NIFTY BANK`, **`INDIA VIX`**
at `PINNED_INDEX` plus six `*FUT` pins (run a Phase-9 sync first if any are
missing — pins re-resolve on every sync); subscribe answers
`effectiveMode: full`; delete `204`; unknown symbol `404 NOT_FOUND_INSTRUMENT`.
The binary-frame guard and reconnect supervision are fake-ticker/fixture
tested (`KiteBinaryFrameParserTest`, `LiveTickerFeedTest`) — nothing to drive
by hand in mock.

## 7. Phase 14 — Black-76 S1 gate (3 min)

```powershell
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'
.\mvnw.cmd -ntp -pl services/market-data-service test "-Dtest=Black76*"
```

**PASS when:** the golden suite is green (490 committed py_vollib vectors,
rel ≤1e-6; IV round-trip ≤ ₹0.01) and `PHASE_GATES.md` carries the checked
S1 section. Fixtures are committed — never regenerated at test time
(`tools/greeks-vectors/README.md` records the A4 exception).

## 8. Phase 15 — Options chain + snapshots (7 min)

> The mock chain follows IST market hours: **inside 09:15–15:30 IST on a
> trading day** you get live two-sided quotes and computed IV/Greeks;
> **outside** you get the documented B-11 degradation — `stale: true`,
> zero bid/ask, all-`ZERO_QUOTE` IV reasons, OI frozen at EOD. Both are
> correct; check whichever window you are in.

```bash
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/options/chain?underlying=NIFTY%2050' | head -c 700
# manual snapshot (202), then the stored pass
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/market/options/snapshot -H 'Content-Type: application/json' -d '{"underlying":"NIFTY 50"}'
sleep 5
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/options/chain/history?underlying=NIFTY%2050' | head -c 400
```

**PASS when:** the chain shows `expiry 2026-06-16`, decimal-string `spot`
(≈24137.55, the ladder anchor), a `pcr`, a `forwardSource`; market-hours: ATM
strikes carry non-null `iv`/`delta` with `priceSource: MID` while the 18000
wing shows `iv: null, ivReason: ZERO_QUOTE` **with its raw quote still
present**. Snapshot provenance (every stored IV recomputable):

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "select count(*) total, count(forward_price) fwd, count(risk_free_rate) rfr, count(iv) iv from marketdata.options_chain_snapshots"
```

**PASS when:** `total = fwd = rfr` (962 per pass) — `iv` may be 0 off-hours
(ZERO_QUOTE rows) and grows only during market-hours passes. The Redis
broadcast key has a TTL:

```powershell
docker exec ay-redis redis-cli ttl "options.chain.NIFTY 50.2026-06-16"
```

**PASS when:** between 1 and 60 right after a snapshot.

## 9. Phase 15A — Futures slice (3 min)

```bash
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/futures/term-structure?underlying=NIFTY%2050'
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=INDIA%20VIX&interval=1d&from=2026-06-01T00:00:00%2B05:30&to=2026-06-13T00:00:00%2B05:30&limit=2'
```

**PASS when:** three contracts (JUN/JUL/AUG) + spot from **one** batched
quote, `state: CONTANGO`, annualized basis ≈ 0.065 (the mock carry), positive
`calendarSpread`; INDIA VIX serves 1d history through the ordinary candles
surface — no special casing anywhere.

## 10. Phase 15B — Continuous futures (4 min)

The roll scheduler runs 16:15 IST; the mock ladder's first roll date is
**2026-06-24** (one trading day before the JUN expiry). Until a roll has
happened the checks are structural:

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "select * from marketdata.roll_events"
docker exec ay-timescaledb psql -U artha -d artha -c "select tradingsymbol, segment, is_active from marketdata.instruments where segment = 'SYN-CONT'"
```

**PASS when:** the table exists (rows appear after the first 16:15 IST run on
or after 2026-06-24); once `NIFTY-FUT-CONT` exists, read it both ways and
compare — `adjust=back` shifts pre-roll bars by the cumulative gap:

```bash
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/candles?exchange=NFO&tradingsymbol=NIFTY-FUT-CONT&interval=1d&adjust=none&from=2026-06-20T00:00:00%2B05:30&to=2026-06-27T00:00:00%2B05:30'
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/candles?exchange=NFO&tradingsymbol=NIFTY-FUT-CONT&interval=1d&adjust=back&from=2026-06-20T00:00:00%2B05:30&to=2026-06-27T00:00:00%2B05:30'
```

The full roll flow (event row with the exact gap, idempotent re-runs,
tombstone exemption, grant coverage) is pinned by
`ContinuousFuturesIntegrationTest` with a frozen clock.

## 11. Phase 16 — Schedulers + canary + docs (3 min)

```powershell
# the cron-pitfall guard: every cron is six-field with the IST zone
.\mvnw.cmd -ntp -pl services/market-data-service test "-Dtest=CronConventionsTest"
Get-Item docs\retention.md, docs\runbook-notes.md
```

**PASS when:** the test is green and both docs exist. The contract canary is
**live-profile only and a no-op under mock by design** — its drift detection
(both directions) + ntfy alerts + Redis daily-once marker are pinned by
`ContractCanaryIntegrationTest` against WireMock. ntfy needs only
`ARTHA_NTFY_TOPIC` in `.env` (blank = silent no-op).

## 12. Phase 16A — Corporate actions (3 min)

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "select status, ratio, anchors_diverged from marketdata.corporate_action_events"
docker exec ay-redis redis-cli get marketdata:integrity:corporate-actions
.\mvnw.cmd -ntp -pl services/market-data-service test "-Dtest=CorporateAction*"
```

**PASS when:** the table exists (empty is correct — mock history is
self-consistent until the planted-split scenario is switched on), the
integrity key appears after the first 16:30 IST run, and the test battery is
green — it drives the full planted-split detect → purge → rebuild → RESOLVED
flow credential-free.

## 13. Phase 17 — Watchlists, screener, system status (7 min)

```bash
# watchlists
WID=$(curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/watchlists -H 'Content-Type: application/json' -d '{"name":"core"}' | python -c "import json,sys;print(json.load(sys.stdin)['id'])")
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/watchlists/$WID/items -H 'Content-Type: application/json' -d '{"exchange":"NSE","tradingsymbol":"RELIANCE"}'
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/v1/watchlists -H 'Content-Type: application/json' -d '{"name":"core"}'   # → 409
curl -s -b /tmp/ay.txt -H "X-XSRF-TOKEN: $XSRF" -X DELETE http://127.0.0.1:8080/api/v1/watchlists/$WID
# screener (momentum works as soon as the live mock bars build 1d history)
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/screener?preset=momentum&lookback=1&limit=3'
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/screener?preset=moonshot'           # → 422
# market + system surfaces (ticks/latest is a keyed MAP, filterable by symbols CSV)
curl -s -b /tmp/ay.txt http://127.0.0.1:8080/api/v1/market/status
curl -s -b /tmp/ay.txt 'http://127.0.0.1:8080/api/v1/market/ticks/latest?symbols=NSE:RELIANCE,NSE:TCS'
curl -s -b /tmp/ay.txt http://127.0.0.1:8080/api/v1/system/status
```

**PASS when:** create `201`, duplicate `409 CONFLICT_WATCHLIST_NAME`,
delete `204` (PUT renames/reorders); momentum returns ranked decimal-string
returns with `avgVolume` + `distanceFromHigh52w` (explicit filters
`minReturnPct/minAvgVolume/minPrice/maxPrice/exchange` work without a
preset); bad preset `422 VALIDATION_FAILED`; market status names the next
trading day; system status carries the B-13 shape — `overall`, `services[]`,
`kite{session,ticker,lastTickAgeMs,rateBudget}`, `market.phase`,
`jobs{0,0}` — from Redis only. Flip the kite key and watch the rollup react within one 5 s window:

```powershell
docker exec ay-redis redis-cli set kite:session:status TOKEN_EXPIRED
# … status shows DEGRADED within ~5 s; put it back:
docker exec ay-redis redis-cli set kite:session:status MOCK
```

`oi_buildup` and `rs_rank` need cached 1d FUT/benchmark history — after one
15:45 IST EOD backfill they answer live; until then their quadrant/percentile
math is pinned by `WatchlistScreenerIntegrationTest`.

---

## Appendix — Live mode (requires real Kite credentials)

1. Put the **brand-new 2.0** API key/secret (A6 — never the v1 pair) into
   `deploy/secrets/kite_api_key` and `deploy/secrets/kite_api_secret`
   (single line, no newline matters not). `artha_master_key` was already
   generated by `ay up`.
2. `SPRING_PROFILES_ACTIVE=live` in `.env`, then
   `docker compose -f deploy/docker-compose.yml --env-file .env up -d`.
   The service **fails fast** if any of the three secret files is blank —
   that is the D13 design, not a bug.
3. Morning ritual: `GET /api/v1/auth/kite/login-url` → complete Zerodha 2FA →
   the callback exchanges + AES-GCM-persists the token. `GET
   /api/v1/auth/kite/status` flips to `CONNECTED` with `tokenValidUntil`
   ~06:00 IST next day. `docker compose restart market-data-service` —
   status returns CONNECTED **without** re-login (decrypt-and-resume).
4. Within ~10 s of the first LIVE probe the **contract canary** runs once for
   the day — `GET /api/v1/auth/kite/status` then carries `lastContractCheck`
   + an empty `contractDrift`; drift would land on your ntfy topic.
5. Run the **minute-depth probe** from `docs/runbook-notes.md` once and
   record the outcome (feeds amendment A3).
