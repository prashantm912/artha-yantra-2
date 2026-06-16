# FII Long Short Ratio — `/app/fii-dii/fii-long-short-ratio`

**Purpose:** FII index long-short ratio (% long) overlaid on Nifty price — read FII positioning vs the
market. Low long% = FII bearish, high = bullish. No sub-tabs.

## Layout
```
sub-tabs: [ FII Long Short Ratio ] [ FII & DII Activity ]   ;  ticker strip
 FII Long Short Ratio - As on 15-06-2026, FII are long for 12.70%   (dynamic title)
┌ combo chart (dual y-axis) ────────────────────────────────────────────────────────────────────────┐
│ left axis: Price (22,000–27,000)   right axis: FII L.S.R % (0–30%)   x: dates (2025-07 … 2026-06)    │
│ green/red CANDLES = Nifty (futures)   red LINE = FII IDX Long Short Ratio                            │
│ ── dataZoom slider ──   legend: ● FII IDX Long Short Ratio   ▭ Nifty Candles   watermark OiPulse     │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
No filter bar — single combo chart over ~400 days.

## Components
| Series | Type | Axis | Source |
|---|---|---|---|
| Nifty Candles | ECharts candlestick | left (Price) | `inNiftyFutOpen/High/Low/Close` |
| FII IDX Long Short Ratio | line (red) | right (%) | `inFutIdxLSR` |
| (Stock LSR available) | — | — | `inFutStkSLR` |

Title dynamically states current FII long% (12.70%). dataZoom slider; ECharts toolbox.

## Vue component state (confirmed — minimal, chart-only page)
```
chartData,       // {xAxisDate, yAxisFutIdxLSR, yAxisNiftyFutCandlestickData}
stEndDate        // "15-06-2026"
```
Only 2 state keys — no filter controls, no table, no socket.

## chartData structure (confirmed)
```json
{
  "xAxisDate": ["2024-10-29", "2024-10-30", ...],       // 420 ISO dates
  "yAxisFutIdxLSR": ["40.05", "35.66", "22.27", ...],  // FII long% as strings
  "yAxisNiftyFutCandlestickData": [
    [24368.8, 24477.7, 24164, 24501.3],                  // [Open, High, Low, Close]
    [24427.95, 24371.05, 24314.95, 24520],
    ...
  ]
}
```
`yAxisFutIdxLSR` values are STRINGS (e.g. `"40.05"`, not `40.05`).
`yAxisNiftyFutCandlestickData` entries are arrays `[O, H, L, C]`.

## Data source / API
`POST /api/fii-dii/getfiilongshortratio` — no request params (returns full ~420-day history):

Response populates `chartData` directly (no intermediate transformation). The chart `xAxisDate` drives the x-axis; `yAxisFutIdxLSR` drives the ratio line; `yAxisNiftyFutCandlestickData` drives the NIFTY candles.

## Replication notes (→ ArthaYantra)
- `ay-echart` combo (dual y-axis): Nifty futures candles (left price axis) + FII index LSR% line (right axis).
- dataZoom slider; dynamic title: "FII Long Short Ratio - As on {date}, FII are long for {lsr}%".
- LSR = FII index-futures longs / (longs + shorts) × 100. Low = bearish FII; high = bullish.
- `yAxisFutIdxLSR` is string — parse to float before plotting.

## Screenshot
ss_1081nm23w (Nifty candles + FII LSR line, FII long 12.70%).
