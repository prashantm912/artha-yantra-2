# Futures EOD OI Analyzer — `/app/futures-analysis/eod-oi-analyzer`

**Purpose:** day-by-day (end-of-day) OI history for a future — the EOD counterpart to the intraday
OI Analysis. ~400 trading days of OHLC + OI + day-over-day changes + interpretation, to study how
OI trends across days. Sub-tabs: `EOD OI Analyzer | Futures Analysis`.

## Layout
```
sub-tabs: [ EOD OI Analyzer ] [ Futures Analysis ]   ;  ticker strip
filter: Name[BANKNIFTY▾]  Expiry[Current Month▾]  Actions:[Go]  ☐ Show chart data  ☐ Show Cumulative Oi  ☐ Show Range Data
┌ daily table (scrollable, paginated 25) ─────────────────────────────────────────────────────────┐
│ Date | Total OI | Day Open | Day High | Day Low | Volume | LTP | Day Range | LTP Change |         │
│   OI Change | % Chng. in LTP | % Chng. in OI | OI Interpretation                                  │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[25▾]                                  1 - 25 of 400   ‹ Previous  Next ›
```
Newest date first (descending).

## Filter bar
| Control | Type | Values |
|---|---|---|
| Name | select | future instrument (BANKNIFTY…) |
| Expiry | select | Current Month / … |
| Go | button (red) | fetch |
| Show chart data | checkbox | reveal a chart view of the EOD series |
| Show Cumulative Oi | checkbox | add cumulative-OI column/series |
| Show Range Data | checkbox | add range (high-low) detail |

## Table columns
| Column | Source | Render |
|---|---|---|
| Date | `stFetchDate` ("15-06-2026") | text |
| Total OI | `inOi` | |
| Day Open | `inDayOpen` | |
| Day High | `inDayHigh` | |
| Day Low | `inDayLow` | |
| Volume | `inVolume` | |
| LTP | `inClose` | day close |
| Day Range | computed `inDayHigh − inDayLow` + `(range/close %)` | e.g. "620 (1.09 %)" |
| LTP Change | computed vs prev day close | green +, red − |
| OI Change | computed vs prev day OI | green/red |
| % Chng. in LTP | computed | green/red |
| % Chng. in OI | computed | green/red |
| OI Interpretation | computed (price×OI signs) | badge per `inOiInterpretation` enum (L.B/L.U/S.B/S.C) |

(`inDelQty` = delivery quantity, available in the row — likely used by "Show … data" toggles.)

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/futures/getavailablefuturesdata` | `{stSelectedModeOfData}` | instrument list `[{text,type,value:[…]}]` |
| `/api/futures/getselectedfutureseodoidata` | `{stSelectedFutures, stSelectedExpiry}` | `data:[ daily row ]` |

Daily row:
```json
{ "stFetchDate":"15-06-2026", "inDayOpen":57800,"inDayHigh":57800,"inDayLow":57180,
  "inClose":57257.2, "inOi":"2267070", "inVolume":878910, "inDelQty":0 }
```
All Change/%/Range/Interpretation columns derived from consecutive daily rows client-side.

## Replication notes (→ ArthaYantra)
- Daily EOD OI series endpoint (per future+expiry) → table; compute day-over-day deltas + OI interpretation.
- Optional toggles add a chart, cumulative OI, and range columns over the same series.

## Screenshot
ss_5899f6c3r (BANKNIFTY daily EOD, 400 rows, full interpretation badges).
