# OiPulse Study — replication reference

Field study of **oipulse.com** (authenticated SPA) to replicate its structure in ArthaYantra.
Captured live via Claude-in-Chrome against the owner's logged-in session.

- Captured: 2026-06-16
- App base: `https://www.oipulse.com/app/*`
- **Data API base: `https://api.oipulse.com/api/*` (POST, JSON)** — this is the gold for data-structure replication
- TradingView charts on Dashboard/Advance Chart use an **external Investing.com datafeed** (not oipulse's API)
- One markdown file per page; screenshots referenced by capture id / saved under `./shots/`

## Global shell (chrome on every `/app` page)

| Region | Contents |
|---|---|
| Top-left | OiPulse logo (links to dashboard) |
| Top nav | `All Menu ▾` (mega-dropdown) · `Dashboard` · `Connecting Dots` · `Advance Chart` |
| Top-right | red **`IC | 1Cliq`** badge · user avatar (account menu) |
| Sub-tab row | page-local tabs (Dashboard page: `Dashboard | Tool`) |
| Ticker strip | horizontal scrolling live quotes — `SYMBOL(F): price ±chg (±%)`, green ▲ / red ▼ arrows. Source: `POST /api/gettickerdata` |
| Theme | dark shell, near-black panels, red brand accent (#e23?), green/red semantic up/down |

### All Menu mega-dropdown
Click `All Menu` → full-width panel, columns grouped by section. Each item = a route. Closes on outside click / navigation (Escape does NOT close).

## Menu map (exact routes)

`✓` = in user's stated study scope. `+` = discovered, NOT in user's list (confirm whether to include).

### Features
| Item | Route | Scope |
|---|---|---|
| Dashboard | `/app/dashboard` | ✓ |
| Connecting Dots | `/app/connecting-dots` | ✓ |
| World Indices | `/app/world-indices` | ✓ |
| Vix & Index | `/app/vix-price` | ✓ |
| Multiple Window | `/app/multiple-window` | + |
| Risk Calculator | `/app/risk-calculator` | + |
| Oi Pulse Plans | `/app/plans` | + |
| Event days | `/app/event-days` | + |
| Market Holidays | `/app/market-holidays` | + |
| Update Logs | `/app/website-update-logs` | + |

### Advance Chart
| Item | Route | Scope |
|---|---|---|
| Advance Chart | `/app/advance-chart` | + |
| Multiframe Chart | `/app/multi-frame-advance-chart` | + |

> OSPL Signal + OSPL Volume are Advance-Chart **indicators** (not routes) — see [advance-chart/ospl-signal.md](advance-chart/ospl-signal.md).

### Futures
| Item | Route | Scope |
|---|---|---|
| OI Analysis | `/app/futures-analysis` | ✓ |
| OI Chart | `/app/futures-analysis/oi-chart` | ✓ |
| OI Spurt | `/app/futures-analysis/oi-spurt` | ✓ |
| OI Buzz | `/app/futures-analysis/oi-buzz` | ✓ |
| Pre-open market | `/app/futures-analysis/pre-open-market` | ✓ |
| Market Movers | `/app/futures-analysis/market-movers` | + |
| Banks Analysis | `/app/futures-analysis/banks-analysis` | ✓ |
| EOD OI Analyzer | `/app/futures-analysis/eod-oi-analyzer` | ✓ |

### Options
| Item | Route | Scope |
|---|---|---|
| OI Analysis | `/app/options-analysis` | ✓ |
| OI Chart | `/app/options-analysis/chart` | ✓ |
| Options Chain | `/app/options-analysis/options-chain` | ✓ |
| Options Chart | `/app/options-analysis/options-chart` | ✓ |
| OI Spurt | `/app/options-analysis/oi-spurt` | ✓ |
| OI Statistics | `/app/options-analysis/oi-stats` | ✓ |
| Options Premium | `/app/options-analysis/option-premium` | ✓ |
| Trending OI | `/app/options-analysis/trending-oi` | ✓ |
| Trending OI - PA | `/app/options-analysis/trending-oi-with-pa` | ✓ |
| Big OI Movement | `/app/options-analysis/big-oi-movement` | ✓ |
| Active Strikes OI | `/app/options-analysis/active-strikes-oi` | ✓ |
| Active Strikes IV | `/app/options-analysis/active-strikes-iv` | ✓ |
| Interval wise OI | `/app/options-analysis/interval-wise-oi` | ✓ |
| Multiple OI Chart | `/app/options-analysis/multiple-oi-chart` | ✓ |

### Strategies (whole section NOT in user's list)
> Live Strategies menu (confirmed live 2026-06-18) = **calender-spread** (sic), iv-strategy, multi-leg-price,
> straddle-chart, strangle-chart, strategy-builder. "Morning Trade" and "3:20 Strategy" are **not navigable
> routes** — they are paid/AI manual-sourced features (0 of 122 app routes match). See
> [PHASE-B-FINDINGS.md](PHASE-B-FINDINGS.md) (V17).

| Item | Route | Scope |
|---|---|---|
| Straddle Chart | `/app/strategies/straddle-chart` | + |
| Strangle Chart | `/app/strategies/strangle-chart` | + |
| Open & High Strategy | `/app/options-analysis/open-high-strategy` | + |
| OI Expiry Strategy | `/app/options-analysis/oi-expiry-strategy` | + |
| Strategy Builder | `/app/strategies/strategy-builder` | + |
| Calendar Spread | `/app/strategies/calender-spread` | + |
| Multi Leg Price | `/app/strategies/multi-leg-price` | + |
| Morning Trade | *not a navigable route* — paid/AI manual-sourced feature only ([morning-trade.md](strategies/morning-trade.md)); confirmed live 2026-06-18 (0 of 122 app routes match) | + |
| 3:20 Strategy | *not a navigable route* — paid/AI manual-sourced feature only ([3-20-strategy.md](strategies/3-20-strategy.md)); confirmed live 2026-06-18 (0 of 122 app routes match) | + |

### Equity
| Item | Route | Scope |
|---|---|---|
| Pre open market | `/app/equity/pre-open-market` | ✓ |
| Open & High-low | `/app/equity/open-high-strategy` | ✓ |
| Index Contribution | `/app/index-contribution` | ✓ |
| Sector Stats | `/app/equity/sector-stats` | ✓ |
| Sector Heatmap | `/app/equity/sector-wise-heatmap` | ✓ |
| Equity Returns | `/app/equity/equity-returns` | ✓ |
| Delivery Data | `/app/equity/delivery-data` | ✓ |
| Announcement | `/app/equity/announcement` | ✓ |

### FII/DII Activity
| Item | Route | Scope |
|---|---|---|
| Capital Market | `/app/fii-dii/capital-market` | ✓ |
| FII Derivative Stats | `/app/fii-dii/fii-derivative-stats` | ✓ |
| Participant wise OI | `/app/fii-dii/participant-wise-oi` | ✓ |
| FII Long Short Ratio | `/app/fii-dii/fii-long-short-ratio` | ✓ |

## Per-page doc template
Each page file captures: route · purpose · layout (positional) · component inventory table
(name/type/position/values/behavior/visual cues) · chart & table detail (columns, types, color rules)
· **data source (API endpoint + payload shape)** · replication notes (→ Angular/PrimeNG) · screenshot.

## Status
- [x] **00 Global shell + tech stack + design tokens** (`00-global-shell.md`)
- [x] **Features (10/10)** — `features/`: dashboard, connecting-dots, world-indices, vix-index, multiple-window, risk-calculator, plans, event-days, market-holidays, update-logs
- [x] **Advance Chart (2/2)** — `advance-chart/`: advance-chart, multiframe-chart
- [x] **Futures (8/8)** — `futures/`: oi-analysis, oi-chart, oi-spurt, oi-buzz, pre-open-market, market-movers, banks-analysis, eod-oi-analyzer
- [x] **Options (14/14)** — `options/`: oi-analysis, oi-chart, options-chain, options-chart, oi-spurt, oi-statistics, options-premium, trending-oi, trending-oi-pa, big-oi-movement, active-strikes-oi, active-strikes-iv, interval-wise-oi, multiple-oi-chart
- [x] **Strategies (7/7)** — `strategies/`: straddle-chart, strangle-chart, strategy-builder, calendar-spread, multi-leg-price, open-high-strategy, oi-expiry-strategy
- [x] **Equity (8/8)** — `equity/`: pre-open-market, open-high-low, index-contribution, sector-stats, sector-heatmap, equity-returns, delivery-data, announcement
- [x] **FII/DII (4/4)** — `fii-dii/`: capital-market, fii-derivative-stats, participant-wise-oi, fii-long-short-ratio

**STUDY COMPLETE — 53/53 pages + global shell.**

## Trading-interpretation layer (folded from the Manual V10 audit)
Per-page docs capture UI + API; the *how-to-trade* methodology, folded in from the OI Pulse Manual (V10), lives in:
- [oi-interpretation-method.md](oi-interpretation-method.md) — the shared OI read (4 states + strength grading, the four-quadrant model, OI/LTP X-crossover, support/resistance/range, timeframe roles, strike selection, connect-the-dots confluence). Per-page docs link to it from their `## Interpretation (how to trade)` sections.
- [strategies/expiry-day-trading-plan.md](strategies/expiry-day-trading-plan.md) · [strategies/morning-trade.md](strategies/morning-trade.md) · [strategies/3-20-strategy.md](strategies/3-20-strategy.md) · [advance-chart/ospl-signal.md](advance-chart/ospl-signal.md) — standalone strategy/signal methodologies (the last three are paid/AI features whose methodology stays manual-sourced; Morning Trade / 3:20 Strategy are **not navigable routes**, confirmed live 2026-06-18 — see [PHASE-B-FINDINGS.md](PHASE-B-FINDINGS.md) V17 and [NOT-CAPTURED.md](NOT-CAPTURED.md)).
- [MANUAL-V10-GAP-ANALYSIS.md](MANUAL-V10-GAP-ANALYSIS.md) — the full audit log. Remaining `[VERIFY]` items (where the older manual may differ from live) are tracked in [PHASE-B-PLAN.md](PHASE-B-PLAN.md).

## API namespace map (`https://api.oipulse.com/api/<ns>/...`)
Per area, the same verb pattern repeats: `getavailable*` (instruments) · `getselected*date` (dates) ·
`get*expirydate` (expiries) · `getselected*strikepricedata` (strikes) · `...alldata` (table) ·
`...alldataforchart` (chart). Envelope `{status,msg,data}`; `st*`=string, `in*`=number/enum, `ob*/obj*`=nested, `dt*`=date.

| Namespace | Pages |
|---|---|
| `gettickerdata` | global ticker strip |
| `trading-view/*` | Advance Chart, Multiframe (UDF datafeed: getcandledata/getlistofsymbols/getservertime/getallstudytemplates) |
| `connecting-dots/*` | Connecting Dots |
| `vix-price/*` | Vix & Index |
| `heatmap/*` | World Indices (getworldindicesdata), OI Buzz, asset lists (getlistofassetforheatmap) |
| `futures/*` | Futures OI Analysis/Chart/Spurt/Buzz, Pre-open, Market Movers, EOD |
| `bank-analysis/*` | Banks Analysis |
| `pre-open-market/*` | Futures + Equity Pre-open |
| `options/*` | Options OI Analysis/Chart/Chain/Premium/Spurt/Stats, Multiple OI, Straddle/Strangle metadata |
| `trending-oi-static/*` | Trending OI, Trending OI-PA |
| `big-oi-movement/*` | Big OI Movement |
| `active-strike-oi/*` | Active Strikes OI, Active Strikes IV |
| `interval-wise-oi/*` | Interval wise OI |
| `open-high-strategy/*` | Options Open & High Strategy |
| `opt-eod-oi-analysis/*` | OI Expiry Strategy |
| `strategy/*` | Straddle/Strangle chart, lot size |
| `strategy-builder/*` | Strategy Builder |
| `equity/*` | Open=High/Low, Sector Stats (getindexconstituentswithdata), Sector Heatmap (getsectorwisestockperformancedata), Returns, Delivery, Announcement |
| `index-contribution/*` | Index Contribution |
| `fii-dii/*` | Capital Market, FII Derivative Stats, Participant-wise OI, FII LSR |
| `market-view/getmarketholidays` | Market Holidays |
| `user-tool-plan/getavailableplans` | Plans |

## Page-type taxonomy (for component reuse in our app)
- **Mirrored Call|Strike|Put tables**: Options OI Analysis, Options Chain, Open & High Strategy.
- **4-quadrant OI scanners**: Futures OI Spurt, Options OI Spurt (the OI-interpretation matrix).
- **Treemap heatmaps**: OI Buzz (flat), Sector Heatmap (sector-grouped).
- **Combo candle+line (ECharts)**: OI Chart, Options Chart, Straddle/Strangle/Calendar/Multi-leg, FII LSR.
- **Dual-axis line**: Vix&Index, Active Strikes OI/IV, OI-vs-price, PCR.
- **Net-value bar charts**: FII Capital Market, FII Derivative Stats, Interval-wise OI, Cumulative/Individual OI, Sector Stats, Premium bars.
- **Filter-bar + paginated p-table**: most Futures/Equity/FII tables.
- **TradingView widgets**: Dashboard (Investing.com feed), Advance/Multiframe (own NSE feed).
- **Interactive builders**: Strategy Builder (payoff/greeks/POP), Multi Leg Price, Calendar Spread.
- **Signal matrix**: Connecting Dots (per-factor 0/1/2 enum + composite).

## Capture method (for resuming)
- Driven live via Claude-in-Chrome on the owner's logged-in session (cookie/localStorage auth survives new tabs).
- SPA nav via injected `window.__go(path)` (clicks router `<a>` — preserves the fetch/XHR interceptor; full reload would lose it).
- `window.__oicap` = injected interceptor logging every `api.oipulse.com` call (url, method, req params, response). `window.__sum()` summarizes shapes (keys+types) to avoid the security filter that blocks raw token-like output.
- Vue 2 component data read via `el.__vue__` when DOM doesn't expose values (e.g. signal ints).
