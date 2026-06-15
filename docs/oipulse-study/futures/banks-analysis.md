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
| Mode | radio | Live / Historical |
| Date | date picker | trading day |
| Time Interval | select | `5 min` (and others) |
| Go | button (red) | fetch |

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

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/bank-analysis/getavailablefuturesdate` | `{stSelectedModeOfData}` | `data:[{text,value}]` dates |
| `/api/bank-analysis/getbanksanalysisallData` | `{inSelectedTimeInterval, stSelectedAvailableDate, stSelectedModeOfData}` | `data:{ <BANK>:[ row ] }` |

Response shape: `data` is an object keyed by bank symbol (`HDFCBANK, ICICIBANK, AXISBANK, SBIN, KOTAKBANK, INDUSINDBK`), each an array of:
```json
{ "stTimeInterval":"09:20-09:25", "stNewTime":"09:25",
  "inOpen":1359.7, "inClose":1373.4,
  "inLtpDiffInPercentage":"1.01", "inOiDiffInPercentage":"0.60", "inOiInterpretation":1 }
```
Per-bank arrays are aligned by `stTimeInterval`; UI pivots them into one matrix.

## Replication notes (→ ArthaYantra)
- We already capture all 17 bank-sector F&O futures (per project memory / `/banks-grid`). This is a time × bank pivot of LTP%/OI%/interpretation.
- Endpoint returns per-bank interval arrays; UI pivots to a matrix `p-table`. Each cell = numbers + interpretation `p-tag`.
- Use the confirmed `inOiInterpretation` enum for the tag.

## Screenshot
ss_5732e0vk0 (6-bank × interval matrix, L.B/S.B/L.U/S.C badges, Flags legend).
