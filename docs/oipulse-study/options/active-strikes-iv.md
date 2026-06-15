# Active Strikes IV — `/app/options-analysis/active-strikes-iv`

**Purpose:** track the active (ATM) strike's **implied volatility** for Call and Put over the day vs price —
read IV expansion/contraction and Call/Put IV skew intraday. Sub-tabs: `Active Strike Iv | Options Analysis`.
(IV counterpart of `active-strikes-oi.md`.)

## Layout
```
sub-tabs: [ Active Strike Iv ] [ Options Analysis ]   ;  ticker strip
filter: Mode(Live/Hist)  Name[BANKNIFTY▾]  Date[📅]  Time Interval[3 min▾]  [Go]    Data Auto-updated At: -
                          Active Strike IV   (centered)
┌ dual-axis line chart ─────────────────────────────────────────────────────────────────────────────┐
│ left axis: IV (13–20)   right axis: Price (57,100–57,800)   x: time 09:15–15:30                      │
│ green line = Call IV, red line = Put IV, orange dotted = Price                                        │
│ legend: ● Call IV  ● Put IV  ● Price    watermark "Oi Pulse"                                          │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Components
| Series | Color | Axis | Source |
|---|---|---|---|
| Call IV | green line | left | `obOiData[].CE` (IV) |
| Put IV | red line | left | `obOiData[].PE` (IV) |
| Price | orange dotted line | right | `inAssetPrice` |

ECharts dual-axis line, toolbox, bottom legend. Put IV typically above Call IV (put skew visible).

## Data source / API
| Call | Response |
|---|---|
| `/api/active-strike-oi/getavailableactivestrikeassetdata` | underlyings |
| `/api/active-strike-oi/getselectedassetdate` | dates |
| `/api/active-strike-oi/getselectedactivestrikeivalldata` | `data:[{ stTime:"09:16:00", inAssetPrice:57758.85, obOiData:[{CE:13.84},{PE:19.2}] }]` |

Same `active-strike-oi` namespace; the `…ivalldata` endpoint returns CE/PE **IV** (not OI) + asset price.

## Replication notes (→ ArthaYantra)
- `ay-echart` dual-axis line: Call IV + Put IV (left) vs price (right) for the active strike; live auto-update.
- Reuse the active-strike resolution from active-strikes-oi; just swap OI→IV series.

## Screenshot
ss_8294m81tg (Call IV green / Put IV red vs Price, BANKNIFTY — put skew visible).
