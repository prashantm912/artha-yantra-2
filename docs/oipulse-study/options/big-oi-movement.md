# Big OI Movement — `/app/options-analysis/big-oi-movement`

**Purpose:** surface the **biggest OI-change events** per interval — strikes that saw notable OI build/unwind,
split Call vs Put, tagged by moneyness. A focused "what just moved" list. Sub-tabs: `Big Oi Movement | Options Analysis`.

## Layout
```
sub-tabs: [ Big Oi Movement ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Select Expiry Date[30-Jun-2026▾]  [Go]
        Underlying: NIFTY BANK at 57198.8 …
        CALL (CE)                                          PUT (PE)
┌ table ───────────────────────────────────┐   ┌ table ───────────────────────────────────┐
│ Time | Asset Price | Strike Price |       │   │ Time | Asset Price | Strike Price |       │
│  Moneyness | Close Price | LTP Chg. |     │   │  Moneyness | Close Price | LTP Chg. |     │
│  OI Chg. | OI Interpretation              │   │  OI Chg. | OI Interpretation              │
└────────────────────────────────────────────┘   └────────────────────────────────────────────┘
```
Two side-by-side tables (CE left, PE right); only the significant OI-move rows (few per session).

## Columns (both tables)
| Column | Source | Render |
|---|---|---|
| Time | `stFetchTime` (interval `stFetchTimeOld`→`stFetchTime`) | |
| Asset Price | `inAssetPrice` (underlying at that time) | |
| Strike Price | `inStrikePrice` | bold |
| Moneyness | `stOptionsMoneyness` | **badge: `OTM` red/orange · `ATM` yellow · `ITM` green** |
| Close Price | option premium | |
| LTP Chg. | computed | green/red |
| OI Chg. | `inOiChange` | green/red |
| OI Interpretation | computed | badge (Long/Short Build Up etc.) |

## Data source / API (`big-oi-movement`)
| Call | Response |
|---|---|
| `/api/big-oi-movement/getavailableoptionsdata` | underlyings |
| `/api/big-oi-movement/getselectedoptionsdate` | dates |
| `/api/big-oi-movement/getselectedoptionsdataexpirydate` | expiries |
| `/api/big-oi-movement/getbigoimovementdata` | main |

Main:
```json
{ "data":[ { "stFetchTimeOld":"10:00:00","stFetchTime":"10:05:00",
             "inAssetPrice":57431.05, "inStrikePrice":"57400", "stOptionsType":"PE",
             "stOptionsMoneyness":"ATM", "inOiChange":… /* + close, ltp chg, interp */ } ],
  "underLyingAssetData":{…} }
```
`stOptionsType` splits CE/PE tables; `stOptionsMoneyness` drives the moneyness badge; rows are pre-filtered to "big" moves server-side.

## Replication notes (→ ArthaYantra)
- Endpoint returns notable OI-move rows (CE+PE) with moneyness; render two `p-table`s.
- Moneyness `p-tag` (OTM/ATM/ITM) + OI interpretation tag. Threshold for "big" is server-side.

## Screenshot
ss_4545wlgno (CE + PE big-move tables, moneyness OTM/ATM/ITM badges).
