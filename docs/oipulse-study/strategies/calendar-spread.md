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
│ candlestick (spread premium) + VWAP + 20 EMA + Volume       │  │ Expiry | Strike Price | Type | Action │
│ dataZoom slider                                             │  │ (empty until legs added)        │
│ legend: ▭Candles ●VWAP ●20 EMA ▭Volume                     │  │ Rows per page: 15               │
│ (empty "No data available" until Add Position)              │  └─────────────────────────────────┘
└─────────────────────────────────────────────────────────────┘
```

## Components
| Component | Type | Detail |
|---|---|---|
| Filter | Mode, Name, Date, Time Interval, Expiry Date, Strike Price, **Options Type (CE/PE)** | pick the leg |
| Add Position | red button | add an expiry leg at the chosen strike/type |
| Refresh All Position | red button | re-fetch all legs |
| Calender Spread Chart | ECharts candlestick | spread premium (near-expiry − far-expiry) candles + VWAP + 20 EMA; Volume sub-pane; dataZoom |
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
socketSubscribedEvents ([]),  // no live socket
updateChartTimeoutId
```

## Data source / API
On load: `/api/options/getavailableoptionsdata`, `getselectedoptionsdate`, `getoptionsdataexpirydate`,
`getselectedoptionsstrikepricedata` (the standard options metadata cascade). The spread series loads on
**Add Position** (per-leg `getselectedoptionsalldataforchart`, differenced across the two expiries).
No live socket — `socketSubscribedEvents: []`.

## Replication notes (→ ArthaYantra)
- Two-leg picker (same strike/type, two expiries) → fetch each leg's premium series → plot the difference as a candlestick + VWAP/EMA/Volume.
- Positions table manages legs; Refresh re-pulls.

## Screenshot
ss_2893zx2yn (empty calendar-spread builder: chart + Positions panel + leg selectors).
