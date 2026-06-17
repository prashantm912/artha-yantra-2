# Banks Analysis — `/app/futures-analysis/banks-analysis`

**Purpose:** side-by-side OI matrix of the major Bank Nifty constituent banks. Per time interval,
each bank shows its (LTP% / OI%) move + OI interpretation — so you see which banks are driving
Bank Nifty and how positions build across them. Sub-tabs: `Banks Analysis | Futures Analysis`.

## Layout
```
sub-tabs: [ Banks Analysis ] [ Futures Analysis ]   ;  ticker strip
filter: Mode(Live/Hist)   Date[📅]   Time Interval[5 min▾]   [Go]
┌ matrix table (scrollable, paginated 25) ────────────────────────────────────────────────────────┐
│ Time | Hdfc Bank ⓘ | Icici Bank ⓘ | Axis Bank ⓘ | SBI ⓘ | Kotak bank ⓘ | Indusind bank ⓘ        │
│ 09:15-09:20 | (1.94%/0.23%)L.B | (0.70%/0.33%)L.B | (0.00%/0.00%) | (0.91%/0.21%)L.B | …          │
│ 15:30-EOD   | (0.64%/-2.18%)S.C | …                                                                │
│ 15:25-15:30 | …                                                                                    │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[25▾]                                   1 - 25 of 78   ‹ Previous  Next ›
┌ Flags (legend) ─ L.B = Long Build Up (grn) · S.B = Short Build Up (red) · L.U = Long Unwinding (yel) · S.C = Short Covering (blu) ┐
```
Top row = current forming interval (09:15-09:20), then `15:30-EOD`, then descending 5-min intervals.

## Filter bar
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live data / Historical |
| Date | date picker | trading day |
| Time Interval | select | 3 min / 5 min / 10 min / 15 min / 30 min / 60 min |
| Go | button (red) | fetch |

No expiry or symbol filter — always shows all 6 banks.

## Matrix cells
Columns: **Time** + one per bank (Hdfc Bank, Icici Bank, Axis Bank, SBI, Kotak bank, Indusind bank;
each header has an ⓘ info icon). Each bank cell:
- Text: `( LTP% / OI% )` — **cumulative-from-day-open** percentages (LTP first, OI second). Green if +, red if −.
- Badge: OI interpretation for that interval — `L.B`/`S.B`/`L.U`/`S.C` colored.

### OI interpretation enum (CONFIRMED — `inOiInterpretation`, reused app-wide)
| int | label | abbrev | badge | color | price×OI |
|---|---|---|---|---|---|
| `1` | Long Buildup | `L.B` | `badge-success` | green `#4caf50` | price↑ OI↑ |
| `2` | Long Unwinding | `L.U` | `badge-warning` | yellow `#ffc107` | price↓ OI↓ |
| `3` | Short Buildup | `S.B` | `badge-danger` | red `#f44336` | price↓ OI↑ |
| `4` | Short Covering | `S.C` | `badge-info` | blue `#003473` | price↑ OI↓ |

> Verified by joining API `inOiInterpretation` ints to rendered badge colors across all banks/rows (35×1, 38×3, 10×4, 6×2 — zero conflicts).

## Vue component state (confirmed)
```
selectedModeOfData, selectedFutures (null), selectedAvailableDate,
selectedTimeInterval,    // default 5
timeInterval,            // [{text:"3 min",value:3},...{value:60}]
availableFuturesData,    // [{"value":null,"text":"Please select a futures name"}]
availableDate, availableModeOfData,
tableData,               // array of 75 rows for full day (09:15–15:30 = 375 min / 5 = 75 at 5-min)
columns,                 // [{label:"Time",field:"stTimeInterval"}, {label:"Hdfc Bank",field:"inOiInterpretation_HDFCBANK"},...]
pagination,              // {perPage:25, page:1, ...}
totalRecords,
minAvailableDate, maxAvailableDate,
disableRefreshDataButton,
socketSubscribedEvents   // ["TICKER_DATA","TICKER_RESET_DATA"] — ticker strip only, NO live OI socket
```

Vue enriched row (flat, per time interval — confirmed):
```json
{
  "stTimeInterval": "14:10-14:15",
  "inOiInterpretation_AXISBANK": 1, "inOiDiffInPercentage_AXISBANK": 1.82, "inLtpDiffInPercentage_AXISBANK": -0.02,
  "inOiInterpretation_HDFCBANK": 1, "inOiDiffInPercentage_HDFCBANK": 0.67, "inLtpDiffInPercentage_HDFCBANK": 1.14,
  "inOiInterpretation_ICICIBANK": 1, "inOiDiffInPercentage_ICICIBANK": 0.27, "inLtpDiffInPercentage_ICICIBANK": 0.17,
  "inOiInterpretation_INDUSINDBK": 1, "inOiDiffInPercentage_INDUSINDBK": 0.72, "inLtpDiffInPercentage_INDUSINDBK": -0.43,
  "inOiInterpretation_KOTAKBANK": 1, "inOiDiffInPercentage_KOTAKBANK": 0.09, "inLtpDiffInPercentage_KOTAKBANK": 0.52,
  "inOiInterpretation_SBIN": 4, "inOiDiffInPercentage_SBIN": 0.66, "inLtpDiffInPercentage_SBIN": -0.85
}
```
Vue creates this by pivoting the per-bank arrays from the API response, aligning on `stTimeInterval`.

## Socket subscriptions
No futures-specific socket — page is fetch-on-demand (Go button).
Only `TICKER_DATA` / `TICKER_RESET_DATA` from the ticker strip component.

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/bank-analysis/getavailablefuturesdate` | `{stSelectedModeOfData}` | `data:[{text,value}]` dates |
| `/api/bank-analysis/getbanksanalysisallData` | `{inSelectedTimeInterval, stSelectedAvailableDate, stSelectedModeOfData}` | `data:{ <BANK>:[ row ] }` |

Response shape: `data` is an object keyed by bank symbol (`HDFCBANK, ICICIBANK, AXISBANK, SBIN, KOTAKBANK, INDUSINDBK`), each an array of:
```json
{
  "inClose": 1363.5, "inOpen": 1370.6,
  "stNewTime": "09:20", "stTimeInterval": "09:15-09:20",
  "inLtpDiffInPercentage": "-0.52",
  "inOiDiffInPercentage": "0.26",
  "inOiInterpretation": 3
}
```
Note: `inLtpDiffInPercentage`/`inOiDiffInPercentage` are STRINGS in raw API (`"-0.52"`) but numbers in Vue enriched rows.
Per-bank arrays are aligned by `stTimeInterval`; UI pivots them into one matrix row.

## Interpretation (how to trade)
- The 6 banks are the 6 highest-contribution Bank Nifty constituents.
- Consensus vs divergence: if all/most banks point one way ⇒ Bank Nifty may move strongly;
  divergence ⇒ consolidation / irrational one-candle moves.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- We already capture all 17 bank-sector F&O futures (per project memory / `/banks-grid`). This is a time × bank pivot of LTP%/OI%/interpretation.
- Endpoint returns per-bank interval arrays; UI pivots to a matrix `p-table`. Each cell = numbers + interpretation `p-tag`.
- Use the confirmed `inOiInterpretation` enum for the tag.

## Screenshot
ss_5732e0vk0 (6-bank × interval matrix, L.B/S.B/L.U/S.C badges, Flags legend).
