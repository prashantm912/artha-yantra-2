# Event days — `/app/event-days`

**Purpose:** educational study deck on how the market behaves around special event days
(primarily the **Union Budget**). A slideshow + a year-wise archive. Content/reference page,
not a live-data tool. Sub-tabs: `Event days | Tool`.

## Layout (top → bottom)
```
sub-tabs: [ Event days ] [ Tool ]   ;  ticker strip
┌ header: "Budget Trading Insight"        "Click here to understand the Budget Trading Insights" ┐ (right link)
├ HERO CAROUSEL (red themed) ───────────────────────────────────────────────────────────────────┤
│  "Union Budget Trading Insights" — "A 10-Year OiPulse Study covering:                           │
│   • Pre-Budget Day  • Budget Day  • Post-Budget Day"      [ < ]  ………slide dashes………  [ > ]      │
│  Previous Slide   1 / 59   Next Slide                                                            │
├ "Event days" panel ─────────────────────────────────────────────────────────────────────────────┤
│  📁 Budget days  (expandable)                                                                     │
│     🔖 2026   🔖 2025   🔖 2024   🔖 2023   🔖 2022   🔖 2021   🔖 2020   🔖 2019  (collapsible)  │
└───────────────────────────────────────────────────────────────────────────────────────────────────┘
footer (social)
```

## Components
| Component | Type | Contents | Behavior |
|---|---|---|---|
| Header link | text link (right) | "Click here to understand the Budget Trading Insights" | opens explainer |
| Hero carousel | image slideshow | 59 slides, red branded study graphics | `< / >` arrows, `Previous Slide / N / 59 / Next Slide`, dashed progress indicator |
| Budget days tree | accordion/tree | group "Budget days" → year nodes 2019–2026 | click year → expands that year's budget-day study (lazy) |

## Visual cues
- Heavy **brand-red** hero (rupee imagery, falling coins), white headline.
- Year nodes: tag/bookmark icon + year label, indented under "Budget days".
- Carousel controls + progress in red outline buttons.

## Data source
No `api.oipulse.com` calls on initial load — carousel slides are **static image assets**; the
year list is static (expanding a year may lazy-fetch that year's deck).

## Replication notes (→ ArthaYantra)
- Low priority (educational content). If replicated: a slideshow component + a year-indexed
  archive of event-day study pages. Not data-driven.

## Screenshots
ss_1442rfrfh (hero), ss_430518x9c (carousel controls + Budget days year tree).
