# Data-Foundation Milestone — value-verify the Wave-1 data pages on real sessions

Status: DESIGN LOCKED (grilled with owner 2026-06-21). Sequenced **after PR-W1, before Wave 2/3**
(master-plan §20.3). Authority: this doc + master-plan §18 (OpenAlgo gap addendum) + §21 (Kite-vs-Upstox
verdict) + [ADR-0001](../../adr/0001-broker-coupling-openalgo-live-upstox-historical.md). Backlog source:
`docs/manual-tests/phase-4-wave1-deferred-ledger.md` (Bucket 5).

## Why

Wave-1 shipped six data pages **structure-QA'd** vs oipulse but NOT **value-verified** — mock data is
synthetic, the live stack off-hours has no Kite session, OI factors go Neutral without captured
snapshots, and Dow is absent. This milestone closes that gap: get **real OHLC + per-bar OI** into our
tables for a recent session so every Wave-1 data page can be rendered in History mode and compared
**value-for-value** with oipulse's same day. Two axes — *feature-completeness* (Wave-1 code, done) vs
*value-verification* (data, this milestone).

## Scope decision (owner-locked)

- **(A) THIS milestone — verify-now on the EXISTING OpenAlgo scaffold.** Reachable with the dormant
  Phase-0 gateways + the live OI-capture spine already on `main`. No new broker SDK, no new appliance.
- **(B) DEFERRED — deep / expired-contract archive.** Direct Upstox-Java SDK + the ExpiryTrack appliance.
  Real payoff is *backtesting*, not page-verification. Recorded in §"Deferred (B)" below; built in a
  later "historical-archive" milestone when backtesting consumes it.

## The 3-source map for historical OHLC + OI (researched 2026-06-21)

| Source | Covers | Form | In repo |
|---|---|---|---|
| **OpenAlgo `/history`** | OHLC **+ per-bar OI**, **active** contracts, 1m/1d | REST appliance (running) | ✅ `OpenAlgoHistoricalCandleGateway` (dormant) |
| **Direct Upstox-Java SDK** | OHLC+OI, **active + expired**, 1/3/5/15/30m+day, deep (Jan 2022) | Apache SDK `com.upstox.api:upstox-java-sdk` (importable, owner's 2nd token) | ❌ (B) |
| **ExpiryTrack** | OHLC+OI, **expired** bulk → DuckDB + CSV/Parquet | AGPL Flask appliance (consume output) | ❌ (B) |

Key fact: **OpenAlgo `/history` already returns per-bar OI for active contracts** (`OpenAlgoCandle.oi` →
`Candle.oi`). So a recent session with still-active contracts needs **only** the OpenAlgo scaffold — the
Upstox-SDK and ExpiryTrack earn their keep only for **expired** contracts (B). Both Upstox historical
APIs (V3 `/historical-candle`, `/expired-instruments/historical-candle`) return `candle[6] = Open
Interest`; ExpiryTrack just wraps the expired one with enumeration + bulk storage.

## Resolved design (the grill output)

### 1. Live routing flip — `optionchain=openalgo` ONLY
- Flip `artha.marketdata.source.optionchain=openalgo` → OpenAlgo's one-call `/optionchain` (per-strike
  OI + greeks) replaces Kite's per-strike quote fan-out (1/s, rate-bound). Gated on `OpenAlgoContractCanary`
  green (§17.11).
- **Keep** `candles` + `quotes` on Kite (don't reroute intraday candles or the live scalp WS tick path;
  §21 — Kite incumbent for live scalp). The historical backfill calls OpenAlgo `/history` via a
  **dedicated client**, not the global `candles` flag → smallest blast radius.
- Accepted risk: live OI capture (`OptionsSnapshotService`, 5-min) now depends on the appliance being up
  09:15–15:30; the canary gate + Kite fallback mitigate. Single-broker consolidation = a LATER decision.

### 2. Snapshot-backfill importer (the main buildable piece)
- **Write-target:** populate the existing snapshot tables — `options_chain_snapshots` (OI Spurt / OI
  Analysis / Active Strikes / CD OI factors) + `futures_oi_snapshots` (CD FutOi + futures pages). The OI
  readers are unchanged. (Candle-driven pages — Straddle, CD candle factors — already work via cache-first
  `/candles` once OpenAlgo serves history.)
- **Resolution:** fetch each leg's **1m OHLC+OI** via the dedicated OpenAlgo-`/history` client; sample to
  the snapshot cadence (options 5-min, futures 3-min) — last-1m-bar-in-bucket → one snapshot row per
  (strike, side, bucket). `oi_change` = diff vs prior bucket; `ltp` = bar close; `spot` = index 1m close
  at that bucket.
- **Greeks:** left **null** in backfill (OI pages don't use them; per-bucket greek recompute needs
  forward/r/t — overkill for verify-now; live chain greeks stay live).
- **Provenance:** add a `source` column to the snapshot tables (new Flyway migration) marking `BACKFILL`
  vs live capture — identify/purge backfills, never confuse with live data.
- **Trigger:** admin endpoint `POST /api/v1/market/admin/oi-backfill {underlying, expiry, date}` — async,
  **idempotent** (`ON CONFLICT` on the snapshot PK), re-runnable.

### 3. Dow Jones factor (Connecting Dots)
- Live-mode: LTP-direction from OpenAlgo `/quotes DOWJONES@GLOBAL_INDEX` (globals are **LTP-only** on
  both OpenAlgo and Upstox — no historical OHLC for globals). History-mode: **Neutral** (no global
  historical series). Faithful: Dow barely moves during Indian hours anyway (US closed), so intraday
  Neutral is the common live value too.

## Token-gated sequencing

Update 2026-06-21: **owner's Upstox token is now in hand** (no longer ~2 days out), so the post-token
step is unblocked. The split still held for the build:

- **Now (token-independent) — BUILT (2026-06-21):** the `source` migration (V023) + the dedicated
  OpenAlgo `/history` client (`OiHistorySource`/`OpenAlgoHistoryClient`) + the `OiBackfillService` +
  admin endpoint + the Dow global-quote feed (`GlobalQuoteSource`/`OpenAlgoGlobalQuoteClient`) +
  routing/flag config; unit + mock-IT green (sampler, backfill writes `source='BACKFILL'`, idempotent
  re-run, admin 202; ConnectingDots/Modulith/contract regressions green).
- **Next (token in hand):** owner connects Upstox in `ay-openalgo` (`deploy/openalgo/.env` — owner-side,
  no creds in repo) and flips the live flags (`artha.openalgo.oi-backfill-enabled`,
  `global-quotes-enabled`, and the canary-gated `source.optionchain=openalgo`); then run `oi-backfill`
  on a real recent session and **value-verify** every Wave-1 data page vs oipulse (the acceptance gate).

## Acceptance gate (definition of done)

For one recent active trading session: backfill its OI → render each Wave-1 data page in History mode →
side-by-side vs oipulse's same day (Claude-in-Chrome, §20.8): OI Spurt, OI Analysis, Connecting-Dots OI
factors (+ Dow live), Chain interval-deltas, Straddle candles. Values match within the documented
divergences. Update each page's `docs/manual-tests/phase-4-wave1-*.md` "value-verified" line + strike the
relevant Bucket-5 ledger rows.

## Build task breakdown (token-independent first)

1. ✅ Flyway: `source` column on `options_chain_snapshots` + `futures_oi_snapshots` (V023; compressed-
   hypertable-safe nullable add + catalog `SET DEFAULT 'LIVE'`).
2. ✅ `OiHistorySource` (port, in `kite`) + `OpenAlgoHistoryClient` (adapter) — dedicated OpenAlgo
   `/history` 1m OHLC+OI fetch, its own bean type so it never competes in the `HistoricalCandleGateway`
   pool; flag-gated `artha.openalgo.oi-backfill-enabled`, live profile.
3. ✅ `OiBackfillService` + `OiBackfillSampler` + admin endpoint `POST /api/v1/market/admin/oi-backfill`
   (new top-level `backfill` module — avoids an options↔futures Modulith cycle). Enumerate chain legs →
   1m OHLC+OI → sample to cadence (options 5-min, futures 3-min) → idempotent `insertBackfill`
   (source=`BACKFILL`, greeks null, oi_change vs prior bucket, spot from index series).
4. ✅ Dow factor wiring in `ConnectingDotsService` via `GlobalQuoteSource`/`OpenAlgoGlobalQuoteClient`
   (live LTP-direction from `DOWJONES@GLOBAL_INDEX`; Neutral in history or when the feed is
   unconfigured); flag `artha.openalgo.global-quotes-enabled`.
5. ✅ Routing/flag config (`application.yml`): the two dormant data-foundation flags (default off) +
   the existing `source.optionchain` knob + canary gate. Activation is an operator action post-token.
6. ✅ IT/mock coverage (`OiBackfillSamplerTest`, `OiBackfillIntegrationTest`) + springdoc recapture for
   the new admin endpoint + TS regen.
7. **(token in hand — next)** live flip + backfill + value-verify pass.

## Deferred (B) — record for the backtesting milestone

When backtesting needs deep / expired-contract OI history: add the **direct Upstox-Java SDK** behind a
`HistoricalCandleGateway` impl (active + expired, deep) and/or run **ExpiryTrack** as an AGPL appliance
(consume its DuckDB/Parquet via an ingest job). The owner's 2nd Upstox token scopes the heavy backfill
away from live rate limits. See ADR-0001.

## Upstox Market-Information adoption (ADR-0002, added 2026-06-22)

Upstox launched **Market Information** + **Company Fundamentals** REST APIs (11 May 2026). They give us,
directly and authoritatively, several things we currently scrape from NSE or do not have. OpenAlgo does
**not** normalize them (verified: not in the 2.0.1.4 `restx_api` namespaces) → a second Upstox-specific
side-channel behind ports, NSE kept as fallback. Full rationale + scope table in
[ADR-0002](../../adr/0002-upstox-market-information-analytics-side-channel.md).

**Owner decision (2026-06-22): plan now, build still waits for Upstox activation** (same gate as §"Token-
gated sequencing"). All new flags land dormant default-off, no behaviour change until a live `200` confirms
access (cost tier is undisclosed in the docs — verify at activation, else NSE stays primary).

Task breakdown (gated — do NOT start before activation):

- **U1.** `upstox/wire/` full-mirror DTOs (hand-rolled `RestClient`, `@JsonIgnoreProperties(ignoreUnknown=true)`,
  same pattern as `kite/wire/`) for `GET /market/fii`, `/market/dii`, `/market/max-pain`, `/market/pcr`,
  `/market/change-oi`; a dedicated long-lived **analytics access token** (Upstox Developer Apps dashboard),
  isolated from the live execution session; own rate-limiter family.
- **U2.** `FiiDiiSource` port — Upstox primary (`Get FII NSE_EQ|CASH` + `Get DII`), **`LiveFiiDiiFetcher`
  (NSE) demoted to fallback** behind a `source.fiidii=upstox|nse` flag. Wave-2 *FII/DII Capital Market* page
  switches feed transparently.
- **U3.** `MaxPainService` + authoritative `PcrService` (Upstox intraday-bucket series) → new read endpoints
  → wires the **W3 OI-Statistics PCR series** and a **Max Pain** page (Max Pain was never built).
- **U4.** FII **derivative** long/short (`Get FII NSE_FO|*`) → feeds the **W3 FII Derivative Stats** page.
- **U5.** Value-verify each against oipulse at activation (same §20.8 gate as Wave-1/2).
- **Keep NSE:** participant-wise OI (`Get FII` is FII-only — no Pro/DII/Client split). `LiveParticipantOiFetcher`
  stays primary.
- **Fundamentals (separate W3 thread, record only):** `Get Corporate Actions` could replace the NSE
  corporate-actions scrape (EOD bhavcopy CA-adjust); `Get Share Holdings` (Promoter/FII/DII/Public) + key
  ratios power a W3 equity-fundamentals page.

**W3 deferral ledger delta:** this retires *FII Derivative Stats* (U4) and *OI-Statistics PCR series* (U3)
from "needs a brand-new endpoint with no source" → "Upstox-fed, gated on activation", and adds a feasible
*Max Pain* page. Participant-wise OI remains NSE-sourced.

## OpenAlgo appliance bump 2.0.1.3 → 2.0.1.4 (2026-06-22)

Re-pinned `marketcalls/openalgo` digest `b1bc2ec` (2.0.1.3) → `892bca72` (2.0.1.4, verified via
`utils/version.py` inside the image). We consume OpenAlgo **API-only**; 2.0.1.4's headline features
(Scalping Terminal, Gamma Density, OI Range, TradeSmart broker) are its **own UI** and do not reach us — the
relevant gain is **perf** (broker keep-warm ~150ms→15ms RTT after idle, off-response-path SQLite commits,
latency caching) on our three consumed endpoints (`/history`, `/quotes`, `/optionchain`, all unchanged). The
named volume `openalgo-data:/app/db` (SQLite sessions/api-keys + DuckDB) and the mounted `.env`
(FERNET_SALT/APP_KEY) persist across the recreate, so the in-progress Upstox broker connection survives.

## Risks

- Appliance uptime dependency for live OI (mitigated: canary + Kite fallback).
- OpenAlgo `/history` for option legs is 1m/1d only — fine (we resample 1m); deeper intervals/expired =
  (B).
- Backfilled OI from 1m candle `oi` is the END-OF-MINUTE OI, not a true tick snapshot — close enough for
  value-verification (oipulse also samples), documented as a backfill approximation.
