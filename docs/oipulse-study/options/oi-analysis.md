# Options OI Analysis — `/app/options-analysis`

**Purpose:** the core options OI table for a single strike — Call and Put OI/price evolution intraday,
mirrored around the strike, with OI interpretation + day high/low breaks for each side. The primary
intraday options-positioning read. Sub-tabs: `Oi Analysis | Options Analysis`.

## Layout
```
sub-tabs: [ Oi Analysis ] [ Options Analysis ]   ;  ticker strip
filter: Mode(Live/Hist)  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]  Strike Price[57100▾]  Time Interval[3 min▾]  [Go]
┌ mirrored table (Call | Strike | Put), per interval ─────────────────────────────────────────────────┐
│  ◄─────────────── CALL side ───────────────►   STRIKE   ◄─────────────── PUT side ───────────────►   │
│ Time | Call OI | Total OI Chng | Call D.H/L Break | Call LTP | Call LTP Chng | Call Chng.in OI |      │
│   Call OI Interpretation | STRIKE | Put Oi Interpretation | Put Chng.in OI | Put LTP Chng | Put LTP | │
│   Put D.H/L Break | Total OI Chng | Put OI                                                            │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[25▾]                                   1 - 25 of 126   ‹ Previous  Next ›
```
Single strike (57100), time descending. Call columns mirror Put columns around the centered Strike.

## Filter bar
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Name | select | options underlying (BANKNIFTY, NIFTY, stocks) — `getavailableoptionsdata` |
| Date | date picker | trading day |
| Expiry Date | select | `30-Jun-2026` etc. — `getoptionsdataexpirydate` |
| Strike Price | select | ATM-centered strikes — `getselectedoptionsstrikepricedata` |
| Time Interval | select | `3 min` etc. |
| Go | button (red) | fetch |

## Columns (per interval row)
| Side | Column | Source | Render |
|---|---|---|---|
| Call | Call OI | CE `inOi` | |
| Call | Total OI Chng | computed (CE OI − day-open) | |
| Call | Call D. H/L Break | computed vs CE `inDayHigh/Low` | badge e.g. **`D.L.B (814.80) ↓`** (red, day-low break) / `D.H.B` (high break) |
| Call | Call LTP | CE `inClose` | |
| Call | Call LTP Chng | computed | green/red |
| Call | Call Chng. in OI | computed (CE OI − prev) | green/red |
| Call | Call OI Interpretation | computed | badge (L.B/L.U/S.B/S.C — `inOiInterpretation` enum) |
| — | **Strike** | `inStrikePrice` | centered, bold |
| Put | Put Oi Interpretation | computed | badge |
| Put | Put Chng. in OI | computed | green/red |
| Put | Put LTP Chng | computed | green/red |
| Put | Put LTP | PE `inClose` | |
| Put | Put D. H/L Break | computed vs PE `inDayHigh/Low` | badge |
| Put | Total OI Chng | computed (PE) | |
| Put | Put OI | PE `inOi` | |

OI interpretation labels (full words here): "Long Build Up", "Short Build Up", "Long Unwinding", "Shorts Covering" — same 4-state enum/colors (see `00-global-shell.md`).

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/options/getavailableoptionsdata` | `{stSelectedModeOfData}` | underlyings `[{text,type}]` |
| `/api/options/getselectedoptionsdate` | `{stSelectedOptions, stSelectedModeOfData}` | dates |
| `/api/options/getoptionsdataexpirydate` | `{stSelectedOptions, stSelectedAvailableDate, stSelectedModeOfData}` | expiries |
| `/api/options/getselectedoptionsstrikepricedata` | `{stSelectedOptions, stSelectedAvailableDate, stSelectedModeOfData, stSelectedAvailableExpiryDate}` | strikes `[{text,value}]` |
| `/api/options/getselectedoptionsalldata` | + `{…strike, …interval}` | `data:[ row ]` (CE & PE rows) |

Row:
```json
{ "stOptionsType":"PE", "inStrikePrice":"57100", "stTime":"09:16:00", "stDataFetchType":"IM",
  "inOi":"17130", "inOpen":780,"inHigh":780,"inLow":511,"inClose":518.3,
  "inDayHigh":780,"inDayLow":511, "inTradedVolume":7170 }
```
`stOptionsType` = `CE`/`PE` splits the row into Call vs Put columns; UI pairs them by `stTime`.
`stDataFetchType`: `IM`=intraday minute, `PEOD`/`EOD` variants.

## Replication notes (→ ArthaYantra)
- One endpoint returns CE+PE interval rows for a strike; UI pivots to a mirrored Call|Strike|Put table.
- Compute per-side: OI chng, LTP chng, OI interpretation, day H/L break. Render with `inOiInterpretation` tags.
- Strike/expiry/underlying cascade from the options-metadata endpoints.

## Screenshot
ss_4233ci4uj (BANKNIFTY 57100 3-min, Call|Strike|Put, D.L.B breaks, interpretation badges).
