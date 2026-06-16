# Trending OI — `/app/options-analysis/trending-oi`

**Purpose:** track aggregated Call vs Put OI across a chosen **band of strikes** over time, and derive
a directional **sentiment** (bullish/bearish) from the net OI change + PCR. Sub-tabs: `Trending OI | Options Analysis`.

## Layout
```
sub-tabs: [ Trending OI ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]  Time Interval[3 min▾]  [Go]  [Change Strike Prices]  ☐ Show Graph View  ☐ Show positional Data
 Selected Strike Prices: 57100, 57200, 57300, … 58500
        Underlying: NIFTY BANK at 57198.8 …
┌ table (scrollable, paginated 100) ──────────────────────────────────────────────────────────────────┐
│ Date | Time | LTP | Day H/L Break | Chng. In Call OI | Chng. In Put OI | Diff. in OI |               │
│   Direction of chng. | Chng. In Direction | Direction of chng. % | Net PCR | Day High/Low Diff. in OI | Sentiment │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[100▾]                                   1 - 100 of 126
```

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Name | uses `stSelectedAsset` (not `stSelectedOptions`) | different from other options pages |
| Date | date picker | |
| Expiry Date | YYMMDD | |
| Time Interval | `3`, `5`, `10`, `15`, `30`, `60` | no 1-min |
| Go | button | sends `selectedStrikePrices` array |
| Change Strike Prices | button (outline) | modal to pick strikes |
| Show Graph View | checkbox → `showGraphData` | switch table → chart |
| Show positional Data | checkbox → `showPositionalData` | show positional/EOD data |

**Default selected strikes**: 15 near-ATM strikes (56700→58100 with 100-pt spacing for BANKNIFTY ATM ~57200).

## Vue component state
```
showGraphData, showPositionalData, selectedAsset, availableAssetData,
tableData, columns, timeInterval, activeStrikeOiData, pcrChartData,
underLyingAssetData, peodCallPutOiData, oiSnapshotData, timeWiseData,
inDayHighDiffInOi, inDayLowDiffInOi, inSpotDayHigh, inSpotDayLow,
tempCeSum, tempPeSum, socketSubscribedEvents
```

`activeStrikeOiData`: `{xAxisData:["16-06-2026 09:18",...], yAxisCallData:[63330,...], yAxisPutData:[73410,...], yAxisPriceData:[57301,...]}`
`pcrChartData`: `{xAxisData, yAxisPcrData, yAxisPriceData}`
`peodCallPutOiData`: `{CE: 3531750, PE: 2011440}` — prev-day EOD OI baseline
`oiSnapshotData`: object keyed by strike price string (e.g. `"56700": {...}`)

## Socket subscriptions
- 15 events: `OD_TOI_BANKNIFTY_260630_{STRIKE}` for each selected strike
- `EQUITY_UNDERLYING_DATA_NIFTY BANK`

## Table columns
| Column | Source / computed | Render |
|---|---|---|
| Date | `stFetchDate` | |
| Time | `stTime` ("EOD","15:30:00") | |
| LTP | `inClose` (underlying) | |
| Day H/L Break | computed | badge `D.L.B (57136.7)` red / `D.H.B` |
| Chng. In Call OI | Δ `totalCeOi` vs prev | green/red |
| Chng. In Put OI | Δ `totalPeOi` | green/red |
| Diff. in OI | (Call OI chng − Put OI chng) | red if calls dominate (bearish), green if puts |
| Direction of chng. | sign of Diff | arrow badge ↑ green / ↓ red |
| Chng. In Direction | net OI direction magnitude | green/red |
| Direction of chng. % | % | green/red |
| Net PCR | `totalPeOi / totalCeOi` | |
| Day High/Low Diff. in OI | computed | |
| Sentiment | derived | **badge `Bearish` (red) / `Bullish` (green)** |

Sentiment is the headline: more call writing than put → Bearish; vice-versa → Bullish.

## Data source / API (`trending-oi-static`)
| Call | Response |
|---|---|
| `/api/trending-oi-static/getavailableassetsdata` | instruments |
| `/api/trending-oi-static/getselectedassetdate` | dates |
| `/api/trending-oi-static/getassetsdataexpirydate` | expiries |
| `/api/trending-oi-static/gettrendingoiforselectedstrikeprices` | main |

Main request + response:
```
POST /api/trending-oi-static/gettrendingoiforselectedstrikeprices
Body: {
  "stSelectedAsset": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "selectedStrikePrices": [56700,56800,56900,57000,57100,57200,57300,57400,57500,57600,57700,57800,57900,58000,58100],
  "stSelectedModeOfData": "live"
}
Response: {
  "status":"success",
  "data": {
    "data": [
      { "stFetchDate":"2026-06-15","stTime":"23:50:00","stDataFetchType":"PEOD",
        "inClose":0,"inHigh":0,"inLow":0,
        "objOiData":[{"CE":3531750},{"PE":2011440}],
        "totalCeOi":3531750,"totalPeOi":2011440 },
      { "stFetchDate":"2026-06-16","stTime":"09:16:00","stDataFetchType":"IM",
        "inClose":57320.3,"inHigh":...,"inLow":...,
        "objOiData":[{"CE":...},{"PE":...}],"totalCeOi":...,"totalPeOi":... }
    ],
    "listOfStrikePrice": [56700,...,58100],
    "underLyingAssetData": {...},
    "oiSnapshotData": {"56700":{...},...}
  }
}
```
`stDataFetchType:"PEOD"` = prev EOD row; `"IM"` = intraday.
All computed cols (CE change, PE change, Diff, Direction, PCR, Sentiment) are client-side from consecutive rows vs PEOD baseline.

## Replication notes (→ ArthaYantra)
- Strike-band selector → aggregate CE/PE OI per interval → compute ΔCall, ΔPut, Diff, PCR, direction, sentiment.
- Table + optional graph view (Diff-in-OI / PCR over time). Sentiment tag drives the at-a-glance read.

## Screenshot
ss_8031xmqfw (BANKNIFTY band aggregate, Bearish sentiment, Net PCR column).
