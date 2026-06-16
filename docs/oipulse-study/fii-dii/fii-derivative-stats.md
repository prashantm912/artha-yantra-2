# FII Derivative Stats — `/app/fii-dii/fii-derivative-stats`

**Purpose:** FII net activity across the four derivative segments (Index Futures/Options, Stock
Futures/Options), daily — charted + tabulated. Tracks FII derivative positioning. No sub-tabs.

## Layout
```
sub-tabs: [ FII Derivative Stats ] [ FII & DII Activity ]   ;  ticker strip
        FII derivative stats (Values in Crores)   (title)
┌ bar chart — Index Futures ───────────────────────────────────────────────────────────────────────┐ (green +/red −, daily)
┌ bar chart — Index Options ───────────────────────────────────────────────────────────────────────┐ (range ~ −120k…+90k)
┌ bar chart — Stock Futures ───────────────────────────────────────────────────────────────────────┐
┌ bar chart — Stock Options ───────────────────────────────────────────────────────────────────────┐
        Detailed FII derivative stats (Values in Crores)   (table)
┌ Date | Index Futures | Index Options | Stock Futures | Stock Options ──────────────────────────────┐
```
**4 stacked net-value bar charts** + a detailed table. x = dates (≈365 days), y = Crore.

## Charts / columns
| Segment | Chart / column | Source key |
|---|---|---|
| Index Futures | bar / col | `statsObj.idx_fut` |
| Index Options | bar / col | `statsObj.idx_opt` |
| Stock Futures | bar / col | `statsObj.stk_fut` |
| Stock Options | bar / col | `statsObj.stk_opt` |

Bars/cells green if net positive (long), red if negative (short). Index Options has the widest range
(FII hedging dominates). ECharts bars, toolbox, watermark.

## Vue component state (confirmed — same structure as capital-market.md)
```
multipleBar2,             // ECharts config (4 series in one chart or 4 charts)
disableRefreshDataButton,
capitalMarketData ([]),   // 365 daily rows (raw from API — nested statsObj)
tableData ([]),           // same 365 rows for table display
columns ([]),             // vue-good-table column defs
pagination,
totalRecords
```

## Columns (vue-good-table — confirmed)
```
Date (dtDate) · Index Futures (idx_fut) · Index Options (idx_opt) · Stock Futures (stk_fut) · Stock Options (stk_opt)
```
Note: column `field` values are flat (`idx_fut`) but data has nested `statsObj.idx_fut`. Vue-good-table resolves these via the table's data accessor.

## Data source / API
`POST /api/fii-dii/getfiiderivativestatsdata` — no filter params, returns ~365 days:

Confirmed rows:
```json
// Older row (Dec 2024)
{"dtDate":"2024-12-19","statsObj":{"stk_opt":-195.15,"stk_fut":-2974.21,"idx_opt":-78122.37,"idx_fut":-1519.35}}
// Recent row (Jun 2026)
{"dtDate":"2026-06-15","statsObj":{"idx_fut":328.88,"idx_opt":-5446.62,"stk_opt":-512.71,"stk_fut":27.31}}
```
`dtDate` = ISO date `YYYY-MM-DD`. All values ₹ Crore. Negative = FII net short.

## Replication notes (→ ArthaYantra)
- One daily endpoint with 4 nested net segment values → four `ay-echart` bar charts + 4-column table.
- Flatten `statsObj` when building table data: `{dtDate, idx_fut, idx_opt, stk_fut, stk_opt}`.
- Green/red by sign. Index Options net is the headline FII derivative sentiment proxy.

## Screenshot
ss_8313wueqk (Index Futures/Options + Stock Futures/Options net bars).
