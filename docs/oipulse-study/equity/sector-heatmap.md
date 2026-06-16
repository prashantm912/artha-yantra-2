# Sector Heatmap — `/app/equity/sector-wise-heatmap`

**Purpose:** a **sector-grouped treemap** of an index's stocks — tiles grouped by sector, sized by market
cap or % change, colored by % change. One-glance read of which sectors/stocks are green vs red. No sub-tabs.

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
| Index (Asset) | select | All F&O Stocks / Nifty 50 / Nifty Bank / Nifty Fin Service / Nifty Mid Select / Nifty It / Nifty Realty / Nifty Infra / Nifty Energy / Nifty Fmcg / Nifty Mnc / Nifty Pharma / Nifty Pse / Nifty Psu Bank / Nifty Serv Sector / Nifty Auto / Nifty Media / Nifty Metal / … |
| Date | date picker | day |
| **Size** | select | `Market Cap` (`MARKETCAP`) / `Change in %` (`CHANGE_IN_PERCENTAGE`) |
| Go | button (red) | render |

Asset list from `heatmap` namespace: `getlistofassetforheatmap`. Same endpoint used by Sector Stats and Open=High/Low filter.

## Treemap
- Grouped by `stSector` (sector boxes with header labels).
- Tile size ∝ `inIssuedSize` (market cap in shares) OR `inChangePercentage` — controlled by **Size** selector.
- Tile color ∝ `inChangePercentage` (green up → red down gradient, intensity by magnitude).
- Tile label = symbol + `±%`.

## Vue component state (confirmed)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedModeOfData, selectedAvailableDate,
selectedAvailableAsset ("Nifty 50"),
availableDate, availableAsset,     // populated from heatmap/getlistofassetforheatmap
availableModeOfData,
selectedSizeType ("MARKETCAP"),    // MARKETCAP | CHANGE_IN_PERCENTAGE
selectedIndexType ("ALL"),         // filter within selected asset? (ALL | NIFTY 50 | NIFTY BANK)
availableIndexType, availableSizeType,
rowData ([])                       // 49 rows for Nifty 50
```

## Data source / API
| Endpoint | Namespace | Request |
|---|---|---|
| `getlistofassetforheatmap` | `heatmap` | `{stSelectedModeOfData}` → asset dropdown |
| `getselectedassetdate` | `heatmap` | `{stSelectedModeOfData, stSelectedAvailableAsset}` → dates |
| `getsectorwisestockperformancedata` | `equity` | `{stSelectedModeOfData, stSelectedAvailableDate, stSelectedAvailableAsset:"Nifty 50"}` → rowData |

Row schema (confirmed — ADANIENT):
```json
{
  "stSymbolName": "ADANIENT",
  "stSector": "Metals & Mining",
  "inIssuedSize": "1154180729",
  "inPrevClose": "2942.5",
  "inClose": "2941.3",
  "inChangePercentage": "-0.04"
}
```
Group by `stSector`; size by `inIssuedSize` (or `inChangePercentage`); color by `inChangePercentage`.

No socket — `socketSubscribedEvents` not present in this component.

## Replication notes (→ ArthaYantra)
- `ay-echart` treemap with `levels` (sector → stock); `value` = market cap or % change; `visualMap` green↔red on `inChangePercentage`.
- Size selector swaps value metric between `inIssuedSize` and `inChangePercentage`.
- Compare with OI Buzz's flat treemap — this one is sector-grouped.

## Screenshot
ss_0484gsrkn (Nifty 50 sector-grouped heatmap, 15 sector boxes).
