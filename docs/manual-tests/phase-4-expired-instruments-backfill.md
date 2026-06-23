# Manual test — Expired-instruments backfill (backtest data foundation)

Native ingester that pulls **expired** NIFTY/SENSEX option + future per-minute OHLCV+OI from the
Upstox Plus **expired-instruments v2 API** into the `candles` hypertable, so strategies can be
backtested on real traded option/future prices. This is Part 1 (data → `candles`); Part 2
(premium-as-primary replay, so a backtest *trades* the option's own OHLC) is a follow-up.

## What it does
- For each underlying (`NIFTY`, `SENSEX`) × expiry in the requested window: enumerate the CE/PE chain
  + future(s), **bound the strikes to ±20% of the underlying's price range over the contract's life**
  (the expired roster lists EVERY strike that ever existed as spot roamed — ~1020/expiry, almost all
  deep OTM/ITM with zero volume; the band keeps only the tradeable ~440/expiry), walk each kept
  contract's 1-minute history into `candles` (`source='BACKFILL'`, `interval='1m'`, OHLCV + OI), and
  register it in `expired_contracts`.
- Symbol grammar is the canonical OpenAlgo form (`NIFTY16JUN2625000CE`, `NIFTY26JUNFUT`) — the SAME
  key in `candles` and `expired_contracts` (expired contracts aren't in the active `instruments` master,
  so the registry is the resolver).
- Exchange = `NFO` (NIFTY) / `BFO` (SENSEX). Re-runs are idempotent + resumable (a registered contract
  is skipped; the candle upsert merges). Contracts are imported by a bounded worker pool (6 concurrent).
- **No continuous-aggregate refresh** — backtest reads `candles` at `1m` directly. (The earlier
  per-run cagg refresh over the whole span OOM-crashed the 1 GB DB; higher-timeframe expired views, if
  ever needed, are a separate throttled admin op.)

## ⚠️ Capacity (learned the hard way)
The unbounded "all strikes" pull (~106k contracts, ~350M rows) **OOM-crashed the live 1 GB Postgres**.
Before running: `ay-timescaledb` `mem_limit` is raised to **4 GB** (`deploy/docker-compose.yml`), and
strikes are bounded to ±20%. Run **off-hours** regardless. Always probe ONE week first + watch
`docker inspect ay-timescaledb --format '{{.State.OOMKilled}}'` before launching the full year.

## Prerequisites
1. **Upstox Plus analytics token** in `deploy/secrets/upstox_analytics_token` (1-yr validity; the
   expired API rejects read-only/daily-OAuth tokens with UDAPI100067).
2. **Live profile** + `ARTHA_UPSTOX_ANALYTICS_ENABLED=true` in `.env` (already set). The
   `UpstoxExpiredInstrumentsClient` bean binds only then; otherwise the endpoint 503s.
3. **Migration `V025__expired_contracts.sql`** applied to the live `artha` DB (flyway-init on `ay up`).

## Deploy (one service)
```bash
# build the service jar (full reactor + -am so market-calendar etc. rebuild)
MVN=$(ls ~/.m2/wrapper/dists/apache-maven-*/*/bin/mvn | head -1)
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl services/market-data-service -am package -DskipTests
# live env values, then rebuild + recreate only market-data (Kite feed untouched)
export ARTHA_DB_NAME=artha ARTHA_REDIS_DB=0
docker compose -f deploy/docker-compose.yml --env-file .env build market-data-service
docker compose -f deploy/docker-compose.yml --env-file .env up -d market-data-service
```

## Trigger (admin, loopback gateway)
```powershell
# login + seed XSRF (see CLAUDE.md gateway-from-PowerShell), then:
# all fields optional — defaults: [NIFTY,SENSEX], trailing 365 days, 1minute
Invoke-WebRequest -UseBasicParsing -Method POST http://127.0.0.1:8080/api/v1/market/admin/expired-backfill `
  -ContentType application/json -Body '{}' -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $s
# → 202 { "jobId": "...", "status": "started" }   (async; ~30 min for a full year, both indices)
```
A single run holds an in-process lock (409 on overlap). Progress + the final summary
(`N expiries, N contracts, N written/skipped/failed, N rows`) land in `docker logs ay-market-data-service`.

### Probe the API directly (sanity, no deploy)
```bash
TOKEN=$(tr -d '\r\n' < deploy/secrets/upstox_analytics_token)
# Windows curl + Avast TLS → add --ssl-no-revoke (else schannel CRYPT_E_NO_REVOCATION_CHECK / HTTP 000)
curl -sS --ssl-no-revoke -H "Authorization: Bearer $TOKEN" \
  "https://api.upstox.com/v2/expired-instruments/expiries?instrument_key=NSE_INDEX%7CNifty%2050"
```

## Verify (in-container SQL — DB is `artha` live / `artha_mock` mock)
```sql
-- expired contracts registered (expect thousands after a full run)
SELECT underlying_symbol, instrument_type, count(*) FROM expired_contracts GROUP BY 1,2;
-- backfilled candles landed (source BACKFILL, NFO/BFO)
SELECT exchange, count(*) FROM candles WHERE source='BACKFILL' AND exchange IN ('NFO','BFO') GROUP BY 1;
-- a sample contract's intraday OHLCV+OI on its expiry day
SELECT bucket, open, high, low, close, volume, oi FROM candles
WHERE exchange='NFO' AND tradingsymbol='NIFTY16JUN2625000CE' AND "interval"='1m'
ORDER BY bucket DESC LIMIT 5;
```
Expect: per-minute rows with non-null `oi`, OHLC as exact NUMERIC(18,4), volume integral. Higher
timeframes (5m/15m/1h) resolve via the caggs (refreshed at run end).

## Known limits
- **NFO + BFO only.** MCX commodities are NOT served by the expired API.
- **Futures are monthly** — a weekly expiry date returns no future (expected).
- **Weeklies are short-lived** — each has data only for the ~1–2 weeks it traded; the window walk
  stops after two empty 28-day windows.
- Backtest reads `candles` at `1m` directly; using this data to actually *trade* the option premium
  needs Part 2 (premium-as-primary replay).
