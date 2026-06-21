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
- **Break-badge price**: oipulse's parenthetical price semantics weren't pinned; we show the bucket ltp.
- **Header labels** drop the `Call`/`Put` prefix (the CALL/PUT colgroup carries it) — brevity divergence.
