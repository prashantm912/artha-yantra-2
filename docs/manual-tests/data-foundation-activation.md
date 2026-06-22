# Data-foundation milestone — activation runbook & value-verify checklist

**Status:** the build is **done and merged** (PR #50 `f732f2a`, ADR-0001(A)); it ships **dormant**
(every flag default-OFF). This doc is the operator runbook to *activate* it and the checklist to
*value-verify* the shipped oipulse pages against real sessions. It is the §20.8 acceptance gate for
the data-foundation milestone.

Authority: [ADR-0001](../adr/0001-broker-coupling-openalgo-live-upstox-historical.md),
[ADR-0002](../adr/0002-upstox-market-information-analytics-side-channel.md), master-plan §2/§3/§4/§20.

---

## TL;DR — two independent tracks

The phrase "data-foundation milestone" bundles two things with **very different prerequisites**.
Separating them is the whole point of this runbook.

| | **Track A — verify-now** (ADR-0001A) | **Track B — Upstox depth** (ADR-0001B + ADR-0002) |
|---|---|---|
| What it lights | OI-backfill of recent sessions → 15 snapshot pages verifiable in History mode | Dow global factor · deep/expired-contract OI · authoritative PCR / FII-derivative / Max-Pain |
| Code state | **Built & merged** (#50) — flip flags, no new code | **Unbuilt** — zero Java (ADR-0002 U1–U5; ADR-0001B SDK) |
| Broker | **Existing Kite/Zerodha OpenAlgo appliance is enough** | Needs **Upstox Plus** (app review + F&O/Equity segments + 2nd analytics token) |
| Owner wait | **None** — activatable today | Upstox app review + then a build cycle |

> **Key fact (corrects a stale code comment):** the `application.yml` note says the OI-backfill flag is
> "flipped … AFTER an Upstox-backed appliance is connected." That over-narrows. Verified in the local
> OpenAlgo checkout — **Zerodha** `broker/zerodha/api/data.py:485` calls the Kite historical endpoint
> with `&oi=1` and returns columns `[timestamp, open, high, low, close, volume, oi]`. So **Kite-backed
> OpenAlgo `/history` already returns per-bar OI for active contracts**, and Track A needs **no Upstox**.
> Upstox is only required for *global indices* (Dow — Kite has none), *expired-contract* OI, and the
> *ADR-0002 analytics* endpoints.

---

## What is built (PR #50) — the dormant surface

- **OI-backfill importer** — `POST /api/v1/market/admin/oi-backfill`
  ([OiBackfillController](../../services/market-data-service/src/main/java/in/arthayantra/marketdata/backfill/OiBackfillController.java)).
  Body `{underlying, expiry, date}`; async → `202 {jobId}`; `409` if one is already running; `503` if not configured.
  Enumerates the (underlying, expiry) chain legs + monthly futures for the session, pulls 1m OHLC+OI from
  OpenAlgo `/history`, samples to **5-min (options) / 3-min (futures)**, computes `oi_change` vs prior
  bucket, writes rows with `source='BACKFILL'`. **Idempotent** (`ON CONFLICT DO NOTHING`).
- **`source` column** — Flyway `V023__snapshot_source_column.sql` on `options_chain_snapshots` +
  `futures_oi_snapshots` (nullable; catalog default `'LIVE'`; live rows = `LIVE`/NULL, backfill = `BACKFILL`).
- **Dedicated history client** — `OiHistorySource` port + `OpenAlgoHistoryClient`, separate from `source.candles`
  routing, wired only when `oi-backfill-enabled=true`.
- **Dow global feed** — `GlobalQuoteSource` + `OpenAlgoGlobalQuoteClient` (`DOWJONES@GLOBAL_INDEX` LTP),
  wired only when `global-quotes-enabled=true`; otherwise Connecting-Dots Dow factor returns **Neutral**.
- **optionchain→OpenAlgo routing + contract canary** — `source.optionchain=openalgo` swaps live per-strike OI
  capture to OpenAlgo `/optionchain`; gated behind the daily `OpenAlgoContractCanary` (§17.11 entry gate).

### The flags (all `market-data-service`, live profile only, default OFF)

| `.env` var | property | default | gate to flip |
|---|---|---|---|
| `ARTHA_OPENALGO_OI_BACKFILL_ENABLED` | `artha.openalgo.oi-backfill-enabled` | `false` | OpenAlgo appliance connected + API key staged |
| `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED` | `artha.openalgo.global-quotes-enabled` | `false` | **Upstox** appliance (global indices) |
| `ARTHA_OPENALGO_CANARY_ENABLED` | `artha.openalgo.contract-canary-enabled` | `false` | appliance live; precedes any `source.*` flip |
| `ARTHA_MD_SOURCE_OPTIONCHAIN` | `artha.marketdata.source.optionchain` | `kite` | canary green ≥1 day (§17.11) |
| `ARTHA_MD_SOURCE_QUOTES` / `_CANDLES` | `artha.marketdata.source.*` | `kite` | appliance live (no OI gate) |

`.env` keys feed compose; `ay.ps1` exports them. Redeploy one service per the CLAUDE.md recipe
(`docker compose -f deploy/docker-compose.yml --env-file .env build market-data && up -d market-data`,
with `ARTHA_DB_NAME=artha` / `ARTHA_REDIS_DB=0` for live).

---

## Track A — activate verify-now (today, Kite appliance)

### A0. Prerequisites (OWNER — I cannot do these)

1. Kite Connect app + `deploy/secrets/kite_api_key` / `kite_api_secret` present (already true if live mode ran before).
2. **Bring up the appliance:** `./ay.ps1 up openalgo` (starts `ay-openalgo` + loopback publisher on `127.0.0.1:5001`).
   On first run `ay` creates `deploy/openalgo/.env` (generates `APP_KEY`/`API_KEY_PEPPER`/`FERNET_SALT`,
   seeds broker creds from `kite_*`). **Never delete `deploy/openalgo/.env`** — losing the Fernet salt makes
   stored broker tokens undecryptable.
3. **Repoint** the Kite Connect app redirect URL to `http://127.0.0.1:5001/zerodha/callback` (developers.kite.trade).
4. **Connect broker** in the OpenAlgo UI (`http://127.0.0.1:5001`): create admin account, complete Zerodha login + TOTP.
   Daily re-login applies (Kite tokens expire ~06:00 IST).
5. **Generate an OpenAlgo API key** in the UI → write it to `deploy/secrets/openalgo_api_key` (single line, no newline).
6. Smoke-test the appliance: `POST http://127.0.0.1:5001/api/v1/quotes {"apikey":…,"symbol":"RELIANCE","exchange":"NSE"}`
   → expect `status:success`.

### A1. Flip the flag + redeploy

The data-foundation flags are passed into the market-data container by the compose `environment:` block
(`ARTHA_OPENALGO_OI_BACKFILL_ENABLED` … added alongside `ARTHA_OPENALGO_BASE_URL`). Set the value and
recreate the one service (replicating `ay`'s live env — a wrong `ARTHA_DB_NAME` points it at `artha_mock`):

```powershell
$env:ARTHA_DB_NAME='artha'; $env:ARTHA_REDIS_DB='0'; $env:ARTHA_OPENALGO_OI_BACKFILL_ENABLED='true'
# IMPORTANT: the running image must be built from a commit that INCLUDES PR #50 (OiBackfillController);
# an older :dev image 404s the admin route. Rebuild if unsure:
docker compose -f deploy/docker-compose.yml --env-file .env build market-data-service
docker compose -f deploy/docker-compose.yml --env-file .env up -d --no-deps --wait market-data-service
```

`printenv | grep OI_BACKFILL` inside the container should show `true`; the admin endpoint stops returning `503`.

### A2. Trigger a backfill (one recent trading session)

Pick a session **inside the market-calendar's covered years** (2024–2026) and an expiry live on that date.
**`underlying` is the index `underlying_tradingsymbol`, not the short name** — `NIFTY 50` / `NIFTY BANK` /
`SENSEX` (this is also the value live capture stores in the snapshot `underlying` column, so the pages match).
The gateway is loopback **http on `:8080`**. Drive it from PowerShell (PS 5.1 `Invoke-WebRequest
-UseBasicParsing`): POST `/api/v1/auth/login` with the owner password into a `-WebSession $s`, GET once to seed
the `XSRF-TOKEN` cookie, then echo it as the `X-XSRF-TOKEN` header on the mutating POST:

```powershell
$base = 'http://127.0.0.1:8080'
$null = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$base/api/v1/auth/login" `
  -ContentType 'application/json' -Body '{"password":"<owner password>"}' -SessionVariable s
$null = Invoke-WebRequest -UseBasicParsing -Uri "$base/api/v1/market/status" -WebSession $s
$xsrf = ($s.Cookies.GetCookies($base) | Where-Object Name -eq 'XSRF-TOKEN').Value
$body = '{"underlying":"NIFTY 50","expiry":"2026-06-30","date":"2026-06-19"}'
Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$base/api/v1/market/admin/oi-backfill" `
  -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $s -ContentType 'application/json' -Body $body
# → 202 { "jobId": "…" }
```

The run is async + rate-limited (≈148 `/history` calls at 5/s + Upstox latency ≈ 2–3 min for a full NIFTY
chain); rows insert as one batch at the end, so the table stays empty until the `oi-backfill … done` log line
(`docker logs ay-market-data-service | grep oi-backfill`). A second POST while running returns `409`. Repeat
per (underlying, expiry) — `NIFTY 50` / `NIFTY BANK` / `SENSEX`. Re-runs are idempotent.

### A3. Confirm rows landed

In-container SQL (DB `artha`; tables live in the `marketdata` schema):

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c `
  "SELECT source, count(*) FROM marketdata.options_chain_snapshots WHERE underlying='NIFTY' AND ts::date = DATE '2026-06-19' GROUP BY source;"
docker exec ay-timescaledb psql -U artha -d artha -c `
  "SELECT source, count(*) FROM marketdata.futures_oi_snapshots WHERE underlying='NIFTY' AND ts::date = DATE '2026-06-19' GROUP BY source;"
```

Expect `BACKFILL` rows at 5-min (options) / 3-min (futures) cadence across 09:00–15:35 IST.

---

## Phase-1 cutover — live per-strike OI capture (`optionchain=openalgo`)

Separate from backfill; switches *live capture* off the Kite per-strike fan-out onto OpenAlgo `/optionchain`.

1. **Measure** the appliance's real per-endpoint rate limits and raise the placeholder
   `resilience4j … openalgo-quote/openalgo-historical = 5/s` to match (Risk R3) — these are conservative guesses.
2. Set `ARTHA_OPENALGO_CANARY_ENABLED=true`; let the daily `OpenAlgoContractCanary` run and stay **green**
   (it probes `/quotes`, `/history`, and at Phase-1 the `/optionchain` per-strike `ce.oi`/`pe.oi` sentinels).
3. Only after ≥1 green day, flip `ARTHA_MD_SOURCE_OPTIONCHAIN=openalgo`. Watch for the canary ntfy CRITICAL
   alert on shape drift; the Kite path stays as the never-deleted fallback (flip back = config change).

---

## Value-verify checklist (§20.8)

Method ([oipulse-live-qa](../oipulse-study/)): for each page, open the oipulse equivalent, **select the
underlying + press Go, wait ~6s for real data**, then compare cell-for-cell against our page in **History
mode** for the same backfilled session. Record divergences (known permanent ones: `+`-prefixed a11y labels,
black76 greeks vs oipulse, badge-ring convention). Update the per-page manual-test docs as each is signed off.

**Dependency legend:** **(a)** live OpenAlgo quotes/chain (verify during market hours) · **(b)** captured OI
snapshots (verify after an A2 backfill of a recent session) · **(c)** NSE/SEBI daily feed · **(d)** zero-dep
(already verifiable) · **[U]** has an Upstox-gated KPI that shows a Neutral sentinel until Track B.

| Page | Dep | What real data verifies it |
|---|---|---|
| Options Chain | a | live per-strike CE/PE OI + greeks; VIX/PCR/days-to-expiry header |
| Options OI Spurt | a+b | per-strike ΔOI + interpretation; 4-quadrant sort/pagination |
| Connecting Dots | d [U] | futures/index/VIX candles; **Dow factor = Neutral until Track B** |
| Straddle/Strangle | a | per-minute CE+PE 1m OHLC → combined premium candle |
| VIX & Index | d | India VIX + NIFTY/BANKNIFTY 1m candles (already shipped zero-BE) |
| Trending OI | b | per-bucket total/CE/PE OI + spot + trend over N buckets |
| Trending OI-PA | b | as Trending OI + Call/Put LTP sums + deltas |
| Big OI Movement | b | top-N strikes by \|ΔOI\| + moneyness + interpretation |
| Options Premium | b | per-strike extrinsic premium + ATM straddle decay line |
| OI Statistics | b [U] | cumulative/individual OI + PCR-vs-price; **active-strike-OI / ATM-IV header = sentinel until Track B** |
| Active Strikes | b | server-picked strike CE/Put OI + sentiment % through session |
| Multiple OI Chart | a | multi-leg OI overlay + underlying price |
| Options OI Chart | a | one strike CE+PE candle + OI/IV series |
| Options OI Analysis | b | per-strike CE+PE time-row buckets |
| Futures OI Spurt | b | per-contract OI + ΔOI + interpretation (index + 17 banks) |
| Futures Movers | b | day price%/OI%, O=L/O=H flag (B.O.-days col dropped — needs multi-day history) |
| Futures EOD | b | per-contract daily OHLC + OI close + ΔOI |
| Banks Analysis / Grid | b | per-bank intraday OI matrix/grid |
| FII/DII Capital Market | c | SEBI cash buy/sell/net |
| FII Long-Short | c | SEBI FII index-futures long/short → LSR% |
| Participant-wise OI | c | SEBI FII/Pro/DII/Client long/short per segment |
| Market Holidays | d | static NSE calendar (already shipped) |

After a Track-A backfill, **everything (b)** is verifiable on a real recent session; **(a)** needs market hours;
**(c)** is independent of OpenAlgo (NSE scrapers, already live); **(d)** is already done. The only data that
stays sentinel/Neutral after Track A is the **[U]** KPIs (Dow factor, active-strike-OI / ATM-IV header) — those
are Track B.

---

## Track B — Upstox depth (gated; needs activation **and** new code)

Track B is **not a flag flip** — most of it is unbuilt. It unlocks:

1. **Dow global factor** (Connecting Dots) — flip `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED=true` once the appliance
   is **Upstox-backed** (Kite has no global indices). This piece *is* built; it only needs the Upstox broker session.
2. **Deep / expired-contract OI** (ADR-0001B) — direct `upstox-java-sdk` behind `HistoricalCandleGateway`,
   scoped by a 2nd Upstox token. **Unbuilt** — backtesting-milestone work.
3. **ADR-0002 analytics** — `upstox/wire/` DTOs + `FiiDiiSource` (Upstox primary, NSE fallback) + `MaxPainService`
   + authoritative intraday `PcrService` + FII-derivative stats (U1–U5). **Unbuilt.** Unlocks W3 *FII Derivative
   Stats*, authoritative *PCR series*, *Max Pain*; replaces the fragile NSE FII/DII scrape.

### Track B prerequisites (OWNER)

1. Submit the Upstox Developer App for review → unlock **Upstox Plus**.
2. Add **F&O + Equity** segments to the app.
3. Generate a **second, long-lived analytics access token** (isolated from the live-execution session).
4. **Confirm the cost tier:** Upstox does not document pricing for Market-Information APIs. Make one live call;
   `200` → proceed, `403`/subscription error → NSE fallbacks stay primary (no code change, flag stays off).
5. Point the OpenAlgo appliance at Upstox (`REDIRECT_URL` `/upstox/` segment + Upstox broker creds in
   `deploy/openalgo/.env`) if you want the Upstox-backed live/Dow path; the Kite appliance can stay for Track A.

Once activated, the build sequence is ADR-0002 U1→U5 then ADR-0001B — each a normal feature
branch + PR, not part of this runbook.

---

## What I cannot do

Activation is **owner-gated end to end on the manual bits**: broker OAuth + TOTP, Kite redirect repoint,
OpenAlgo API-key generation, the Upstox app review / token / cost-tier confirmation, and any entry of
credentials. I can: prep the flags, run the backfill `POST` + verify SQL once the appliance + API key exist,
build the Track-B code after activation, and drive the page-by-page value-verify with you.
