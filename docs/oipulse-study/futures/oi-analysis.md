# Futures OI Analysis — `/app/futures-analysis`

**Purpose:** the core futures Open-Interest table. For a chosen future + expiry, shows per-interval
OI, price, volume and the **OI interpretation** (Long Buildup / Short Buildup / Short Covering /
Long Unwinding) so a trader reads where positions are building or unwinding intraday.
Sub-tabs: `Oi Analysis | Futures Analysis`.

## Layout
```
sub-tabs: [ Oi Analysis ] [ Futures Analysis ]   ;  ticker strip
┌ filter bar ───────────────────────────────────────────────────────────────────────────────────┐
│ Mode:(•)Live ( )Historical  Name:[BANKNIFTY▾]  Expiry:[Current Month▾]                          │
│                              Date:[Mon, Jun 15 2026 📅]  Time Interval:[3 min▾]  Actions:[Go]    │
│                                                                       Data last Updated At: -     │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
┌ table (scrollable, paginated) ──────────────────────────────────────────────────────────────────┐
│ # | Date Time | Total OI | Total Chng. In OI | Day High | Day Low | Level Break | Volume |       │
│     LTP | LTP Change | OI Change | OI Interpretation                                              │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[25▾]                                         1 - 25 of 127   ‹ Previous  Next ›
```

## Filter bar
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live data / Historical |
| Name | select | future instrument (BANKNIFTY, NIFTY, stocks…) — from `getavailablefuturesdata` |
| Expiry | select | Current Month / Next / Far (per instrument) |
| Date | date picker | trading day (Historical) |
| Time Interval | select | 3 min (and others) |
| Go | button (red) | fetch |

## Table columns
First row is `15:30-EOD` (end-of-day summary), then descending 3-min intervals.

| Column | Source | Notes / render |
|---|---|---|
| Date Time | `stTime` → interval label ("15:27-15:30", "15:30-EOD") | text |
| Total OI | `inOi` | absolute OI |
| Total Chng. In OI | computed: `inOi − day-open OI` | cumulative day OI change |
| Day High | `inDayHigh` | |
| Day Low | `inDayLow` | |
| Level Break | computed | breakout marker vs day high/low ("-" if none) |
| Volume | `inTradedVolume` (interval delta) | |
| LTP | `inClose` | last price of interval |
| LTP Change | computed: `inClose − prev inClose` | green if +, red if − |
| OI Change | computed: `inOi − prev inOi` | green/red |
| **OI Interpretation** | computed from sign(LTP Change) × sign(OI Change) | **badge** (see matrix) |

### OI Interpretation matrix (REUSED across Futures & Options pages)
| Price (LTP) | OI | Interpretation | Badge color | Arrow |
|---|---|---|---|---|
| ↑ up | ↑ up | **Long Buildup** | green `badge-success` | ↑ |
| ↓ down | ↑ up | **Short Buildup** | red `badge-danger` | ↓ |
| ↑ up | ↓ down | **Short Covering** | blue `badge-info` | ↑ |
| ↓ down | ↓ down | **Long Unwinding** | yellow/orange `badge-warning` | ↓ |

(Observed live: "Short Covering ↑" blue, "Long Unwinding ↓" yellow.)

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/futures/getavailablefuturesdata` | `{stSelectedModeOfData}` | `data:[{text, type, value:[…expiries…]}]` instrument list |
| `/api/futures/getselectedfuturesdate` | `{stSelectedFutures, stSelectedExpiry, stSelectedModeOfData}` | `data:[{text,value}]` available dates |
| `/api/futures/getselectedfuturesalldata` | `{stSelectedFutures, stSelectedExpiry, stSelectedAvailableDate, stSelectedModeOfData}` | `data:[ row ]` |

Raw row (display columns derived from these):
```json
{ "stDate":"2026-06-12", "stTime":"23:46:00", "stDataFetchType":"PEOD",
  "inOi":"2395620", "inOpen":55975, "inHigh":56926.4, "inLow":55800, "inClose":56872.4,
  "inDayOpen":55975, "inDayHigh":56926.4, "inDayLow":55800, "inTradedVolume":34767000 }
```
`stDataFetchType`: e.g. `PEOD` (previous EOD), intraday types — flags row provenance. `inOi` is a numeric string.

## Replication notes (→ ArthaYantra)
- We already capture futures OI per memory (bank-sector F&O). Build: instrument+expiry+date+interval filter → fetch raw OI/price rows → compute Total Chng, LTP Change, OI Change, OI Interpretation, Level Break client-side.
- Render PrimeNG `p-table` (scrollable, paginator 25); OI Interpretation = `p-tag` per the 4-state matrix.
- The OI Interpretation matrix is the reusable primitive across most OI pages.

## Screenshot
ss_5634diys0 (BANKNIFTY 3-min, 127 rows, Short Covering/Long Unwinding badges).
