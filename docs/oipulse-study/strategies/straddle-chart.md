# Straddle Chart — `/app/strategies/straddle-chart`

**Purpose:** candlestick chart of the **ATM straddle** premium (Call + Put combined) for a strike over time,
with VWAP, 20 EMA, and the individual Call/Put price lines — for trading straddle premium decay/expansion.
Sub-tabs: `Straddle Chart | Strategies`.

## Layout
```
sub-tabs: [ Straddle Chart ] [ Strategies ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Select Expiry Data[30-Jun-2026▾]  Select Time Interval[3 min▾]  Select Strike Price[57100▾]  [Go]
        Underlying: NIFTY BANK at 57198.8 …
                          Options Straddle Chart   (centered)
┌ candlestick + overlays ───────────────────────────────────────────────────────────────────────────┐
│ y: straddle premium (1,500–2,000)   x: time 09:18–15:30                                              │
│ candles = straddle (CE+PE) premium;  blue line = VWAP;  yellow line = 20 EMA                          │
│ Call Price line + Put Price line;  day H/L markers (green ▲ 1862 / red ▼ 1516)                        │
│ ── dataZoom slider ──   legend: ▭Candles ●VWAP ●20 EMA ●Call Price ●Put Price                        │
│ watermark "Straddle Chart / BANKNIFTY 57100"                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Components
| Series | Type | Source |
|---|---|---|
| Straddle candles | ECharts candlestick | per interval OHLC = CE OHLC + PE OHLC summed |
| VWAP | line (blue) | computed on straddle |
| 20 EMA | line (yellow) | computed on straddle |
| Call Price | line | CE `inClose` |
| Put Price | line | PE `inClose` |
| Day H/L markers | markPoint | straddle day high (green ▲) / low (red ▼) |
| dataZoom | range slider | time scrub |

## Data source / API (`strategy`)
`POST /api/strategy/getstraddlechartdata` →
```json
{ "data":[ { "stTime":"09:16:00",
             "obOiData":[ {"PE":{"inOpen":780,"inClose":518.3,"inHigh":780,"inLow":511,"inVolume":7170}},
                          {"CE":{"inOpen":1121.2,"inClose":1165.8,"inHigh":1183.45,"inLow":1113,"inVolume":6300}} ] } ],
  "underLyingAssetData":{…} }
```
Per interval: CE and PE OHLC+volume. Straddle candle = CE+PE summed OHLC; Call/Put lines = each `inClose`; VWAP & 20 EMA derived.

## Replication notes (→ ArthaYantra)
- Sum CE+PE OHLC per interval → straddle candlestick; overlay VWAP, 20 EMA, and CE/PE close lines.
- `ay-echart` candlestick + lines + dataZoom; strike/expiry/interval selectors.

## Screenshot
ss_2663u0sww (BANKNIFTY 57100 straddle candles + VWAP/20EMA/Call/Put lines).
