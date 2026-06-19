# Manual test guide — Phase 1: OpenAlgo market-data routing

Branch `feat/openalgo-phase1-routing`. Covers master-plan **Phase 1** (§4 routing + §17.4/§17.5/§17.11
gates). PowerShell-first (this box). Read the Phase-0 guide (`phase-0-openalgo-spine.md`) first — Phase 1
builds on that appliance.

**What Phase 1 IS.** Phase 0 stood up the OpenAlgo appliance + the anti-corruption gateways behind the
existing ports, all default-`kite`. Phase 1 adds the pieces needed to actually *route* market-data
capture (incl. per-strike OI) through OpenAlgo, and the **entry gates** that must pass on the LIVE
appliance before any `source.*` flag flips. The flip itself is the last step and stays OFF until the
gates are green — capture is irreplaceable, so an unverified live contract must never be cut over.

---

## Part A — what landed in this branch (offline, automated)

These are mergeable and CI-verified without a broker; run the build to confirm.

```powershell
Push-Location C:\Trading\ArthaYantra\artha-yantra-2
$mvn = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" | Select-Object -First 1).FullName
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
& $mvn -pl services/market-data-service -am verify        # migrations + gateways + tests
& $mvn -pl libs/market-calendar -am verify                # 2024/2025 holiday extension
Pop-Location
```

1. **Schema (marketdata lineage).**
   - `V018__candles_source_enum.sql` — `candles.source` CHECK expanded to add `OPENALGO`,
     `EXPIRYTRACK`, `OPENCHART` (the four V003 values stay valid). Required before any OpenAlgo /
     backfill write. **Live-apply note:** `candles` has compression enabled, and Timescale blocks a
     CHECK swap on a compressed hypertable, so the migration decompresses → swaps → recompresses. On
     CI/test (no chunks) it is a no-op; on the **live** DB it decompresses the candle history once
     (bounded to `candles`, far smaller than `options_chain_snapshots`) and the policy recompresses
     aged chunks on its next run — apply with disk headroom.
   - `V019__candles_3m_cagg.sql` (+ `.sql.conf` `executeInTransaction=false`) — the `candles_3m`
     continuous aggregate (IST-origin `time_bucket`, rolled from raw 1m), the spine the Phase-2 `3m`
     scalper interval reads via `CandleReader`. Verified by the Testcontainers Flyway lineage (ephemeral
     Postgres — **never** `ay reset-db`, which would wipe the owner's live-captured OI).
2. **`libs/market-calendar`** — 2024 + 2025 NSE equity trading holidays added (was 2026-only). Gate
   for §5 backfill + §14 scalp backtests over those years (an uncovered year fails loudly). Dates were
   reconstructed from the NSE circulars, weekday-verified, and cross-checked against public mirrors.
3. **Option-chain routing spine (additive, default-off).**
   - `OptionChainGateway` port (`kite/`) + `OpenAlgoOptionChainGateway` (`/api/v1/optionchain`, one call
     → every strike's CE/PE legs **with per-strike `oi`**). OpenAlgo's `/quotes` omits OI, so the chain
     can only come from `/optionchain`. `Chain.totalOi()` is the OI-coverage signal.
   - `OpenAlgoSymbols` — the `DDMMMYY` expiry-token grammar (`30DEC26`).
   - Bound only when `artha.marketdata.source.optionchain=openalgo` (new flag, default `kite`).
   - WireMock-tested (`OpenAlgoOptionChainGatewayTest`, `OpenAlgoSymbolsTest`).

`quotes` + `candles` routing was already bean-level from Phase 0 (`openAlgoQuoteGateway` /
`openAlgoHistoricalCandleGateway` bind their ports when `source.{quotes,candles}=openalgo`). The
option-chain port is new because the **consumer** (`OptionsChainService`) currently fans out per-strike
`QuoteGateway` calls — rewiring it to the chain gateway is the cutover (Part C).

---

## Part B — LIVE entry gates (§17.11 — must pass BEFORE any flip)

Bring the appliance up + broker logged in per `deploy/openalgo/README.md` (Phase-0 guide §4). Then:

### B1. Contract canary GREEN against the LIVE appliance
```powershell
# market-data-service against the live appliance, canary enabled
$env:ARTHA_OPENALGO_CANARY_ENABLED = "true"
# (rebuild+redeploy market-data-service only; set ARTHA_DB_NAME/ARTHA_REDIS_DB to the LIVE values)
# then inspect the recorded result:
docker exec ay-redis redis-cli -n 0 GET openalgo:contract:check
```
**Expect:** `drift: []` — zero `MISSING:`/`TYPE:`/`PROBE_FAILED:` entries, incl. the per-strike
`chain.*.ce.oi` / `chain.*.pe.oi` sentinels. Any drift blocks the cutover.

### B2. OI-coverage probe — `sum(oi) > 0` on a real chain (the M0 guard, Risk R1)
The canary's live `/optionchain` OI probe needs the nearest expiry resolved via `POST /api/v1/expiry`
(`{apikey, symbol:"NIFTY", exchange:"NFO", instrumenttype:"options"}`), then
`POST /api/v1/optionchain` (`{apikey, underlying:"NIFTY", exchange:"NSE_INDEX", expiry_date:"<DDMMMYY>"}`).
Manually:
```powershell
$key = (Get-Content deploy/secrets/openalgo_api_key -Raw).Trim()
$exp = Invoke-WebRequest -UseBasicParsing -Method POST http://127.0.0.1:5001/api/v1/expiry -ContentType "application/json" -Body (@{apikey=$key;symbol="NIFTY";exchange="NFO";instrumenttype="options"}|ConvertTo-Json) | Select-Object -ExpandProperty Content
# pick the nearest expiry from $exp, then:
$body = @{ apikey=$key; underlying="NIFTY"; exchange="NSE_INDEX"; expiry_date="<DDMMMYY>" } | ConvertTo-Json
$chain = Invoke-WebRequest -UseBasicParsing -Method POST http://127.0.0.1:5001/api/v1/optionchain -ContentType "application/json" -Body $body | ConvertFrom-Json
($chain.chain | ForEach-Object { $_.ce.oi + $_.pe.oi } | Measure-Object -Sum).Sum   # MUST be > 0
```
**Expect:** a non-zero sum for NIFTY **and** SENSEX. `0` on a live F&O chain = the broker backend does
not fill per-strike index-option OI → **do NOT flip `source.optionchain`**; keep it `kite`. (When the
canary OI probe is wired to run automatically, a zero sum raises an ntfy-critical.)

### B3. Live A/B parity — Kite-direct vs OpenAlgo (Risk R2/R3/R6)
On a recent trading day, for ≥5 symbols incl. NIFTY/BANKNIFTY/SENSEX + a few F&O strikes, compare the
Kite-direct path against OpenAlgo:
- 1m bar OHLCV matches to the tick; chain-leg `oi` within 0.1%; quote bid/ask within a tick.
- timestamps IST-aligned (OpenAlgo epoch-seconds → IST bucket).
- `/optionchain` round-trips ~100 strikes in < 1s (no batch `/quotes` — virtual-thread fan-out).
- measure the real rate limit (OpenAlgo default ~50/s) and raise the `application.yml` limiters off the
  conservative 5/s placeholder if confirmed.

---

## Part C — the cutover (only after Part B is fully green)

1. **Rewire the chain consumer.** Make `OptionsChainService` fetch per-strike quotes via
   `OptionChainGateway` (matching its instrument list by `strike` + CE/PE) when the bean is present,
   falling back to the existing per-strike `QuoteGateway` fan-out otherwise. IV/greeks STILL come from
   `libs/black76-math` (§17.9) — only the raw quote+OI fields come from the chain gateway. Add an
   integration test; re-run `-am verify`.
2. **Mock/live isolation (§17.8).** Couple `profile=mock ⇒ OpenAlgo analyzer/sandbox ON` in `ay.ps1`
   (`POST /api/v1/analyzer/toggle`), and never point a mock stack at a live broker session.
3. **Flip order** (`.env`, rebuild+redeploy market-data-service only, LIVE `ARTHA_DB_NAME`/`ARTHA_REDIS_DB`):
   `ARTHA_MD_SOURCE_CANDLES=openalgo` and `ARTHA_MD_SOURCE_QUOTES=openalgo` first; then
   `ARTHA_MD_SOURCE_OPTIONCHAIN=openalgo` **last**, only after B2 confirmed non-zero OI live.
   Each capability flips independently; Kite stays the never-deleted fallback (revert by setting the
   flag back to `kite`).

---

### Pass criteria (Phase-1 exit gate)
- [ ] `mvnw -pl services/market-data-service -am verify` green incl. V018/V019 Flyway lineage +
      `OpenAlgoOptionChainGatewayTest` + `OpenAlgoSymbolsTest`.
- [ ] `mvnw -pl libs/market-calendar -am verify` green; `coveredYears()` = {2024, 2025, 2026}.
- [ ] (LIVE) contract canary drift-free against the pinned appliance incl. per-strike OI sentinels.
- [ ] (LIVE) NIFTY + SENSEX `Σoi > 0` on a real chain.
- [ ] (LIVE) Kite↔OpenAlgo A/B parity within tolerances.
- [ ] Source flags stay `kite` until every LIVE box above is checked; `optionchain` flips last.
