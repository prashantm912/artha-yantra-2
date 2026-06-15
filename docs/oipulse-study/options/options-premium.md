# Options Premium — `/app/options-analysis/option-premium`

**Purpose:** compare Call vs Put premium across strikes (the premium "smile"/decay profile around ATM).
Sub-tabs: `Options Premium | Options Analysis`.

## Layout
```
sub-tabs: [ Options Premium ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Select Expiry Date[30-Jun-2026▾]  Show Strikes[Near ATM 10 Strikes▾]  [Go]  ☐ Show LTP
        Underlying: NIFTY BANK at 57198.80 …
                     Individual Options Premium Left   (centered)
┌ grouped bar chart ────────────────────────────────────────────────────────────────────────────────┐
│ x: strikes 56100 … 57100(ATM) … 58100   y: premium (0–800)                                           │
│ green bar = Call Premium, red bar = Put Premium (paired per strike)                                  │
│ ATM marker (green ▲ / red ▼)   legend: ● Call Premium  ● Put Premium   watermark OiPulse             │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Filter bar
| Control | Type | Values |
|---|---|---|
| Mode | radio | Live / Historical |
| Select Name | select | underlying |
| Select Date | date picker | day |
| Select Expiry Date | select | expiry |
| Show Strikes | select | `Near ATM 10 Strikes` (window of strikes around ATM) |
| Go | button (red) | fetch |
| Show LTP | checkbox | annotate bars with premium LTP values |

## Chart
ECharts grouped bar: per strike a Call bar (green) + Put bar (red); x = strike ladder centered on ATM
(marker at spot); y = premium. Reveals where calls vs puts are richer and the ATM crossover.

## Data source / API
`POST /api/options/getoptionspremiumdataforselectedoptions` →
```json
{ "data": [ { "stFetchTime":"23:45:00", "inStrikePrice":"56100", "stOptionsType":"PE", "inNewClose":"351.6" } ],
  "underLyingAssetData": {"stUnderLyingAsset":"NIFTY BANK","inLtp":57198.8, ...} }
```
`inNewClose` = option premium. CE/PE split by `stOptionsType`, paired per `inStrikePrice`.
`Show Strikes` limits to N strikes around ATM (nearest to underlying `inLtp`).

## Replication notes (→ ArthaYantra)
- `ay-echart` grouped bar: Call vs Put premium per strike, ATM markLine, strike-window selector.
- One fetch (CE+PE close per strike); ATM = nearest strike to underlying.

## Screenshot
ss_0066t58v8 (BANKNIFTY premium bars 56100–58100, ATM 57100, Call green / Put red).
