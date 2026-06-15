# Futures OI Spurt — `/app/futures-analysis/oi-spurt`

**Purpose:** market-wide F&O **OI scanner**. Scans all F&O stocks and buckets each into one of the
four OI-interpretation categories, ranked by OI change — surfacing where fresh longs/shorts are
building or unwinding across the market. Sub-tabs: `Oi Spurt | Futures Analysis`.

## Layout
```
sub-tabs: [ Oi Spurt ] [ Futures Analysis ]   ;  ticker strip
filter: Mode(Live/Hist)  Asset[All F&O Stocks▾]  Expiry[Current Month▾]  Date[📅]  Search[…]  [Go]
                                                                          Data last Updated At: -
┌ Long Build Up ───────────────────────────┐ ┌ Short Build Up ──────────────────────────┐
│ (price↑ OI↑)                              │ │ (price↓ OI↑)                              │
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
┌ Short Covering ──────────────────────────┐ ┌ Long Unwinding ──────────────────────────┐
│ (price↑ OI↓)                              │ │ (price↓ OI↓)                              │
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
```
**2×2 grid of four ranked tables**, one per OI interpretation (see matrix in `oi-analysis.md`).

## Filter bar
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live data / Historical |
| Asset | select | `All F&O Stocks` / index / sector groups (from `getlistofassetforheatmap`) |
| Expiry | select | Current Month / … |
| Date | date picker | trading day |
| Search | text input | "Search for particular symbol" — filter rows |
| Go | button (red) | scan |

## Per-quadrant table columns
| Column | Source | Render |
|---|---|---|
| Name | `stSymbolName` (+ mini-chart icon) | text |
| LTP | `inNewClose` | |
| Prev. Close | `inOldClose` | |
| LTP Chg % | computed `(new−old)/old` | green if +, red if − |
| OI Chg % | computed `(newOi−oldOi)/oldOi` | green/red |
| New OI | `inNewOi` | |
| Old OI | `inOldOi` | |
| OI Chg. | computed `newOi−oldOi` | green/red |

- All columns sortable (⇅). Each table independently paginated (`Rows per page 8`, `1-8 of N`, Prev/Next).
- Counts seen: Long Build Up 69 · Short Build Up 27 · Short Covering 104 · Long Unwinding 19.
- Bucketing rule = OI Interpretation matrix: sign(LTP Chg) × sign(OI Chg).

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/heatmap/getlistofassetforheatmap` | — | `data:[{text,value}]` asset groups |
| `/api/futures/getfuturesoispurtdate` | `{stSelectedModeOfData}` | `data:[{text,value}]` dates |
| `/api/futures/getfuturesoispurtdata` | `{stSelectedAsset, stSelectedExpiry, stSelectedAvailableDate, stSelectedModeOfData}` | `data:[ row ]` |

Row:
```json
{ "stSymbolName":"RADICO","stFetchDate":"...","stFetchTime":"...",
  "inOldOi":"181200","inOldClose":"3559.3","inNewOi":"225000","inNewClose":"3609.3" }
```
All 4 quadrants come from ONE `getfuturesoispurtdata` response; client computes %changes and buckets.

## Replication notes (→ ArthaYantra)
- One scan endpoint returning {symbol, oldOi, oldClose, newOi, newClose} for the asset universe.
- Client: compute LTP%/OI%; partition into 4 buckets; render 4 sortable `p-table`s, each paginated; Search filters symbol.

## Screenshot
ss_372655whl (4 quadrants: Long/Short Build Up, Short Covering, Long Unwinding).
