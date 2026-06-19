# Calendar Spread — `/app/strategies/calender-spread`

**Purpose:** chart a **calendar (horizontal) spread** — same strike/type across two expiries — to trade
the premium differential / time-decay between expiries. Sub-tabs: `Calendar spread | Strategies`.

## Layout
```
sub-tabs: [ Calendar spread ] [ Strategies ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Time Interval[3 min▾]
        Expiry Date[30-Jun-2026▾]  Strike Price[57100▾]  Options Type[CE▾]   [Add Position]  [Refresh All Position]
        Underlying: NIFTY BANK at 57198.8 …
┌ Calender Spread Chart (left) ──────────────────────────────┐  ┌ Positions (right) ─────────────┐
│ candlestick (spread premium) + 2 overlay lines (VWAP/EMA)   │  │ Expiry | Strike Price | Type | Action │
│   + Volume histogram + day High/Low pins + dataZoom slider  │  │ (empty until legs added)        │
│ legend: ▭Candles ●VWAP ●20 EMA ▭Volume                     │  │ Rows per page: 15               │
│ (empty "No data available" until Add Position)              │  └─────────────────────────────────┘
└─────────────────────────────────────────────────────────────┘
```
The full overlay set (candles + VWAP + 20 EMA + Volume histogram + day H/L pins + dataZoom) is **present**,
confirmed live 2026-06-18 (resolves the earlier "overlays present?" caveat). Time Interval includes 1 min.

## Components
| Component | Type | Detail |
|---|---|---|
| Filter | Mode, Name, Date, Time Interval, Expiry Date, Strike Price, **Options Type (CE/PE)** | pick the leg |
| Add Position | red button | add an expiry leg at the chosen strike/type |
| Refresh All Position | red button | re-fetch all legs |
| Calender Spread Chart | ECharts candlestick | spread premium (near-expiry − far-expiry) candles + VWAP + 20 EMA overlay lines; Volume histogram sub-pane; day High/Low pins; dataZoom (overlay set confirmed live 2026-06-18) |
| Positions panel | table | each leg: Expiry, Strike Price, Type, Action (remove); paginated 15 |

Build a spread by adding the same strike/type at two expiries → chart plots the differential premium over time.

## Vue component state (confirmed)
```
minAvailableDate, maxAvailableDate, disableRefreshDataButton,
selectedModeOfData, selectedOptions ("BANKNIFTY"), selectedAvailableDate,
selectedStrikePrice ("57200"), selectedAvailableExpiryDate ("260630"),
selectedStrikePriceForTable, selectedTimeInterval (3),
availableOptionsData, availableDate, availableExpiryDate, availableStrikePrices,
availableModeOfData,
timeInterval,         // [{text:"1 min",value:1},...{value:60}] — has 1-min
underLyingAssetData,
strategyLegs,         // {} initially; keyed by leg when added
selectedOptionsType ("CE"), optionsType ([{text:"CE"},{text:"PE"}]),
arCallCandleStickData, arPutCandleStickData, ar1minCandleData,
arFinalCandleStickData,      // combined spread candle series
arFinalCandleStickChartData, // ECharts-ready chart object
columns,              // [{label:"Expiry",field:"stExpiryDate"},{label:"Strike Price",field:"inStrikePrice"},{label:"Type",field:"stOptionsType"},{label:"Action",field:"stAction"}]
socketSubscribedEvents,  // populated per added leg — see Data source / API below
updateChartTimeoutId
```

## Data source / API
On load: `/api/options/getavailableoptionsdata`, `getselectedoptionsdate`, `getoptionsdataexpirydate`,
`getselectedoptionsstrikepricedata` (the standard options metadata cascade). The spread series loads on
**Add Position** (per-leg `getselectedoptionsalldataforchart`, differenced across the two expiries).
**Live socket (confirmed 2026-06-18):** each added leg subscribes `CALENDAR_SPREAD_OPT_{SYM}_{EXP}_{STRIKE}_{CE|PE}`,
frame ARRAY[9] `[time, expiry, strike, side, O, H, L, C, volume]` (no OI — premium candle). Correcting an earlier
draft that said "no live socket": the channel only registers *after* "Add Position". Deep-ITM/illiquid strikes
return "No data available" — pick a liquid strike. See [Phase B findings](../PHASE-B-FINDINGS.md) (V15 + §2).

Overlay set (candles + VWAP + 20 EMA + Volume + day H/L pins + dataZoom) verified live —
see [PHASE-B-FINDINGS.md](../PHASE-B-FINDINGS.md) (V15).

## Replication notes (→ ArthaYantra)
- Two-leg picker (same strike/type, two expiries) → fetch each leg's premium series → plot the difference as a candlestick + VWAP/EMA/Volume + day H/L pins.
- Positions table manages legs; Refresh re-pulls.

## Screenshot
ss_2893zx2yn (empty calendar-spread builder: chart + Positions panel + leg selectors).
