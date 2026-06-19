# Multiframe Chart — `/app/multi-frame-advance-chart`

**Purpose:** view multiple **Advance Charts side-by-side** to compare instruments/timeframes at once.
Each frame is a full, independent Advance Chart instance.

## Layout
```
header (global)
┌ Frame 1 (own toolbar) ───────────────┐┌ Frame 2 (own toolbar) ───────────────┐
│ [BANKNIFTY▾][3m▾] Indicators … Refresh││ [NIFTY▾][3m▾] Indicators … Refresh    │
│ BANKNIFTY26JUNFUT·3·NSE candles       ││ NIFTY26JUNFUT·3·NSE candles           │
│ VWAP/VWMA/SuperTrend + OSPL Vol + RSI ││ VWAP/VWMA/SuperTrend + OSPL Vol + RSI │
└───────────────────────────────────────┘└───────────────────────────────────────┘
```
Observed: **2 frames** horizontally (BANKNIFTY-I left, NIFTY-I right). Layout likely supports 2/3/4 frames.

## Components
- Each frame = a complete Advance Chart (see `advance-chart.md` for full toolbar/overlay/datafeed detail):
  symbol▾, interval▾, Indicators, layout, Show Trade history, Audio Alerts, Show Oi Bar (Beta),
  undo/redo, Save▾, ⚙, fullscreen, 📷, Refresh; overlays VWAP/VWMA20/SuperTrend; sub-panes OSPL Volume + RSI.
- Frames are independent (different symbols/intervals per frame).

## Vue component state (confirmed — 2 instances of same advance-chart component)
```
// Frame 1: BANKNIFTY-I, 3-min
// Frame 2: NIFTY-I, 3-min
// Each instance: same 20-key state as advance-chart.md
websiteUrl, enabledNotification, notificationAudioFile, tvWidget, showTradeHistoryFlag,
latestActiveSymbol, prevPaneHeight, resizeTimeout, stCvsId, mouseMoveHandler,
showOiBars, socketReconnecting, socketSubscribedEvents, latestPriceRangeIntervalId,
latestPriceRange, chartEle, chartCvs, chartCvsCtx, oiBarChart, widgetOptions
```
Two independent instances of the same single-chart Vue component rendered side-by-side.

## Data source
Identical to Advance Chart — `/api/trading-view/*` (`getcandledata`, `getlistofsymbols`,
`getservertime`, `getallstudytemplates`), called once per frame (2× each on page load).

## Interpretation (how to trade)
- Intended use is a top-down timeframe cascade (Weekly → Daily → 3-min) for trade confirmation; a Double-SMA(100,200) overlay is a useful daily preset.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- A CSS grid of N Advance-Chart components, each independently configurable. Optional symbol/interval sync.
- Reuse the single-chart component; parameterize per cell.

## Screenshot
ss_59874k4zz (2-up: BANKNIFTY + NIFTY, 3m, identical overlays).
