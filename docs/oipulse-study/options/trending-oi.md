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

The "Change Strike Prices" modal has Clear-all and Reset buttons, a "Total Strike Prices" count header, and an unbounded multi-select. The legacy control "Show detail view" is the inverse of the "Show Graph View" toggle (detail on = table, off = graph); graph view renders two line panels (ΔCall-OI and ΔPut-OI over time).

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
| Diff. in OI | `ΔPut OI − ΔCall OI` (puts minus calls) | green (positive) = bullish, red (negative) = bearish |
| Direction of chng. | sign of Diff | arrow badge ↑ green / ↓ red |
| Chng. In Direction | net OI direction magnitude | green/red |
| Direction of chng. % | % | green/red |
| Net PCR | `totalPeOi / totalCeOi` | |
| Day High/Low Diff. in OI | computed | |
| Sentiment | derived | **badge `Bearish` (red) / `Bullish` (green)** |

**`Diff. in OI = ΔPut OI − ΔCall OI`, and positive = Bullish** (confirmed live 2026-06-18 by arithmetic on
the live rows: Put chng `26,111,280` − Call chng `21,478,740` = `+46,32,540` → Sentiment "Bullish"; a row
with calls dominating gave `−42,28,120` → "Bearish"). An earlier draft framed this as "Call − Put" — that is wrong.
Confirmed live columns, in order: Date, Time, LTP, Day H/L Break, Chng. In Call OI, Chng. In Put OI, Diff. in OI,
Direction of chng., Chng. In Direction, Direction of chng. %, Net PCR, Sentiment.

Sentiment is the headline: more put writing than call (positive Diff) → Bullish; more call writing → Bearish.
See [Phase B findings](../PHASE-B-FINDINGS.md) (V2).

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
  "selectedStrikePrices": [],  // empty [] on initial page load → server auto-selects 15 near-ATM strikes
  // After "Change Strike Prices" modal: [56700,56800,56900,57000,57100,57200,57300,57400,57500,57600,57700,57800,57900,58000,58100],
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

## Interpretation (how to trade)
- What "trending" means: the widening gap between ΔCall-OI and ΔPut-OI; use Change-in-OI (not Total OI) for intraday.
- 5-level sentiment, derived from the sign of the Difference-in-OI and whether each side's OI rose or fell vs the previous interval: Extreme Bullish / Bullish / Neutral / Bearish / Extreme Bearish (this adds Neutral and Extreme tiers beyond a binary read). A positive Difference-in-OI (puts dominant) is a bullish lean.
- Strength ladder: no remark = Simple; a Day-High/Low Break = Moderate; a Day-Break plus a reduction in the opposite side's OI (and continuous breaks) = Extreme. Refinement: Call-OI still rising alongside Put writing is only Moderate bullish; Call-OI falling (unwinding) is Strong.
- The Call/Put OI columns are cumulative Δ vs the prior-day EOD baseline; the table reads bottom-to-top (newest on top).
- Day-High/Low Break trigger: the Difference-in-OI is a new day extreme AND exceeds the previous interval's value (positive for a High Break, negative for a Low Break); it seeds after ~09:16.
- Trading playbook: focus on OTM CE/PE strikes; a long needs price above VWAP with rising volume and RSI; veto the entry if RSI is overbought or major resistance is near; the bearish case mirrors it. "Buy on Dips / Sell on Rise", and never trade Trending-OI alone.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Strike-band selector → aggregate CE/PE OI per interval → compute ΔCall, ΔPut, Diff, PCR, direction, sentiment.
- Table + optional graph view (Diff-in-OI / PCR over time). Sentiment tag drives the at-a-glance read.

## Screenshot
ss_8031xmqfw (BANKNIFTY band aggregate, Bearish sentiment, Net PCR column).
