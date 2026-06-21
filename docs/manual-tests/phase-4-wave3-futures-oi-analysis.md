# Phase 4 · Wave 3 — Futures OI Analysis (manual test)

The oipulse **Futures OI Analysis** per-interval table (`docs/oipulse-study/futures/oi-analysis.md`),
React route `/futures/oi-analysis`, mega-menu **Futures → OI Analysis**.

One futures contract's intraday OI table: descending intervals, each row carrying the OI interpretation
and the level break. New-endpoint page — a single small additive BE add (the active-strikes pattern of
"raw points from BE, FE folds every derived column", mirroring the options OI-Analysis strike-series).

## What was built
- **BE** `GET /api/v1/market/futures/oi-analysis-series` (`FuturesAnalyticsController`): for the chosen
  index it anchors on the newest captured bucket, reads that IST day's `reader.series(...)`, and returns
  **one contract's** raw `FutPoint`s (oldest-first) as `{items, underlying, interval, asOf}`. The contract =
  the requested `expiry`, else the **active front** (most captured buckets that day; ties → nearest expiry).
  `422 DATA_GAP` only when the underlying has no snapshot at all; an expiry matching no contract → `200` + empty.
- **BE** `FuturesSnapshotReader.series()` now projects `volume` (added to `FutPoint`) — the one new column
  the table needs. Additive: every other caller reads `FutPoint` by field name; `/oi-analysis` (untyped
  `Map`) just gains a `volume` key (no springdoc drift); only the new path drifts the contract (additive).
- **FE** `FuturesOiAnalysisPage` + `useFuturesOiSeries` + `futuresOiAnalysisFold` (single-contract port of
  the options `oiAnalysisFold`), rendered via the generic `DataTable` (sortable, paginated 25).

## Preconditions
- Stack up (`./ay.ps1 up`); sign in (owner password).
- **Forward-only data**: the page needs ≥2 captured buckets of the index future for that day. In **live**
  during market hours the futures capture accrues automatically. In **mock**, seed `futures_oi_snapshots`
  (see the IT `oiAnalysisSeriesReturnsRawBucketsOldestFirstWithVolume` for the column set) or run during a
  live session. Off-hours with no capture → the empty state ("No intraday futures data for this contract yet.").

## Steps
1. Open **Futures → OI Analysis** (`/futures/oi-analysis`).
2. Pick an index (NIFTY / NIFTY BANK / SENSEX) in the control bar; Interval **3 min** (default); press **Go**.
3. Verify the table renders **newest interval first** (Date Time descending), 25 rows/page with Prev/Next.

## Column checklist (11 columns, oipulse order)
| Column | Expect |
|---|---|
| Date Time | `HH:mm-HH:mm` interval window (bucket start + interval) |
| Total OI | absolute OI (grouped en-IN) |
| Total Chng. In OI | signed: bucket OI − **first captured bucket OI** of the day (green +, red −) |
| Day High / Day Low | the captured session extremes |
| Level Break | `D.H.B (level) ↑` (green) on a new session high, `D.L.B (level) ↓` (red) on a new low, else blank |
| Volume | per-interval traded volume (cumulative day-volume delta; first row / a reset → `—`) |
| LTP | last price |
| LTP Change | signed decimal vs the prior bucket |
| OI Change | signed count vs the prior bucket |
| OI Interpretation | 4-state badge — Long Build Up (green) / Short Build Up (red) / Shorts Covering (blue) / Long Unwinding (orange), with the ↑/↓ glyph (non-colour cue) |

4. Sort by **Total OI** / **OI Change** / **Volume** (click the header) — the active-sort arrow flips, rows reorder.
5. Switch **Interval** (5 / 15 / 30 / 60) + Go — the Date-Time windows widen accordingly.
6. Toggle **History** + pick a past date with captured data — the table scopes to that IST day.
7. Mobile (S24-Ultra ~480px): rows collapse to per-interval cards (Time / Total OI / Chng OI / Vol / LTP / LTP Chg / OI Chg / OI Int).

## Faithful divergences (documented, acceptable)
- **No 15:30-EOD / PEOD row.** oipulse's first row is NSE's post-close clearing-member-adjusted OI from a
  separate source we do not capture — the table shows only intraday (IM) intervals. Same omission as the
  options OI-Analysis page.
- **Total Chng. In OI baseline is sign-capable.** Our day-open OI baseline is the session's **first captured
  bucket** OI (forward-capture from boot), not NSE's prior-close-carried day-open OI. On a declining-OI
  session the cumulative value can differ in **sign**, not just magnitude, from oipulse's.
- **Level Break** fires when the captured `day_high`/`day_low` advances in a bucket (the interval that made
  the new session extreme). This uses the **real** captured extremes (V015) — faithful, not the close-based
  proxy the options page is forced into.
- **Interval set** is 3/5/15/30/60 (our `OiInterval` lacks oipulse's 10m).
- **Reduced universe** for tie-breaking: the active front contract is the one with the most captured buckets.

## Verify trio (already green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
BE: `mvnw -pl services/market-data-service -am test -Dtest='FuturesAnalyticsControllerIntegrationTest,FuturesSnapshotReaderIntegrationTest,Futures*ServiceTest'`
Contract: recaptured (`-Dcontracts.capture=true`) + TS regen (`openapi-typescript@7`) — additive (new path), ci-contracts WARN.
