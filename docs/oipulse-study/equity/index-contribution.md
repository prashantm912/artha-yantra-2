# Index Contribution — `/app/index-contribution`

**Purpose:** how much each constituent contributes to the index's point move (weighted). Which stocks
are pushing the index up vs dragging it down, in index points. No sub-tabs.

## Layout
```
filter: Mode(Live/Hist)  Index[Nifty 50▾]  Date[📅]  Search[…]  [Go]  [Show Graph View]
        Underlying: NIFTY 50 at 23994.95, Chg: 141.05 (0.59%) as on 16 Jun 2026, 14:41:00 IST
┌ Advances: 30 / Points: 189.76 ────────────┐ ┌ Decline: 20 / Points: -45.59 ────────────┐
│ # | Name | Point | LTP                     │ │ # | Name | Point | LTP                    │
│ 1   RELIANCE   +37.28   1331.70 (1.89%)    │ │ 1   HINDALCO  -10.66   983.90 (-2.96%)    │
│ 2   HDFCBANK   +29.22   786.35 (1.16%)     │ │ …                                         │
│ …                                          │ └────────────────────────────────────────────┘
└─────────────────────────────────────────────┘
```

## Filter / header
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Index | select | `Nifty 50` / `Nifty Bank` / `Nifty Fin Service` / `Nifty Mid Select` (from `getlistofindexes`) |
| Date | date picker | day |
| Search | text | locate constituent |
| Go | button (red) | fetch |
| Show Graph View | button | toggles `showChart` — time-series area chart view |
| Header | text | index name, LTP, point change, % change, timestamp |

## Tables (Advances | Declines)
Columns: # (row rank) · Name (`stSymbolName`) · Point (`inIndexPoint`, green + / red −) · LTP (`inClose` with `(inLtpChangePercentage%)`)

Header per table: count + summed points e.g. "Advances: 30 / Points: 189.76".

## Vue component state (confirmed)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedModeOfData, selectedAvailableDate, selectedAvailableIndex ("Nifty 50"),
availableDate, availableIndex, availableModeOfData,
advanceStock (30), declineStock (20),
searchSymbol, doneTypingInterval,
stLastUpdatedAt,
socketSubscribedEvents ([]),   // live updates per constituent: "EQ_ICD_{SYMBOL}"
underlyingDetails,             // index summary row
tableAdvanceData ([]),         // advance constituents
tableDeclineData ([]),         // decline constituents
tempAdvanceData, tempDeclineData,
subscribedSymbolList, randomIdString, realTimeDataUpdateTimeoutId,
allData, testData,
showChart (false),             // "Show Graph View" toggle
chartData,                     // time-series for graph view
stIndexSymbolName,
indexPrevCloseData, stockPrevCloseData
```

## Socket subscriptions (confirmed)
Pattern: `EQ_ICD_{SYMBOL}` — one per constituent. For Nifty 50 (50 stocks):
```
EQ_ICD_RELIANCE, EQ_ICD_HDFCBANK, EQ_ICD_BAJFINANCE, EQ_ICD_HCLTECH, EQ_ICD_HINDUNILVR,
EQ_ICD_NTPC, EQ_ICD_TCS, EQ_ICD_INFY, EQ_ICD_ITC, EQ_ICD_BHARTIARTL, EQ_ICD_LT,
EQ_ICD_ICICIBANK, EQ_ICD_TATACONSUM, EQ_ICD_TITAN, EQ_ICD_BAJAJFINSV, EQ_ICD_COALINDIA,
EQ_ICD_KOTAKBANK, EQ_ICD_ONGC, EQ_ICD_ADANIPORTS, EQ_ICD_M&M, EQ_ICD_NESTLEIND,
EQ_ICD_TECHM, EQ_ICD_JIOFIN, EQ_ICD_ETERNAL, EQ_ICD_SHRIRAMFIN, EQ_ICD_SBILIFE,
EQ_ICD_WIPRO, EQ_ICD_ASIANPAINT, EQ_ICD_SUNPHARMA, EQ_ICD_ADANIENT, EQ_ICD_HINDALCO,
EQ_ICD_SBIN, EQ_ICD_JSWSTEEL, EQ_ICD_TATASTEEL, EQ_ICD_MARUTI, EQ_ICD_INDIGO,
EQ_ICD_GRASIM, EQ_ICD_HDFCLIFE, EQ_ICD_APOLLOHOSP, ... (all 50 constituents)
```

## Data source / API (`index-contribution`)
| Call | Response |
|---|---|
| `/api/index-contribution/getlistofindexes` | `[{text:"Nifty 50",value:"Nifty 50"},{text:"Nifty Bank",value:"Nifty Bank"},{text:"Nifty Fin Service",...},{text:"Nifty Mid Select",...}]` |
| `/api/index-contribution/getselectedindexdate` | available dates |
| `/api/index-contribution/getselectedindexalldata` | `data:[ indexRow, ...constituentRows ]` |

Confirmed constituent row schema:
```json
{
  "stSymbolName": "RELIANCE",
  "inClose": "1331.70",
  "inDayHigh": "1333.40",
  "inDayLow": "1306.40",
  "inDayOpen": "1313.40",
  "inPrevDayClose": "1307.00",
  "inLtpChange": 24.7,
  "inLtpChangePercentage": 1.89,
  "inIndexPoint": 37.28,
  "inWeightage": 8.27
}
```

Index summary row (first row of response):
```json
{
  "stSymbolName": "NIFTY 50",
  "inPrevDayClose": 23853.9,
  "inDayOpen": 23923.9,
  "inDayHigh": 24001.6,
  "inDayLow": 23888.2,
  "inClose": 23994.95,
  "inWeightage": null,
  "stDateTime": "16 Jun 2026, 14:41:00",
  "inLtpChange": 147.1
}
```

`inWeightage: null` on the index row; constituents carry `inWeightage`. `inIndexPoint` is the pre-computed contribution (server-side).

## Graph View (chartData)
When "Show Graph View" is toggled, overlays a time-series area chart:
```json
{
  "xAxisData": ["14:41"],
  "yAxisAdvanceData": ["189.76"],
  "yAxisDeclineData": ["45.59"],
  "yAxisIndexData": ["23994.95"]
}
```
Three series: Advance total points (green area) + Decline total points (red area) + Index LTP (line) vs time.

## Replication notes (→ ArthaYantra)
- Endpoint returns pre-computed `inIndexPoint` per constituent — no client-side weight×move calculation needed.
- Split constituents into tableAdvanceData / tableDeclineData by sign of `inIndexPoint`.
- Two ranked tables (advances/declines) + `showChart` toggles the time-series chart view.
- Socket pattern `EQ_ICD_{SYMBOL}` streams LTP updates per constituent for live mode.

## Screenshot
ss_52248r77w (NIFTY 50 contributions: Advances 34 / Decline 16).
