# Vix & Index — `/app/vix-price` (title: "Vix & Price Chart")

**Purpose:** visualize India VIX against index price intraday to read the (usually inverse)
volatility↔price relationship. Two stacked dual-axis line charts.

## Layout
```
sub-tabs: [ Vix & Index ] [ Tool ]
ticker strip
┌ filter bar ─────────────────────────────────────────────────────────────────────────┐
│ Mode: (•)Live data ( )Historical    Select Date:[Mon, Jun 15 2026 📅]   Action:[Go]   │
│                                                            Data Auto-updated At: -     │ (right)
└───────────────────────────────────────────────────────────────────────────────────────┘
              India Vix Vs. Nifty            (centered title)
┌ line chart 1 (dual y-axis) ──────────────────────────────────────────────────────────┐
│ left axis: Vix (13.9–14.8)   right axis: Nifty price (23,820–24,030)   x: 09:09–15:26  │
│ blue line = Vix, orange line = Price        watermark "Oi Pulse"   toolbox top-right   │
│ legend (bottom, centered): ●Vix  ●Price                                                │
└───────────────────────────────────────────────────────────────────────────────────────┘
              India Vix Vs. Banknifty
┌ line chart 2 (same structure, right axis = Banknifty 57,100–57,800) ──────────────────┐
└───────────────────────────────────────────────────────────────────────────────────────┘
```

## Components
| Component | Type | Position | Values | Behavior / cues |
|---|---|---|---|---|
| Mode | radio | filter L | Live data / Historical | |
| Select Date | date picker | filter center | day to plot | |
| Go | button (red) | filter | — | re-fetch |
| Data Auto-updated At | text | filter R | timestamp ("-" if n/a) | live auto-refresh indicator |
| Chart title ×2 | centered text | above each chart | "India Vix Vs. Nifty" / "…Vs. Banknifty" | |
| Line chart ×2 | **ECharts** line, dual y-axis | body | see below | hover tooltip, toolbox |
| Vix series | line | both charts | blue, left axis | `INDIA VIX` |
| Price series | line | chart1 / chart2 | orange, right axis | `NIFTY 50` / `NIFTY BANK` |
| Legend | bottom center | per chart | `●Vix` (blue) `●Price` (orange) | toggle series |
| Toolbox | icon row | chart top-right | zoom-select, restore, line/bar toggle, refresh, **download PNG** | ECharts `toolbox` (dataZoom/restore/magicType/saveAsImage) |
| Watermark | faint text | chart center | "Oi Pulse" | branding |

Axes auto-scaled to each series' range (independent left/right). Grid lines faint grey on dark.

## Chart library
**ECharts** (canvas — 2 `<canvas>`; not exposed on `window`, imported as Vue component). Toolbox + dual-axis + watermark + bottom legend are ECharts idioms. (Not Highcharts/Chart.js/ApexCharts.)

## Vue component state (confirmed — 11 keys)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedAvailableDate ("2026-06-16"),
selectedModeOfData ("live"),
availableDate,           // dropdown options from date API
availableModeOfData,
vixNiftyData,            // pre-computed chart series for Chart 1
vixBankniftyData,        // pre-computed chart series for Chart 2
socketSubscribedEvents,  // ["EQ_VPD_NIFTY 50","EQ_VPD_NIFTY BANK","EQ_VPD_INDIA VIX"]
stLastUpdatedAt          // "-" initially; updates on data refresh
```

**vixNiftyData structure (confirmed)**:
```json
{
  "xAxisData": ["09:09","09:16","09:17",...],  // 361 entries (1-min; first "09:09" is PEOD baseline)
  "yAxisVixData": [14.3525, 13.95, ...],       // INDIA VIX values (floats)
  "yAxisPriceData": [23923.9, 23909.35, ...]   // NIFTY 50 futures LTP (floats)
}
```
`vixBankniftyData` has same structure with NIFTY BANK prices in `yAxisPriceData`.

**Socket subscriptions (live mode)**:
`["EQ_VPD_NIFTY 50","EQ_VPD_NIFTY BANK","EQ_VPD_INDIA VIX"]` — pattern `EQ_VPD_{INDEX_NAME}`.
Live ticks push new data points; chart auto-extends without re-fetch.

**Socket payload ([Phase B confirmed](../PHASE-B-FINDINGS.md))** — channels `EQ_VPD_{NAME}`
(`NIFTY 50` / `NIFTY BANK` / `INDIA VIX`). Live frame is a **keyed object** `{stName, stDateTime, inLtp}`
where `stDateTime` is a full ISO timestamp `YYYY-MM-DDTHH:MM:SS`:
```json
{"stName":"INDIA VIX","stDateTime":"2026-06-18T09:10:00","inLtp":13.1875}
```
These price channels also emit a **snapshot frame on subscribe**, so the schema is capturable even pre-open.

## Data source / API
| Call | Method | Request | Response |
|---|---|---|---|
| `/api/vix-price/getdatesofvixpricedataforchart` | POST | `{stSelectedModeOfData}` | `{...,data:[{text,value}]}` available dates |
| `/api/vix-price/getvixpricedataforchart` | POST | `{stSelectedAvailableDate, stSelectedModeOfData}` | `{...,data:[ row ]}` |

Row:
```json
{ "stTime":"09:09:00",
  "obVixData":[ {"NIFTY BANK":57679.65}, {"NIFTY 50":23984.85}, {"INDIA VIX":14.7175} ] }
```
`obVixData` = array of single-key objects (one per instrument) per minute. Chart1 uses INDIA VIX + NIFTY 50; chart2 uses INDIA VIX + NIFTY BANK.

## Interpretation (how to trade)
- VIX is the "fear index" — the market's collective perceived risk (contrast with IV, which is the seller's perceived risk). It is computed from Nifty OTM call/put premiums. Rising VIX is bearish, falling VIX is bullish; the CE-side reading is proportional to price and the PE-side is inverse.
- Signal: a steep VIX–Price "X / V-shape" crossover marks a momentum-build-up trigger. Ignore VIX when it is erratic. Although computed from Nifty options, it applies to Bank Nifty as well.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Two `ay-echart` line charts, dual y-axis, one minute-series of {time, INDIA VIX, NIFTY 50/BANK}.
- Bind Vix to left axis (tight range), price to right axis. Blue/orange. Bottom legend, PNG export.
- Source: our minute store for INDIA VIX + index price for the selected date; Live mode auto-refresh.

## Screenshot
ss_0313azh2y (both charts, inverse Vix/price visible).
