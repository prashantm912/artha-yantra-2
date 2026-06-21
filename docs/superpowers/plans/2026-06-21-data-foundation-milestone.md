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

Owner's Upstox account is processing — token ~2 days out, so the appliance can't serve real Upstox data
yet. The DESIGN is unaffected; the SCHEDULE splits:

- **Now (token-independent):** build the backfill importer + `source` migration + Dow wiring + routing
  config + canary gate; unit/IT-test against mock/synthetic.
- **After token (~2 days):** owner connects Upstox in `ay-openalgo` (`deploy/openalgo/.env` — owner-side,
  no creds in repo); flip the canary-gated `optionchain` routing; run `oi-backfill` on a real recent
  session; **value-verify** every Wave-1 data page vs oipulse (the acceptance gate).

## Acceptance gate (definition of done)

For one recent active trading session: backfill its OI → render each Wave-1 data page in History mode →
side-by-side vs oipulse's same day (Claude-in-Chrome, §20.8): OI Spurt, OI Analysis, Connecting-Dots OI
factors (+ Dow live), Chain interval-deltas, Straddle candles. Values match within the documented
divergences. Update each page's `docs/manual-tests/phase-4-wave1-*.md` "value-verified" line + strike the
relevant Bucket-5 ledger rows.

## Build task breakdown (token-independent first)

1. Flyway: `source` column on `options_chain_snapshots` + `futures_oi_snapshots` (suffix-versioned).
2. `OpenAlgoHistoryClient` — dedicated OpenAlgo-`/history` fetch client (1m OHLC+OI), isolated from the
   routed `candles` gateway.
3. `OiBackfillService` + admin endpoint — enumerate chain legs → fetch 1m OHLC+OI → sample to cadence →
   idempotent upsert into the snapshot tables (source=`BACKFILL`); futures variant.
4. Dow factor wiring in `ConnectingDotsService` (live LTP-direction; history Neutral) — replace the
   hard-coded Neutral.
5. Routing config + the `optionchain=openalgo` flip behind the canary gate (config + a small wiring/test;
   not activated until the token lands).
6. IT/mock coverage for 1–4; springdoc recapture for the new admin endpoint.
7. **(token-gated)** live flip + backfill + value-verify pass.

## Deferred (B) — record for the backtesting milestone

When backtesting needs deep / expired-contract OI history: add the **direct Upstox-Java SDK** behind a
`HistoricalCandleGateway` impl (active + expired, deep) and/or run **ExpiryTrack** as an AGPL appliance
(consume its DuckDB/Parquet via an ingest job). The owner's 2nd Upstox token scopes the heavy backfill
away from live rate limits. See ADR-0001.

## Risks

- Appliance uptime dependency for live OI (mitigated: canary + Kite fallback).
- OpenAlgo `/history` for option legs is 1m/1d only — fine (we resample 1m); deeper intervals/expired =
  (B).
- Backfilled OI from 1m candle `oi` is the END-OF-MINUTE OI, not a true tick snapshot — close enough for
  value-verification (oipulse also samples), documented as a backfill approximation.
