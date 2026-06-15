# Sector Heatmap — `/app/equity/sector-wise-heatmap`

**Purpose:** a **sector-grouped treemap** of an index's stocks — tiles grouped by sector, sized by market
cap, colored by % change. One-glance read of which sectors/stocks are green vs red. Sub-tabs: `Sector-wise heatmap | Equity`.

## Layout
```
sub-tabs: [ Sector-wise heatmap ] [ Equity ]   ;  ticker strip
filter: Mode(Live/Hist)  Index[Nifty 50▾]  Date[📅]  Size[Market Cap▾]  [Go]
┌ "All" (outer container) ──────────────────────────────────────────────────────────────────────────┐
│ ┌ Financial Services ┐ ┌ Oil Gas & … ┐ ┌ Automobile … ┐ ┌ FMCG ┐ ┌ Healthcare ┐ ┌ Consumer Durables ┐│
│ │ ICICIBANK -0.98%   │ │ RELIANCE …  │ │ MARUTI 3.28% │ │ …    │ │ …          │ │ TITAN 2.38%       ││
│ │ SBIN  HDFCBANK …   │ │ ONGC COALIND│ │ M&M  EICHERMOT│ │      │ │            │ │                   ││
│ └────────────────────┘ └─────────────┘ └──────────────┘ └──────┘ └────────────┘ └───────────────────┘│
│ … IT, Metals & Mining, Telecommunication, Power, Construction, Construction Materials, Services, …    │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
**Nested (grouped) treemap**: outer = index, level-1 = sector (labeled boxes), level-2 = stock tiles.

## Filter
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Index | select | `Nifty 50` / others (`getlistofassetforheatmap`) |
| Date | date picker | day |
| **Size** | select | `Market Cap` (tile-size metric; likely also volume/value options) |
| Go | button (red) | render |

## Treemap
- Grouped by `stSector` (sector boxes with header labels).
- Tile size ∝ `inIssuedSize` (market cap) — controlled by **Size** selector.
- Tile color ∝ `inChangePercentage` (green up → red down gradient, intensity by magnitude).
- Tile label = symbol + `±%`.

## Data source / API
`POST /api/equity/getsectorwisestockperformancedata` →
```json
{ "data":[ { "stSymbolName":"ICICIBANK", "stSector":"Financial Services",
             "inIssuedSize":<marketCap>, "inPrevClose":…, "inClose":…, "inChangePercentage":-0.98 } ] }   // 49 stocks
```
Group by `stSector`; size by `inIssuedSize`; color by `inChangePercentage`.

## Replication notes (→ ArthaYantra)
- `ay-echart` treemap with `levels` (sector → stock); `value` = market cap; `visualMap` green↔red on % change.
- Size selector swaps the value metric. (Compare with OI Buzz's flat treemap — this one is sector-grouped.)

## Screenshot
ss_0484gsrkn (Nifty 50 sector-grouped heatmap, 15 sector boxes).
