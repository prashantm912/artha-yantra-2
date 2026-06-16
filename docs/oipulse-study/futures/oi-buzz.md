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
| Toolbox | ≡ icon | top-right | chart toolbar |
| Heatmap | **ApexCharts treemap** (NOT ECharts) | body | one tile per constituent |
| Tile | rectangle | — | label = symbol + `±%`; **size ∝ weight**, **fill color ∝ %change** (green=up, red=down, intensity by magnitude) |

### Color scale
Continuous green→red: strong gainers dark green, flat pale, strong losers dark red. White tile labels.

## Vue component state (confirmed)
```
selectedModeOfData, selectedAvailableDate, selectedAvailableAsset,
availableDate, availableAsset, availableModeOfData,
advanceStock, declineStock,
heatmapData, tempHeatmapData,  // tempHeatmapData backs searchSymbol filter
searchSymbol, doneTypingInterval,
socketSubscribedEvents,         // ["FD_OIB_NIFTY 50"] — pattern: FD_OIB_{ASSET}
chart, chartOptions             // ApexCharts options
```

`chartOptions.series[0]`:
```json
{ "name": "% Change", "data": [{"x": "TATACONSUM", "y": 2.65}, {"x": "HCLTECH", "y": 2.05}, ...] }
```

Vue enriched `heatmapData` row:
```json
{
  "stSymbolName": "TATACONSUM",
  "inNewLtp": 1135.4, "inOldLtp": "1106.1",
  "inNewOi": "17331050", "inOldOi": "17463050",
  "inNewDayHigh": 1136.4, "inNewDayLow": 1112.9, "inNewDayOpen": 1116,
  "inLtpChangeInPercentage": 2.65, "inLtpChange": 29.3,
  "inOiChangeInPercentage": -0.76, "inOiChange": -132000,
  "stOiInterpretation": "Short Covering"
}
```

## Socket subscriptions
- `FD_OIB_NIFTY 50` — pattern: `FD_OIB_{ASSET}` (e.g. `FD_OIB_NIFTY BANK`)

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/heatmap/getlistofassetforheatmap` | `{}` | index/sector options (Nifty 50, Nifty Bank, Nifty Fin Service, etc.) |
| `/api/heatmap/getselectedassetdate` | `{stSelectedModeOfData}` | dates |
| `/api/heatmap/getselectedassetalldata` | `{stSelectedAsset, stSelectedAvailableDate, stSelectedModeOfData}` | `data:[ row ]` |

Raw API row (confirmed):
```json
{ "stSymbolName": "ADANIENT",
  "inOldOi": "19006281", "inOldClose": "2957.4",
  "inNewDayOpen": 2974, "inNewDayHigh": 3006, "inNewDayLow": 2935,
  "inNewClose": 2958.1, "inNewOi": "19453404" }
```
Tile `%` = `inLtpChangeInPercentage` (computed client-side). Advance/Decline = counts of sign of that %. OI fields power the `stOiInterpretation` badge (shown on tile tooltip or detail).

## Replication notes (→ ArthaYantra)
- `ay-echart` treemap: value = weight (tile size), color mapped to change% via `visualMap` (green↔red).
- Header advance/decline counters; index selector drives the constituent set; search highlights a tile.

## Screenshot
ss_1724qkouw (Nifty 50 heatmap, Advance 34 / Decline 16).
