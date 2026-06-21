# Phase 4 · Wave 3 — Multiple OI Chart (manual test)

The oipulse **Multiple OI Chart** (`docs/oipulse-study/options/multiple-oi-chart.md`), React route
`/options/multiple-oi-chart`, mega-menu **Options → Multiple OI Chart**. Multi-select several option legs
("57200 CE", "57100 PE") and overlay each leg's **OI line** (right axis) + the **underlying price** (left
axis, dotted) on one chart.

## What was built
- **BE** `GET /api/v1/market/options/multiple-oi` (`OptionsAnalyticsController`): `leg` is a **repeated**
  param (`?leg=57200 CE&leg=57100 PE`). **One** `reader.series()` read (all strikes for the session) is
  folded into one OI line per requested leg + a single underlying-price (spot) line. The leg join key is
  scale-normalised (`strike.stripTrailingZeros().toPlainString()+"|"+side`). Map envelope `{items, spot,
  underlying, expiry, interval, asOf}`. `400` when no leg is given; `422` only when the underlying has no
  snapshot.
- **FE** `LegMultiSelect` atom (cloned from ColumnSettings — searchable native-checkbox dropdown, no custom
  ARIA) + `useMultiLegOiChart` + `multiLegOiSeries` fold + `MultiLegOiChart` (dual-axis multi-line) + lazy
  `MultipleOiChartPage` (Strike list × CE/PE, ATM CE+PE default, removable colour-swatch chips).

## Preconditions
- Stack up; sign in. The strike list + ATM come from the live `/chain-table` (no snapshot needed). The OI
  lines need captured options snapshots for the session; off-hours with no capture → the empty state.

## Steps
1. Open **Options → Multiple OI Chart**.
2. Pick an underlying + expiry. The picker defaults to **ATM CE + ATM PE** (two chips appear).
3. Open **Select strikes ▾**, search a strike, tick a few legs (mix CE + PE); press **Go**.
4. Verify the dual-axis chart: **underlying price** as a blue **dotted** line on the **left** axis; one
   **coloured OI line per leg** on the **right** axis; the legend lists `Price` + each leg; the chip
   colour swatch matches its line colour.
5. Toggle a legend entry to hide/show a line; scrub the **dataZoom** slider.
6. Remove a chip (the **×**, aria-label "Remove <leg>") — its line disappears. With **no** legs selected the
   page shows "Select ≥1 strike" (Go fires nothing — the hook is disabled until ≥1 leg).
7. Switch **Interval** (incl 1 min) + Go; toggle **History** + a past date. Mobile (~480px): the chart resizes.

## Faithful divergences (documented)
- **REST/poll, not socket** — oipulse subscribes `OD_OPT_CHART_*` per leg after selection; we are
  request/response like every other AY OI page. (Disclosed; the data is identical, just not push-live.)
- **Underlying price folded from the same read** — oipulse sources it from a separate
  `EQUITY_UNDERLYING_DATA` channel; we take the chain-wide `spot_price` per bucket from the same `series()`
  read (one fewer round-trip, visually identical).
- **Dotted** underlying line (oipulse) — a deliberate divergence from the repo's dashed reference lines.

## Verify (green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
BE: `mvnw -pl services/market-data-service -am test -Dtest='MultipleOiIntegrationTest'`
Contract: recaptured + TS regen — additive (new path), ci-contracts WARN.
