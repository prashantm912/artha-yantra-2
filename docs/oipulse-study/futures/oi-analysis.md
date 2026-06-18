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
| Control | Type | Values | Notes |
|---|---|---|---|
| Mode | radio | Live data / Historical | `selectedModeOfData` |
| Name | select | BANKNIFTY, NIFTY, FINNIFTY, MIDCPNIFTY, NIFTYNXT50, BANKEX, FOCIT, SENSEX, SENSEX50 + ~200 stocks | `selectedFutures` |
| Expiry | select | `I`=Current Month · `II`=Next Month · `III`=Far Month | `selectedExpiry` |
| Date | date picker | trading day | `selectedAvailableDate` |
| Time Interval | select | 3 / 5 / 10 / 15 / 30 / 60 min | `selectedTimeInterval`; default 3 |
| Go | button (red) | fetch | |

**Expiry availability**: `futuresDataWiseAvailableExpiry` map — each instrument shows which of I/II/III exist.

**Pagination**: the rows-per-page selector offers 25 / 50 / 75 / All.

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

### 15:30-EOD data
The first `15:30-EOD` row is NSE's post-close **adjusted** OI, distinct from the live 15:15–15:30
close. The gap between the two is much larger for single-stock futures than for indices. It is
caused by Clearing-Member reconciliation, and the readjustment only ever **decreases** OI — so the
EOD OI-interpretation can differ from the last intraday reading. Historical look-back ≈ 2 months.

### OI Interpretation matrix (REUSED across Futures & Options pages)
| Price (LTP) | OI | Interpretation | Badge color | Arrow |
|---|---|---|---|---|
| ↑ up | ↑ up | **Long Buildup** | green `badge-success` | ↑ |
| ↓ down | ↑ up | **Short Buildup** | red `badge-danger` | ↓ |
| ↑ up | ↓ down | **Short Covering** | blue `badge-info` | ↑ |
| ↓ down | ↓ down | **Long Unwinding** | yellow/orange `badge-warning` | ↓ |

(Observed live: "Short Covering ↑" blue, "Long Unwinding ↓" yellow.)

## Vue component state (confirmed)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedModeOfData, selectedFutures, selectedExpiry,
selectedAvailableDate, selectedTimeInterval,
availableFuturesData, availableExpiryData, availableDate, availableModeOfData,
futuresDataWiseAvailableExpiry,   // {BANKNIFTY:["I","II","III"], NIFTY:[...], ...}
timeInterval,                     // [{text:"3 min",value:3}, ...]
tableData, columns, pagination, totalRecords,
socketSubscribedEvents,           // ["FD_OIA_BANKNIFTY-I"]
randomIdString, stLastUpdatedAt
```

## Socket subscriptions
- `FD_OIA_BANKNIFTY-I` — pattern: `FD_OIA_{SYMBOL}-{EXPIRY}` (e.g. `FD_OIA_NIFTY-II`)

**Socket payload ([Phase B confirmed](../PHASE-B-FINDINGS.md))** — channel `FD_OIA_{SYM}-I`
(near-month future, e.g. `NIFTY-I`, `SENSEX-I`). Two encodings coexist:
- The **REST batch** (`…alldata`) returns the **object** rows documented under Data source / API
  below (`stTime / stDataFetchType / inOi (string) / inOpen …`).
- The **live socket push** is a compact **array[8]** `[symbol, time, open, high, low, close, volume, OI]`:
```
FD_OIA_NIFTY-I   ["NIFTY-I","09:22:00",24097.5,24102.8,24094,24094,8125,16826875]
```

> **V6 — Pattern column confirmed absent.** The live column order is `Date Time | Total OI |
> Total Chng. In OI | Day High | Day Low | Level Break | Volume | LTP | LTP Change | OI Change |
> OI Interpretation` — there is **no "Pattern" column** (the manual was stale). See the Table columns
> section above, which already matches this order.

## Data source / API
| Call | Request | Response |
|---|---|---|
| `/api/futures/getavailablefuturesdata` | `{stSelectedModeOfData}` | `data:[{text, type:"FUTIDX"/"FUTSTK", value:["I","II","III"]}]` |
| `/api/futures/getselectedfuturesdate` | `{stSelectedFutures, stSelectedExpiry, stSelectedModeOfData}` | `data:[{text,value}]` available dates |
| `/api/futures/getselectedfuturesalldata` | `{stSelectedFutures, stSelectedExpiry, stSelectedAvailableDate, stSelectedModeOfData}` | `data:[ row ]` |

PEOD row (previous EOD baseline):
```json
{ "stDate":"2026-06-15", "stTime":"23:46:00", "stDataFetchType":"PEOD",
  "inOi":"2267070", "inOpen":57800, "inHigh":57800, "inLow":57180, "inClose":57257.2,
  "inDayOpen":57800, "inDayHigh":57800, "inDayLow":57180, "inTradedVolume":878910 }
```

IM row (intraday, confirmed full schema):
```json
{
  "stDate": "2026-06-16", "stDataFetchType": "IM",
  "inOi": "2265990",
  "inHigh": 57256.8, "inLow": 57218, "inClose": 57220,
  "inDayOpen": 57280, "inDayHigh": 57420, "inDayLow": 57079,
  "inTradedVolume": 660,
  "stNewTime": "14:03",
  "inLastOi": 2266350,
  "inOiChange": 0,
  "isDayHighBrake": false,
  "isDayLowBrake": false,
  "isDayHighVolume": false,
  "inTotalChangeInOi": -720,
  "stTimeInterval": "14:00-14:03",
  "inLtpDiff": -22.2,
  "inOiDiff": 420,
  "inDayHighPrev": 57420,
  "inDayLowPrev": 57079,
  "inOiInterpretation": 3
}
```
`inOi` is a numeric string. `stDataFetchType`: `PEOD` (prev EOD), `IM` (intraday). `isDayHighBrake`/`isDayLowBrake` = level break flags. `inOiInterpretation` uses the 4-state enum.

## Interpretation (how to trade)
- The four OI states split by intent: **Long/Short Build-Up** = fresh positions (strong);
  **Short Covering / Long Unwinding** = position-closing (weak — no fresh money entering).
- Signal strength: **strongest** = high Volume + the matching Level-Break + significant ΔLTP & ΔOI;
  **strong** = high Volume, no break; **weak** = low Volume or insignificant change. Bullish states
  (Long Build-Up, Short Covering) pair with a Day-High-Break, bearish states with a Day-Low-Break
  (`isDayHighBrake`/`isDayLowBrake`, `isDayHighVolume`).
- Scenario flips the weak states: Short Covering reads strongest on a bullish day; Long Unwinding
  strongest on a bearish day.
- OI vs Volume: OI counts distinct outstanding contracts, Volume counts times traded, so Volume ≥ OI
  always; OI rises on fresh writing, falls on buy-back; high Volume signals conviction.
- Interval roles: **60-min** = trend / overnight context (weak in the first half), **15-min** =
  intraday trend, **5-min** = entry timing — confirm a small-TF entry against the larger TF.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- We already capture futures OI per memory (bank-sector F&O). Build: instrument+expiry+date+interval filter → fetch raw OI/price rows → compute Total Chng, LTP Change, OI Change, OI Interpretation, Level Break client-side.
- Render PrimeNG `p-table` (scrollable, paginator 25); OI Interpretation = `p-tag` per the 4-state matrix.
- The OI Interpretation matrix is the reusable primitive across most OI pages.

## Screenshot
ss_5634diys0 (BANKNIFTY 3-min, 127 rows, Short Covering/Long Unwinding badges).
