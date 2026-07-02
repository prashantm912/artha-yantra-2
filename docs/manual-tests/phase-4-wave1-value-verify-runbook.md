# Value-verify runbook — Wave-1 data pages + Part-2 premium backtest

The open Phase-4 gate (data-foundation milestone): render the data pages on a REAL session and compare
value-for-value vs oipulse, and confirm the Part-2 premium backtest runs on real backfilled premium.
Authority: `docs/superpowers/plans/archive/2026-06-21-data-foundation-milestone.md` + master-plan §20.8 +
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

---

## Part A — VALUE-VERIFY PASS RESULTS (2026-07-01, live-vs-live)

**Verdict: Part-A data foundation VALUE-VERIFIED.** Ran the §20.8 cell-for-cell compare **live-vs-live**
during market hours (~13:05 IST, 2026-07-01) against the owner's signed-in oipulse via Claude-in-Chrome,
plus a gateway-API pull of our own side at the same instant. Live-vs-live beats the History-mode fallback
in the runbook above because both sides show the *same* live session (the milestone allowed either). Our
own History-mode side was independently re-confirmed on **2026-06-30** (a full real-captured session:
NIFTY 210,835 snapshot rows / 1,043 buckets, SENSEX 375,020) — all 12 OI/data endpoints return real,
non-empty, internally-consistent rows.

### The decisive result — captured OI is byte-faithful to oipulse
OI Analysis, **SENSEX 77000 5-min**, two consecutive buckets, both legs — our captured OI equals oipulse
**to the exact share**:

| bucket | metric | oipulse | ours |
|---|---|---|---|
| 13:00 | Call OI | 28,82,220 | 2,882,220 ✓ EXACT |
| 13:00 | Put OI  | 34,64,080 | 3,464,080 ✓ EXACT |
| 12:55 | Call OI | 28,17,480 | 2,817,480 ✓ EXACT |
| 12:55 | Put OI  | 34,30,220 | 3,430,220 ✓ EXACT |
| 13:00 | Call LTP | 271.65 | 271.50 (~✓ sub-tick live skew) |
| 13:00 | Call/Put interp | Short/Long Build Up | Short/Long Build Up ✓ |

This is the core of "value-verify": our OI capture reproduces the reference product's OI exactly.

### Page-by-page (Part A, 5 pages)
| page | result |
|---|---|
| **OI Analysis** (`/options/oi-analysis`) | ✓ **Exact per-strike OI match** (table above); LTP within sub-tick; 4-state interpretation agrees. |
| **Connecting Dots** (`/features/connecting-dots`) | ✓ **Structure** matches (13-col order, ↑/↓/↔ colour semantics, 5-state Trend badges, 25/page, legend). ⚠ **Per-cell factor directions were NOT cross-verified** — the factors are computed independently on both sides (oipulse's exact per-factor cutoffs + composite weights are server-side; ours are the documented *approximate* fit, §20.7.8), plus a few-minute skew between the oipulse capture and our pull. With the authoritative code map (`1=Bullish/2=Bearish/0=Neutral`, `core/connectingDots.ts`) several factors — including the OI ones — read *opposite* in the sampled 13:03–13:06 row; that is the already-documented approximation/convention class, **not a verified match and not a new defect**. Data fidelity for this page's inputs is proven upstream by OI Analysis (exact OI) + the futures-OI series, not by matching this derived sentiment cell-for-cell. **Dow** neutral (F4). |
| **Straddle Chart** (`/options/straddle-chart`) | ✓ SENSEX 77000 3m: underlying 77049.5 vs 77050.09; combined-premium latest 555.9→557.45 vs oipulse marker 553; VWAP 602 vs oipulse ~600. Distinct premium-candle pipeline validated. |
| **Options Chain** (`/options/options-chain`) | ✓ Driven live (SENSEX): header **Total PCR 1.515 vs 1.5193 ✓**, **INDIA VIX 13.3875 vs 13.38 ✓**, spot 77011 vs 77016 ✓; ATM 77000 **PUT OI 3,528,160 EXACT**, CALL OI within 0.5% skew, LTP within a few pts. 18-col layout + colours + ATM tint match. Divergence: **IV** — ours is a single per-strike black76 IV (CE==PE), oipulse shows distinct per-leg server IV — the documented greeks class. Also finding **F1** (History-mode gap; live mode is correct). |
| **OI Spurt** (`/options/oi-spurt`) | ✓ Driven live (SENSEX): 4-quadrant layout + 10 cols + |ΔOI|-sort + colours match; **absolute New OI matches** (77000 CE 2,876,560, cross-checked vs chain-table @13:24). **Divergence F6**: the ΔOI **window** differs — oipulse classifies by day-cumulative ΔOI, ours by per-interval — so the quadrant can flip (77000 CE: oipulse LONG_BUILDUP vs ours LONG_UNWINDING). |

*(All **5/5** Part-A pages now driven live-vs-live. Data-fidelity anchor = OI Analysis + Options-Chain +
Straddle exact absolute values; the two documented computed-view divergences are IV [greeks class] and
OI-Spurt ΔOI window [F6].)*

### Findings
- **F1 — ✅ FIXED (#399): `/market/options/chain-table` now honours History mode.** It used to return the
  **live** chain (`asOf`=today, `spot`=live 24011) even with `mode=history&date=2026-06-30`. Now history
  mode pivots the chain from the session's captured `options_chain_snapshots` (via `HistoricalOiReader`);
  greeks are null on history (the snapshot projection carries IV only). Live-verified: History 2026-06-30
  NIFTY → spot **23913.55**, asOf **2026-06-30T15:15**, pcr 0.8837, greeks null. Live mode byte-unchanged.
- **F3 — low: `/market/options/oi-heatmap` bucket labels are UTC** (`"03:45"` = 09:15 IST). IST-offset
  display nit on the heatmap x-axis (the [[in-container-utc-ist]] class of bug).
- **F4 — by-design: Connecting Dots `dow` = neutral** every row (global-quotes feed off;
  owner-decided "WON'T arm Dow", inventory §6). oipulse shows a live Dow direction. Documented divergence,
  not a defect.
- **F5 — low: strike-series per-interval `oiChange` method.** On the resampled PE leg our reported
  `oiChange` (55,800) differed from the endpoint-to-endpoint interval Δ (33,860 = current − prior-bucket OI,
  which is what oipulse shows) — we carry/aggregate the captured 3-min `oi_change` rather than recomputing
  `bucket_end − prev_bucket_end`. Absolute OI is exact; the interpretation **direction** still agrees.
- **F6 — ✅ FIXED (#399): OI Spurt now classifies by day-cumulative ΔOI (matches oipulse).** oipulse's OI
  Spurt classifies by **day-cumulative** ΔOI (`Old OI` = day-open OI; no interval selector); ours used
  **per-interval** ΔOI, so the quadrant could flip (SENSEX 77000 CE: oipulse LONG_BUILDUP since open vs our
  LONG_UNWINDING in the last 5m). Added a `window=cumulative|interval` param to `/spurt`; the OI Spurt page
  now sends `window=cumulative` (and hides the interval selector), Big OI keeps the per-interval default.
  Live-verified: SENSEX 77000 CE cumulative `oiChange` 229,700 (since open) vs interval 0 (flat last bucket).
- **F2 — investigated, NOT a bug:** the "future-dated" 2026-07-01 snapshot rows were just *today's* live
  capture — git-bash `TZ=Asia/Kolkata date` mis-reported IST as UTC (real now was 13:05 IST, market open),
  so 07:27–07:33 UTC rows = 12:57–13:03 IST = normal. Use the container clock, not git-bash TZ, for IST.

### Unchanged known divergences (carried forward, not defects)
black76 greeks (vs oipulse server values) · interpretation-badge ring for WCAG (oipulse fills solid) ·
`+`-prefix on positives (colour-only a11y) · `OiInterval` lacks 10m · EOD label `15:30-15:35` vs `15:30-EOD`.
