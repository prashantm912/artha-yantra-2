# Options Chart — `/app/options-analysis/options-chart`

**Purpose:** candlestick chart of the **option premium itself** for a strike's Call and Put legs,
with OI, VWAP, IV and Volume overlays — read the premium's own price action + OI/IV context.
Sub-tabs: `Options Chart | Options Analysis`.

## Layout
```
sub-tabs: [ Options Chart ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Expiry[30-Jun-2026▾]  Strike[57100▾]  Interval[3 min▾]  Show[Both (Side By side)▾]  [Go]
        Underlying: NIFTY BANK at 57198.8, Chg 384.00 (0.68%) as on 15 Jun 2026 23:45 IST
        Call Option Chart                                    Put Option Chart
┌ candlestick + overlays ───────────────┐   ┌ candlestick + overlays ───────────────┐
│ candles = CE premium (right axis)     │   │ candles = PE premium (right axis)     │
│ blue line = OI (left axis)            │   │ blue line = OI (left axis)            │
│ red line = VWAP                       │   │ red line = VWAP                       │
│ day H/L markers (green ▲ / red ▼)     │   │ day H/L markers                       │
│ ── IV sub-pane (green line) ──        │   │ ── IV sub-pane ──                     │
│ ── Volume sub-pane (green/red bars) ──│   │ ── Volume sub-pane ──                 │
│ ── dataZoom slider ──                 │   │ ── dataZoom slider ──                 │
│ legend: ▭Candles ●Oi ●VWAP ●IV ▭Vol  │   │ legend: ▭Candles ●Oi ●VWAP ●IV ▭Vol  │
│ watermark BANKNIFTY-260630-57100-CE   │   │ watermark BANKNIFTY-260630-57100-PE   │
└────────────────────────────────────────┘   └────────────────────────────────────────┘
```

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Name | 9 Index + 211 Stocks | |
| Date | date picker | |
| Expiry Date | YYMMDD | |
| Strike Price | 193 strikes | |
| Interval | `1`(1 min), `3`, `5`, `10`, `15`, `30`, `60` | **Has 1-min option** |
| Show | `SBS`(Both Side By side), `UAD`(Both Up and down), `CALL`(Call only), `PUT`(Put only) | 4 options |
| Go | button | |

## Vue component state
```
fields: selectedOptions, selectedAvailableDate, selectedAvailableExpiryDate, selectedStrikePrice,
  selectedStrikePriceForTable, selectedTimeInterval, selectedShowCriteria,
  availableStrikePrices, timeInterval, availableShowCriteria,
  showCallChart, showPutChart, chartShowCriteriaGrid,
  callChartData, putChartData, underlyingDetails, stLastUpdatedAt, noOfDecimalPoints,
  socketSubscribedEvents
```

`callChartData` / `putChartData` structure:
```json
{
  "typeOfData": "call",
  "xAxisData": ["09:18", "09:21", ...],
  "yAxisOiData": [137250, 140820, ...],
  "yAxisIvData": [13.03, 13.72, ...],
  "toolTipData": ["Short Build Up", ...],
  "yAxisCandlestickData": [["811.25","782.45","760.95","858.75"], ...],
  "yAxisVolumeData": [[0, 39600, -1], [1, 25680, -1], ...]
}
```
`yAxisCandlestickData` order: `[open, close, low, high]` (NOT standard OHLC order).
`yAxisVolumeData`: `[index, volume, -1]` — third element likely color flag.
`chartShowCriteriaGrid`: `{lg:6, md:6, sm:12, xs:12}` (side-by-side) → `{lg:12, md:12, sm:12, xs:12}` (up-and-down/single).

## Socket subscriptions
- `OD_OPT_CHART_BANKNIFTY_260630_57200` — live option price/OI ticks
- `EQUITY_UNDERLYING_DATA_NIFTY BANK` — underlying LTP

## Components (per chart, ×2)
| Element | Type | Axis | Source |
|---|---|---|---|
| Premium candles | ECharts candlestick | right | `inOpen/inHigh/inLow/inClose` (option premium) |
| OI line | line (blue) | left | `inOi` |
| VWAP line | line (red) | right | computed from premium+volume |
| Day H/L markers | markPoint | — | premium day high (green ▲) / low (red ▼) |
| IV | sub-pane line (green) | own | `inIv` |
| Volume | sub-pane histogram | own | `inVolume` (green/red) |
| dataZoom | range slider | x | scrub time |
| Legend | bottom | — | Candles / Oi / VWAP / IV / Volume (toggleable) |

## Data source / API
`POST /api/options/getselectedoptionsalldataforchart` →
```json
{ "stOptionsType":"PE", "inStrikePrice":"57100", "stTime":"09:16:00",
  "inOi":"17130", "inOpen":780,"inHigh":780,"inLow":511,"inClose":518.3,
  "inIv":19.18, "inVolume":7170 }
```
Same endpoint as Options OI Chart but consumed as candles: OHLC→candlestick, `inOi`→OI line,
`inIv`→IV pane, `inVolume`→volume pane. CE/PE split by `stOptionsType`; `Show` picks which leg(s) render.

## Replication notes (→ ArthaYantra)
- Per leg: `ay-echart` (or lightweight-charts) candlestick of premium + OI line (left axis) + VWAP + IV pane + Volume pane + dataZoom.
- `Show` toggles single vs side-by-side CE/PE. One fetch yields both legs (filter by `stOptionsType`).

## Screenshot
ss_7245ubu5z (BANKNIFTY 57100 CE + PE premium candles, OI/VWAP/IV/Volume, side-by-side).
