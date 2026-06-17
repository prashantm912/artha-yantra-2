# Straddle Chart — `/app/strategies/straddle-chart`

**Purpose:** Candlestick chart of the ATM straddle premium (Call + Put combined) for a strike over time,
with VWAP, 20 EMA, and individual Call/Put price lines — for trading straddle premium decay/expansion.
Sub-tabs: `Straddle Chart | Strategies`.

## Layout
```
sub-tabs: [ Straddle Chart ] [ Strategies ]   ;  ticker strip
filter bar:
  Mode[Live data◉ / Historical○]  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]
  Time Interval[3 min▾]  Strike Price[57200▾]  [Go]

underlying header: NIFTY BANK | 16 Jun 2026, 13:22:00 | LTP: 57200.9 | DO: 57198.8

                     Options Straddle Chart   (centered title)
Last Updated At: -   (shows last API fetch time)

┌ ECharts candlestick + overlays ──────────────────────────────────────────────────────────────────┐
│ y: straddle premium   x: time 09:18–13:22                                                         │
│ candles = straddle (CE+PE) premium OHLC;  blue line = VWAP;  yellow line = 20 EMA                  │
│ Call Price line + Put Price line                                                                    │
│ Day H/L markers (green ▲ high / red ▼ low)                                                         │
│ ── dataZoom slider ──                                                                               │
│ legend: ▭Straddle Candles  ●VWAP  ●20 EMA  ●Call Price  ●Put Price                               │
│ watermark "Straddle Chart / BANKNIFTY 57200" (bottom center)                                       │
│ ECharts toolbox (save/restore/zoom)                                                                 │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Filter bar — exact controls
| Control | Values | Notes |
|---|---|---|
| Mode | live / historical | |
| Name | same 9 Index + 211 Stocks | |
| Date | date picker | min 2019-01-01, max today |
| Expiry Date | YYMMDD format | 6 expiries for BANKNIFTY |
| Time Interval | `1`(1 min), `3`(3 min), `5`(5 min), `10`(10 min), `15`(15 min), `30`(30 min), `60`(60 min) | **Has 1-min option** — unlike OI Analysis which starts at 3 min |
| Strike Price | 193 strikes (ATM default = 57200 on 2026-06-16) | |
| Go | button | fetch + re-subscribe socket |

## Chart series
| Series | Type | Computation |
|---|---|---|
| Straddle candles | ECharts candlestick | per interval: O=CE.inOpen+PE.inOpen, H=CE.inHigh+PE.inHigh, L=CE.inLow+PE.inLow, C=CE.inClose+PE.inClose |
| VWAP | line (blue) | cumulative (straddle_price × volume) / cumulative_volume |
| 20 EMA | line (yellow) | EMA(20) on straddle close |
| Call Price | line | CE `inClose` per interval |
| Put Price | line | PE `inClose` per interval |
| Day H/L markers | markPoint | straddle day high (green ▲) / day low (red ▼) |
| dataZoom | range slider | time-axis scrub |

## Data structure (Vue component state — confirmed live)
```json
{
  "closeAndVwapData": {
    "xAxisData": ["09:18:00","09:21:00",...],
    "...series data for chart..."
  },
  "separateData": {
    "09:16:00": {
      "PE": {"inOpen":749.1,"inClose":686,"inHigh":749.1,"inLow":630.75,"inVolume":14790},
      "CE": {"inOpen":811.25,"inClose":775.25,"inHigh":858.75,"inLow":761,"inVolume":20730}
    },
    "09:17:00": { ... }
  },
  "timeframeData": {
    "09:18:00": {
      "PE": {"inOpen":749.1,"inClose":680,"inHigh":749.1,"inLow":630.75,"inVolume":28170},
      "CE": {"inOpen":811.25,"inClose":782.45,"inHigh":858.75,"inLow":760.95,"inVolume":39600}
    },
    "09:21:00": { ... }
  }
}
```
- `separateData`: 1-min resolution CE/PE candle data
- `timeframeData`: CE/PE candle data aggregated to selected interval
- `closeAndVwapData`: pre-computed chart series (xAxisData + series arrays)

## Vue component state (confirmed)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedModeOfData, selectedOptions ("BANKNIFTY"), selectedAvailableDate,
selectedStrikePrice ("57200"), selectedAvailableExpiryDate ("260630"),
selectedStrikePriceForTable, selectedTimeInterval (3),
availableOptionsData, availableDate, availableExpiryDate, availableStrikePrices,
availableModeOfData,
timeInterval,          // [{text:"1 min",value:1},...{value:60}] — includes 1-min
underLyingAssetData,   // {stUnderLyingAsset:"NIFTY BANK", stDateTime:"16 Jun 2026, 14:22:00", inLtp:57251.75, inDayOpen:57198.8}
closeAndVwapData,      // {xAxisData, yAxisCallCloseData, yAxisPutCloseData, yAxisCloseData, yAxisVwapData, yAxisCandlestickData, volumeData}
randomIdString,
socketSubscribedEvents,
stLastUpdatedAt,
separateData,          // {HH:MM:SS: {CE:{...},PE:{...}}} — 1-min raw
timeframeData,         // {HH:MM:SS: {CE:{...},PE:{...}}} — aggregated to selected interval
realTimeDataTimeOutId
```

`closeAndVwapData` series (confirmed keys):
- `xAxisData`: `["09:18:00","09:21:00",...]` — interval timestamps at selected resolution
- `yAxisCallCloseData`: CE close per interval (strings)
- `yAxisPutCloseData`: PE close per interval (strings)
- `yAxisCloseData`: CE+PE combined close per interval (strings)
- `yAxisVwapData`: VWAP of combined per interval (strings)
- `yAxisCandlestickData`: combined OHLC per interval
- `volumeData`: volume bars

## Socket subscriptions (live mode — confirmed)
```
socketSubscribedEvents: [
  "OD_SSC_BANKNIFTY_260630_57200_CE",
  "OD_SSC_BANKNIFTY_260630_57200_PE",
  "EQUITY_UNDERLYING_DATA_NIFTY BANK"
]
```
Pattern: `OD_SSC_{SYMBOL}_{EXPIRY_YYMMDD}_{STRIKE}_{CE|PE}` + `EQUITY_UNDERLYING_DATA_{INDEX_NAME}`

## Data source / API
| Endpoint | Request | Response |
|---|---|---|
| `POST /api/options/getavailableoptionsdata` | `{stSelectedModeOfData}` | instruments |
| `POST /api/options/getselectedoptionsdate` | + `{stSelectedOptions}` | dates |
| `POST /api/options/getoptionsdataexpirydate` | + `{stSelectedAvailableDate}` | expiries |
| `POST /api/options/getselectedoptionsstrikepricedata` | + `{stSelectedAvailableExpiryDate}` | 193 strikes |
| `POST /api/strategy/getstraddlechartdata` | `{stSelectedModeOfData, stSelectedOptions, stSelectedAvailableDate, stSelectedAvailableExpiryDate:"260630", inSelectedStrikePrice:"57200"}` | chart data |

Note: parameter is `inSelectedStrikePrice` (not `st*`). No time interval in request — all 1-min data returned, client aggregates.

Main endpoint response (confirmed body):
```json
{
  "data": {
    "data": [
      { "stTime": "09:16:00",
        "obOiData": [
          {"PE": {"inOpen":749.1,"inClose":686,"inHigh":749.1,"inLow":630.75,"inVolume":14790}},
          {"CE": {"inOpen":811.25,"inClose":775.25,"inHigh":858.75,"inLow":761,"inVolume":20730}}
        ]
      }
    ]
  }
}
```
Note: response is `data.data` (double-nested). `underLyingAssetData` appears as a separate top-level key in the response.
Per interval: CE and PE OHLC+volume in `obOiData` array. Straddle = CE+PE summed OHLC.

## Interpretation (how to trade)
- Definition: a Straddle = CE + PE at the same ATM strike; the objective is to harvest premium decay.
- When to use: high IV on both sides plus flat / no-trend OI means sell premium — deploy a Straddle when you're directionless.
- Pre-trade qualify: confirm a non-trending day (Trending-OI flat + Active-IV high) before deploying.
- Chart read: the combined CE+PE premium (the candlestick series — see Chart series) reverting to its VWAP (blue line) is an entry / scale-in trigger.

See [OI interpretation method](../oi-interpretation-method.md) for the shared OI/strength/quadrant logic.

## Replication notes (→ ArthaYantra)
- Time interval has 1-min option (others start at 3 min) — important for scalping
- Sum CE+PE OHLC per interval → straddle candlestick; overlay VWAP, 20 EMA, CE/PE close lines
- Socket subscription pattern: `OD_SSC_{SYMBOL}_{YYMMDD}_{STRIKE}_{CE|PE}` for live tick
- `ay-echart` candlestick + lines + markPoint + dataZoom + toolbox
- Strike ATM defaults to nearest round-number to underlying LTP
- `stLastUpdatedAt` shown as "Last Updated At: HH:MM" near chart title

## Screenshot
ss_2663u0sww (BANKNIFTY 57100 straddle candles + VWAP/20EMA/Call/Put lines).
