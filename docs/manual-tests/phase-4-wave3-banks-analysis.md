# Phase 4 · Wave 3 — Banks Analysis (manual test)

The oipulse **Banks Analysis** (`docs/oipulse-study/futures/banks-analysis.md`), React route `/futures/banks`,
mega-menu **Futures → Banks**. A **time × 6-bank OI matrix**: rows = descending intervals, columns = the
top-6 BankNifty banks (HDFC / ICICI / Axis / SBI / Kotak / Indusind). Each cell = **(LTP% / OI%)**
cumulative-from-day-open + a **per-interval** 4-state OI interpretation badge.

## What was built
- **BE** `GET /api/v1/market/futures/banks-analysis` (`FuturesBankAnalysisService`): sector-wide (no
  name/expiry). Anchors on the newest captured bucket's IST day, reads the whole day across the 6 banks via
  `reader.seriesAll`, picks each bank's **front** contract (most captured buckets; tie → nearest expiry),
  and pivots to per-interval rows. Map envelope `{banks, rows, interval, asOf}`. `422` until ≥1 bucket
  accrues; `400` history-without-date.
- **FE** `useBanksAnalysis` (name-free hook) + `BanksAnalysisTable` (raw scrollable matrix, 25/page, Legend)
  + `BanksAnalysisPage` (name-less FilterBar).

## Cell math (faithful, with two deliberate baselines)
- **LTP%** = (curLTP − the captured **day-open**) / day-open · 100 — uses `FutPoint.dayOpen` (the broker
  session open, V015) — strictly more faithful than a first-bucket proxy.
- **OI%** = (curOI − the **first captured** OI of the day) / firstOI · 100 — OI has no captured day-open
  analog, so the first bucket is the baseline (sign-capable, forward-capture).
- **Interpretation badge** = `classify(curLTP − prior-bucket LTP, curOI − prior-bucket OI)` — **per-interval**
  (vs the prior bucket), **NOT** derived from the cumulative %s. So a cell can show a **positive cumulative
  OI%** yet a **Short-Covering** badge (OI fell *this* interval) — this is correct and matches oipulse.

## Preconditions
- Stack up; sign in. **Forward-only**: bank-sector futures OI accrues from capture; before ≥1 bucket the
  matrix 422s → the empty state. In **mock** the analytics read seeded `futures_oi_snapshots` directly (the
  IT seeds a private bank universe).

## Steps
1. Open **Futures → Banks** (`/futures/banks`). There is **no Name / Expiry** filter — only Mode · Date ·
   Interval · Go.
2. Interval **5 min**; press **Go**.
3. Verify a **6-column** matrix (one per bank), rows **newest interval first** (`HH:mm-HH:mm` windows), 25/page.
4. Each cell: a signed **`+x.xx% / +y.yy%`** (LTP% / OI%, green +, red −) over a 4-state badge
   (**L.B.** green / **S.B.** red / **S.C.** blue / **L.U.** orange). The Legend sits below.
5. Confirm cumulative %s **drift across later intervals** (accumulate from the day open), while the **badge**
   reflects only the latest interval's move (a positive cumulative OI% can pair with an OI-down badge).
6. Switch **Interval** (3/10/15/30/60) + Go; toggle **History** + a past date. A bank with no point at an
   interval shows a muted **—**. Mobile (~480px): the matrix scrolls horizontally.

## Faithful divergences (documented)
- **No 15:30-EOD row** — oipulse's post-close adjusted-OI row is a separate NSE source we don't capture.
- **OI%-baseline** is the first captured bucket (sign-capable vs a true prior-close/day-open OI).
- **6 of 17** captured bank futures (config `artha.futures.bank-analysis-stocks`, oipulse column order).
- **No 1-min** (oipulse's banks page starts at 3m); interval set 3/5/10/15/30/60.
- Badge is a **ring** (WCAG contrast) + a **+** prefix on positives — the standard app-wide OI divergences.

## Verify (green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
BE: `mvnw -pl services/market-data-service -am test -Dtest='FuturesBankAnalysisIntegrationTest,FuturesAnalyticsControllerIntegrationTest'`
Contract: recaptured + TS regen — additive (new path), ci-contracts WARN.
