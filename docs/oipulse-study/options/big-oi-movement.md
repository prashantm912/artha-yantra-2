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

The 4 OI-interpretation badge colours: Long Build-Up green, Short Covering blue, Long Unwinding grey, Short Build-Up red. Moneyness badges: ATM yellow, ITM green, OTM orange.

## Data source / API (`big-oi-movement`)
| Call | Response |
|---|---|
| `/api/big-oi-movement/getavailableoptionsdata` | underlyings |
| `/api/big-oi-movement/getselectedoptionsdate` | dates |
| `/api/big-oi-movement/getselectedoptionsdataexpirydate` | expiries |
| `/api/big-oi-movement/getbigoimovementdata` | main |

Main request + confirmed row schema:
```
POST /api/big-oi-movement/getbigoimovementdata
Body: {
  "stSelectedOptions": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedAvailableExpiryDate": "260630",
  "stSelectedModeOfData": "live"
}
Response: {
  "status": "success",
  "data": {
    "data": [
      { "stFetchTimeOld": "13:35:00", "stFetchTime": "13:40:00",
        "inAssetPrice": 57244, "inStrikePrice": "57300", "stOptionsType": "CE",
        "stOptionsMoneyness": "OTM", "inOiChange": "23190",
        "inLtpChange": "-0.50", "inLtp": "669.75" },
      ...
    ],
    "underLyingAssetData": {...}
  }
}
```
Vue enriches each row client-side with `inOiInterpretation:3` (enum) + `stOiInterpretation:"Short Build Up"` (string).
Confirmed row fields: `{stFetchTimeOld, stFetchTime, inAssetPrice, inStrikePrice, stOptionsType, stOptionsMoneyness, inOiChange, inLtpChange, inLtp, inOiInterpretation, stOiInterpretation}`.
`stOptionsType` splits into `callTableData` (CE) / `putTableData` (PE). "Big" threshold is server-side.

## Socket subscriptions
- `BIG_OI_MOVEMENT_BANKNIFTY_260630` — live big-move events for expiry
- `EQUITY_UNDERLYING_DATA_NIFTY BANK`

## Interpretation (how to trade)
- Row-read recipe (ΔOI × ΔLTP): OI↑/price↑ = Long Build-Up; OI↑/price↓ = Short Build-Up; OI↓/price↑ = Short Covering; OI↓/price↓ = Long Unwinding.
- Methodology: Big-OI is a confirmation / position-sizing gate, not a standalone entry. If Big-OI confirms your bias, size up (aggressive); if it doesn't, or the table is empty, size down (cautious). Watch for bull/bear traps ("two candles that remove weak hands"). Confluence recipe: 2 consecutive candles + rising volume + SuperTrend + RSI, then confirm via Big-OI.
- Purpose is a position-sizing aid; "big" is an AI/server-side significance filter; the manual-era coverage was index-only.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Endpoint returns notable OI-move rows (CE+PE) with moneyness; render two `p-table`s.
- Moneyness `p-tag` (OTM/ATM/ITM) + OI interpretation tag. Threshold for "big" is server-side.

## Screenshot
ss_4545wlgno (CE + PE big-move tables, moneyness OTM/ATM/ITM badges).
