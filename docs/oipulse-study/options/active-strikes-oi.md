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

ECharts toolbox on each; "Oi Pulse" watermark; bottom legend. Both charts support a line/bar render toggle.

**Confirmed live 2026-06-18 (V4):** the left "Active Strike Change in OI" chart draws **Call OI = GREEN line,
Put OI = RED line** (the study was correct; the manual's "blue Call" was wrong — the blue line belongs to the
*separate* "Active Strike Sentiment %" chart on the right, which the manual conflated). Page scope = **NSE indices
only** (BANKNIFTY / FINNIFTY / MIDCPNIFTY / NIFTY — no SENSEX). See [Phase B findings](../PHASE-B-FINDINGS.md) (V4).

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Name | underlyings list | uses `stSelectedAsset` |
| Date | date picker | |
| Time Interval | `3`, `5`, `10`, `15`, `30`, `60` | no 1-min |
| Go | button | |

## Vue component state
```
activeStrikeOiData: { xAxisData, yAxisCallData, yAxisPutData }
socketSubscribedEvents
```
`activeStrikeOiData` has NO `yAxisPriceData` axis (unlike IV version).

## Socket subscriptions
**None** — confirmed live 2026-06-18 (V4): the page is **REST-driven** (auto-refresh, shown as
"Data Auto-updated At: …"); no socket channel is registered even after a Go. (An earlier draft listed
`AS_OI_BANKNIFTY` as a live event — that was not reproducible from the socket.)

## Data source / API (`active-strike-oi`)
| Call | Response |
|---|---|
| `/api/active-strike-oi/getavailableactivestrikeassetdata` | underlyings |
| `/api/active-strike-oi/getselectedassetdate` | dates |
| `/api/active-strike-oi/getselectedactivestrikeoialldata` | main |

Main request + confirmed row schema:
```
POST /api/active-strike-oi/getselectedactivestrikeoialldata
Body: {
  "stSelectedAsset": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedModeOfData": "live"
}
```
**No expiry date, no strike price in request** — server determines active strike.

Response row schema (confirmed):
```json
{ "stTime": "09:16:00", "obOiData": [ { "CE": 2092.5 }, { "PE": 3150 } ] }
```
Per interval `stTime`: active strike's `CE`/`PE` OI. Chart 1 plots CE & PE; **Sentiment %** derived
(e.g. `(CE − PE)/PE` or net OI bias) → blue line (deep negative ⇒ call-heavy ⇒ bearish).

## Interpretation (how to trade)
- The "active strike" is the strike with the greatest activity by Volume / ΔOI (auto-picked by the server), not simply the ATM strike.
- Active Strike Sentiment %: above 0 = bullish, below 0 = bearish; rising while positive = strengthening bullishness. The value is an unbounded percentage (can reach into the thousands), so the y-axis is not a fixed small band.
- Treat sentiment as a confirmation tool — time entries off the price chart, not off a sentiment spike.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Determine active strike per interval (ATM/highest activity); two `ay-echart` lines:
  CE vs PE OI, and a computed sentiment % series. Live auto-update.

## Screenshot
ss_3378a7bxu (Call/Put OI lines + Sentiment % line, BANKNIFTY).
