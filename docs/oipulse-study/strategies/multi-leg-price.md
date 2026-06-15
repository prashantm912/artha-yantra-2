# Multi Leg Price — `/app/strategies/multi-leg-price`

**Purpose:** build a custom **multi-leg options basket** and chart its *combined net premium* over time,
with full technical overlays (spot, VWAP, EMA, Volume, RSI). For tracking a bespoke strategy's price.
Sub-tabs: `Multi Leg Price | Strategies`.

## Layout
```
sub-tabs: [ Multi Leg Price ] [ Strategies ]   ;  ticker strip
filter: Mode  Name[BANKNIFTY▾]  Date[📅]  Expiry Date[30-Jun-2026▾]  Strike[57100▾]
        Type: (•)Call ( )Put    Side: (•)Buy ( )Sell    Lots:[− 1 +]    Interval[3 min▾]   ☐ multiply by lot size 30
        [Add](red)  [Refresh](red)
        Underlying: NIFTY BANK at 57198.8 …
┌ Multi Leg Chart (left) ────────────────────────────────────┐  ┌ Positions (right) ─────────────────────────┐
│ candlestick (combined leg premium)                          │  │ Expiry | Strike | Type | Entry Price | LTP | Lots | Action │
│ + Spot Price line + Price Line + VWAP + 20 EMA              │  │ (empty until legs added; paginated 15)     │
│ ── Volume sub-pane ──   ── RSI(14) sub-pane (0/50/100) ──   │  └──────────────────────────────────────────────┘
│ dataZoom slider                                             │
│ legend: ▭Candles ●Spot Price ●Price Line ●VWAP ●20 EMA ▭Volume ●RSI(14) │
└─────────────────────────────────────────────────────────────┘
```

## Components
| Component | Type | Detail |
|---|---|---|
| Leg inputs | selects/radios | Name, Date, Expiry, Strike, **Type (Call/Put)**, **Side (Buy/Sell)**, **Lots** (stepper) |
| multiply by lot size 30 | checkbox | scale premium by contract lot size |
| Add / Refresh | red buttons | add a leg / re-fetch all legs |
| Multi Leg Chart | ECharts candlestick | combined net premium of all legs; overlays: Spot Price, Price Line, VWAP, 20 EMA; sub-panes Volume + **RSI(14)**; dataZoom |
| Positions | table | per leg: Expiry, Strike, Type, **Entry Price**, LTP, Lots, Action (remove) |

## Data source / API
On load: `/api/options/getavailableoptionsdata`, `getselectedoptionsdate`, `getoptionsdataexpirydate`,
`getselectedoptionsstrikepricedata` + **`/api/strategy/getoptionslotsizedata`** (lot size, e.g. 30 for BANKNIFTY).
Combined-leg series loads on **Add** (sum of each leg's premium series × side × lots).

## Replication notes (→ ArthaYantra)
- Leg builder (type/side/lots) → net basket premium series = Σ(legPremium × ±side × lots[×lotSize]); chart as candlestick + VWAP/EMA/RSI/Volume.
- Positions table tracks legs with entry vs LTP. Lot size from metadata endpoint.

## Screenshot
ss_1610w8518 (empty multi-leg builder: chart with Spot/VWAP/EMA/RSI + Positions panel + leg inputs).
