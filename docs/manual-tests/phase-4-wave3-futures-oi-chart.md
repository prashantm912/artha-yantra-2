# Phase 4 · Wave 3 — Futures OI Chart (manual test)

The oipulse **Futures OI Chart** (`docs/oipulse-study/futures/oi-chart.md`), React route `/futures/oi-chart`,
mega-menu **Futures → OI Chart**. The dual-axis OI-vs-price combo for one index future: price
**candlesticks** (right axis) + the **OI line** (left axis), with day high/low markers, a dataZoom
scrubber and the save/zoom toolbox.

## What was built
- **BE** `GET /api/v1/market/futures/oi-chart` (`FuturesOiChartService`): for the chosen index it resolves
  the **front** FUT contract (the same monthly ladder `FuturesPinner` pins), reads that contract's intraday
  **1m candles cache-first** via the generic `CandleQueryService` (real OHLC — the identical mechanism the
  Straddle Chart uses; no new capture), folds them to the requested minute interval, and **left-joins the
  OI line** from the existing `FuturesSnapshotReader.series()` read. Map envelope `{items, underlying,
  tradingsymbol, expiry, interval, asOf}` — no new typed schema.
- **Two-table session alignment**: candle buckets (1m-aggregated) and OI buckets (snapshots, re-keyed) both
  align to the 09:15-IST open via the same `bucketStart`, so OI lands on the matching candle. A candle with
  no OI sample carries `null` (the line gaps it); an OI sample with no candle creates no phantom candle.
- **FE** `FuturesOiChartPage` + `useFuturesOiChart` + `futuresOiChartSeries` fold + `FuturesOiChart` (dual-axis
  ECharts candlestick + OI line), lazy-loaded (ECharts chunk).

## Preconditions
- Stack up (`./ay.ps1 up`); sign in (owner password).
- **Candles** backfill from broker history on demand (cache-first), so the price series renders for any
  listed index future on a trading session — even off-hours (a never-warmed contract may show empty until
  history accrues). The **OI line** is forward-only (live capture); a past session with no captured OI shows
  candles with an empty OI line. In **mock** the candle path fabricates 1m bars on a trading day.

## Steps
1. Open **Futures → OI Chart** (`/futures/oi-chart`).
2. Pick an index (NIFTY 50 / NIFTY BANK / SENSEX); Interval **3 min** (default); press **Go**.
3. Verify: green/red **candlesticks** on the **right** price axis; a blue **OI line** on the **left** axis;
   the **Day High** (▲) + **Day Low** (▼) markers on the candles; a **dataZoom** slider below; the header
   shows the resolved **Contract** (e.g. `NIFTY26JUNFUT`), Expiry, Interval, Last-updated.
4. Hover — the tooltip shows `O · H · L · C` + the bucket's `OI`.
5. Switch **Interval** including **1 min** + Go — candles re-aggregate; at 1 min the OI line floors to ~3-min
   grain (sparser points), candles stay 1-min (see divergences).
6. Toggle **History** (mode) — the chart scopes to the selected IST day.
7. Toolbox (top-right): box-zoom, restore, save-as-image. Mobile (~480px): the chart resizes to width.

## Faithful divergences (documented, acceptable)
- **OI line resolution** — oipulse offers a 1-min OI option; our `OiInterval` has no 1-min member (min 3m),
  so the OI line is read at 3m and re-keyed onto the candle grid. On the **1-min** candle interval the OI
  line therefore floors to ~3-min grain (honest, not fabricated). The **candles** are real 1-min-aggregated
  OHLC at every interval.
- **Front contract only** — the page charts the active front month; the oipulse Expiry (I/II/III) selector
  is deferred (the BE accepts `expiry` but the page does not surface it yet).
- **Toolbox** has zoom / restore / save-image; oipulse's line↔bar magicType toggle (bar-form OI colouring)
  and the refresh icon are deferred.
- **No volume sub-pane** and **no per-point OI-interpretation tooltip label** (that label lives on the OI
  Analysis table page) — minimal cut.
- **Sub-tabs** `[Oi Chart | Futures Analysis]` are mega-menu siblings, not in-page tabs.

## Verify trio (green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
BE: `mvnw -pl services/market-data-service -am test -Dtest='FuturesOiChartIntegrationTest,FuturesAnalyticsControllerIntegrationTest'`
Contract: recaptured (`-Dcontracts.capture=true`) + TS regen — additive (new path), ci-contracts WARN.
