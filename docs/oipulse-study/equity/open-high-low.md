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

## Vue component state (confirmed)
```
isSocketConnectedSecondTime (false),
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
availableModeOfData, availableAsset, availableDate,
selectedModeOfData, selectedAvailableAsset (null), selectedAvailableDate,
searchSymbol, doneTypingInterval,
stLastUpdatedAt,
openHighData ([]),   // 66 rows (O=H stocks)
openLowData ([]),    // 17 rows (O=L stocks)
allData ([]),        // 83 = openHighData + openLowData combined
tempAllData
```
No socket subscription — `isSocketConnectedSecondTime` flag stays `false` (snapshot page).

## Data source / API
| Endpoint | Namespace | Request | Notes |
|---|---|---|---|
| `getequitydate` | `equity` | `{stSelectedModeOfData}` | available dates |
| `getlistofassetforheatmap` | `heatmap` | `{stSelectedModeOfData}` | Asset dropdown (All F&O Stocks + specific indices) |
| `getequityopenhighlowdata` | `equity` | `{stSelectedAsset:null, stSelectedAvailableDate:"2026-06-16", stSelectedModeOfData:"live"}` | main data |

Raw API row (confirmed — ADANIENSOL sample):
```json
{
  "stSymbolName": "ADANIENSOL",
  "inOldLtp": 1485,
  "inOldDayOpen": 1498,
  "inOldDayHigh": 1498,
  "inOldDayLow": 1485,
  "isOH": true,
  "isOL": false,
  "inNewLtp": 1502.7,
  "inNewDayHigh": 1519,
  "inNewDayLow": 1475.5,
  "stOldDayHighBreakTime": "09:29:00",
  "stOldDayLowBreakTime": null
}
```

Earlier confirmed row (ABB):
```json
{ "stSymbolName":"ABB", "inOldLtp":6858, "inOldDayOpen":6880,"inOldDayHigh":6880,"inOldDayLow":6846,
  "isOH":true,"isOL":false, "inNewLtp":6947.5,"inNewDayHigh":6977.5,"inNewDayLow":6821,
  "stOldDayHighBreakTime":"10:25:00","stOldDayLowBreakTime":null }
```

`isOH`/`isOL` booleans drive which setup the stock matches. "Far from" % computed client-side: `(inNewLtp − inOldDayHigh) / inOldDayHigh * 100`. Break-time fields null until the level is broken.

## Replication notes (→ ArthaYantra)
- Asset dropdown uses `heatmap` namespace endpoint (shared with Sector Heatmap).
- Equity universe scan: detect Open==High / Open==Low via `isOH`/`isOL` booleans.
- Compute distance-from-level + break time client-side from the raw OHLC fields.
- Mirrored table (O=H left / O=L right) with Hit/% Far badges. Cash-market OHLC source.
- allData = openHighData + openLowData; filtered by search; paginated.

## Screenshot
ss_05561cyho (66 F&O stocks, O=H/O=L mirror, Hit/Far badges).
