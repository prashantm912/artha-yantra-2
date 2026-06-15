# Strangle Chart — `/app/strategies/strangle-chart`

**Purpose:** same as Straddle Chart but for a **strangle** — a Call and a Put at *different* (usually OTM)
strikes. Candlestick of combined strangle premium with VWAP/20 EMA/Call/Put lines. Sub-tabs: `Strangle Chart | Strategies`.
(See `straddle-chart.md` for shared chart detail.)

## Layout / filter (difference: two strike selectors)
```
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Select Expiry Data[30-Jun-2026▾]  Select Time Interval[3 min▾]
        Select Call Strike Price[57700▾]   Select Put Strike Price[56600▾]   [Go]
                          Options Strangle Chart
        watermark: "Strangle Chart / BANKNIFTY 57700 CE x 56600 PE"
```

## Chart
Identical structure to Straddle Chart:
- candles = strangle premium (CE@callStrike + PE@putStrike combined OHLC per interval)
- blue VWAP, yellow 20 EMA, Call Price line, Put Price line
- day H/L markers, dataZoom slider, legend (Candles/VWAP/20 EMA/Call Price/Put Price)

## Data source / API
`POST /api/strategy/getstranglechartdata` — same response shape as `getstraddlechartdata`
(`data:[{stTime, obOiData:[{PE:{OHLC,vol}},{CE:{OHLC,vol}}]}], underLyingAssetData`) but the request carries
**separate Call and Put strikes**; combined candle = CE(callStrike) + PE(putStrike).

## Replication notes (→ ArthaYantra)
- Reuse the Straddle chart component; parameterize with independent CE/PE strikes.

## Screenshot
ss_03475l5wv (BANKNIFTY 57700 CE × 56600 PE strangle candles + overlays).
