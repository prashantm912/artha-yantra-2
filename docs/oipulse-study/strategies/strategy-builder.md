# Strategy Builder — `/app/strategies/strategy-builder`

**Purpose:** options strategy **payoff builder** (Sensibull/Opstra-style). Add option legs, see the
P&L payoff diagram, Greeks, key stats (max profit/loss, breakeven, POP), and simulate spot moves.
Sub-tabs: `Strategy Builder | Strategies`. Two modes: **Strategy Builder** / **Strategy Simulator**.

## Layout
```
sub-tabs: [ Strategy Builder ] [ Strategies ]   ;  ticker strip
Mode: (•)Strategy Builder ( )Strategy Simulator   Select Asset[BANKNIFTY▾]  Select Date[📅]   [Add Positions](red)
┌ Payoff chart (left) ───────────────────────────┐  ┌ right panel (tabs) ─────────────────────────┐
│ P&L vs spot;  legend: ● P&L  ● T+0              │  │ [Strategy Positions][Greeks][P&L][Save&Load][Remove all] │
│ (empty "No data available" until legs added)   │  │ Strategy Positions table:                   │
│                                                 │  │  B/S | Seg. | Name | IV | LTP | Entry | Exit | Lot | Action │
├ Stats ──────────────────────────────────────────┤  └──────────────────────────────────────────────┘
│ Max Profit | Max Loss | Risk to reward | Breakeven │  ┌ Settings ──────────────────────────────────┐
│ Days Left | POP                                  │  │ Spot % move slider: -7 … 0 … +7            │
└──────────────────────────────────────────────────┘  └──────────────────────────────────────────────┘
```

## Components
| Region | Component | Detail |
|---|---|---|
| Mode | radio | Strategy Builder vs **Strategy Simulator** (intraday replay of the strategy) |
| Filter | Select Asset, Select Date | underlying + day |
| Add Positions | red button | opens leg picker (option chain) to add CE/PE buy/sell legs |
| Payoff chart | ECharts line | **P&L** (expiry payoff) + **T+0** (today's theoretical) vs spot; breakeven crossings |
| Stats | metrics grid | Max Profit, Max Loss, Risk to reward, Breakeven, Days Left, **POP** (probability of profit) |
| Strategy Positions tab | table | per leg: **B/S** (buy/sell), **Seg.**, **Name** (e.g. 57100 CE), IV, LTP, Entry, Exit, Lot, Action (remove) |
| Greeks tab | table | per-leg + net Delta/Gamma/Theta/Vega |
| P&L tab | view | running P&L of the basket |
| Save & Load tab | actions | persist/recall strategies (paid feature) |
| Remove all | action | clear legs |
| Settings | slider | **Spot % move** (−7…+7) to shift the payoff/T+0 and recompute stats |

## Vue component state (confirmed)
```
stLatestFetchTime, allowedTime,
inFillableLot, inFillablePrice, blStopAutoPlay,
showPositionMenu, showSimulatorMenu, showOptionChainLoading,
inNoOfStrikeInPayoff,
inDaysLeftInNearestExpiry, inDaysLeftInNearestExpirySlider, inDaysLeftRangeSlider,
inHistoricalVolatility (18.76),   // HV used for greek computation
inHistoricalVolatilityRangeSlider,
inSpotPercentageChange, inSpotPercentageChangeProcess, inSpotPercentageChangeRangeSlider,
inRiskFreeReturn (10),            // risk-free rate %
showFuturesExpiryData,
inAtmStrikePrice ("57200"),
stNearestExpiryDate,
underLyingAssetData,              // {stFetchDate, stFetchTime, stUnderLyingAsset:"NIFTY BANK", inSpotLtp:57247.2, inFuturesLtp:57280, inLotSize:30}
chartData,                        // payoff chart series (empty until legs added)
maxProfit (null), maxLoss (null), inPop (null), breakevenPoints ([]),
inMinimumGap,
selectedMarketView (null), selectedStrategy (null),
strikePriceItems,                 // option chain rows — enriched with greeks per strike
fields,                           // table column defs
tabIndex (0),                     // 0=Strategy Positions, 1=Greeks, 2=P&L, 3=Save&Load
availableFuturesExpiryItems,
selectedModeOfData, selectedAvailableDate, selectedAvailableOptions ("BANKNIFTY"),
selectedAvailableExpiryDate ("260630"),
socketSubscribedEvents ([])       // no socket — fully computed/polling
```

Option chain row (`strikePriceItems[i]` — confirmed):
```json
{
  "inStrikePrice": "43000",
  "inCeIv": 0, "inCeVega": 0, "inCeTheta": 0, "inCeDelta": 0,
  "inCePrice": 0, "inCeOi": 0,
  "inPePrice": 3.05, "inPeOi": 109800,
  "inPeDelta": 0, "inPeTheta": 0, "inPeVega": 0, "inPeIv": 51.38,
  "_cellVariants": { "inStrikePrice": "warning", "ceBuySell": "light", ... }
}
```
`_cellVariants` highlights ATM strike (`"warning"` = yellow). Greeks computed client-side via Black-Scholes using `inHistoricalVolatility` + `inRiskFreeReturn` + `inDaysLeftInNearestExpiry`.

Table columns (`fields` — confirmed):
`Action(ceBuySell) | OI(inCeOi) | Vega(inCeVega) | Theta(inCeTheta) | Delta(inCeDelta) | IV(inCeIv) | Price(inCePrice) || Strikes(inStrikePrice) || Price(inPePrice) | Iv(inPeIv) | Delta(inPeDelta) | Theta(inPeTheta) | Vega(inPeVega) | OI(inPeOi) | Action(peBuySell)`

## Data source / API (confirmed namespaces)
| Call | Request | Response |
|---|---|---|
| `POST /api/options/getselectedoptionsdate` | `{stSelectedOptions, stSelectedModeOfData}` | dates |
| `POST /api/options/getoptionsunderlyingdata` | `{stSelectedOptions, stSelectedModeOfData, stSelectedAvailableDate}` | `{stFetchDate, stFetchTime, stUnderLyingAsset, inSpotLtp, inFuturesLtp, inLotSize}` |
| `POST /api/options/getoptionsdataexpirydate` | `{stSelectedModeOfData, stSelectedOptions, stSelectedAvailableDate}` | expiry list `[{text:"260630",value:"260630"},...]` |
| `POST /api/options/getselectedassetspotalldata` | `{stSelectedModeOfData, stSelectedAvailableDate, stAssetName:"NIFTY BANK"}` | `data.data:[{stFetchTime:"09:16:00",inClose:57320.3},...]` |
| `POST /api/options/getselectedoptionchainalldata` | `{stSelectedModeOfData, stSelectedOptions, stSelectedAvailableDate, stSelectedAvailableExpiryDate:"260630"}` | `data:[{stFetchTime, objData:{strike:{inPeOi,inPePrice},...}}]` |

Note: namespace is `options` (not `strategy-builder`). Payoff, Greeks, Max P/L, breakeven, POP are **computed client-side** — no greeks from API; computed from HV + days-to-expiry + spot via Black-Scholes.

## Replication notes (→ ArthaYantra)
- Leg builder (add CE/PE buy/sell legs from chain) → compute expiry payoff + T+0 (Black-76 — we have `libs/black76-math`) → render P&L curve, Greeks, stats, POP.
- Spot-move slider re-evaluates. Save/Load persists baskets. Simulator mode replays intraday.

## Screenshot
ss_58692p4lb (empty builder: payoff chart, Stats, Positions/Greeks tabs, Spot % slider).
