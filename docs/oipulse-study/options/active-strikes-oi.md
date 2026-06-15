# Active Strikes OI — `/app/options-analysis/active-strikes-oi`

**Purpose:** track the **active (ATM/most-active) strike's** Call vs Put OI through the day and a derived
sentiment %. Reads intraday positioning at the strike that matters most right now.
Sub-tabs: `Active Strike Oi | Options Analysis`.

## Layout
```
sub-tabs: [ Active Strike Oi ] [ Options Analysis ]   ;  ticker strip
filter: Mode(Live/Hist)  Name[BANKNIFTY▾]  Date[📅]  Time Interval[3 min▾]  [Go]    Data Auto-updated At: -
        Active Strike Change in OI                          Active Strike Sentiment %
┌ line chart ───────────────────────────────┐   ┌ line chart ───────────────────────────────┐
│ y: OI (0–800,000)  x: time 09:15–15:30     │   │ y: % (+50 … −200)  x: time                 │
│ green = Call OI, red = Put OI              │   │ blue = Sentiment %                         │
│ legend: ● Call OI  ● Put OI               │   │ legend: ● Sentiment %                      │
└────────────────────────────────────────────┘   └────────────────────────────────────────────┘
```

## Components
| Chart | Type | Series | Notes |
|---|---|---|---|
| Active Strike Change in OI | ECharts line | Call OI (green), Put OI (red) | active strike's CE/PE OI over time |
| Active Strike Sentiment % | ECharts line | Sentiment % (blue) | derived from CE vs PE OI; negative = bearish (more call writing) |

ECharts toolbox on each; "Oi Pulse" watermark; bottom legend.

## Data source / API (`active-strike-oi`)
| Call | Response |
|---|---|
| `/api/active-strike-oi/getavailableactivestrikeassetdata` | underlyings |
| `/api/active-strike-oi/getselectedassetdate` | dates |
| `/api/active-strike-oi/getselectedactivestrikeoialldata` | `data:[{ stTime, obOiData:[{CE:4275},{PE:5355}] }]` |

Per interval `stTime`: active strike's `CE`/`PE` OI. Chart 1 plots CE & PE; **Sentiment %** derived
(e.g. `(CE − PE)/PE` or net OI bias) → blue line (deep negative ⇒ call-heavy ⇒ bearish).

## Replication notes (→ ArthaYantra)
- Determine active strike per interval (ATM/highest activity); two `ay-echart` lines:
  CE vs PE OI, and a computed sentiment % series. Live auto-update.

## Screenshot
ss_3378a7bxu (Call/Put OI lines + Sentiment % line, BANKNIFTY).
