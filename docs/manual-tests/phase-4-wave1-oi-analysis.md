# Phase 4 / Wave 1 — True Options OI Analysis (manual test + live-QA gate)

Page: React `OiAnalysisPage` → `/options/oi-analysis`. Feed:
`GET /api/v1/market/options/oi-analysis/strike-series`. Authority: §20.7.5 + the live oipulse page;
spec doc `docs/oipulse-study/options/oi-analysis.md`.

## How to run
Same as the chain. Open OI Analysis, pick an underlying + expiry, the **Strike** selector defaults to
ATM; pick an interval; Go. Needs ≥2 captured buckets at that strike for the deltas.

## Automated coverage (green)
- `oiAnalysisFold.spec.ts` — interval ΔOI, cumulative ΔOI, 4-state interpretation, day-high break,
  first-bucket nulls, `bucketTime`.
- `OiBadge4.spec.tsx` — abbreviation vs `full` label variant.
- `npm run lint` / `test:ci` / `build` green.

## Live QA results — 2026-06-21 (Claude-in-Chrome vs live oipulse OI Analysis, SENSEX 76800 5-min)
**Confirmed matching (real data):** the **16-column** mirrored order
(`Time · Call OI · Total OI Chng · Call D.H/L · Call LTP · Call LTP Chng · Call Chng. in OI · Call OI
Interpretation · Strike · Put OI Interpretation · Put Chng. in OI · Put LTP Chng · Put LTP · Put D.H/L ·
Total OI Chng · Put OI`); newest-interval-first ordering; green/red tones on LTP-Chng + Chng-in-OI;
the OI-Interpretation badges + day-low-break badge (`D.L.B (422.45) ↓`); Indian lakh grouping; constant
Strike column; 25-rows pagination.

**Fixed after QA:**
- **Time = interval window** `15:25-15:30` (was a single `15:25`) — `bucketWindow(start, intervalMin)`.
- **Total OI Chng = plain** number (was signed-toned `+…`) — oipulse renders the cumulative plain.
- **D.H/L Break = blank** when no break (was an em-dash); the badge carries the price `(ltp)`.
- **OI-Interpretation = FULL labels** on this page — oipulse uses abbreviations only on the dense Options
  Chain, full labels (`Long Build Up` / `Short Build Up` / `Shorts Covering` / `Long Unwinding`) here.
  Added a `full` variant to `OiBadge4`; updated labels to oipulse's exact spelling.

## Remaining divergences / gaps
- **Underlying header**: oipulse shows `<name> | datetime | LTP | DH | DL | DO`. `strike-series` carries
  only `spot`, so we show the spot; DH/DL/DO + datetime pending (underlying-quote gap, shared with chain).
- **`+` prefix** on positives (a11y, oipulse colour-only) — intentional, consistent.
- **Badge fill**: oipulse fills the interpretation badge solid; ours keeps a ring (WCAG) — consistent
  with the chain, documented.
- **Interval set**: oipulse has 3/5/10/15/30/60; our `OiInterval` lacks **10m** (offered 3/5/15/30/60).
- **EOD label**: oipulse labels the last window `15:30-EOD`; ours computes `15:30-15:35`.
- **Header labels** drop the `Call`/`Put` prefix (the CALL/PUT colgroup carries it) — brevity divergence.

## Second QA pass — 2026-06-21 (Historical mode, SENSEX 76200 5-min)
Owner re-loaded with **Mode = Historical** + a different date/strike. Confirmed: **history mode works**
(the strike-series date-scopes correctly); both **D.H.B** (green ↑) and **D.L.B** (red ↓) break badges
render; all four interpretation states + colours match.
- **Fixed:** the break badge shows the **broken level** — the prior running extreme the bucket crossed
  (e.g. `D.L.B (534.65)` while the row LTP is 532.00), NOT the current LTP. Added `breakLevel` to the fold.
- **Known approximation (data limit):** our day-high/low break is computed from the bucket **CLOSE**
  running extreme, because we capture point-in-time snapshots, not true intraday OHLC bars. So the exact
  break level can differ from oipulse's (which uses true 1-min highs/lows). Faithful in shape + signal;
  the level value is approximate until/unless per-bucket OHLC is captured.

## Value-verify pass — 2026-07-01 (live-vs-live, SENSEX 77000 5-min) — DECISIVE
Compared our `strike-series` against oipulse's live OI Analysis for the same strike, two consecutive
buckets, both legs. **Our captured OI equals oipulse to the exact share:**

| bucket | Call OI (oipulse → ours) | Put OI (oipulse → ours) |
|---|---|---|
| 13:00 | 28,82,220 → **2,882,220 ✓** | 34,64,080 → **3,464,080 ✓** |
| 12:55 | 28,17,480 → **2,817,480 ✓** | 34,30,220 → **3,430,220 ✓** |

Call LTP 271.65 vs 271.50 (sub-tick live skew); Call/Put OI-Interpretation (Short/Long Build Up) agree.
**This is the core data-foundation value-verify proof — our OI capture reproduces the reference product's
OI exactly.** One low finding (**F5**): our per-interval `oiChange` on the resampled leg carries the
captured 3-min Δ rather than recomputing `bucket_end − prev_bucket_end` (55,800 vs the endpoint-diff
33,860 = oipulse); absolute OI is exact and the interpretation direction still agrees. See
`phase-4-wave1-value-verify-runbook.md` (Part A results).
