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

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/heatmap/getlistofassetforheatmap` | — | asset options |
| `/api/futures/getfuturesoispurtdate` | `{stSelectedModeOfData}` | dates |
| `/api/futures/getlimitedprevdayhighlows` | — | `data:[{stFuturesName, in52WeekHigh, in52WeekLow, inPrevOi, inHighs:[…], inLows:[…]}]` (breakout reference) |
| `/api/futures/getfuturesmarketmoversdata` | `{stSelectedAsset, stSelectedExpiry, stSelectedAvailableDate, stSelectedModeOfData}` | `data:[{stSymbolName, stFetchTime, inOldOi, inOldClose, inNewOi, inNewClose, …OHLC}]` |

Gainers/Losers derived by ranking movers data on LTP%; Min.B.O./O=H/L computed against prevdayhighlows + intraday OHLC; New-H/L feed watches live extremes.

## Replication notes (→ ArthaYantra)
- Movers endpoint (symbol OHLC+OI) + a prev-day high/low reference endpoint (52w H/L + recent highs/lows arrays).
- Compute breakout-days, O=H/L, OI interpretation; rank gainers/losers; live new-H/L watcher appends rows.

## Screenshot
ss_9803txx0g (Top Gainers + Top Losers with L.B/S.C/L.U/S.B badges; New High/Low empty).
