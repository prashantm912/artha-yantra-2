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

**Confirmed live 2026-06-18 (V7):** the page is a time-series chart of **Call IV (green) / Put IV (red) /
Price (orange dotted, right axis)** — chart structure and colours verified. Page scope = **NSE indices only**;
REST auto-refresh ("Data Auto-updated At: …"), no socket. The numeric IV-band bounds, the "~50K candle", and the
RSI thresholds are **not exposed in the live UI** — they remain manual-sourced/qualitative (caveat retained).
See [Phase B findings](../PHASE-B-FINDINGS.md) (V7).

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
activeStrikeIvData: { xAxisData, yAxisInAssetPrice, yAxisCallData, yAxisPutData }
socketSubscribedEvents
```
`activeStrikeIvData` has `yAxisInAssetPrice` (unlike OI version which has no price axis).

## Socket subscriptions
**None** — confirmed live 2026-06-18 (V7): the page is **REST-driven** (auto-refresh "Data Auto-updated At: …");
no socket channel is registered even after a Go. (An earlier draft listed `AS_IV_BANKNIFTY` as a live event —
that was not reproducible from the socket.)

## Data source / API
| Call | Response |
|---|---|
| `/api/active-strike-oi/getavailableactivestrikeassetdata` | underlyings |
| `/api/active-strike-oi/getselectedassetdate` | dates |
| `/api/active-strike-oi/getselectedactivestrikeivalldata` | main |

Main request + confirmed row schema:
```
POST /api/active-strike-oi/getselectedactivestrikeivalldata
Body: {
  "stSelectedAsset": "BANKNIFTY",
  "stSelectedAvailableDate": "2026-06-16",
  "stSelectedModeOfData": "live"
}
```
**No expiry date in request** (same as OI version). Server determines active strike.

Response row schema (confirmed):
```json
{ "stTime": "09:16:00", "inAssetPrice": 57758.85, "obOiData": [ { "CE": 13.84 }, { "PE": 19.2 } ] }
```
`inAssetPrice` present per row (key difference from OI endpoint); `obOiData` carries CE/PE **IV** values.

Same `active-strike-oi` namespace; the `…ivalldata` endpoint returns CE/PE **IV** (not OI) + asset price.

## Interpretation (how to trade)
- IV reflects seller-perceived risk: HIGH IV = sellers see more risk / buyers show more demand; LOW IV = less of both.
- IV regimes (qualitative): low IV favours trend moves and option buying; rising IV erodes premium; high IV is an option-selling environment; very high IV means volatile conditions — book profits fast. Strike distance: in low IV pick ATM/near strikes, in high IV far-OTM strikes get expensive.
- "Magic of IV" — the CE–PE IV-spread rule: when the Put-side and Call-side IV differ by ~10 points, buy the higher-IV side in the trade direction (it appreciates faster if price moves that way). A tight spread means both sides erode (a sell-side regime). Compute `PutIV − CallIV` as the signal.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- `ay-echart` dual-axis line: Call IV + Put IV (left) vs price (right) for the active strike; live auto-update.
- Reuse the active-strike resolution from active-strikes-oi; just swap OI→IV series.

## Screenshot
ss_8294m81tg (Call IV green / Put IV red vs Price, BANKNIFTY — put skew visible).
