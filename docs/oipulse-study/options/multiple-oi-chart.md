# Multiple OI Chart — `/app/options-analysis/multiple-oi-chart`

**Purpose:** overlay the OI of several user-selected strikes on one chart (with the underlying price line)
to compare how different strikes' OI evolve together. Sub-tabs: `Multiple Oi Chart | Options Analysis`.

## Layout
```
sub-tabs: [ Multiple Oi Chart ] [ Options Analysis ]   ;  ticker strip
filter: Mode  Select Name[BANKNIFTY▾]  Select Date[📅]  Expiry Date[30-Jun-2026▾]  Select Strike Price[multi-select ▾]  [Go]
                          Individual OI   (centered)
┌ dual-axis line chart ─────────────────────────────────────────────────────────────────────────────┐
│ left axis: Price (57,250–57,550)   right axis: OI (0–25,000)   x: time                               │
│ blue dotted = NIFTY BANK (underlying); one colored line per selected strike (green 45000 CE, yellow 44000 CE) │
│ legend: ● NIFTY BANK  ● 45000 CE  ● 44000 CE  …                                                       │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
empty ("No data available") until ≥1 strike is selected.
```

## Components
| Control | Type | Notes |
|---|---|---|
| Select Strike Price | **multi-select autocomplete** | options = `<strike> <CE/PE>` each with an "Add" action; "N options selected" |
| Go | button (red) | fetch overlay |
| Individual OI chart | ECharts dual-axis line | underlying price (left, blue dotted) + one OI line per selected strike (right axis) |

Each selected strike → one colored OI line; underlying price overlaid for context. Toolbox + legend (toggle lines).

## Data source / API
`POST /api/options/getoptionsoidataformultipleoichart` →
```json
{ "data": { "strikePriceData": { /* per selected strike: time→OI series */ },
            "underlyingData":  { /* underlying price time series */ } } }
```
Request carries the selected strikes (+ name/date/expiry). `strikePriceData` → one OI line per strike; `underlyingData` → price line.

## Replication notes (→ ArthaYantra)
- Multi-select strike picker (PrimeNG `p-multiSelect`/autocomplete) → fetch per-strike OI series + underlying price → `ay-echart` dual-axis multi-line.
- Color per strike; underlying as a reference line.

## Screenshot
ss_9909q6m9o (45000 CE + 44000 CE OI overlaid with NIFTY BANK price).
