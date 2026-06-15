# Futures OI Chart — `/app/futures-analysis/oi-chart`

**Purpose:** the chart view of futures OI vs price — candlesticks overlaid with the OI line so you
see OI building/falling against price moves on one timeline. Sub-tabs: `Oi Chart | Futures Analysis`.
Same filter bar as Futures OI Analysis.

## Layout
```
sub-tabs: [ Oi Chart ] [ Futures Analysis ]   ;  ticker strip
filter bar: Mode  Name[BANKNIFTY▾]  Expiry[Current Month▾]  Date[📅]  Time Interval[3 min▾]  [Go]
                                                                       Data last Updated At: -
                         Futures Oi Vs. Price Analysis   (centered title)
┌ combo chart (dual y-axis) ──────────────────────────────────────────────────────────────────────┐
│ left axis: OI (2,300,000–2,420,000)         right axis: Price (57,100–57,800)   x: 09:09–15:24    │
│ green/red CANDLESTICKS = price    blue LINE = OI    watermark "Oi Pulse / BANKNIFTY-I"            │
│ day-low marker (red ▼ "57180")    toolbox top-right                                                │
│ ── dataZoom range slider ──                                                                        │
│ legend: ▭ Candles   ● Oi                                                                           │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Components
| Component | Type | Position | Notes |
|---|---|---|---|
| Filter bar | inputs | top | Mode, Name, Expiry, Date, Time Interval, Go (same as oi-analysis) |
| Title | text | above chart | "Futures Oi Vs. Price Analysis" |
| Candlestick series | ECharts candlestick | right y-axis | price (green up / red down) |
| OI series | ECharts line | left y-axis | blue line |
| Day low/high marker | markPoint | on chart | red ▼ with price label |
| Toolbox | icons | top-right | zoom, restore, line/bar, refresh, save PNG |
| dataZoom slider | range brush | below chart | scrub/zoom the time window |
| Legend | bottom center | — | `▭ Candles` (green) · `● Oi` (blue) |
| Watermark | faint text | center | "Oi Pulse / BANKNIFTY-I" |

## Chart library
**ECharts** — candlestick + line, dual y-axis, `dataZoom` slider, `toolbox`, `markPoint`.

## Data source / API
Same instrument/date discovery as oi-analysis, plus the chart-data endpoint:
`POST /api/futures/getselectedfuturesalldataforchart`
`{stSelectedFutures, stSelectedExpiry, stSelectedAvailableDate, stSelectedModeOfData}` →
```json
{ "stTime":"...", "stDataFetchType":"...", "inOi":"...",
  "inOpen":..,"inHigh":..,"inLow":..,"inClose":.., "inVolume":.. }
```
Candles from OHLC, OI line from `inOi`. (Note the `…alldataforchart` variant vs `…alldata` for the table.)

> **API pattern (whole app):** per area → `getavailable<area>data` (instruments), `getselected<area>date` (dates), `getselected<area>alldata` (table rows), `getselected<area>alldataforchart` (chart series).

## Replication notes (→ ArthaYantra)
- `ay-echart` combo: candlestick (price, right axis) + line (OI, left axis), dataZoom slider, day extreme markers.
- Bind to same futures OHLC+OI series as the table; one fetch, two renders (table vs chart).

## Screenshot
ss_1836wtxq1 (BANKNIFTY price candles + OI line, dataZoom slider).
