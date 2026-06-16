# Options OI Chart — `/app/options-analysis/chart`

**Purpose:** chart view of the per-strike Call/Put OI data (chart counterpart of Options OI Analysis).
Compares Call vs Put OI and each side's OI against its premium, intraday. Sub-tabs: `Oi Chart | Options Analysis`.
Same filter bar as Options OI Analysis (Name, Date, Expiry, Strike, Time Interval, Go).

## Layout
```
sub-tabs: [ Oi Chart ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Expiry[30-Jun-2026▾]  Strike[57100▾]  Time Interval[3 min▾]  [Go]
                          Call Vs. Put Oi Analysis   (centered)
┌ line chart (full width) ──────────────────────────────────────────────────────────────────────────┐
│ y: OI (0–70,000)   x: time 09:18–15:27   green line = Call OI, red line = Put OI                     │
│ watermark "Oi Pulse / BANKNIFTY 57100"   toolbox   legend: ● Call OI  ● Put OI                       │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
        Call Oi Analysis                              Put Oi Analysis
┌ dual-axis line ───────────────────┐   ┌ dual-axis line ───────────────────┐
│ left: OI  right: Price            │   │ left: OI  right: Price            │
│ green OI line + orange Price line │   │ red OI line + orange Price line   │
│ legend: ● OI  ● Price             │   │ legend: ● OI  ● Price             │
└────────────────────────────────────┘   └────────────────────────────────────┘
```

## Components
| Chart | Type | Series | Axes |
|---|---|---|---|
| Call Vs. Put Oi Analysis | ECharts line (full width) | Call OI (green), Put OI (red) | single y = OI, x = time |
| Call Oi Analysis | ECharts dual-axis line | OI (green, left), Price (orange, right) | left OI / right premium |
| Put Oi Analysis | ECharts dual-axis line | OI (red, left), Price (orange, right) | left OI / right premium |

Each chart: ECharts toolbox (zoom/restore/line-bar/refresh/save PNG), "Oi Pulse / BANKNIFTY 57100" watermark, bottom legend (clickable to toggle series).

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Name | same 9 Index + 211 Stocks | |
| Date | date picker | |
| Expiry Date | YYMMDD values | |
| Strike Price | 193 strikes, ATM default | |
| Time Interval | `3`(3 min), `5`, `10`, `15`, `30`, `60`(60 min) | **no 1-min, no Full Day/null** — starts at 3 min |
| Go | button | fetch |

**Time interval is client-side aggregation** — the API request does NOT include `stSelectedTimeInterval`. Server returns 1-min resolution; client aggregates to selected interval.

## Vue component state fields
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton, selectedModeOfData,
selectedOptions, selectedAvailableDate, selectedAvailableExpiryDate,
selectedStrikePrice, selectedStrikePriceForTable, selectedTimeInterval,
availableModeOfData, availableOptionsData, availableDate, availableExpiryDate,
availableStrikePrices, timeInterval,
callChartData, putChartData, callPutOiData,
underlyingDetails, socketSubscribedEvents, socketDataUpdateTimeoutId
```

Chart data structures:
- `callPutOiData`: `{xAxisData:["09:18","09:21",...] (88pts), yAxisCallOiData:["137250","140820",...], yAxisPutOiData:["95340","103740",...]}`
- `callChartData`: `{typeOfData, xAxisData, yAxisOiData:["137250",...], yAxisPriceData:[782.45,...], toolTipData:["Short Build Up",...]}`
- `putChartData`: same structure as callChartData
- `toolTipData` = OI interpretation label per interval (computed client-side)

## Socket subscriptions
- `OD_OI_CHART_BANKNIFTY_260630_57200` — live OI+price updates for this strike

## Data source / API
```
POST /api/options/getselectedoptionsalldataforchart
Body: {
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "inSelectedStrikePrice": "57200",        ← NOTE: "in" prefix, not "st"
  "stSelectedModeOfData": "live",
  "stSelectedAvailableExpiryDate": "260630"
  // NO stSelectedTimeInterval — client aggregates
}
Response: {
  "status": "success",
  "data": [
    { "stOptionsType": "PE", "inStrikePrice": "57200", "stTime": "09:16:00",
      "inOi": "90450", "inOpen": 749.1, "inHigh": 749.1, "inLow": 630.75,
      "inClose": 686, "inIv": 18.78, "inVolume": 14790 },
    { "stOptionsType": "CE", "inStrikePrice": "57200", "stTime": "09:16:00",
      "inOi": "129900", "inOpen": 811.25, "inHigh": 858.75, "inLow": 761,
      "inClose": 775.25, "inIv": 13.03, "inVolume": ... }
    ...
  ]
}
```
Row has `inIv` (IV per minute) — not present in OI Analysis endpoint. Interleaved CE/PE by stTime (1-min resolution).

## Replication notes (→ ArthaYantra)
- Three `ay-echart` line charts off ONE CE/PE strike series:
  - top: Call OI vs Put OI (single axis);
  - bottom-left: Call OI vs Call premium (dual axis);
  - bottom-right: Put OI vs Put premium (dual axis).
- Toggle Call/Put by `stOptionsType`; premium = `inClose`.

## Screenshot
ss_0318y6owy (Call vs Put OI + Call/Put OI-vs-Price, BANKNIFTY 57100).
