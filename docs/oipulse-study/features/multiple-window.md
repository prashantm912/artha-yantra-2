# Multiple Window — `/app/multiple-window`

**Purpose:** a user-composable multi-panel workspace. A grid (2×2 seen) of independent "windows",
each of which can host any analysis widget from the app, with its own filter bar. Lets a trader
watch several analyses side-by-side on one screen.

## Layout
```
sub-tabs: [ Multiple Window ] [ Tool ]   ;  ticker strip
┌ panel (Futures OI Analysis)        [+]┐┌ panel (Options OI Analysis)        [+]┐
│ own filter bar (Mode/Name/Date/        ││ own filter bar (Mode/Name/Date/        │
│ Interval/Expiry/Go)                     ││ Strike/Interval/Expiry/Go)             │
│ data table                              ││ data table                              │
└─────────────────────────────────────────┘└─────────────────────────────────────────┘
┌ panel (Options OI Statistics)      [+]┐┌ panel (Options Premium)            [+]┐
│ filter bar + charts                     ││ filter bar + chart                      │
└─────────────────────────────────────────┘└─────────────────────────────────────────┘
```

## Components
| Component | Type | Notes |
|---|---|---|
| Panel ×4 | card with header | each header: widget title (left) + red **`+` / ▾ dropdown** (right) to choose/swap the widget shown |
| Per-panel filter bar | inputs | same filters the standalone page uses (Mode, Name, Date, Strike, Interval, Expiry, Go) |
| Panel body | table or chart | the embedded widget renders here |

Each panel is an **embed of another page's widget**. Observed defaults:
- Futures OI Analysis (→ see `../futures/oi-analysis.md`)
- Options OI Analysis (→ see `../options/oi-analysis.md`)
- Options OI Statistics (→ see `../options/oi-statistics.md`)
- Options Premium (→ see `../options/options-premium.md`)

The manual-era canonical layout is 4 panes in a 2×2 grid. The per-pane widget picker enumerates 7 widgets: Futures OI Analysis, Options OI Analysis, Options OI Chart, Options OI Statistics, Options OI Spurt, Options Chain, Options Premium. Each pane is fully independent — its own Mode/Name/Date/Interval/Strike, with no cross-pane sync. Rationale: a single-screen fast decision.

## Data source
No unique endpoints — it calls the SAME APIs as its embedded widgets. Discovery calls fired on load:
`/api/options/getavailableoptionsdata`, `/api/futures/getavailablefuturesdata`,
`/api/options/getselectedoptionsdate`, `/api/futures/getselectedfuturesdate`,
`/api/options/getoptionsdataexpirydate` … (each panel then fetches its own data).

## Replication notes (→ ArthaYantra)
- A dashboard-grid shell where each cell renders a selectable widget component + its filter inputs.
- Widget registry: futures-oi, options-oi, oi-stats, options-premium, etc. `+`/dropdown swaps the cell's widget.
- Persist layout (which widget + filters per cell) per user. Reuse the same data services as standalone pages.

## Screenshot
ss_5834lmsuf (2×2: Futures OI Analysis · Options OI Analysis · OI Statistics · Options Premium).
