# Equity Open & High-low — `/app/equity/open-high-strategy` (title: "Open=High/Low")

**Purpose:** equity version of the Open=High / Open=Low intraday setup. For each F&O stock: did it open
at its day high (O=H, bearish) or day low (O=L, bullish), how far price has moved from that level, and
when it triggered. Sub-tabs: `Open & High Strategy | Equity`.

## Layout
```
sub-tabs: [ Open & High Strategy ] [ Equity ]   ;  ticker strip
filter: Mode(Live/Hist)  Asset[All F&O Stocks▾]  Date[📅]  Search[…]  [Go]
                              Open=High / Open=Low   (centered)
┌ mirrored table (Open=High | Name | Open=Low) ─────────────────────────────────────────────────────┐
│ Day Open | Day High | New High | Far from High? | Triggered Time | Name | Chart | LTP |             │
│   Triggered Time | Far from Low? | New Low | Day Low | Day Open                                      │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
Rows per page:[200▾]                                   1 - 66 of 66
```

## Columns
| Side | Column | Source | Render |
|---|---|---|---|
| O=H | Day Open / Day High | `inOldDayOpen` / `inOldDayHigh` | equal ⇒ O=H (green-tinted row) |
| O=H | New High | `inNewDayHigh` | |
| O=H | **Far from High?** | computed `(inNewLtp − inOldDayHigh)/…` | badge: `Hit ✓` (yellow, broke) · `0.93% Far` (green, near) · `-1.34% Far` (red, moved away) |
| O=H | Triggered Time | `stOldDayHighBreakTime` | `10:25 ✓` |
| center | Name / Chart / LTP | `stSymbolName` / icon / `inNewLtp` | LTP blue clickable |
| O=L | Far from Low? | computed vs `inOldDayLow` | badge (Hit / % Far) |
| O=L | New Low / Day Low / Day Open | `inNewDayLow` / `inOldDayLow` / `inOldDayOpen` | O=L row red-tinted |

`isOH` / `isOL` booleans flag which setup the stock matches.

## Data source / API
`POST /api/equity/getequityopenhighlowdata` →
```json
{ "data":[ { "stSymbolName":"ABB", "inOldLtp":6858, "inOldDayOpen":6880,"inOldDayHigh":6880,"inOldDayLow":6846,
             "isOH":true,"isOL":false, "inNewLtp":6947.5,"inNewDayHigh":6977.5,"inNewDayLow":6821,
             "stOldDayHighBreakTime":"10:25:00","stOldDayLowBreakTime":null } ] }
```
`isOH`/`isOL` drive the setup match; "Far from" % computed from `inNewLtp` vs old day high/low; trigger = break-time fields.

## Replication notes (→ ArthaYantra)
- Equity universe scan: detect Open==High / Open==Low (booleans), compute distance-from-level + break time.
- Mirrored table (O=H left / O=L right) with Hit/% Far badges. Cash-market OHLC source.

## Screenshot
ss_05561cyho (66 F&O stocks, O=H/O=L mirror, Hit/Far badges).
