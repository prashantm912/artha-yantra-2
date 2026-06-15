# Trending OI — `/app/options-analysis/trending-oi`

**Purpose:** track aggregated Call vs Put OI across a chosen **band of strikes** over time, and derive
a directional **sentiment** (bullish/bearish) from the net OI change + PCR. Sub-tabs: `Trending OI | Options Analysis`.

## Layout
```
sub-tabs: [ Trending OI ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]  Time Interval[3 min▾]  [Go]  [Change Strike Prices]  ☐ Show Graph View  ☐ Show positional Data
 Selected Strike Prices: 57100, 57200, 57300, … 58500
        Underlying: NIFTY BANK at 57198.8 …
┌ table (scrollable, paginated 100) ──────────────────────────────────────────────────────────────────┐
│ Date | Time | LTP | Day H/L Break | Chng. In Call OI | Chng. In Put OI | Diff. in OI |               │
│   Direction of chng. | Chng. In Direction | Direction of chng. % | Net PCR | Day High/Low Diff. in OI | Sentiment │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[100▾]                                   1 - 100 of 126
```

## Filter bar
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Name / Date / Expiry Date / Time Interval | selects | as elsewhere |
| Go | button (red) | fetch |
| Change Strike Prices | button (red outline) | pick which strikes to aggregate (the "Selected Strike Prices" band) |
| Show Graph View | checkbox | switch table → chart |
| Show positional Data | checkbox | overlay positional/EOD data |

**Selected Strike Prices** band (below filter) lists the aggregated strikes (default ~15 ATM strikes).

## Table columns
| Column | Source / computed | Render |
|---|---|---|
| Date | `stFetchDate` | |
| Time | `stTime` ("EOD","15:30:00") | |
| LTP | `inClose` (underlying) | |
| Day H/L Break | computed | badge `D.L.B (57136.7)` red / `D.H.B` |
| Chng. In Call OI | Δ `totalCeOi` vs prev | green/red |
| Chng. In Put OI | Δ `totalPeOi` | green/red |
| Diff. in OI | (Call OI chng − Put OI chng) | red if calls dominate (bearish), green if puts |
| Direction of chng. | sign of Diff | arrow badge ↑ green / ↓ red |
| Chng. In Direction | net OI direction magnitude | green/red |
| Direction of chng. % | % | green/red |
| Net PCR | `totalPeOi / totalCeOi` | |
| Day High/Low Diff. in OI | computed | |
| Sentiment | derived | **badge `Bearish` (red) / `Bullish` (green)** |

Sentiment is the headline: more call writing than put → Bearish; vice-versa → Bullish.

## Data source / API (`trending-oi-static`)
| Call | Response |
|---|---|
| `/api/trending-oi-static/getavailableassetsdata` | instruments |
| `/api/trending-oi-static/getselectedassetdate` | dates |
| `/api/trending-oi-static/getassetsdataexpirydate` | expiries |
| `/api/trending-oi-static/gettrendingoiforselectedstrikeprices` | main |

Main response:
```json
{ "data": [ { "stFetchDate":"2026-06-12","stTime":"23:50:00","stDataFetchType":"PEOD",
              "inClose":0,"inHigh":0,"inLow":0,
              "objOiData":[{"CE":2059260},{"PE":476130}], "totalCeOi":2059260,"totalPeOi":476130 } ],
  "listOfStrikePrice":[…selected strikes…],
  "underLyingAssetData":{…}, "oiSnapshotData":{…/* for graph view */} }
```
Per-interval `totalCeOi`/`totalPeOi` aggregate the selected strikes; all Chng/Diff/PCR/Sentiment computed client-side over consecutive rows.

## Replication notes (→ ArthaYantra)
- Strike-band selector → aggregate CE/PE OI per interval → compute ΔCall, ΔPut, Diff, PCR, direction, sentiment.
- Table + optional graph view (Diff-in-OI / PCR over time). Sentiment tag drives the at-a-glance read.

## Screenshot
ss_8031xmqfw (BANKNIFTY band aggregate, Bearish sentiment, Net PCR column).
