# Options OI Spurt — `/app/options-analysis/oi-spurt`

**Purpose:** Option-strike OI scanner. Buckets every CE/PE strike of an expiry into four
OI-action quadrants ranked by OI change — surfaces which strikes are seeing fresh writing/unwinding.
Breadcrumb: `OI Spurt | Options Analysis`.

## Layout
```
breadcrumb: OI Spurt  |  Options Analysis
ticker strip
filter bar:
  Mode[Live data◉ / Historical○]  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]
  Search[🔍 text filter by strike]  [Go]
underlying header: NIFTY BANK | LTP: 57156.8 | DH: 57399.7 | DL: 57076.25

┌ Rise in OI & Rise in Price ──┐   ┌ Rise in OI & Fall in Price ──┐
│ "Long Build Up"  (12 rows)   │   │ "Short Build Up" (129 rows)  │
│ table: columns below         │   │ table: same columns          │
└──────────────────────────────┘   └──────────────────────────────┘
┌ Fall in OI & Rise in Price ──┐   ┌ Fall in OI & Fall in Price ──┐
│ "Short Unwinding" (6 rows)   │   │ "Long Unwinding" (129 rows)  │
└──────────────────────────────┘   └──────────────────────────────┘
```
2×2 grid of tables. Each table paginated (7 rows shown). Search filters all 4 tables simultaneously.
**Live data** (as of 13:20 IST BANKNIFTY 30-Jun): 12 / 129 / 6 / 129 distribution.

## Quadrant names — exact Vue data keys
| Quadrant (top-left→right, bottom-left→right) | Vue key | OI direction | Price direction | Interpretation |
|---|---|---|---|---|
| Long Build Up | `RiseInOiRiseInPriceData` | ↑ OI | ↑ Price | Fresh longs added |
| Short Build Up | `RiseInOiSlideInPriceData` | ↑ OI | ↓ Price | Fresh shorts added |
| Short Unwinding (Short Covering) | `SlideInOiRiseInPriceData` | ↓ OI | ↑ Price | Shorts exiting |
| Long Unwinding | `SlideInOiSlideInPriceData` | ↓ OI | ↓ Price | Longs exiting |

Temp arrays (`temp*`) hold original data before search filter — restored on search clear.

## Per-quadrant table columns (12 fields confirmed from live data)
| Column | Field | Notes |
|---|---|---|
| Strike | `inStrikePrice` | "43000", "58200", etc. |
| Type | `stOptionsType` | "CE" or "PE" |
| Expiry | `stExpiryDate` | raw API: "260630" (YYMMDD); Vue displays as "30-Jun-2026" |
| LTP (New) | `inNewLtp` | current interval close |
| Prev LTP | `inOldLtp` | previous interval close |
| % Chng LTP | `inLtpChangeInPercentage` | computed, e.g. 1.01 |
| LTP Chng | `inLtpChange` | absolute change, e.g. 13.15 |
| New OI | `inNewOi` | "4980" (string) |
| Old OI | `inOldOi` | "4380" |
| % Chng OI | `inOiChangeInPercentage` | e.g. 13.7 |
| OI Chng | `inOiChange` | absolute change, e.g. 600 |
| Volume | `inVolume` | "2370" |

Sample row (Rise in OI + Rise in Price):
```json
{
  "inNewLtp": "1315.5", "inOldLtp": "1302.35",
  "inLtpChangeInPercentage": 1.01, "inLtpChange": 13.15,
  "inNewOi": "4980", "inOldOi": "4380",
  "inOiChangeInPercentage": 13.7, "inOiChange": 600,
  "inVolume": "2370",
  "stOptionsType": "PE", "inStrikePrice": "58200", "stExpiryDate": "30-Jun-2026"
}
```

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Name | same 9 Index + 211 Stocks | |
| Date | date picker | |
| Expiry Date | YYMMDD values | |
| Search | text input | filters by `inStrikePrice` across all 4 tables |
| Go | button | triggers `getoispurtdataforselectedoptions` |

## Additional state
- `strikePriceIndex`: object mapping `"57200PE":{arrayIndex:N, arrayType:"SlideInOiSlideInPriceData"}` — fast strike lookup
- `doneTypingInterval: 300` — search debounce 300ms
- `underLyingAssetData`: NIFTY BANK LTP/DH/DL/DO displayed above tables
- Socket: `Array(2)` events subscribed for live updates
- `randomIdString`: "Oz6" — per-instance identifier (ignore)

## Data source / API
| Endpoint | Request | Response |
|---|---|---|
| `POST /api/options/getavailableoptionsdata` | `{stSelectedModeOfData}` | grouped instruments |
| `POST /api/options/getselectedoptionsdate` | + `{stSelectedOptions}` | dates |
| `POST /api/options/getoptionsdataexpirydate` | + `{stSelectedAvailableDate}` | expiries |
| `POST /api/options/getoispurtdataforselectedoptions` | `{stSelectedOptions, stSelectedAvailableDate, stSelectedAvailableExpiryDate, stSelectedModeOfData}` | all rows |

Main endpoint response:
```json
{
  "status": "success",
  "data": {
    "data": [
      { "inStrikePrice":"43000", "stOptionsType":"PE", "stExpiryDate":"260630",
        "inOldOi":"115770", "inOldClose":"3.5", "inNewOi":"114480", "inNewClose":"3.6",
        "inTradedVolume":"23340", "stFetchTime":"23:45:00", "stFetchDate":"2026-06-15T..." }
    ],
    "underLyingAssetData": { "stUnderLyingAsset":"NIFTY BANK", "inLtp":57156.8, ... }
  }
}
```
Client buckets rows into 4 quadrants by sign(ΔPrice) × sign(ΔOI), then sorts by OI change magnitude descending.

## Interpretation (how to trade)
- The four-quadrant model plus a strength filter: a strike merely *appearing* in a quadrant is not a signal — it qualifies only when %ΔLTP > 50% AND %ΔOI > 50% (the `inLtpChangeInPercentage` / `inOiChangeInPercentage` columns are the decision metrics). Per-quadrant trade role: Q1 Long Build-Up = buyer focus; Q2 Short Build-Up = writer focus; Q3 Short Covering = buyer but short-lived; Q4 Long Unwinding = retail avoid. Calls and Puts in the same quadrant imply opposite market direction — read all four together.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- One API call returns all strikes (CE+PE) for the expiry; client-side quadrant bucketing
- 4 PrimeNG p-tables (one per quadrant) with shared search filter
- Quadrant labels: "Rise in OI & Rise in Price" (header) + "Long Build Up" (subtitle)
- strikePriceIndex for O(1) lookup when socket update arrives
- Underlying header strip: same LTP/DH/DL/DO pattern as OI Analysis

## Screenshot
ss_5965jlxgl (BANKNIFTY 30-Jun strikes: Long/Short Build Up, Short/Long Unwinding).
