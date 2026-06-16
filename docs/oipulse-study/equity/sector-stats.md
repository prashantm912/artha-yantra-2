# Sector Stats — `/app/equity/sector-stats`

**Purpose:** sector performance overview — sector index cards + a stock-factors table showing
per-stock constituent data with Factor and sector grouping. No sub-tabs.

## Layout
```
filter: Mode(Live/Hist)  Select Date[📅]  [Go]             Data Auto-updated At: 16-06-2026, 14:44:00
                              Sector Stats  (title)
┌ sector index cards (grid, ~19 cards) ────────────────────────────────────────────────────────────────┐
│ NIFTY 50:   23978.90 (+122.35 / 0.51%)   NIFTY BANK:   57239.25 (+75.55 / 0.13%)                    │
│ NIFTY MID SELECT:  14482.35              NIFTY FIN SERVICE:  26416.15                                 │
│ NIFTY IT: 28533.60 (+1.60%)  NIFTY INFRA  NIFTY ENERGY  NIFTY FMCG  NIFTY PHARMA (-0.27%)           │
│ NIFTY PSU BANK (-0.42%)  NIFTY SERV SECTOR  NIFTY AUTO (-0.25%)  NIFTY METAL (-1.69%)               │
│ NIFTY COMMODITIES (-0.20%)  NIFTY PVT BANK  NIFTY CONSR DURBL  NIFTY HEALTHCARE (-0.41%)            │
│ NIFTY OIL AND GAS  NIFTY IND DEFENCE (-0.30%)                                                         │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
┌ Stock Factors table ──────────────────────────────────────────────────────────────────────────────────┐
│ Name (stEquityName) | Factor (inFactor) | Chg.% (inPercentageChange) | Sector (stIndustryName)       │
│ Close (inClose) | Y.Day Close (inPrevClose)                                                            │
│ (populated from indexConstituents data; sortable)                                                      │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
19 sector cards total. Green % = up sectors, red % = down sectors.

## Components
| Component | Type | Detail |
|---|---|---|
| Sector index cards | grid of cards | one card per sector (19 total); shows index name, LTP, (+chg / %chg); green=up, red=down |
| Stock Factors table | vue-good-table | per-stock breakdown: Name, Factor, Chg%, Sector, Close, Y.Day Close; sortable |

## Vue component state (confirmed)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedAvailableDate, selectedModeOfData,
availableDate, availableModeOfData,
stStartTime ("09:15:00"), stEndTime (null),
sectorStats ([]),        // 19 sector index entries (used for top cards)
stockFactors ([]),        // constituent-level flat list (used for bottom table)
indexConstituents,        // raw constituents from API
columns,                  // vue-good-table column defs for stockFactors table
socketSubscribedEvents ([]  — no socket),
randomIdString, stLastUpdatedAt
```

## Confirmed sector list (19 sectors)
NIFTY METAL · NIFTY PSU BANK · NIFTY HEALTHCARE · NIFTY IND DEFENCE · NIFTY PHARMA ·
NIFTY AUTO · NIFTY COMMODITIES · NIFTY BANK · NIFTY 50 · NIFTY INFRA · NIFTY OIL AND GAS ·
NIFTY IT · NIFTY FMCG · NIFTY FIN SERVICE · NIFTY ENERGY · NIFTY MID SELECT ·
NIFTY PVT BANK · NIFTY CONSR DURBL · NIFTY SERV SECTOR

## sectorStats row schema (confirmed)
```json
{
  "stIndexName": "NIFTY METAL",
  "inClose": 12866.75,
  "inPrevClose": 13088.55,
  "inChngPerc": -1.69,
  "inPositiveStocks": 2,
  "inNegativeStocks": 9,
  "inTotalStocks": 11,
  "data": [
    { "stSymbolName": "NMDC",    "inClose": 87.88,  "inWeight": 3.15, "inPrevClose": 88.47,  "inChngPerc": -0.67 },
    { "stSymbolName": "SAIL",    "inClose": 180.65, "inWeight": 3.06, "inPrevClose": 181.96, "inChngPerc": -0.72 },
    { "stSymbolName": "VEDL",    "inClose": 299.7,  "inWeight": 6.21, "inPrevClose": 301.9,  "inChngPerc": -0.73 },
    { "stSymbolName": "ADANIENT","inClose": 2941.7, "inWeight": 8.72, "inPrevClose": 2940,   "inChngPerc":  0.06 },
    { "stSymbolName": "HINDALCO","inClose": 981.3,  "inWeight": 16.9, "inPrevClose": 1013.4, "inChngPerc": -3.17 }
  ]
}
```

## columns (stockFactors table — confirmed)
```json
[
  {"label":"Name",       "field":"stEquityName",          "sortable":true},
  {"label":"Factor",     "field":"inFactor",              "sortable":true},
  {"label":"Chg. %",     "field":"inPercentageChange",    "sortable":true},
  {"label":"Sector",     "field":"stIndustryName",        "sortable":true},
  {"label":"Close",      "field":"inClose",               "sortable":true},
  {"label":"Y.Day Close","field":"inPrevClose",           "sortable":false}
]
```

## Data source / API
| Endpoint | Namespace | Request | Notes |
|---|---|---|---|
| `getindexconstituentswithdata` | `equity` | `{stSelectedModeOfData, stSelectedAvailableDate, stStartTime:"09:15:00", stEndTime:"15:30:00"}` | returns sectorStats (19 sectors + constituent arrays) |

Response envelope: `{status, msg, data:[...sectorRows]}`. Each sector row has `inPositiveStocks`, `inNegativeStocks`, `inTotalStocks` + `data:[]` array of constituent stocks.

No socket — `socketSubscribedEvents: []`.

## Replication notes (→ ArthaYantra)
- One endpoint → all 19 sectors + constituents. Compute `stockFactors` flat list by flattening sector.data arrays.
- Sector cards show index LTP + chg%; Stock Factors table shows per-stock detail.
- `inPositiveStocks`/`inNegativeStocks`/`inTotalStocks` in each sector for breadth display.

## Screenshot
ss_5994c46qf (sector bar chart + NIFTY 50 / BANK / MID SELECT constituent tables with breadth).
