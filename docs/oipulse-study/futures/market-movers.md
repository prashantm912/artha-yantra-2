# Futures Market Movers — `/app/futures-analysis/market-movers`

**Purpose:** intraday movers scanner with breakout + open-positioning + OI-interpretation signals.
Top gainers/losers across F&O, plus a live "new high/low maker" feed. Sub-tabs: `Market Movers | Futures Analysis`.
(Not in the user's original list — discovered extra.)

## Layout
```
sub-tabs: [ Market Movers ] [ Futures Analysis ]   ;  ticker strip
filter: Mode  Asset[All F&O Stocks▾]  Expiry[Current Month▾]  Date[📅]  Search[…]  [Go]   Data last Updated At:-
┌ Top Gainers                         Filter ┐ ┌ New High/Low Maker ───────────────────────┐
│ table                                       │ │ Time|Name|Chart|Min.B.O.|LTP|%LTP Chg|Oi  │
│                                             │ │ (fills live when a future makes new H/L)  │
└─────────────────────────────────────────────┘ └───────────────────────────────────────────┘
┌ Top Losers                          Filter ┐
│ table                                       │
└─────────────────────────────────────────────┘
```

## Filter bar
Mode (Live/Hist) · Asset (All F&O Stocks / index / sector) · Expiry · Date · Search · Go.
Each Gainers/Losers card also has a **`Filter`** link (column/threshold filter).

## Top Gainers / Top Losers — columns
| Column | Source | Render |
|---|---|---|
| Name | `stSymbolName` | text |
| Chart | mini-chart icon | opens chart |
| LTP | `inNewClose` | |
| LTP Chng % | computed | green (gainers) / red (losers) |
| OI Chng % | computed | green/red by sign |
| Min. B.O. Days | from `getlimitedprevdayhighlows` (inHighs/inLows) | e.g. `0 D. High` / `0 D. Low` — days since breakout of prior high/low |
| O=H/L | intraday open vs high/low | `O=L` (open=low, bullish) / `O=H` (open=high, bearish) / `-` |
| Oi Int. | OI interpretation (abbrev) | badge: **`L.B`** green (Long Buildup), **`S.C`** blue (Short Covering), **`L.U`** yellow (Long Unwinding), **`S.B`** red (Short Buildup) |

Sortable columns. Paginated (8 rows; Gainers 1-8 of 170, Losers 1-8 of 46).

## New High/Low Maker (live feed, right)
Columns: Time, Name, Chart, Min. B.O., LTP, % LTP Chg., Oi. Empty until a current-month future
prints a new high/low during market hours, then appends a row (timestamped). Placeholder text:
"Data will only show up here whenever any current month futures makes new high or new low in market hours."

## Vue component state (confirmed)
```
selectedModeOfData, selectedAvailableAsset (null=all), selectedExpiry,
selectedAvailableDate, availableAsset, availableDate, availableExpiryData, availableModeOfData,
topGainers,        // 225 rows today — sorted by inLtpChangeInPercentage desc
topLosers,         // 159 rows today — sorted asc
newHighLowData,    // 5 rows — live new-H/L events during session
arPrevDaysHighLowData,   // from getlimitedprevdayhighlows (for breakout reference)
searchSymbol, doneTypingInterval,
socketSubscribedEvents,          // ["FD_OIS"]
socketSeperateSubscribedEvents,
stLastUpdatedAt, isSocketConnectedSecondTime, socketDataUpdateTimeoutId
```

Top gainer enriched row (confirmed):
```json
{
  "stSymbolName": "360ONE",
  "inNewLtp": "1141.3", "inOldLtp": "1133.5",
  "inLtpChangeInPercentage": 0.69, "inLtpChange": 7.8,
  "inNewOi": "5939000", "inOldOi": "5792500",
  "inOiChangeInPercentage": 2.53, "inOiChange": 146500,
  "inDayHighLow": "",
  "inNewDayOpen": 1147, "inNewDayHigh": 1168.8, "inNewDayLow": 1133.7,
  "inOiInterpretation": 1,
  "inDaysBreakOut": 13
}
```

New High/Low live row (confirmed):
```json
{
  "stName": "NIFTY-I", "stTime": "14:10:20",
  "ltp": 24016.6, "ltpChgPercentage": 0.418,
  "highOrLow": "H",   // "H" = new high, "L" = new low
  "inOi": 17710680,
  "inDaysBreakOut": 0,
  "is52WeekHighBroken": false, "is52WeekLowBroken": false,
  "inOiInterpretation": 4,
  "inChangeInOiPercentage": "-0.60"
}
```

## Socket subscriptions
- `FD_OIS` — same all-market futures feed as OI Spurt
- `socketSeperateSubscribedEvents` — secondary socket (e.g. for new-H/L live events)

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/heatmap/getlistofassetforheatmap` | `{}` | asset options |
| `/api/futures/getfuturesoispurtdate` | `{stSelectedModeOfData}` | dates |
| `/api/futures/getlimitedprevdayhighlows` | `{}` | `data:[{stFuturesName, in52WeekHigh, in52WeekLow, inPrevOi, inHighs:[15 daily highs], inLows:[15 daily lows]}]` |
| `/api/futures/getfuturesmarketmoversdata` | `{stSelectedAsset, stSelectedExpiry, stSelectedAvailableDate, stSelectedModeOfData}` | `data:[ row ]` |

Raw movers API row:
```json
{ "stSymbolName": "360ONE", "stFetchTime": "14:09:00",
  "inOldOi": "5792500", "inOldClose": "1133.5",
  "inNewOi": "5939000", "inNewClose": "1141.3" }
```
Gainers/Losers: rank by `inLtpChangeInPercentage` client-side. `inDaysBreakOut` from comparing new-high vs `inHighs` array. `inOiInterpretation` uses 4-state enum.

## Replication notes (→ ArthaYantra)
- Movers endpoint (symbol OHLC+OI) + a prev-day high/low reference endpoint (52w H/L + recent highs/lows arrays).
- Compute breakout-days, O=H/L, OI interpretation; rank gainers/losers; live new-H/L watcher appends rows.

## Screenshot
ss_9803txx0g (Top Gainers + Top Losers with L.B/S.C/L.U/S.B badges; New High/Low empty).
