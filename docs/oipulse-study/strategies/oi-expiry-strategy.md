# OI Expiry Strategy — `/app/options-analysis/oi-expiry-strategy` (title: "Options EOD Oi Analysis")

**Purpose:** daily EOD OI/price history for **each selected strike** (Call + Put), to study how strikes
behave across days approaching expiry (expiry-week OI build/decay). Sub-tabs: `Oi Expiry Strategy | Options Analysis`.
(Listed under the Strategies menu column.)

## Layout
```
sub-tabs: [ Oi Expiry Strategy ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]  [Go]  [Change Strike Prices]
 Selected Strike Prices: 56500, 57000, 57500, 58000, 58500
        Underlying: NIFTY BANK at 57198.8 …
┌ 56500 CE ───────────────────────────────┐ ┌ 56500 PE ───────────────────────────────┐
│ daily EOD table                          │ │ daily EOD table                          │
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
┌ 57000 CE ───────────────────────────────┐ ┌ 57000 PE ───────────────────────────────┐
└───────────────────────────────────────────┘ └───────────────────────────────────────────┘
… one CE|PE table pair per selected strike …
```
**Grid of per-strike CE/PE daily-EOD tables** (one pair per selected strike).

## Per-table columns (daily, newest first)
| Column | Source | Render |
|---|---|---|
| Date | `stDate` | daily |
| Open / High / Low / Close | `inDayOpen` / `inDayHigh` / `inDayLow` / `inDayClose` | premium OHLC |
| Volume | `inVolume` | |
| % Chg Close | computed day-over-day | green/red |
| % Chg OI | computed | green/red |
| OI | `inOi` | |
| OI Interpretation | computed (price×OI) | badge (Short Covering / Long Build Up / Short Build Up / Long Unwinding) |

Each table paginated (7 rows; `1-7 of 31` days). Day-high cell highlighted green, day-low red in some rows.

## Data source / API (`opt-eod-oi-analysis`)
| Call | Response |
|---|---|
| `/api/opt-eod-oi-analysis/getselectedoptionsdate` | dates |
| `/api/opt-eod-oi-analysis/getselectedoptionsdataexpirydate` | expiries |
| `/api/opt-eod-oi-analysis/getselectedoptionseoddata` | main |

Main:
```json
{ "data":[ { "inStrikePrice":"56500",
             "objStrikePriceData":[
               {"CE":[ {"stDate":"2026-06-15","inDayOpen":1500.05,"inDayHigh":1611.35,"inDayLow":1175.1,"inDayClose":1220.5,"inOi":250590,"inVolume":1034820}, … ]},
               {"PE":[ … ]} ] } ],
  "underLyingAssetData":{…}, "listOfStrikePrice":[…selected…] }
```
Per strike → CE & PE daily-EOD arrays; %Chg/interpretation computed day-over-day. "Change Strike Prices" sets which strikes render.

## Replication notes (→ ArthaYantra)
- Multi-strike picker → per strike fetch CE/PE daily EOD OHLC+OI → render a grid of table pairs.
- Compute %Chg Close/OI + OI interpretation day-over-day; reuse the options EOD series.

## Screenshot
ss_9039i7nwg (per-strike CE|PE daily EOD tables: 56500/57000/57500, interpretation badges).
