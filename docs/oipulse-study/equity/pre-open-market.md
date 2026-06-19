# Equity Pre-open market — `/app/equity/pre-open-market`

**Purpose:** the **equity (cash market)** pre-open scan — advancing/declining stocks and indices at
pre-open vs prev close, with prev-day break. Equity counterpart of the Futures pre-open page.
Sub-tabs: `Pre open market | Equity`.

> **Confirmed live 2026-06-18:** pre-open is **two separate routes** — this `/app/equity/pre-open-market`
> and `/app/futures-analysis/pre-open-market` (the study's split is correct, not the manual's single combined page).
> See [PHASE-B-FINDINGS.md](../PHASE-B-FINDINGS.md) (§5 V14).

## Layout
```
sub-tabs: [ Pre open market ] [ Equity ]   ;  ticker strip
filter: Mode(Live/Hist)  Date[📅]  Indices[All F&O Data▾]  Search[…]  [Go]
header: NIFTY 50: 23984.85 +1.53%  ·  NIFTY BANK: 57679.65 +1.52%        Data last Updated At: 15-06-2026 09:09:00
                                                                          Advances: 210 | Declines: 1 | Unchanged: 0
┌ Pre-open Market Advances ─────────────────┐ ┌ Pre-open Market Declines ────────────────┐
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
┌ Pre-open Indice Advances ─────────────────┐ ┌ Pre-open Indice Declines ────────────────┐
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
Note: For Live trading day, Pre open market data will be updated here on or after 09:09:30 AM.
```
**2×2 grid** (stocks advances/declines on top, indices below). Identical column set to Futures pre-open.

## Filter / header
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Date | date picker | day |
| **Indices** | select | `All F&O Data` / specific index universe (replaces Futures' Expiry filter) |
| Search | text | locate symbol |
| Go | button (red) | fetch |
| header indices | text | NIFTY 50 + NIFTY BANK with change% (green/red) |
| Advances/Declines/Unchanged | counters | green/red/grey |

## Columns (all 4 tables)
Name (cash symbol, no `-I` suffix) · Today Open (`inPreOpenClose`) · Prev. day Close (computed) ·
LTP Chng % (computed, green/red) · LTP Chng (`inPreOpenChange`) · Prev. Day Break (`inPrevDayBreak`
→ green `High Break` / red `Low Break` / `-`). Sortable; paginated 7.

## Vue component state (confirmed — identical structure to futures/pre-open-market.md)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedModeOfData, selectedAvailableDate,
availableDate, selectedIndex (null), availableIndex, availableModeOfData,
preOpenMarketMajorIndexData,
preOpenMarketIndexAdvancesSymbol, preOpenMarketIndexDeclinesSymbol,
tempPreOpenMarketIndexAdvancesSymbol, tempPreOpenMarketIndexDeclinesSymbol,
preMarketAdvancesSymbol, preMarketDeclinesSymbol, preMarketUnchangedSymbol,
tempPreMarketAdvancesSymbol, tempPreMarketDeclinesSymbol, tempPreMarketUnchangedSymbol,
searchSymbol, doneTypingInterval
```
No socket subscription (snapshot page).

Raw API row (confirmed): `{stSymbolName:"LODHA", inPreOpenClose:925.95, inPreOpenChange:"5.10", inPrevDayBreak:null}`
Note: equity symbols have NO `-I` suffix (vs futures version which appends `-I`).

## Data source / API
Shares the pre-open namespace (no expiry param — equity mode):
- `/api/pre-open-market/getlistofassetpreopenmarket`
- `/api/pre-open-market/getpreopenmarketdate`
- `/api/pre-open-market/getpreopenmarketdata` → `data:{ sData:[…stocks], iData:[…indices] }`
  (row: `{stSymbolName, inPreOpenClose, inPreOpenChange, inPrevDayBreak}`)

## Interpretation (how to trade)
- The advances/declines skew sets the day's bias; Prev-Day-Break badges flag continuation candidates; require sector-index agreement before acting.
- Threshold heuristic: a stock already down ~5% is "too high" to chase (avoid); one down ~1–3% is a "good opportunity" with a ~1% intraday target.
- Historical pre-open is an exchange-unavailable differentiator, so we must persist daily pre-open snapshots ourselves.
- Timing: the exchange publishes pre-open around 09:08; the market opens 09:15.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Same as Futures pre-open (see `../futures/pre-open-market.md`) but equity universe + `Indices` filter; no expiry.

## Screenshot
ss_5439wmk76 (equity pre-open, Advances 210, High Break badges, index header).
