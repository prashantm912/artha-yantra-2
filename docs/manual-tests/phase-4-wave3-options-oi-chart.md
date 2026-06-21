# Phase 4 · Wave 3 — Options OI Chart (manual test)

The oipulse **Options OI Chart** (`docs/oipulse-study/options/oi-chart.md`), React route `/options/oi-chart`,
mega-menu **Options → OI Chart**. The chart counterpart of Options OI Analysis: three line charts off one
strike's CE+PE series — **Call vs Put OI**, **Call OI vs Call premium**, **Put OI vs Put premium**.

## What was built — ZERO backend
- Rides the **existing** `/oi-analysis/strike-series` feed (`useStrikeSeries`) — `StrikePoint` already carries
  `oi` + `ltp` (premium) per CE/PE bucket. No new endpoint, no contract change.
- **FE** `optionsOiChartFold` (split CE/PE per bucket → Call/Put OI + premium arrays; decimal→number only at
  the chart boundary) + `OptionsOiCharts` (`CallPutOiChart` single-axis + a reusable `OiVsPriceChart`
  dual-axis) + lazy `OptionsOiChartPage` (Strike+ATM selector reusing the OI-Analysis pattern).

## Preconditions
- Stack up; sign in. The strike list + ATM come from the latest `/oi-analysis` bucket; the lines need
  captured options snapshots for the session (forward-only). Off-hours with no capture → the empty state.

## Steps
1. Open **Options → OI Chart**.
2. Pick an underlying + expiry; the **Strike** defaults to ATM; Interval **3 min**; press **Go**.
3. Verify the top chart **Call Vs. Put OI Analysis** — a green **Call OI** line + a red **Put OI** line on one
   OI axis.
4. Below, two dual-axis charts: **Call OI Analysis** (green Call OI left + orange premium right) and **Put OI
   Analysis** (red Put OI left + orange premium right). On desktop they sit side-by-side; on phone they stack.
5. Hover — the cross tooltip reads the OI + premium at that interval. A leg with no snapshot at a bucket gaps
   (connectNulls bridges).
6. Change **Strike** / **Interval** (5/15/30/60) + Go; toggle **History** + a past date.

## Faithful divergences (documented)
- **Three line charts**, not a single combined view — matches the oipulse layout (top Call-vs-Put OI, bottom
  Call/Put OI-vs-premium).
- **Premium = option `ltp`** (the snapshot close) — the same per-bucket close the OI-Analysis table uses.
- Interval set **3/5/15/30/60** (our `OiInterval` lacks oipulse's 1m/10m on this page); toolbox line/bar
  toggle deferred (the repo charts ship line + dataZoom).
- REST/poll, not the oipulse `OD_OI_CHART_*` socket (consistent with every AY OI page).

## Verify (green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
(No BE change — `optionsOiChartFold` vitest + the existing strike-series endpoint cover it.)
