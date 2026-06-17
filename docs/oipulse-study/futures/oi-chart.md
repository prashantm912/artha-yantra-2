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

**Toolbox** has 6 actions (zoom, restore-zoom, line [default], bar, restore-chart, save-image);
line/bar is a first-class toggle — the bar form colours OI by up/down. The hover tooltip surfaces
the OI-interpretation label per point (`toolTipData`).

## Chart library
**ECharts** — candlestick + line, dual y-axis, `dataZoom` slider, `toolbox`, `markPoint`.

## Vue component state (confirmed)
```
selectedFutures, selectedExpiry, selectedAvailableDate, selectedTimeInterval,
availableFuturesData, availableExpiryData, availableDate, availableModeOfData,
futuresDataWiseAvailableExpiry,
timeInterval,     // [{text:"1 min",value:1},{text:"3 min",value:3},...{value:60}]
candleData,       // array (numeric keys 0..96) — structure unclear; may be raw rows
priceOiData,      // main chart data object (see below)
socketSubscribedEvents,   // ["FD_OIA_BANKNIFTY-I"] — same socket as OI Analysis
stLastUpdatedAt
```

`priceOiData` (confirmed chart data object):
```json
{
  "toolTipData": ["Long Build Up", "Short Build Up", ...],
  "xAxisData": ["09:18", "09:21", ...],
  "yAxisOiData": ["2269770", "2273190", ...],
  "yAxisCandlestickData": [["57280.00","57312.40","57231.60","57420.00"], ...],
  "yAxisVolumeData": [[0, 52740, 1], [1, 10140, -1], ...]
}
```
- `yAxisCandlestickData` format: `[open, close, low, high]` (NOT standard OHLC — same as options-chart)
- `yAxisVolumeData`: `[index, volume, 1/-1]` color flag (1=green/up, -1=red/down)
- `toolTipData`: OI interpretation strings per interval

**Time intervals**: 1/3/5/10/15/30/60 min — **has 1-min option** (unlike OI Analysis table which starts at 3).
**No `stSelectedTimeInterval` in API request** — time interval is client-side aggregation from 1-min base data.

## Socket subscriptions
- `FD_OIA_BANKNIFTY-I` — same socket as OI Analysis page; pattern `FD_OIA_{SYMBOL}-{EXPIRY}`

## Data source / API
Same instrument/date discovery as oi-analysis, plus the chart-data endpoint:
```
POST /api/futures/getselectedfuturesalldataforchart
Body: {
  "stSelectedFutures": "BANKNIFTY",
  "stSelectedExpiry": "I",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedModeOfData": "live"
}
```
**No time interval in request** — server returns 1-min base data; Vue aggregates client-side.
Candles from OHLC, OI line from `inOi`. (Note the `…alldataforchart` variant vs `…alldata` for the table.)

> **API pattern (whole app):** per area → `getavailable<area>data` (instruments), `getselected<area>date` (dates), `getselected<area>alldata` (table rows), `getselected<area>alldataforchart` (chart series).

## Interpretation (how to trade)
- The dual-axis OI-vs-price chart is the per-strike decision tool; its left/right axis scaling is
  intentional — don't independently auto-scale both axes.
- OI/LTP "X-crossover": a steep X between the OI line and the price/premium line marks momentum
  (method doc §6). Best read on 15-min.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- `ay-echart` combo: candlestick (price, right axis) + line (OI, left axis), dataZoom slider, day extreme markers.
- Bind to same futures OHLC+OI series as the table; one fetch, two renders (table vs chart).

## Screenshot
ss_1836wtxq1 (BANKNIFTY price candles + OI line, dataZoom slider).
