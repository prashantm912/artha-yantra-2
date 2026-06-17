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

## Vue component state (confirmed — same keys as straddle-chart.md except two strike fields)
```
selectedCallStrikePrice ("57800"), selectedPutStrikePrice ("56600"),
selectedAvailableExpiryDate ("260630"), selectedTimeInterval (3),
separateData, timeframeData, closeAndVwapData,  // same structure as straddle
socketSubscribedEvents, stLastUpdatedAt, realTimeDataTimeOutId
```
`closeAndVwapData` keys: `{xAxisData, yAxisCallCloseData, yAxisPutCloseData, yAxisCloseData, yAxisVwapData, yAxisCandlestickData, volumeData}` — identical to straddle.

## Socket subscriptions (confirmed)
```
socketSubscribedEvents: [
  "OD_SSC_BANKNIFTY_260630_57800_CE",   // CE at call strike
  "OD_SSC_BANKNIFTY_260630_56600_PE",   // PE at put strike (different!)
  "EQUITY_UNDERLYING_DATA_NIFTY BANK"
]
```
Pattern: `OD_SSC_{SYMBOL}_{YYMMDD}_{CALL_STRIKE}_CE` + `OD_SSC_{SYMBOL}_{YYMMDD}_{PUT_STRIKE}_PE`

## Data source / API
| Endpoint | Request | Response |
|---|---|---|
| same discovery chain as straddle | same | same |
| `POST /api/strategy/getstranglechartdata` | `{stSelectedModeOfData, stSelectedOptions, stSelectedAvailableDate, stSelectedAvailableExpiryDate:"260630", inSelectedCallStrikePrice:"57800", inSelectedPutStrikePrice:"56600"}` | same shape as straddle |

Request carries **two separate strike params** (`inSelectedCallStrikePrice` + `inSelectedPutStrikePrice`) vs straddle's single `inSelectedStrikePrice`.
Response shape identical: `data.data:[{stTime, obOiData:[{PE:{...}},{CE:{...}}]}]`.

## Interpretation (how to trade)
- Definition: a Strangle = CE + PE at different OTM strikes; the objective is to harvest premium decay.
- When to use: high IV with OI not building on either side means sell premium — deploy an OTM Strangle, selecting OTM strikes outside the established day high/low.
- Pre-trade qualify: Trending-OI flat + Active-IV high.
- Chart read: the combined CE+PE premium (the candlestick series — see Chart) reverting to its VWAP (blue line) is an entry / scale-in trigger.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Reuse the Straddle chart component; parameterize with independent CE/PE strikes.

## Screenshot
ss_03475l5wv (BANKNIFTY 57700 CE × 56600 PE strangle candles + overlays).
