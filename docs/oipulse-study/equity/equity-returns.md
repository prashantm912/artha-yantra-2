# Equity Returns — `/app/equity/equity-returns`

**Purpose:** multi-timeframe **returns screener** — every stock's return over Current Day / 1 Week /
1 Month / 6 Months / 1 Year, with per-column sort & filter. Sub-tabs: `Equity returns | Equity`.

## Layout
```
sub-tabs: [ Equity returns ] [ Equity ]   ;  ticker strip
filter: Period[All data▾]  [Go]                                            Data as on 15-06-2026 23:45:00
┌ table (per-column filter row + sort) ───────────────────────────────────────────────────────────────┐
│ Name [filter] | Industry [filter] | LTP [filter] | Current Day ⇅ | 1 Week | 1 Month | 6 Months | 1 Year │
│ KALYANKJIL | Consumer Durables | 383.00 | 11.09% | -2.67% | -2.35% | -27.4% | -33.57%                 │
│ …                                                                                                       │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
~238 stocks. Default sorted by Current Day desc. Name column yellow-tinted (sticky).

## Components
| Component | Type | Notes |
|---|---|---|
| Period | select | `All data` (or restrict the universe/date) |
| Go | button (red) | fetch |
| Table | sortable + **per-column filter** | each column header has a filter input + sort arrows |

## Columns
| Column | Source | Render |
|---|---|---|
| Name | `stSymbolName` | text (sticky, tinted) |
| Industry | `stIndustryName` | text |
| LTP | `inCurrentClose` | |
| Current Day | `in1DayReturns` | % green/red |
| 1 Week | `in1WeekReturns` | % green/red |
| 1 Month | `in1MonthReturns` | % green/red |
| 6 Months | `in6MonthReturns` | % green/red |
| 1 Year | `in1YearReturns` | % green/red |

All return cells green if positive, red if negative.

## Data source / API
`POST /api/equity/getequityreturnsdata` →
```json
{ "data":[ { "stFetchDate":"...","stFetchTime":"...","stSymbolType":"...",
             "stSymbolName":"KALYANKJIL","stIndustryName":"Consumer Durables",
             "inCurrentClose":383.0,
             "in1DayReturns":11.09,"in1WeekReturns":-2.67,"in1MonthReturns":-2.35,
             "in6MonthReturns":-27.4,"in1YearReturns":-33.57 } ] }   // 238 stocks
```
All return periods precomputed server-side.

## Replication notes (→ ArthaYantra)
- One endpoint with precomputed 1D/1W/1M/6M/1Y returns → PrimeNG `p-table` with sort + per-column filters; conditional green/red on each return cell.

## Screenshot
ss_0140yevlq (238-stock returns screener, multi-period % columns).
