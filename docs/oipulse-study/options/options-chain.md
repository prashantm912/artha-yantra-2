# Options Chain — `/app/options-analysis/options-chain`

**Purpose:** Full option chain — every strike's Call and Put OI/IV/LTP/changes/interpretation
with per-strike PCR, ATM-centered. Dense master view of positioning across all strikes.
Breadcrumb: `Options Chain | Options Analysis`.

## Layout
```
breadcrumb: Options Chain  |  Options Analysis
ticker strip (same 9 symbols as all pages)
filter bar:
  Mode[Live data◉ / Historical○]  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]
  Interval[Full Day▾]  [Go]  [Column Setting]

header strip (live, updates every ~60s):
  INDIA VIX: 13.4725  DH:14.7175  DL:13.01  DO:14.3525
  Total PCR: 1.013  (prev: 1.036, chg: -0.023)
  ATM: 57200  |  Days to Expiry: 14.35
  NIFTY BANK: LTP 57166.95  DH: 57399.7  DL: 57076.25  DO: 57198.8  |  16 Jun 2026, 13:19:00

┌ AG-Grid chain table (CALL | STRIKE | PUT) — 194 strikes ──────────────────────────────────────────┐
│  ◄────────────── CALL (visible cols) ──────────────►            ◄────────── PUT (visible) ─────────►│
│  OI Int | OI% | OI | OI Chng | IV | LTP* | LTP% | LTP Chg | STRIKE | LTP Chg | LTP% | LTP* | IV  │
│  OI Chng | OI | OI% | OI Int | PCR Ratio                                                           │
│  (* = animated flash on change; CustomAgAnimateShowChangeCellRenderer)                               │
│  ATM strike row highlighted: backgroundColor #ffeeba, clickable (opens chart sub-view)              │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```
**194 strikes** for BANKNIFTY (43000–78000+ range). Table uses AG-Grid (not vue-good-table).

## Filter bar — exact controls
| Control | Type | Values |
|---|---|---|
| Mode | radio | `live` / `historical` |
| Name | grouped select | same 9 Index + 211 Stocks as OI Analysis |
| Date | date picker | min 2019-01-01, max today |
| Expiry Date | select | same expiry list (YYMMDD values) |
| Interval | select | `null`(Full Day), `3`(Last 3 min), `5`(Last 5 min), `10`(Last 10 min), `15`(Last 15 min), `30`(Last 30 min), `60`(Last 1 Hour), `120`(Last 2 Hour), `240`(Last 4 Hour), `PREV_DAY_EOD_WITH_CUSTOM_TIME`(Only Custom End time), `CUSTOM_TIME`(Start & End Both Custom time) |
| Go | button (red) | fetch |
| Column Setting | button (outline) | opens modal to show/hide columns |

Custom time: `startTimeValue:{HH:"09",mm:"16"}`, `endTimeValue:{HH:"15",mm:"30"}` — editable time pickers.

## Header strip — exact fields
```json
{
  "indiaVixData": { "stUnderLyingAsset":"INDIA VIX", "stDateTime":"16 Jun 2026, 13:19:00",
    "inLtp":13.4725, "inDayHigh":14.7175, "inDayLow":13.01, "inDayOpen":14.3525 },
  "underLyingAssetData": { "stUnderLyingAsset":"NIFTY BANK", "stDateTime":"16 Jun 2026, 13:19:00",
    "inLtp":57166.95, "inDayHigh":57399.7, "inDayLow":57076.25, "inDayOpen":57198.8 },
  "totalPcr": "1.013",
  "totalOldPcr": 1.036,
  "totalChangeInPcr": "-0.023",
  "optionChainAtm": 57200,
  "inDaysLeftInExpiry": 14.35
}
```

## AG-Grid column definitions — 3 groups: CALL | Strike | PUT
**CALL columns** (18 total; `typeOfColumn:"CALL"`):

| Header | Field | Default visible | Notes |
|---|---|---|---|
| Chart | `inCallChart` | hidden | custom button renderer |
| Straddle | `inStraddlePrice` | hidden | Call+Put LTP sum |
| Combine Premium | `inCombinePremium` | hidden | |
| Premium | `inCallPremium` | hidden | time value portion |
| Intrinsic | `inCallIntrinsicValue` | hidden | |
| Delta | `inCallDelta` | hidden | Greek |
| O = L | `inCallOpenLow` | hidden | Open = Low flag |
| O = H | `inCallOpenHigh` | hidden | Open = High flag |
| **OI Int.** | `inCallOiInterpretation` | **visible** | badge; minWidth 50 |
| **OI %** | `inCallOiChangePerc` | **visible** | % change; minWidth 65 |
| **OI** | `inCallOi` | **visible** | with inline data-bar; minWidth 80 |
| **OI Chng.** | `inCallOiChange` | **visible** | minWidth 80 |
| Volume | `inCallVolume` | hidden | |
| IV Chng. | `inCallIvChange` | hidden | |
| **IV** | `inCallIv` | **visible** | minWidth 50 |
| **LTP** | `inCallLtp` | **visible** | **animated** (CustomAgAnimateShowChangeCellRenderer); fontWeight 700; minWidth 115 |
| **LTP %** | `inCallLtpChangePerc` | **visible** | minWidth 65 |
| **LTP Chg** | `inCallLtpChange` | **visible** | minWidth 65 |

**Strike column** (center):
- Field: `inStrikePrice` | Header: "Strike" | `backgroundColor:"#ffeeba"`, `color:"#000000"`, `cursor:"pointer"` | `cellRenderer:"CustomStrikePriceButtonRenderer"` | sortable:true

**PUT columns** (17 total; `typeOfColumn:"PUT"`, mirror of CALL):

| Header | Field | Default visible |
|---|---|---|
| **LTP Chg** | `inPutLtpChange` | **visible** |
| **LTP %** | `inPutLtpChangePerc` | **visible** |
| **LTP** | `inPutLtp` | **visible** (animated) |
| **IV** | `inPutIv` | **visible** |
| IV Chng. | `inPutIvChange` | hidden |
| Volume | `inPutVolume` | hidden |
| **OI Chng.** | `inPutOiChange` | **visible** |
| **OI** | `inPutOi` | **visible** |
| **OI %** | `inPutOiChangePerc` | **visible** |
| **OI Int.** | `inPutOiInterpretation` | **visible** |
| O = H | `inPutOpenHigh` | hidden |
| O = L | `inPutOpenLow` | hidden |
| Delta | `inPutDelta` | hidden |
| Intrinsic | `inPutIntrinsicValue` | hidden |
| Premium | `inPutPremium` | hidden |
| **PCR Ratio** | `inPcrRatio` | **visible** |
| Chart | `inPutChart` | hidden |

Selectable columns (17): `[inCallOiInterpretation, inCallOiChangePerc, inCallOi, inCallOiChange, inCallIv, inCallLtp, inCallLtpChangePerc, inCallLtpChange, inPutLtpChange, inPutLtpChangePerc, inPutLtp, inPutIv, inPutOiChange, inPutOi, inPutOiChangePerc, inPutOiInterpretation, inPcrRatio]`

## Complete row schema (45 fields per strike row — confirmed live)
```json
{
  "inTime": "-",
  "inStrikePrice": 57200,
  "inCallChart": "-",
  "inPutChart": "-",
  "inStraddlePrice": "1394.95",
  "inCombinePremium": "1361.90",
  "inCallPremium": "692.55",
  "inCallIntrinsicValue": 0,
  "inCallDayOpen": "811.25",
  "inCallDayHigh": "858.75",
  "inCallDayLow": "634.1",
  "inCallDelta": "0.56",
  "inCallOiInterpretation": 3,
  "inCallIvChange": "-1.55",
  "inCallIv": 13.12,
  "inCallOiChange": 144930,
  "inCallOi": 276330,
  "inCallOiChangePerc": 110.30,
  "inCallVolume": 1667430,
  "inCallLtp": 692.55,
  "inCallLtpChangePerc": -12.66,
  "inCallLtpChange": "-100.40",
  "inCallOldLtp": "792.95",
  "inCallOldOi": 131400,
  "inCallOldIv": 14.67,
  "inPutLtpChange": "-28.20",
  "inPutLtpChangePerc": -3.86,
  "inPutLtp": 702.4,
  "inPutVolume": 1736100,
  "inPutOi": 214470,
  "inPutOiChange": 123390,
  "inPutOiChangePerc": 135.47,
  "inPutIv": 17.6,
  "inPutIvChange": "-0.54",
  "inPcrRatio": "-",
  "inPutOiInterpretation": 3,
  "inPutDayOpen": "749.1",
  "inPutDayHigh": "782.85",
  "inPutDayLow": "630.75",
  "inPutDelta": "-0.45",
  "inPutPremium": "669.35",
  "inPutIntrinsicValue": 33.05,
  "inPutOldLtp": "730.6",
  "inPutOldOi": 91080,
  "inPutOldIv": 18.14
}
```
Note: deeply ITM/OTM rows have `"-"` for many fields (no market activity). `inPcrRatio:"-"` for strikes without valid PCR.

## Max OI reference (used for inline data-bar widths)
```json
{
  "maxCallOi": 1660770, "maxCallOiIndex": 152,
  "maxPutOi": 1404300, "maxPutOiIndex": 92,
  "maxCallOiChange": 190260, "maxCallOiChangeIndex": 122,
  "maxPutOiChange": 123390, "maxPutOiChangeIndex": 124,
  "minCallOiChange": -103230, "minPutOiChange": -54900
}
```
Bar width = (cellValue / maxValue) × 100%. Separate max for call/put OI and OI change.

## Data source / API
| Endpoint | Request | Response |
|---|---|---|
| `POST /api/options/getavailableoptionsdata` | `{stSelectedModeOfData}` | grouped `[{label:"Index",options:[...]},{label:"Stocks",options:[...]}]` |
| `POST /api/options/getselectedoptionsdate` | + `{stSelectedOptions}` | dates array |
| `POST /api/options/getoptionsdataexpirydate` | + `{stSelectedAvailableDate}` | expiries YYMMDD |
| `POST /api/options/getoptionschaindataforselectedoptions` | + `{stSelectedAvailableExpiryDate, stSelectedModeOfData, inSelectedavailableTimeRange:null, stStartTime:null, stEndTime:null}` | full chain response (below) |

Full chain API response structure:
```json
{
  "status": "success",
  "data": {
    "data": [ { /* CE or PE row per strike — raw */ } ],
    "indiaVixData": { "stUnderLyingAsset":"INDIA VIX", "stDateTime":"...", "inLtp":13.47, ... },
    "underLyingAssetData": { "stUnderLyingAsset":"NIFTY BANK", "stDateTime":"...", "inLtp":57166.95, ... }
  }
}
```
Raw CE/PE rows (`stOptionsType:"CE"/"PE"`) have: `inStrikePrice`, `inNewClose`, `inNewDayHigh`, `inNewDayLow`, `inNewDayOpen`, `inNewIv`, `inNewOi`, `inTradedVolume`, `inOldClose`, `inOldOi`, `inOldIv`, `inOldTradedVolume`.
Vue component pairs CE/PE by `inStrikePrice` → computes all deltas/Greeks/PCR/interpretation client-side.

## Visual cues
- **ATM row**: `backgroundColor:"#ffeeba"` (cream/yellow), `cursor:"pointer"` — click opens chart sub-view
- **OI data-bars**: inline horizontal bars in OI cells; width = value/maxOI × 100%; red=CALL side, green=PUT side
- **LTP animation**: `CustomAgAnimateShowChangeCellRenderer` — cell flashes on value change
- **OI interpretation**: same 4-state badge as OI Analysis (badge-success/warning/danger/info)
- **ITM tint**: rows where strike < underlying LTP (calls ITM) or > underlying (puts ITM) get light tint
- **PCR Ratio column**: rightmost PUT column; "-" for strikes without meaningful PCR

## Replication notes (→ ArthaYantra)
- Use AG-Grid (not p-table) for this page — it uses custom cell renderers + animated cells
- One endpoint returns raw CE+PE rows + VIX + underlying in one response envelope
- Pair CE/PE by inStrikePrice; compute OI%/LTP%/chng/interp/PCR client-side
- Column visibility modal (17 toggleable columns)
- ATM computed from `optionChainAtm` (nearest strike to underlying LTP)
- Days to expiry = `inDaysLeftInExpiry` (fractional)
- Socket events (CONFIRMED): `OD_OC_{SYMBOL}_{EXPIRY}` (chain ticks), `EQUITY_UNDERLYING_DATA_NIFTY BANK` (underlying), `EQUITY_UNDERLYING_DATA_INDIA VIX` (VIX)

## Screenshot
ss_5693wz43d (BANKNIFTY 30-Jun chain, ATM band, OI data-bars, PCR column).
