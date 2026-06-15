# FII/DII Capital Market — `/app/fii-dii/capital-market`

**Purpose:** FII & DII **cash-market** buy/sell/net flows — daily, charted and tabulated. Track who's
buying/selling the cash market. Sub-tabs: `Capital Market | FII & DII Activity`.

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
| Detailed table | table | Date, FII Buy/Sell/Net, **In Market** (green badge = total turnover), DII Net/Buy/Sell |

Table value coloring: Net columns green (+) / red (−).

## Data source / API
`POST /api/fii-dii/getcapitalmarketdata` →
```json
{ "data":[ { "dtDate":"15-06-2026",
             "inFiiBuyValue":15650.2,"inFiiSellValue":15450.15,"inFiiNetValue":200.05,
             "inDiiBuyValue":21080.9,"inDiiSellValue":17891.64,"inDiiNetValue":3189.26 } ] }   // 365 days
```
Charts use `inFiiNetValue` / `inDiiNetValue`; "In Market" = total turnover (buy+sell) computed.

## Replication notes (→ ArthaYantra)
- We already have an FII-DII net-flow chart (per project memory). This = two `ay-echart` net-value bar charts + a detailed flows table off one daily endpoint.
- Green/red bars by net sign; table mirrors all six buy/sell/net fields.

## Screenshot
ss_2052tq4p8 (FII Net + DII Net bar charts + detailed flows table).
