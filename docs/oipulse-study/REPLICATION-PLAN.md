# OiPulse Replication — High-Level Roadmap

Rough plan to rebuild the oipulse OI/options suite inside ArthaYantra. Derived from the
53-page field study in this folder (`README.md` = menu map + page-type taxonomy + API map).
High-level on purpose — phase/wave shape, not per-page specs. Page specs live in the study docs.

**North star:** the owner's goal is **index-options scalping**. Build order is by scalping value,
not by oipulse's menu order.

## Stack decision

OiPulse runs **Vue 2 + Bootstrap 4** + TradingView + ECharts. ArthaYantra is **Angular 21
(zoneless) + PrimeNG 21** with `ay-echart` (ECharts) and `lightweight-charts` already wired,
plus a real backend (market-data, backtest, optimizer) and live OI capture.

**We stay on the ArthaYantra stack.** Reasons:
- Vue 2 is end-of-life — porting a mature platform onto it is a step backwards.
- We already own the data layer, auth, deploy, and half the chart stack.
- "Same stack as oipulse" applies to the *visual/charting* layer, not the framework:
  reuse the libs that make oipulse look the way it does —
  - **ECharts** for every OI/heatmap/net-value/combo viz (already have `ay-echart`).
  - **TradingView** widget for the Advance Chart pages (new dependency, isolated to those pages).
  - **black76-math** (existing lib) for greeks/IV instead of oipulse's server-side values.

Net: same *look and behaviour*, our framework.

## Build component-first, not page-first

The study collapsed 53 pages into **~9 reusable component types**. Build each type **once** as a
configurable Angular component; most pages then become a config + an API binding, not new code.

| Component type | Drives | Build cost |
|---|---|---|
| Mirrored Call \| Strike \| Put table | Options OI Analysis, Options Chain, Open&High | high (anchor of the suite) |
| 4-quadrant OI scanner | Futures/Options OI Spurt | medium |
| Treemap heatmap | OI Buzz, Sector Heatmap | medium |
| Combo candle+line (ECharts) | OI Chart, Straddle/Strangle, FII LSR | medium |
| Dual-axis line | Vix, Active Strikes, PCR | low |
| Net-value bar charts | FII stats, Interval OI, Sector Stats | low |
| Filter-bar + paginated table | most Futures/Equity/FII tables | low (PrimeNG `p-table`) |
| TradingView widget | Advance / Multiframe Chart | medium (new dep) |
| Interactive builder | Strategy Builder, Multi-leg, Calendar | high |
| Signal matrix | Connecting Dots | low |

Two signal enums are shared app-wide (capture once, reuse everywhere):
OI interpretation (Long/Short buildup/unwind/cover) + Connecting-Dots 3-state sentiment. See
`00-global-shell.md`.

## Foundation (do before any page)

1. **Data layer** — most pages just render OI / options-chain / FII-DII data. Confirm what's
   already captured (live full-chain OI for NIFTY+SENSEX + bank futures is already running) and
   close gaps: intraday OI history depth, options chain with greeks, FII/DII participant data,
   sector/equity feeds. *This is the long pole — pages are cheap, data is not.*
2. **Shell + nav** — port the mega-menu / sub-tab / ticker chrome into the existing Angular shell.
3. **Shared component library** — the 9 types above + the two signal enums + design tokens.

## Waves (by scalping value)

Each wave = a coherent slice that ships and is usable on its own.

- **Wave 1 — Core scalping signals.** Options OI Analysis (mirrored table), Options OI Spurt
  (4-quadrant scanner), Options Chain (live + greeks), Connecting Dots (sentiment), Straddle/
  Strangle premium charts. *This is the minimum viable scalping cockpit.*
- **Wave 2 — Depth & context.** Trending OI / OI-PA, Active Strikes (OI & IV), Big OI Movement,
  Interval-wise OI, Multiple OI Chart, the Futures OI suite, FII/DII suite. Some already exist
  (bank-stock futures grid, premium-decay / futures-spurt / futures-EOD pages, FII-DII net-flow) —
  reuse, don't rebuild.
- **Wave 3 — Breadth & equity.** Equity pages (pre-open, sector stats/heatmap, returns, delivery,
  index contribution), World Indices, Vix & Index, Market Movers, OI Buzz heatmap.
- **Wave 4 — Tools & charts.** Strategy Builder, Multi-leg Price, Calendar Spread, Risk
  Calculator, Advance / Multiframe Chart (TradingView), plus the static pages (Event Days,
  Market Holidays, Plans, Update Logs).

## Already in ArthaYantra (reuse, don't rebuild)

- Live OI capture: full option chain NIFTY + SENSEX, 17 bank-sector futures (3-min).
- Pages shipped: bank-stock futures grid, OI premium-decay, futures-spurt, futures-EOD, FII-DII net-flow chart.
- Charting: `ay-echart` wrapper, `lightweight-charts`; `black76-math` for greeks.

## Out of scope / later

- OiPulse account/billing (Plans page) — we are single-owner; render static or skip.
- TradingView dashboard panels that use oipulse's external Investing.com datafeed — use our own NSE feed.
- Anything purely cosmetic that doesn't serve the scalping workflow.

## Sequencing summary

```
Foundation (data + shell + 9 components)  →  Wave 1 (scalping cockpit)
   →  Wave 2 (depth)  →  Wave 3 (breadth)  →  Wave 4 (tools)
```

Pages are config once the components exist; the real work is the data layer (foundation) and the
three "high cost" components (mirrored CSP table, builders, TradingView). Everything else is wiring.
