# FII Long Short Ratio — `/app/fii-dii/fii-long-short-ratio`

**Purpose:** FII index long-short ratio (% long) overlaid on Nifty price — read FII positioning vs the
market. Low long% = FII bearish, high = bullish. Sub-tabs: `FII Long Short Ratio | FII & DII Activity`.

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

## Data source / API
`POST /api/fii-dii/getfiilongshortratio` →
```json
{ "data":[ { "stFetchDate":"…",
             "inFutIdxLSR":12.70, "inFutStkSLR":…,
             "inNiftyFutOpen":…,"inNiftyFutHigh":…,"inNiftyFutLow":…,"inNiftyFutClose":…,"inNiftyFutVolume":… } ] }   // 400 days
```
`inFutIdxLSR` → ratio line; Nifty futures OHLC → candles.

## Replication notes (→ ArthaYantra)
- `ay-echart` combo: Nifty candles (left) + FII index LSR line (right), dataZoom; dynamic title with latest LSR.
- LSR = FII index-futures long contracts / total (×100). Low = bearish FII, high = bullish.

## Screenshot
ss_1081nm23w (Nifty candles + FII LSR line, FII long 12.70%).
