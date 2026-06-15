# Risk Calculator — `/app/risk-calculator`

**Purpose:** position-sizing / risk utility. Client-side calculator (no charts/tables). Three cards.

## Layout
```
sub-tabs: [ Risk Calculator ] [ Tool ]   ;  ticker strip
┌ Risk calculator ─────────────────────────────────────────────────────────────────────┐
│ Enter Capital [Capital Value]   Capital deployed [Trade Value]   Risk Percentage [▾]   │
│ Select Name [▾]   Date [📅]   Expiry Date [▾]   Strike Price [▾]   Options type [▾]    │
└───────────────────────────────────────────────────────────────────────────────────────┘
┌ Stoploss calculator ──────────────────────────────────────┐  ┌ 1% target (green) ──────┐
│ Enter Lots [Lots]  Avg Entry Price [..]  Stop Loss [..]    │  │ Target [1% target]      │
└────────────────────────────────────────────────────────────┘  └─────────────────────────┘
footer (social icons)
```

## Components
| Card | Field | Type | Placeholder | Notes |
|---|---|---|---|---|
| Risk calculator | Enter Capital | number input | "Capital Value" | total trading capital |
| | Capital deployed | number input | "Trade Value" | amount in trade |
| | Risk Percentage | select | "Please select an risk" | % of capital to risk |
| | Select Name | select | "Please select an options name" | instrument (from API) |
| | Date | date picker | "Please select a date" | |
| | Expiry Date | select | "Please select an expiry date" | |
| | Strike Price | select | "Please select a strike price" | |
| | Options type | select | "Please select an options name" (CE/PE) | |
| Stoploss calculator | Enter Lots | number input | "Lots" | |
| | Avg Entry Price | number input | "Avg Entry Price" | |
| | Stop Loss | number input | "Stop Loss" | |
| 1% target (green card) | Target | input (computed) | "1% target" | 1% of capital target value |

Visual: standard dark form cards; "1% target" card has a **green tint** background (positive/target accent). Output values computed client-side from inputs + selected option's price.

## Data source
`POST /api/options/getavailableoptionsdata` `{stSelectedModeOfData}` — populates Select Name; chained option-date/expiry/strike calls when a name is picked. All math (risk amount, SL, target) is client-side.

## Replication notes (→ ArthaYantra)
- Reactive form: capital + risk% → max risk amount; with option price + lots → suggested SL distance & target; 1% target = capital × 0.01.
- Name/Expiry/Strike/Type cascade from our options metadata endpoints.

## Screenshot
ss_9750ae7px.
