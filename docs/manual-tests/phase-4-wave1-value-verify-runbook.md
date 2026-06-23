# Value-verify runbook — Wave-1 data pages + Part-2 premium backtest

The open Phase-4 gate (data-foundation milestone): render the data pages on a REAL session and compare
value-for-value vs oipulse, and confirm the Part-2 premium backtest runs on real backfilled premium.
Authority: `docs/superpowers/plans/2026-06-21-data-foundation-milestone.md` + master-plan §20.8 +
[[oipulse-live-qa-method]]. Two INDEPENDENT parts on two different data sources — do them separately.

> **Two data sources, do not conflate.**
> - **Part A (data pages)** read `options_chain_snapshots` / `futures_oi_snapshots` — fed by the LIVE
>   3-min OI capture (running since 2026-06-15) + `OiBackfillService` (OpenAlgo `/history`) for a past
>   session. **NOT gated on the expired-instruments backfill.**
> - **Part B (premium backtest)** reads `candles` where `source='BACKFILL'` — the expired-instruments
>   ingester (#112–#116). **Gated on the backfill** reaching the target session's expiry.

---

## Part A — data pages vs oipulse (snapshot-based)

### A0. Pick a session + confirm coverage
Pick a recent liquid trading day that (a) oipulse can still display (its history depth is limited) and
(b) has OI snapshots in our tables. Most robust: the **most recent completed trading day**. Confirm via
the Data Ops **Query console** (`/data-ops/query`, once deployed) or `psql`:

```sql
-- snapshot coverage for the session (NIFTY chain)
SELECT bucket::date, count(*) rows, count(DISTINCT strike) strikes,
       min(bucket)::time first, max(bucket)::time last
  FROM marketdata.options_chain_snapshots
 WHERE underlying = 'NIFTY' AND bucket::date = DATE '<SESSION>'
 GROUP BY 1;
```
A full session ≈ 09:15–15:30, ~75 five-min buckets × the captured strikes. If thin/empty, either the
capture wasn't running that day or run `POST /api/v1/market/admin/oi-backfill {underlying,expiry,date}`
for it first (OpenAlgo source, flag-gated).

### A1. Verify order (candle-free first)
Per the §20.8 side-by-side method (Claude-in-Chrome on the owner's logged-in oipulse, cell-for-cell vs
`docs/oipulse-study/<area>/<page>.md`):

1. **Options Chain** (`/options/options-chain`, History mode → SESSION) — 18 cols, ATM tint, PCR, the
   OI/ΔOI/LTP colour semantics. Greeks are ours (black76), a documented divergence.
2. **Options OI Spurt** (`/options/oi-spurt`) — the 4-quadrant ΔOI scanner.
3. **Options OI Analysis** (`/options/oi-analysis`) — per-strike intraday time-rows for a chosen strike.
4. **Straddle/Strangle Chart** (`/options/straddle-chart`) — CE+PE combined premium candles + VWAP/EMA
   (needs option 1m candles for the expiry; degrades clean if absent → structure-QA only).
5. **Connecting Dots** (`/features/connecting-dots`) — the 11-factor sentiment matrix per interval
   (needs futures + index candles + VIX + snapshots; Dow factor is live-LTP only → Neutral in history).

Record each page's residual divergence in its `docs/manual-tests/phase-4-wave1-<page>.md`. A page is
done when it matches oipulse within the already-documented substitutions (LWC/echarts, `--ay-*` theme,
decimal handling, black76 greeks, no AI badge).

---

## Part B — Part-2 premium backtest (BACKFILL candles)

### B0. Confirm the target expiry is backfilled
The backfill loads OLDEST expiries first (2025-06 → 2026-06), so recent sessions land LAST. Confirm the
target session's CE/PE contracts have 1m premium via the **Coverage dashboard** (`/data-ops/coverage`)
or:
```sql
SELECT tradingsymbol, count(*) bars, min(bucket)::date, max(bucket)::date
  FROM marketdata.candles
 WHERE source='BACKFILL' AND exchange IN ('NFO','BFO')
   AND tradingsymbol LIKE 'NIFTY%' AND bucket::date = DATE '<SESSION>'
 GROUP BY 1 ORDER BY bars DESC LIMIT 20;
```
If the ATM±1 CE/PE for that session show ~375 bars each, it's ready. **If not yet loaded, wait** — the
new pre-flight will 422 `DATA_GAP` rather than silently return 0 trades.

### B1. Import + run
1. Import `phase-4-wave1-value-verify-nifty-atm-scalper.yaml` (this folder) via the React Strategy
   Editor → validate → save (id `nifty-atm-value-verify`) → publish.
2. Run the backtest (React Backtest Runner, or `POST /api/v1/backtests/run`):
   ```json
   {
     "strategyId": "nifty-atm-value-verify",
     "from": "<SESSION>T09:20:00+05:30",
     "to":   "<SESSION>T15:12:00+05:30",
     "interval": "1m",
     "initialCapital": 200000
   }
   ```
   → 202 `{jobId}`; watch progress on `/backtests/jobs` (jobs WS); results at `/backtests/{runId}`.

### B2. What to confirm
- The run **completes** (not 422) → the ATM±1 premium series covered the window.
- Each trade is on the **option leg** (`tradingsymbol` = a `NIFTY…CE/PE`), entry/exit are **premiums**
  (~tens–hundreds, not the ~25000 index), `premium_source = CANDLE_1M`.
- P&L is **cost-inclusive** (slippage + the options stack — confirm it's a touch below the gross
  premium delta; the Part-2 hardening, #123).
- Equity curve **marks per bar** (dips intra-trade, not just at exits).
- Cross-check 1–2 trades against the same-day **Straddle Chart** + **Connecting Dots** bias: at an
  entry time, the premium on the chart ≈ the trade's entry, and the CD bias ≈ the entry direction.

---

## Acceptance
- **Part A:** every data page matches oipulse within documented divergences (recorded per page).
- **Part B:** the scalper backtest completes on BACKFILL premium, trades the option leg with
  cost-inclusive P&L, and reconciles against the same-day chart/bias.

## Known constraints
- oipulse history depth limits which past session can be compared (prefer the most recent).
- Recent-expiry premium loads last in the backfill → Part B's recent-session target is the final thing
  the backfill makes available.
- Dow factor (Connecting Dots) is live-LTP only → Neutral in History mode (documented divergence).
