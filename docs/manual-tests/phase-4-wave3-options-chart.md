# Phase 4 · Wave 3 — Options Chart (manual test)

The oipulse **Options Chart** (`docs/oipulse-study/options/options-chart.md`), React route
`/options/options-chart`, mega-menu **Options → Options Chart**. Per leg (Call + Put of one strike): the
option-**premium candlestick** (right axis) + the **OI line** (left axis) + a **VWAP** overlay, with day
high/low markers and a dataZoom scrubber. A **Show** toggle picks which legs render + the layout.

## What was built
- **BE** `GET /api/v1/market/options/options-chart` (`OptionsOiChartService`): resolves the strike's CE +
  PE legs, reads each leg's intraday **1m premium candles cache-first** via `CandleQueryService` (real
  OHLC — the `StraddleChartService` mechanism, legs **not** summed), folds to the requested interval, and
  **left-joins OI + IV** from a single `OptionsSnapshotReader.strikeSeries` read (re-keyed onto the same
  09:15-IST candle grid, last-wins). **One fetch returns both legs**. Map envelope (no typed schema).
  422 unlisted strike; 400 off-set interval.
- **FE** `useOptionsChart` + `optionsChartSeries` fold (reuses `vwapOf`) + `OptionsLegChart` (dual-axis
  candlestick + OI line + VWAP) + lazy `OptionsChartPage` (Strike+ATM selector, raw-minutes interval
  1/3/5/10/15/30/60, **Show** toggle).

## Preconditions
- Stack up; sign in. Premium **candles** backfill from broker history on demand (render for any listed
  strike on a trading session). The **OI/IV** line is forward-only (live options-chain capture); a past
  session with no captured snapshots shows candles with an empty OI/IV line. Mock fabricates 1m option
  bars on a trading day.

## Steps
1. Open **Options → Options Chart**.
2. Pick an index + expiry; the **Strike** defaults to ATM; Interval **3 min**; **Show** = Both
   side-by-side; press **Go**.
3. Verify per leg: green/red **premium candlesticks** (right axis); a blue **OI line** (left axis); an
   orange **VWAP** line; **Day High** (▲) / **Day Low** (▼) markers; a **dataZoom** slider; the header
   shows Underlying LTP / DO / Strike / Interval / Last-updated.
4. **Show** toggle — *Both side-by-side* (CE left, PE right on desktop), *Both up-down* (stacked), *Call
   only*, *Put only*. The two `Call Option Chart` / `Put Option Chart` titles match the spec.
5. Hover — tooltip shows `O · H · L · C`, `OI`, `VWAP`.
6. Switch **Interval** incl **1 min** + Go — candles re-aggregate; at 1 min the OI/IV floors to ~3-min
   grain (sparser points, bridged).
7. Toggle **History** + a past date — the chart scopes to that IST day. Mobile (~480px): legs stack.

## Faithful divergences (documented)
- **IV sub-pane + Volume sub-pane deferred** — the `iv` value is returned on every candle (free from the
  snapshot read) and surfaces in a follow-up that adds the stacked multi-grid panes; no backend/contract
  change needed. The minimal cut ships candle + OI line + VWAP + day H/L (the page core).
- **OI/IV line resolution** floors to ~3m grain on the 1-min interval (`OiInterval` read at M3 + re-keyed,
  same as the futures OI Chart); the candles are real per-interval OHLC.
- **Candle close ≠ snapshot LTP** — the candle body is the broker 1m close; OI/IV come from the snapshot.
  Two sources at two cadences; never cross-sourced.

## Verify (green at build)
`Push-Location frontend-react; npm run lint; npm run test:ci; npm run build`
BE: `mvnw -pl services/market-data-service -am test -Dtest='OptionsOiChartIntegrationTest,OptionsStraddleChartIntegrationTest'`
Contract: recaptured + TS regen — additive (new path), ci-contracts WARN.
