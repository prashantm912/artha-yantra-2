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

## Data source
Identical to Advance Chart — `/api/trading-view/*` (`getcandledata`, `getlistofsymbols`,
`getservertime`, `getallstudytemplates`), once per frame.

## Replication notes (→ ArthaYantra)
- A CSS grid of N Advance-Chart components, each independently configurable. Optional symbol/interval sync.
- Reuse the single-chart component; parameterize per cell.

## Screenshot
ss_59874k4zz (2-up: BANKNIFTY + NIFTY, 3m, identical overlays).
