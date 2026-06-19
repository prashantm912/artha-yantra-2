# Futures EOD OI Analyzer — `/app/futures-analysis/eod-oi-analyzer`

**Purpose:** day-by-day (end-of-day) OI history for a future — the EOD counterpart to the intraday
OI Analysis. ~400 trading days of OHLC + OI + day-over-day changes + interpretation, to study how
OI trends across days. Sub-tabs: `EOD OI Analyzer | Futures Analysis`.

## Layout
```
sub-tabs: [ EOD OI Analyzer ] [ Futures Analysis ]   ;  ticker strip
filter: Name[BANKNIFTY▾]  Expiry[Current Month▾]  Actions:[Go]  ☐ Show chart data  ☐ Show Cumulative Oi  ☐ Show Range Data
┌ daily table (scrollable, paginated 25) ─────────────────────────────────────────────────────────┐
│ Date | Total OI | Day Open | Day High | Day Low | Volume | LTP | Day Range | LTP Change |         │
│   OI Change | % Chng. in LTP | % Chng. in OI | OI Interpretation                                  │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[25▾]                                  1 - 25 of 400   ‹ Previous  Next ›
```
Newest date first (descending).

## Filter bar
| Control | Type | Values |
|---|---|---|
| Name | select | future instrument (BANKNIFTY…) |
| Expiry | select | Current Month / … |
| Go | button (red) | fetch |
| Show chart data | checkbox | reveal a chart view of the EOD series |
| Show Cumulative Oi | checkbox | add cumulative-OI column/series |
| Show Range Data | checkbox | add range (high-low) detail |

> **Confirmed live 2026-06-18:** the page exposes exactly these **three checkboxes** (Show chart data /
> Show Cumulative Oi / Show Range Data) — there is **no "Show Detail View" button** (the manual was
> stale; the study is correct). See [PHASE-B-FINDINGS.md](../PHASE-B-FINDINGS.md) (§5 V8).

## Table columns
| Column | Source | Render |
|---|---|---|
| Date | `stFetchDate` ("15-06-2026") | text |
| Total OI | `inOi` | |
| Day Open | `inDayOpen` | |
| Day High | `inDayHigh` | |
| Day Low | `inDayLow` | |
| Volume | `inVolume` | |
| LTP | `inClose` | day close |
| Day Range | computed `inDayHigh − inDayLow` + `(range/close %)` | e.g. "620 (1.09 %)" |
| LTP Change | computed vs prev day close | green +, red − |
| OI Change | computed vs prev day OI | green/red |
| % Chng. in LTP | computed | green/red |
| % Chng. in OI | computed | green/red |
| OI Interpretation | computed (price×OI signs) | badge per `inOiInterpretation` enum (L.B/L.U/S.B/S.C) |

(`inDelQty` = delivery quantity, available in the row — likely used by "Show … data" toggles.)

## Vue component state (confirmed)
```
showChartData, showCummulativeOi, showEodRangeData,   // checkbox flags
minAvailableDate ("2019-01-01"), maxAvailableDate ("2026-06-16"),
disableRefreshDataButton, disableEodRangeDataRefreshButton, disableExpirySelection,
selectedModeOfData, selectedFutures ("BANKNIFTY"), selectedExpiry ("I"),
availableFuturesData, availableExpiryData, availableModeOfData,
tableData,               // array of 400 rows (current month going back to 2019)
columns,                 // [{label:"Date",field:"stFetchDate"}, {label:"Total OI",field:"inOi"}, ...]
priceOiData,             // {toolTipData, xAxisData, yAxisOiData, yAxisVolumeData, yAxisDeliveryData, yAxisCandlestickData}
stStartDate, stEndDate,  // date range for Range Data section (default: last 7 days)
eodRangeData,            // array of all-F&O futures rows for the selected date range
stTypeOfData,            // "FUTURES" (vs "OPTIONS" for equity section)
filterCondition,         // {range:{comparison,inInputValue}, change:{comparison,inInputValue}}
listOfAvailableComparison // [{text:"None",value:null},{text:">",value:"GREATER_THAN"},{text:">=","GREATER_THAN_OR_EQUAL_TO"},{text:"<","LESS_THAN"},{text:"<=","LESS_THAN_OR_EQUAL_TO"}]
```

Vue enriched table row (confirmed — adds computed fields to raw row):
```json
{
  "stFetchDate": "2026-06-16",
  "inDayOpen": 57280, "inDayHigh": 57420, "inDayLow": 57079,
  "inClose": 57300.2, "inOi": "2268330", "inVolume": 401670, "inDelQty": 0,
  "inDayRange": 341,
  "inLtpDiff": 43,
  "inLtpDiffInPercentage": 0.075,
  "inOiDiff": 1260,
  "inOiDiffInPercentage": 0.056,
  "stOiInterpretation": "Long Build Up",
  "inOiInterpretation": 1,
  "inDayRangePercentage": 0.6
}
```

Chart data (`priceOiData`) structure — EOD version has 6 series (extra `yAxisDeliveryData` vs intraday):
```json
{
  "toolTipData": ["Short Build Up", "Long Build Up", ...],
  "xAxisData": ["2024-11-01", "2024-11-04", ...],
  "yAxisOiData": ["2649435", "2720130", ...],
  "yAxisVolumeData": [[0, 401670, 1], ...],
  "yAxisDeliveryData": [[0, 0, 1], ...],
  "yAxisCandlestickData": [["51995.00","51927.75","51903.00","52234.85"], ...]
}
```
`yAxisDeliveryData` — delivery quantity series (only in EOD chart, not intraday).
Candlestick format: `[open, close, low, high]` (same as intraday — NOT standard OHLC).
The chart price series is the **adjusted close**; the EOD OI-vs-price chart supports a line/bar
toggle. For indices the Name dropdown exposes Current / Next / Far month variants inline. Historical
look-back ≈ 2 months.

Range Data row (from `getfutureseodrangedata`):
```json
{
  "stSymbolType": "FUTIDX", "stSymbolName": "NIFTY",
  "inOpen": 23230, "inHigh": 24029.7, "inLow": 23105, "inClose": 23916.6,
  "inRangePercentage": "3.98", "inChangePercentage": "2.96",
  "inVolume": 736612630
}
```

## Socket subscriptions
No socket — fetch-on-demand (Go button). `socketSubscribedEvents` not present on this component.

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/futures/getavailablefuturesdata` | `{stSelectedModeOfData:"live"}` | instrument list `[{text,type,value:["I","II","III"]}]` |
| `/api/futures/getselectedfutureseodoidata` | `{stSelectedFutures:"BANKNIFTY", stSelectedExpiry:"I"}` | `data:[ daily row ]` (~400 rows) |
| `/api/futures/getfutureseodrangedata` | `{stStartDate:"2026-06-09", stEndDate:"2026-06-16", stTypeOfData:"FUTURES"}` | `data:[ all-futures range row ]` |

Note: `getselectedfutureseodoidata` takes NO date filter — returns full history (~400 rows back to 2019).
`getfutureseodrangedata` triggered only when "Show Range Data" checkbox is checked.

Daily row (raw API):
```json
{ "stFetchDate": "2026-06-16", "inDayOpen": 57280, "inDayHigh": 57420, "inDayLow": 57079,
  "inClose": 57300.2, "inOi": "2268330", "inVolume": 401670, "inDelQty": 0 }
```
All Change/%/Range/Interpretation columns derived from consecutive daily rows client-side.

## Interpretation (how to trade)
- Expiry-day tactic: read ~10 days of Future OI to decode big-player activity.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Daily EOD OI series endpoint (per future+expiry) → table; compute day-over-day deltas + OI interpretation.
- Optional toggles add a chart, cumulative OI, and range columns over the same series.

## Screenshot
ss_5899f6c3r (BANKNIFTY daily EOD, 400 rows, full interpretation badges).
