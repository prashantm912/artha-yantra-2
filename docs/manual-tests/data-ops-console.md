# Manual test — Data Ops Console (B1–B6)

Operator console over the expired/OI backfill. Spec: [`docs/superpowers/plans/archive/2026-06-24-data-ops-console-wave.md`](../superpowers/plans/archive/2026-06-24-data-ops-console-wave.md).
Route group `/data-ops/*`; mega-menu section **Data Ops**.

## How to run (dev, against the mock stack)
1. `./ay.ps1 up` (mock profile). Gateway on 8080, React dev on 4300 (or the built app via the gateway).
2. Log in with the owner password.
3. Open **All Menu → Data Ops**.

> The collection/quota surfaces are Upstox-analytics-gated. On the **mock** stack the analytics
> client is absent, so the quota gauge shows "not configured" and a trigger 503s — that is correct.
> Full live behaviour is exercised on the live stack (where the expired backfill actually runs).

## What to check

### Collection Status — `/data-ops/status` (B1)
- The **expired** backfill card shows a state badge (NEVER_RUN/RUNNING/OK/FAILED), a stat grid
  (Expiries, Contracts, Candle rows, Written, Skipped, Failed), and the current expiry while RUNNING.
- The **Log feed** shows recent per-expiry lines; **Details** opens a modal with the same feed + jobId/error.
- The **OI backfill** card shows its last-run state + option/futures row tallies.
- The **Quota gauge** renders (mock: "not configured"; live: three bars 1s/1m/30m).
- On the **live** stack with a backfill running: counts climb every ~2 s (the page polls only while RUNNING).

### Coverage — `/data-ops/coverage` (B2)
- Top stat pills total Contracts / Complete / Partial / Candle rows.
- The table has one row per (underlying, exchange) with Complete %, candle rows, min/max expiry. Sortable.
- Numbers reconcile with `psql` (`SELECT … FROM marketdata.expired_contracts GROUP BY underlying_symbol, exchange`).

### Run Backfill — `/data-ops/collection` (B3)
- 4-step wizard: indices → date range → options (force) → review. Next is disabled until each step is valid.
- **Start backfill** POSTs the existing trigger. Mock: 503 "not configured" surfaces as an alert. Live: 202 → redirects to Status; a second start while one is running → 409 "already running".

### Query Console — `/data-ops/query` (B5)
- Preset buttons load SQL. `⌘/Ctrl+Enter` runs. Results render with a `{n} rows` (and `truncated`) note; **Download CSV** saves the grid.
- Security (try these — all must be rejected with a 400): `DROP TABLE candles`, `UPDATE candles SET close=0`,
  `SELECT 1; DROP TABLE candles`, `WITH d AS (DELETE FROM candles RETURNING *) SELECT * FROM d`.
- A `SELECT … FROM candles WHERE source='BACKFILL' LIMIT 100` returns rows.

### Export — `/data-ops/export` (B6)
- Wizard: underlying → expiry → contract → format + date range → **Download**. CSV has
  `openalgo_symbol,date,time,timestamp,open,high,low,close,volume,oi`; JSON is an array of bars.

## Documented divergences (from ExpiryTrack / the spec)
- **No credential-entry UI** — the Upstox token stays in `deploy/secrets/`; the console is read-only status only.
- **No contract-type selector** in the collection wizard — the server pulls options + futures together (the
  trigger has no contract-type field). The wizard maps to the real request (underlyings, date range, force).
- **Status is in-memory last-run**, not a job-history table — a market-data restart resets it. (A `backfill_jobs`
  audit table is a parked decision in the spec.)
- **B6 export is per-contract** (≤100k rows, synchronous), not per-expiry bulk; ZIP/Parquet + async streaming
  are deferred. The B5 query console covers arbitrary slices in the meantime.
- **B1 progress bar is indeterminate** while RUNNING (the expired backfill has no known total expiry count).

## Automated coverage
- Backend: `AdminQueryServiceTest` (the B5 allowlist), `ExpiredBackfillServiceTest` (run + status), market-data
  unit suite. `ContractCaptureTest` snapshots the new admin endpoints.
- Frontend: `QuotaGauge`/`Stepper`/`MultiCheckboxGroup` RTL specs; `npm run lint` + `test:ci` + `build` green.
