# Open & High Strategy — `/app/options-analysis/open-high-strategy`

**Purpose:** scan strikes for the **Open = High / Open = Low** intraday options setup. When an option's
day Open equals its day High (O=H) the premium is expected to fall (sell signal), and vice-versa for O=L.
Shows which strikes match, when they triggered, the probability, and whether it hit. Sub-tabs:
`Open & High Strategy | Options Analysis`. (Listed under the Strategies menu column.)

## Layout
```
sub-tabs: [ Open & High Strategy ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Select Expiry Date[30-Jun-2026▾]  [Go]   [Open = High | Open = Low]  (toggle)
        Underlying: NIFTY BANK at 57198.8 …
┌ mirrored table (Call | Strike | Put) ─────────────────────────────────────────────────────────────┐
│ CALL: Day Open | Day High | New D.High | New D.Low | O=H/O=L | Triggered Time | Probability | Call LTP │
│ STRIKE | PUT: Put LTP | Probability | Triggered Time | O=H/O=L | New D.Low | New D.High | Day High | Day Open │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[30▾]                                    1 - 19 of 19
```

## Filter
Mode · Name · Date · Expiry Date · Go · **Open=High / Open=Low** toggle (which setup to scan).

## Columns (mirrored Call | Strike | Put)
| Column | Source | Render |
|---|---|---|
| Day Open / Day High | `inOldDayOpen` / `inOldDayHigh` | the O=H condition is `Open == High` |
| New D.High / New D.Low | `inNewDayHigh` / `inNewDayLow` | current day extremes |
| O=H / O=L | computed | **green `O = H` badge** when condition met (red dot if recently) |
| Triggered Time | `stOldDayHighBreakTime` / `stOldDayLowBreakTime` | time the level broke; ✓ check if triggered |
| Probability | computed | **`Hit ✓` (yellow)** or `90% / 60%` (red text) — historical hit-rate |
| Call/Put LTP | `inNewLtp` | blue (clickable) |
| Strike | `inStrikePrice` | centered, cream/ATM highlight |

Row coloring: matching/active rows green-tinted; no-data rows dark.

## Data source / API (`open-high-strategy`)
| Call | Response |
|---|---|
| `/api/open-high-strategy/getselectedoptionsdate` | dates |
| `/api/open-high-strategy/getselectedoptionsdataexpirydate` | expiries |
| `/api/open-high-strategy/getoptionsopenhighstrategydata` | `{ data:[ row ], underLyingAssetData }` |

Row:
```json
{ "inStrikePrice":"43000","stOptionsType":"PE",
  "inOldLtp":2,"inOldDayOpen":2,"inOldDayHigh":2,"inOldDayLow":2,
  "inNewLtp":3.6,"inNewDayHigh":4.2,"inNewDayLow":2,
  "stOldDayHighBreakTime":"09:17:00","stOldDayLowBreakTime":null }
```
O=H when `inOldDayOpen == inOldDayHigh`; trigger time = the break-time field; CE/PE pivoted by strike.

## Replication notes (→ ArthaYantra)
- Per strike (CE/PE): detect Open==High (or Open==Low), record break/trigger time, compute hit probability.
- Mirrored chain table; O=H/O=L + Hit badges; toggle which setup. Probability from historical backtest of the setup.

## Screenshot
ss_71353v83g (BANKNIFTY O=H scan, green O=H badges, Hit/Triggered columns).
