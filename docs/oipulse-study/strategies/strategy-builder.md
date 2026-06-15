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

## Data source / API (`strategy-builder`)
| Call | Purpose |
|---|---|
| `/api/strategy-builder/getoptionsunderlyingdata` | underlyings |
| `/api/options/getselectedoptionsdate` / `/api/strategy-builder/getoptionsdataexpirydate` | dates / expiries |
| `/api/strategy-builder/getselectedassetspotalldata` | spot/underlying series (for payoff x-axis & T+0) |
| `/api/strategy-builder/getselectedoptionchainalldata` | option chain (IV, LTP, greeks inputs) for leg pricing |

Payoff, Greeks, Max P/L, breakeven, POP are **computed client-side** from the selected legs + chain (IV/LTP) + spot, recomputed as the Spot % slider moves.

## Replication notes (→ ArthaYantra)
- Leg builder (add CE/PE buy/sell legs from chain) → compute expiry payoff + T+0 (Black-76 — we have `libs/black76-math`) → render P&L curve, Greeks, stats, POP.
- Spot-move slider re-evaluates. Save/Load persists baskets. Simulator mode replays intraday.

## Screenshot
ss_58692p4lb (empty builder: payoff chart, Stats, Positions/Greeks tabs, Spot % slider).
