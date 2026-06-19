# Equity Returns — `/app/equity/equity-returns`

**Purpose:** multi-timeframe **returns screener** — stocks + indices returns over Current Day / 1 Week /
1 Month / 6 Months / 1 Year. Filter by Stock/Index/All. Uses **AG Grid** (not vue-good-table). No sub-tabs.
Browser page title says "Equity Delivery Data" — likely a title bug in OiPulse; route is `/equity-returns`.

## Layout
```
filter: Period[All data▾]  [Go]                                  Data as on 16-06-2026 14:49:00
┌ AG Grid table (per-column floating filter + sort) ──────────────────────────────────────────────────┐
│ Name ⇅ [filter] | Industry [filter] | LTP [filter] | Current Day ⇅ | 1 Week | 1 Month | 6 Months | 1 Year │
│ 360ONE | Financial Services | 1139.60 | 0.78% | 5.19% | 3.38% | -1.08% | 0%                          │
│ …                                                                                                       │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
238 rows (211 stocks + 27 indices). Default sorted by Current Day desc. Name column yellow (#ffeeba) with cursor pointer.

## Vue component state (confirmed)
```
lastUpdatedAt,
tableData ([]),           // 238 combined (stocks + indices)
stockItems ([]),          // 211 stock rows
indexItems ([]),          // 27 index rows
getRowNodeId,             // AG Grid row id function
columnDefs ([]),          // AG Grid column definitions
defaultColDef,
gridApi, columnApi,       // AG Grid API refs
disableRefreshDataButton,
selectedSymbolType ("ALL"),
availableSymbolType       // [{text:"Stock Only",value:"STOCK"},{text:"Index Only",value:"INDEX"},{text:"All data",value:"ALL"}]
```

**Note**: uses AG Grid (ag-grid-vue), NOT vue-good-table. AG Grid-specific config: `floatingFilter:true`, per-column `agTextColumnFilter`/`agNumberColumnFilter`.

## Columns (AG Grid columnDefs — confirmed)
| AG field | Header | Notes |
|---|---|---|
| `stSymbolName` | Name | `agTextColumnFilter`, `floatingFilter`, yellow bg, cursor pointer |
| `stIndustryName` | Industry | `agTextColumnFilter`, `floatingFilter` |
| `inCurrentClose` | LTP | `agNumberColumnFilter`, sortable |
| `in1DayReturns` | Current Day | default sort desc (`sort:"desc"`, `sortIndex:0`) |
| `in1WeekReturns` | 1 Week | |
| `in1MonthReturns` | 1 Month | |
| `in6MonthReturns` | 6 Months | |
| `in1YearReturns` | 1 Year | |

## Data source / API
`POST /api/equity/getequityreturnsdata` — no filter params needed (returns all stocks + indices):
```json
{ "data":[ {
  "stFetchDate": "2026-06-16",
  "stFetchTime": "14:49:00",
  "stSymbolType": "STK",
  "stSymbolName": "360ONE",
  "stIndustryName": "Financial Services",
  "inCurrentClose": 1139.6,
  "in1DayReturns": "0.78",
  "in1WeekReturns": "5.19",
  "in1MonthReturns": "3.38",
  "in6MonthReturns": "-1.08",
  "in1YearReturns": "0"
}] }
```
Index row sample (stSymbolType:"IDX", stIndustryName:null): BANKEX, inCurrentClose:64498, returns as strings.
All return periods precomputed server-side. Returns are STRING not number in API (e.g. `"0.78"` not `0.78`).

## Replication notes (→ ArthaYantra)
- One endpoint — split response into stockItems/indexItems by `stSymbolType`; Period filter (STOCK/INDEX/ALL) combines or filters the two lists.
- Use PrimeNG `p-table` with `[globalFilterFields]` and column sort; or replicate the AG Grid floating filter pattern.
- Name column sticky with yellow tint. Return cells green/red by sign.

## Screenshot
ss_0140yevlq (238-stock returns screener, multi-period % columns).
