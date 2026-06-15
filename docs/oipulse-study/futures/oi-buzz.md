# Futures OI Buzz — `/app/futures-analysis/oi-buzz` (title: "Futures Heatmap")

**Purpose:** index-constituent **heatmap** — see at a glance which stocks in an index are up/down
and by how much. Sub-tabs: `Oi Buzz | Futures Analysis`.

## Layout
```
sub-tabs: [ Oi Buzz ] [ Futures Analysis ]   ;  ticker strip
filter: Mode(Live/Hist)  Index[Nifty 50▾]  Date[📅]  Search[…]  [Go]      Data last Updated At: -
 Oi Buzz (Change in % wise)        Advance: 34 / Decline: 16        (≡ toolbox top-right)
┌ TREEMAP heatmap ─────────────────────────────────────────────────────────────────────────────────┐
│ [ TRENT 5.06% ][ HDFCLIFE 4.39% ][ INDIGO 3.51% ] … big green tiles …  [ ONGC -1.05% ][ NTPC -1.63%]│
│ tile size ∝ weight,  tile color ∝ % change (green gainers → red losers, gradient by magnitude)      │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Components
| Component | Type | Position | Notes |
|---|---|---|---|
| Mode | radio | filter | Live / Historical |
| Index | select | filter | `Nifty 50` / Bank Nifty / sectors (from `getlistofassetforheatmap`) |
| Date | date picker | filter | |
| Search | text | filter | locate a symbol in the map |
| Go | button (red) | filter | refresh |
| Title | text | above map | "Oi Buzz (Change in % wise)" |
| Advance / Decline | text counters | center header | `Advance: N / Decline: M` (green/red counts) |
| Toolbox | ≡ icon | top-right | ECharts treemap menu (save image, etc.) |
| Heatmap | **ECharts treemap** | body | one tile per constituent |
| Tile | rectangle | — | label = symbol + `±%`; **size ∝ weight**, **fill color ∝ %change** (green=up, red=down, intensity by magnitude) |

### Color scale
Continuous green→red: strong gainers dark green, flat pale, strong losers dark red. White tile labels.

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/heatmap/getlistofassetforheatmap` | — | index/asset options |
| `/api/heatmap/getselectedassetdate` | `{stSelectedModeOfData}` | dates |
| `/api/heatmap/getselectedassetalldata` | `{stSelectedAsset, stSelectedAvailableDate, stSelectedModeOfData}` | `data:[ row ]` |

Row:
```json
{ "stSymbolName":"TRENT","inOldOi":"...","inOldClose":"...",
  "inNewDayOpen":..,"inNewDayHigh":..,"inNewDayLow":..,"inNewClose":..,"inNewOi":"..." }
```
Tile `%` = `(inNewClose − inOldClose)/inOldClose`. Advance/Decline = counts of sign. (Title implies an OI%/price% lens; OI fields present for an OI-change variant.)

## Replication notes (→ ArthaYantra)
- `ay-echart` treemap: value = weight (tile size), color mapped to change% via `visualMap` (green↔red).
- Header advance/decline counters; index selector drives the constituent set; search highlights a tile.

## Screenshot
ss_1724qkouw (Nifty 50 heatmap, Advance 34 / Decline 16).
