# Options OI Analysis — `/app/options-analysis`

**Purpose:** Core options OI table for a single strike — Call and Put OI/price evolution intraday,
mirrored around the strike, with OI interpretation + day high/low breaks for each side. Primary
intraday options-positioning read. Breadcrumb: `Oi Analysis | Options Analysis` (no sub-tabs; breadcrumb only).

## Layout
```
breadcrumb: Oi Analysis  |  Options Analysis
ticker strip: NIFTY(F): 23960.00 +43.40 (0.18%) | BANKNIFTY(F): 57181.00 -76.20 ... (9 symbols, scrolling)
filter bar:
  Mode[Live data◉ / Historical○]  Name[BANKNIFTY▾]  Date[Tue, Jun 16, 2026📅]
  Expiry Date[30-Jun-2026▾]  Strike Price[57100▾]  Time Interval[3 min▾]  [Go]

underlying header strip:
  NIFTY BANK  |  16 Jun 2026, 13:11:00  |  LTP: 57151.6  DH: 57399.7  DL: 57076.25  DO: 57198.8

┌ mirrored vue-good-table (Call | Strike | Put), per 3-min interval ──────────────────────────────┐
│ #  Time       Call OI   Total OI Chng  Call D.H/L  Call LTP  Call LTP Chng  Call Chng.OI       │
│    Call OI Interpretation  |  STRIKE  |  Put OI Interpretation  Put Chng.OI  Put LTP Chng       │
│    Put LTP  Put D.H/L  Total OI Chng  Put OI                                                    │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[10▾]     [25]  pagination: ‹ Previous  Next ›
Total rows: ~80 displayed (filtered from 484 raw API rows = 242 CE + 242 PE paired by stTime)
```

## Filter bar — exact controls
| Control | Type | Values / Options | Cascade trigger |
|---|---|---|---|
| Mode | radio | `live` (Live data) / `historical` (Historical) | reloads instrument list |
| Name | grouped select | **Index** (9): BANKNIFTY, NIFTY, FINNIFTY, MIDCPNIFTY, NIFTYNXT50, SENSEX, BANKEX, FOCIT, SENSEX50 · **Stocks** (211): 360ONE, ABB, ABCAPITAL … ZOMATO (full F&O universe) | triggers date fetch |
| Date | date picker | min: 2019-01-01, max: today; defaults to today | triggers expiry fetch |
| Expiry Date | select | for BANKNIFTY 2026-06-16: 30-Jun-2026(260630), 28-Jul-2026(260728), 25-Aug-2026(260825), 29-Sep-2026(260929), 29-Dec-2026(261229), 30-Mar-2027(270330) — format value=YYMMDD | triggers strike fetch |
| Strike Price | select | 193 strikes for BANKNIFTY (null default + 192 actual) — ATM-centered | no cascade |
| Time Interval | select | `null`(Please select), `3`(3 min), `5`(5 min), `10`(10 min), `15`(15 min), `30`(30 min), `60`(60 min) | no cascade |
| Go | button (red) | triggers `getselectedoptionsalldata` | — |

## Underlying header strip (above table)
Live-updating every 3 min (socket). Fields from `underlyingDetails`:
- `stUnderLyingAsset`: "NIFTY BANK" (full name of underlying)
- `stDateTime`: "16 Jun 2026, 13:11:00"
- `inLtp`: 57151.6
- `inDayHigh`: 57399.7
- `inDayLow`: 57076.25
- `inDayOpen`: 57198.8

## Columns — vue-good-table (16 columns)
| Col# | Header | Field | Render / Notes |
|---|---|---|---|
| 1 | Time | `stTimeInterval` | Bold, e.g. "13:09-13:12". Width 100px |
| 2 | Call OI | `inCallLatestOi` | formatted "1,71,540" |
| 3 | Total OI Chng | `inCallCumulativeOiChange` | cumulative OI change from day-open |
| 4 | Call D. H/L Break | `isCallDayHighBrake` / `isCallDayLowBrake` | badge "D.H.B" / "D.L.B (price) ↓" when boolean true |
| 5 | Call LTP | `inCallClose` | current interval close price |
| 6 | Call LTP Chng | `inCallLtpDiff` | green(+) / red(-) colored |
| 7 | Call Chng. in OI | `inCallOiChange` | green(+) / red(-) colored |
| 8 | **Call OI Interpretation** | `inCallOiInterpretation` | badge (see enum below) — centered |
| 9 | **Strike** | `inStrikePrice` | centered, bold — anchor column |
| 10 | **Put Oi Interpretation** | `inPutOiInterpretation` | badge — mirrored |
| 11 | Put Chng. in OI | `inPutOiChange` | green(+) / red(-) |
| 12 | Put LTP Chng | `inPutLtpDiff` | green(+) / red(-) |
| 13 | Put LTP | `inPutClose` | current interval close price |
| 14 | Put D. H/L Break | `isPutDayHighBrake` / `isPutDayLowBrake` | badge when true |
| 15 | Total OI Chng | `inPutCumulativeOiChange` | PUT side cumulative |
| 16 | Put OI | `inPutLatestOi` | formatted |

## OI Interpretation badge CSS (confirmed from live DOM)
| Enum | Label | CSS class | Icon class | Color |
|---|---|---|---|---|
| 1 | Long build up | `badge-success` | `i-Up1` (↑) | Green |
| 2 | Long unwinding | `badge-warning` | `i-Down1` (↓) | Yellow/Orange |
| 3 | Short build up | `badge-danger` | `i-Down1` (↓) | Red |
| 4 | Shorts covering | `badge-info` | `i-Up1` (↑) | Blue/Teal |

Full CSS: `badge text-capitalize badge-interpretation text-right badge-{class}`

## Row schema (computed by Vue — paired CE+PE from raw API)
```json
{
  "stTimeInterval": "13:09-13:12",
  "stNewTime": "13:12",
  "stDate": "2026-06-16",
  "stName": "BANKNIFTY",
  "stDataFetchType": "IM",
  "inCallOi": "173580",
  "inCallLatestOi": 170880,
  "inCallOiChange": -3420,
  "inCallCumulativeOiChange": 103410,
  "inCallClose": 731.05,
  "inCallOpen": 728,
  "inCallHigh": 736.95,
  "inCallLow": 726.25,
  "inCallDayHigh": 911.95,
  "inCallDayLow": 683,
  "inCallDayHighPrev": 911.95,
  "inCallDayLowPrev": 683,
  "inCallTradedVolume": 9330,
  "inCallLtpDiff": 3.05,
  "inCallOiInterpretation": 3,
  "isCallDayHighBrake": false,
  "isCallDayLowBrake": false,
  "inStrikePrice": "57100",
  "inPutOi": "151530",
  "inPutLatestOi": 148620,
  "inPutOiChange": -3420,
  "inPutCumulativeOiChange": 62370,
  "inPutClose": 677,
  "inPutOpen": 679.5,
  "inPutHigh": 680.45,
  "inPutLow": 665.65,
  "inPutDayHigh": 733.2,
  "inPutDayLow": 591.65,
  "inPutDayHighPrev": 733.2,
  "inPutDayLowPrev": 591.65,
  "inPutTradedVolume": 8130,
  "inPutLtpDiff": -2.0,
  "inPutOiInterpretation": 4,
  "isPutDayHighBrake": false,
  "isPutDayLowBrake": false
}
```
Vue pairs raw CE+PE rows by `stTime` into this combined structure and computes derived fields.

## Data source / API
| Endpoint | Request params | Response |
|---|---|---|
| `POST /api/options/getavailableoptionsdata` | `{stSelectedModeOfData:"live"}` | `data:[{value:null,text:"Please select..."},{label:"Index",options:[{text,value}]},{label:"Stocks",options:[...]}]` — grouped |
| `POST /api/options/getselectedoptionsdate` | `{stSelectedOptions:"BANKNIFTY", stSelectedModeOfData:"live"}` | `data:[{value:null,text:...},{text:"Tue, Jun 16, 2026",value:"2026-06-16"}]` |
| `POST /api/options/getoptionsdataexpirydate` | + `{stSelectedAvailableDate:"2026-06-16"}` | `data:[{value:null,...},{text:"30-Jun-2026",value:"260630"},...]` value=YYMMDD |
| `POST /api/options/getselectedoptionsstrikepricedata` | + `{stSelectedAvailableExpiryDate:"260630"}` | `data:[{value:null,...},{text:"57100",value:"57100"},...]` 193 strikes |
| `POST /api/options/getselectedoptionsalldata` | + `{stSelectedStrikePrice:"57100", stSelectedTimeInterval:"3"}` | `data:[raw_row×484]` |

Raw API row (one CE or PE entry, 484 total = 242 CE + 242 PE):
```json
{
  "stOptionsType": "PE",
  "inStrikePrice": "57100",
  "stTime": "09:16:00",
  "stDataFetchType": "IM",
  "inOi": "34260",
  "inOpen": 702.6,
  "inHigh": 702.6,
  "inLow": 591.65,
  "inClose": 640.9,
  "inDayHigh": 702.6,
  "inDayLow": 591.65,
  "inTradedVolume": 9240
}
```
Vue pairs CE+PE rows by `stTime`, computes OI change/cumulative/LTP diff/interpretation/DH-DL-break per side.
`stDataFetchType`: `IM`=intraday-minute.

## Replication notes (→ ArthaYantra)
- Raw endpoint returns alternating CE/PE rows per time tick → pair by stTime → mirrored table
- 16 columns: 7 Call + Strike + 7 Put (symmetric). vue-good-table with line-numbers column
- Compute per-side: `inOiChange` (curr−prev OI), `inCumulativeOiChange` (curr−day-open OI), `inLtpDiff` (curr−prev close), interpretation enum, day H/L brake booleans
- OI interpretation badge: `badge-success/warning/danger/info` + `i-Up1/i-Down1` icon classes
- Underlying header strip: live LTP/DH/DL/DO from separate socket subscription
- 211 stocks + 9 indices in Name dropdown (same instrument set across all options/* pages)
- Expiry value format: YYMMDD string (e.g. "260630" = 30-Jun-2026)
- Refresh button (red "Go") disabled during fetch (`disableRefreshDataButton`)

## Screenshot
ss_4233ci4uj (BANKNIFTY 57100 3-min, Call|Strike|Put, D.L.B breaks, interpretation badges).
