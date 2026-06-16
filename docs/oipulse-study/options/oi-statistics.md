# Options OI Statistics — `/app/options-analysis/oi-stats`

**Purpose:** OI distribution & PCR analytics for an expiry — cumulative Call vs Put OI, per-strike OI
profile (support/resistance, ATM), and intraday PCR vs price. Sub-tabs: `Oi Stats | Options Analysis`.

## Layout
```
sub-tabs: [ Oi Stats ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Select Expiry Date[30-Jun-2026▾]  Select Period[Full day▾]  [Go]  ☐ Show Chg. in OI
        Underlying: NIFTY BANK at 57198.8 …
        Cumulative OI                          Individual OI
┌ bar (2 bars) ─────────────┐   ┌ bar per strike ──────────────────────────────────────┐
│ green=Call OI ~15.5M       │   │ x: strikes 51200–62400   green=Call OI, red=Put OI    │
│ red=Put OI ~16M            │   │ ATM marker (red ▲)   dataZoom slider                  │
│ dataZoom; legend Call/Put  │   │ legend: Call OI / Put OI                              │
└────────────────────────────┘   └───────────────────────────────────────────────────────┘
                         PCR Chart   BANKNIFTY - 30-Jun-26                    [Refresh]
┌ dual-axis line (full width) ──────────────────────────────────────────────────────────────┐
│ left: PCR (0.8–1.4, blue line)   right: price (57,100–57,800, orange line)   x: intraday    │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Name | same 9 Index + 211 Stocks | |
| Date | date picker | min 2019-01-01, max today |
| Expiry Date | YYMMDD values | same 6 expiries for BANKNIFTY |
| Select Period | `null`(Full day), `3`(Last 3 minutes), `5`(Last 5 min), `10`, `15`, `30`, **`45`**(Last 45 minutes), `60`(Last 60 min) | **Has 45-min option** — unique to this page |
| Go | button (primary blue) | triggers both API calls |
| Show Chg. in OI | checkbox (Bootstrap `custom-switch`) | toggles OI vs OI-change view on both bar charts |

## Vue component state
```
allDataFields: [minAvailableDate, maxAvailableDate, disableRefreshDataButton, selectedModeOfData,
  selectedOptions, selectedAvailableDate, selectedAvailableExpiryDate, selectedAvailableTimePeriod,
  availableOptionsData, availableDate, availableExpiryDate, availableTimePeriod, availableModeOfData,
  underLyingAssetData, showChangeInOiData, oiData, oiPcrData, pcrChartData,
  inCallTotalOi, inPutTotalOi, individualChartXaxis, individualChartXaxisData1, individualChartXaxisData2,
  cummulativeOiChartData, tempCummulativeOiChartData, individualOiChartData, tempIndividualOiChartData,
  strikePriceIndex, socketSubscribedEvents, randomIdString, socketDataUpdateTimeoutId]
```

Key state:
- `showChangeInOiData: false` — checkbox state; triggers `changeOiDataChartView()`
- When `false`: `calculateDataForOptionsOi()` → absolute OI (Call ~16.4M, Put ~16.7M total)
- When `true`: `calculateDataForChangeInOptionsOi()` → OI delta (newOi − oldOi), e.g. Call change ~929K, Put change ~677K
- `cummulativeOiChartData`: `{xAxisData:["OI"], xAxisCallData:[16486530], xAxisPutData:[16796910], xAxisMarkLine:null}`
- `individualOiChartData`: `{selectedOptions:"BANKNIFTY", xAxisData:["43000","43500",...,"69000"] (193 strikes), xAxisCallData:[...], xAxisPutData:[...], xAxisMarkLine:[{xAxis:"57200"}]}`
- `pcrChartData`: `{xAxisData:["09:16:00",...], yAxisPcrData:["1.09","1.09",...], yAxisPriceData:[57320.3,...]}` (258 points)

## Socket subscriptions
- `OD_OI_STATS_BANKNIFTY_260630` — live OI updates for this expiry
- `EQUITY_UNDERLYING_DATA_NIFTY BANK` — underlying LTP

## Charts
| Chart | Type | Series | Notes |
|---|---|---|---|
| Cumulative OI | ECharts bar (2 bars) | Call OI total (green), Put OI total (red) | sum across strikes; dataZoom; legend |
| Individual OI | ECharts grouped bar | per strike: Call OI (green) vs Put OI (red) | x = strike ladder; **ATM marker** (red ▲) at spot; dataZoom; shows OI walls (support/resistance) |
| PCR Chart | ECharts dual-axis line | PCR (blue, left), price (orange, right) | intraday PCR vs underlying; Refresh button |

## Data source / API

### OI Stats endpoint
```
POST /api/options/getoistatsdataforselectedoptions
Body: {
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "stSelectedAvailableTimePeriod": null,
  "stSelectedModeOfData": "live"
}
Response: {
  "status": "success",
  "msg": "Data fetched successfully.",
  "data": {
    "data": [
      { "inNewOi": "690", "inStrikePrice": 43000, "stOptionsType": "CE", "inOldOi": "690" },
      { "inNewOi": "110280", "inStrikePrice": 43000, "stOptionsType": "PE", "inOldOi": "114480" },
      ...
    ],
    "underLyingAssetData": {
      "stUnderLyingAsset": "NIFTY BANK",
      "stDateTime": "16 Jun 2026, 13:33:00",
      "inLtp": 57231.15,
      "inDayHigh": 57399.7,
      "inDayLow": 57076.25,
      "inDayOpen": 57198.8
    }
  }
}
```
311 rows (CE + PE interleaved, all 193+ strikes). Client sums by CE/PE for cumulative; groups by strike for individual bars.

### PCR endpoint (note typo: `pptions` not `ptions`)
```
POST /api/options/getoipcrdataforselectedpptions
Body: {
  "stSelectedModeOfData": "live",
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630"
}
Response: {
  "status": "success",
  "msg": "Data fetched successfully.",
  "data": [
    { "stFetchTime": "09:16:00", "inClose": 57320.3, "inPcr": "1.09" },
    { "stFetchTime": "09:17:00", "inClose": 57353.2, "inPcr": "1.09" },
    ...
  ]
}
```
375 rows for full day (1-min resolution 09:16:00–15:30:00). `inPcr` = string decimal; `inClose` = float.

- Cumulative OI = sum `inNewOi` over CE vs PE. Individual OI = per `inStrikePrice` CE/PE bars (ATM = nearest to underlying `inLtp`).
- "Show Chg. in OI" → use `inNewOi − inOldOi`.
- PCR Chart from the PCR time series (`inPcr` vs `inClose`).

## Replication notes (→ ArthaYantra)
- `ay-echart`: (1) 2-bar cumulative CE/PE OI; (2) per-strike grouped bar with ATM markLine + dataZoom; (3) dual-axis PCR-vs-price line.
- Strike OI profile is the support/resistance / max-pain visual — high CE OI = resistance, high PE OI = support.

## Screenshot
ss_5723g3iej (Cumulative OI, per-strike Individual OI with ATM, intraday PCR chart).
