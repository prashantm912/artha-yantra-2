# Futures Pre-open market — `/app/futures-analysis/pre-open-market`

**Purpose:** pre-market (09:00–09:08) session scan. Which F&O stocks and indices are advancing /
declining at pre-open vs prev close, and whether they've broken the previous day's high/low.
Sub-tabs: `Pre Open Market | Futures Analysis`.

## Layout
```
sub-tabs: [ Pre Open Market ] [ Futures Analysis ]   ;  ticker strip
filter: Mode(Live/Hist)  Date[📅]  Expiry[Current Month▾]  Search[…]  [Go]
                                              Advances: 187 | Declines: 22 | Unchanged: 2  (right)
┌ Pre-open Market Advances ─────────────────┐ ┌ Pre-open Market Declines ────────────────┐
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
┌ Pre-open Indice Advances ─────────────────┐ ┌ Pre-open Indice Declines ────────────────┐
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
Note: For Live trading day, Pre open market data will be updated here on or after 09:09:30 AM.
```
**2×2 grid**: stocks (advances/declines) on top, indices (advances/declines) below.

## Filter bar + header
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Date | date picker | trading day |
| Expiry | select | Current Month / … |
| Search | text | locate a symbol |
| Go | button (red) | fetch |
| Advances / Declines / Unchanged | counters (right) | green / red / grey counts |

## Table columns (all 4 tables identical)
| Column | Source | Render |
|---|---|---|
| Name | `stSymbolName` (e.g. ASHOKLEY-I, BANKNIFTY-I) | text; `-I` suffix = futures |
| Today Open | `inPreOpenClose` (pre-open price) | |
| Prev. day Close | computed (`inPreOpenClose − inPreOpenChange`) | |
| LTP Chng % | computed (`inPreOpenChange / prevClose`) | green if +, red if − |
| LTP Chng | `inPreOpenChange` | green/red |
| Prev. Day Break | `inPrevDayBreak` | **badge: green `High Break` (H)** / red `Low Break` (L) / `-` none |

- All columns sortable (⇅). Each table paginated (`Rows per page 7`, `1-7 of N`, Prev/Next).
- Counts: Market Advances 189, Declines 22; Indice Advances 5, Declines 0.

## Vue component state (confirmed)
```
selectedModeOfData, selectedAvailableDate,
selectedIndex (null = all), selectedExpiry,
availableDate, availableIndex, availableExpiryData, availableModeOfData,
preOpenMarketMajorIndexData,
preOpenMarketIndexAdvancesSymbol, preOpenMarketIndexDeclinesSymbol,
tempPreOpenMarketIndexAdvancesSymbol, tempPreOpenMarketIndexDeclinesSymbol,
preMarketAdvancesSymbol,    // 125 today
preMarketDeclinesSymbol,    // 86 today
preMarketUnchangedSymbol,   // 7 today
tempPreMarketAdvancesSymbol, tempPreMarketDeclinesSymbol, tempPreMarketUnchangedSymbol,
searchSymbol, doneTypingInterval
```
(No `socketSubscribedEvents` — pre-open is a snapshot, no live socket.)

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/pre-open-market/getlistofassetpreopenmarket` | `{}` | index options (same list as heatmap) |
| `/api/futures/getpreopenmarketdate` | `{stSelectedModeOfData}` | dates |
| `/api/futures/getpreopenmarketdata` | `{stSelectedModeOfData, stSelectedAvailableDate, stSelectedExpiry, stSelectedIndex}` | `data:{ sData:[…stocks], iData:[…indices] }` |

Raw API row (confirmed):
```json
{ "stSymbolName": "ADANIPORTS-I", "inPreOpenClose": 1816, "inPreOpenChange": "2.00", "inPrevDayBreak": null }
```

Vue enriched row (confirmed):
```json
{
  "stSymbolName": "ADANIPORTS-I",
  "inPrevDayBreak": null,
  "inPreOpenClose": 1816,
  "inPreOpenChange": 2,
  "inPrevDayClose": 1814,
  "inPreOpenChangePercentage": "0.11"
}
```
`inPrevDayBreak`: `"H"`=prev-day high break, `"L"`=prev-day low break, `null`=none. `stSymbolName` includes `-I` suffix for Current Month futures. Advances/Declines/Unchanged split client-side by sign of `inPreOpenChange`.

## Replication notes (→ ArthaYantra)
- Pre-open snapshot endpoint returning {symbol, preOpenPrice, change, prevDayBreak} for stocks + indices.
- Client splits advances/declines by sign; 4 sortable `p-table`s; Prev Day Break → `p-tag` (High/Low Break).
- Pre-open feed only valid after 09:09:30 on live days (show the note).

## Screenshot
ss_8385i3ebb (Advances 187/Declines 22; High Break badges).
