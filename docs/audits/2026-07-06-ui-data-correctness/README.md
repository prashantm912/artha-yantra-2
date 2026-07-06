# Deep UI data-correctness audit — 2026-07-06 (live market, ~11:00 IST)

Full per-page **data-correctness** audit (does each page show the RIGHT numbers, not just render):
6 cluster agents, ~76 pages, every displayed figure independently recomputed from the DB +
the documented formula. Per-cluster detail in the sibling files.

## Verdict: the app's data is CORRECT across the board.
Byte-reconciled clean: **Options OI-suite 19/19** (PCR, sentimentLevelPct 39.59, oi_change bucket-lag,
spurt, straddle, calendar sign, ATM Black-76 δ≈0.5); **Futures** all serve the dated front NIFTY26JULFUT
(not the stale CONT); **Screeners** gate-math/RS-rank/VCP-geometry/MA all exact; breadth (1227/1123/34);
index-contribution (0.76−0.22=0.54); **Charts** OHLCV + 3m rollup byte-identical; VIX 11.88; world-indices;
risk-calc; connecting-dots live (not NEUTRAL); **Backtests** metrics/curves/optimizer; **Data-Ops** 143.6M
candle rows. Composite Σ(w·s)/Σw + graduation Win%/PF/expectancy correct.

## Findings (2 real display bugs + minor)

| # | Sev | Page | Defect | Fix |
|---|-----|------|--------|-----|
| D1 | **MED** | Participant-Wise OI | `/participant-oi` includes a synthetic `TOTAL` row; `participantOiFold.ts` keeps it in the group list AND the %-denominator (already = Σ of the 4 real participants) → **every Long%/Short% is HALVED** (FII Future-Index Long shows 3.8% vs correct 7.6%) | exclude `clientType==='TOTAL'` (list + denominator) |
| D2 | **MED** | Strategies list | `/strategies` sends no `limit` → server caps at **50 of 73**; a published+enabled strategy (`scalp-btst-stbt-nifty`) is silently hidden (list 44 vs graduation 45). FE-display only; engine unaffected | `strategies.ts` add `limit=200` |
| D3 | LOW | Sector Stats | `latestMapped()` = newest-row-per-symbol → ~157 thin names show a pre-07-03 close under one "as of 07-03" badge | pin to `max(trade_date)` (market-data SQL) |
| D4 | LOW | Signals | "Strategy" column shows a version-UUID fragment, not the strategy name | map version→strategy name |
| D5 | cosmetic | Data-Ops collection-status | `recentLogs` UTF-8 mojibake (`â†'` for `→`) | encoding |
| D6 | cosmetic | Backtest results | negative-equity run shows `CAGR 0.00%` (annualization undefined for final<0) beside a −219% return | label as n/a |
| D7 | cosmetic | Backtest results | downsampled drawdown-curve visible max (235.62) sits under the exact metric (238.20) | (downsample artifact) |

## Data / config actions (not code bugs)
- **Partial 2026-07-02 bhavcopy** — 181 EQ/BE rows vs the normal ~2670 (upstream ingestion gap); leaks
  into Equity-Returns r1d + delivery. → **re-fetch** the 07-02 bhavcopy.
- **Dow dot permanently Neutral live** — `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED=false` gates its feed, so
  the Connecting-Dots composite never gets the Dow vote. → owner config choice to enable.

## Already-known / deferred
- Paper **account-header** marks non-ticking swing equities at entry (fabricated breakeven equity/dayPnl,
  disagrees with the honest `—` in its positions table) = existing audit **M4 (swing MTM blind)**.

## Non-issues (verified, do NOT re-flag)
- Live signals empty today = the strict ~30-rail scalper gate fired 0 (memory), not a bug.
- oi-stats header PCR (1.5219) vs native peOi/ceOi (1.5203) = intentional Upstox full-chain override.
- MTARTECH `aboveSma50:false` + `passesAll:true` = a soft non-blocking flag, correct.
- Banks per-interval badge disagreeing with the cumulative % = deliberate.

*6 agents, ~1.0M tokens, every high/medium finding recomputed from the DB. External oipulse anchor in
`oipulse-anchor.md` (NIFTY-F 24459.60, live Connecting-Dots trend sequence).*
