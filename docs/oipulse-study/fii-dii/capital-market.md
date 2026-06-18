# FII/DII Capital Market — `/app/fii-dii/capital-market`

**Purpose:** FII & DII **cash-market** buy/sell/net flows — daily, charted and tabulated. Track who's
buying/selling the cash market. No sub-tabs.

## Layout
```
sub-tabs: [ Capital Market ] [ FII & DII Activity ]   ;  ticker strip
        FII/DII Capital Market Activity (Values in Crores)   (title)
┌ bar chart — FII Net Value ────────────────────────────────────────────────────────────────────────┐
│ y: Crore (−25,000 … +15,000)   x: dates (23-12-24 … 12-06-26)   green = net buy, red = net sell      │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
┌ bar chart — DII Net Value ────────────────────────────────────────────────────────────────────────┐
│ y: Crore   x: dates   green/red bars (mostly green = DII net buyers)                                  │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
        Detailed FII/DII Capital Market Activity (Values in Crores)   (table)
┌ Date | FII Buy | FII Sell | FII Net | In Market | DII Net | DII Buy | DII Sell ─────────────────────┐
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Components
| Component | Type | Source |
|---|---|---|
| FII Net Value chart | ECharts bar | `inFiiNetValue` per day (green +, red −); watermark; toolbox |
| DII Net Value chart | ECharts bar | `inDiiNetValue` per day |
| Detailed table | table | Date, FII Buy/Sell/Net, **In Market** (= FII Net + DII Net), DII Net/Buy/Sell |

Table value coloring: Net columns green (+) / red (−).

## Vue component state (confirmed)
```
multipleBar2,                // ECharts config object (2 bar chart series in one chart)
disableRefreshDataButton,
capitalMarketData ([]),      // 365 daily rows (all data)
tableData ([]),              // same 365 rows used for table display
columns ([]),                // vue-good-table column defs
pagination,                  // server-side pagination state
totalRecords                 // 365
```

## Columns (vue-good-table — confirmed)
```
Date (dtDate) · FII Buy (inFiiBuyValue) · FII Sell (inFiiSellValue) · FII Net (inFiiNetValue) ·
In Market (inMarketNet) · DII Net (inDiiNetValue) · DII Buy (inDiiBuyValue) · DII Sell (inDiiSellValue)
```
`inMarketNet` = computed = **`FII Net + DII Net`**. No sort on any column.

> **Confirmed live 2026-06-18** by arithmetic on live rows: `101.59 + 1561.4 = 1662.99` and
> `200.05 + 3189.26 = 3389.31` — the **"In Market" column = FII Net + DII Net** (the study's reading),
> NOT the manual's "cash-market net per row". See [PHASE-B-FINDINGS.md](../PHASE-B-FINDINGS.md) (§5 V3).

## Data source / API
`POST /api/fii-dii/getcapitalmarketdata` — no filter params (returns full ~365 days):

Confirmed rows:
```json
// Older row (Dec 2024)
{"dtDate":"2024-12-23","inFiiBuyValue":8705.49,"inFiiSellValue":8874.2,"inFiiNetValue":-168.71,"inDiiBuyValue":11083.76,"inDiiSellValue":8856.08,"inDiiNetValue":2227.68}
// Recent row
{"dtDate":"2026-06-15","inFiiBuyValue":15650.2,"inFiiSellValue":15450.15,"inFiiNetValue":200.05,"inDiiBuyValue":21080.9,"inDiiSellValue":17891.64,"inDiiNetValue":3189.26}
```
Note: `dtDate` uses ISO date format `YYYY-MM-DD`. All values in ₹ Crore. `inMarketNet` not in API — computed client-side.

Chart: `multipleBar2` = ECharts config with `{backgroundColor, title, tooltip, toolbox, axisPointer, grid, xAxis, yAxis, series}`. Two bar series (FII Net, DII Net) in one chart.

## Interpretation (how to trade)
- A green bar means institutions invested that day (bullish); a red bar means redemption (bearish). Judge collective action over several days, not any single bar.
- FII/DII activity influences but does not determine the next session — never use it in isolation.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- We already have an FII-DII net-flow chart on the dashboard. This page = two `ay-echart` bar series (FII Net + DII Net) + a detailed flows table off one daily endpoint.
- `inMarketNet` = FII Net + DII Net (computed client-side).
- Green/red bars by net sign; table shows all 6 buy/sell/net fields + computed `inMarketNet`.

## Screenshot
ss_2052tq4p8 (FII Net + DII Net bar charts + detailed flows table).
