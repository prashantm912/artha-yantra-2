# OI-Analytics Suite (oipulse parity) — Master Implementation Plan

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


> **For agentic workers:** This is a MASTER/PROGRAM plan spanning multiple subsystems. Do NOT execute it directly task-by-task. At execution time, write a detailed bite-sized sub-plan per phase (REQUIRED SUB-SKILL: `superpowers:writing-plans` again, scoped to one phase), then implement with `superpowers:subagent-driven-development`. Steps here are phase-level deliverables with verification, not 2-minute code steps.

**Goal:** Reproduce oipulse.com's full options/OI analytics suite (~40 pages, 12 layout archetypes) inside ArthaYantra — same data, same interface conventions — sourced entirely from our own Kite feed + free NSE EOD, self-hosted and backtestable.

**Architecture:** A new intraday **option-chain snapshot pipeline** (the foundation nothing currently captures) feeds new TimescaleDB hypertables. An **analytics layer** (new module in `market-data-service`, or a new `analytics-service` — see Decision D1) computes 3 primitives (OI-Interpretation, buildup classification, Active-Strike tracking) + derived metrics (PCR, max pain, straddle, Greeks via `black76-math`). The Angular frontend gets a **shared-component foundation** (control bar, symbol-context store, OI-Int badge, in-cell data bars, chart wrapper, contextual header) so 40 pages collapse to 12 archetype components driven by config.

**Tech Stack:** Java/Spring (services) · TimescaleDB hypertables + Flyway (marketdata lineage) · Kite Connect (chain/futures quotes) · `black76-math` (IV/Greeks) · NSE public EOD files (FII/DII, participant OI, bhavcopy, delivery) · Angular 21 zoneless + PrimeNG 21 · lightweight-charts / echarts (`ay-echart`) · `SessionStore`.

---

## 0. Key decisions & assumptions (RESOLVE BEFORE PHASE 1)

| # | Decision | Recommendation | Why |
|---|---|---|---|
| **D1** | Where do analytics live? | New module in `market-data-service` for capture; new read endpoints `/api/v1/options/*` in same service. Promote to standalone `analytics-service` only if load demands. | market-data-service already owns candles + Kite data; avoids a new service + new schema role. Keeps D10 single-writer. |
| **D2** | New schema lineage? | Extend **marketdata** lineage; new hypertables `option_chain_snapshots`, `futures_oi_snapshots`, `nse_eod_*`. | OI snapshots are market data. No new role needed. |
| **D3** | Snapshot cadence | Capture full chain every **1 min** during market hours; derive 3/5/15/30/60 by resampling. | Capture finest once; all intervals = downsample. Matches oipulse interval dropdown without N pipelines. |
| **D4** | Proprietary signals (Connecting Dots fusion, Active Strike Sentiment %) | Replicate **all inputs exactly**; implement fusion as a **configurable, documented formula** (equal-weight vote v1), then tune/backtest. | Exact weights are oipulse IP, unknowable. Inputs are not. Ours becomes backtestable — theirs isn't. |

### Assumptions / hard constraints (state to stakeholder)
- **A1 — Intraday OI history only accrues from go-live.** Kite does not serve historical full-chain OI cheaply; like oipulse, our intraday OI time-series starts the day we switch the capture job on. Daily/EOD OI **can** backfill from NSE bhavcopy.
- **A2 — IV is computed, not fed.** Kite quotes give OI/LTP/volume but **no IV**. Active-Strike IV, vol charts, Greeks all come from `black76-math` (LTP + spot + strike + tenor + rate → IV). Tiny diffs vs oipulse's IV are expected (model/rate assumptions).
- **A3 — "Exact same data" = same source, not byte-identical.** Live values match because both read NSE via broker; minor differences from snapshot timing, rate/tenor conventions, and rounding are unavoidable and acceptable.
- **A4 — NSE EOD ingestion** (FII/DII, participant-wise OI, delivery) hits NSE public files directly; NSE rate-limits/anti-bots — needs a polite cached daily fetch (not per-request).
- **A5 — `libs/market-calendar` covers the current year only** (known gotcha) — historical/date-picker windows outside it will fail until extended.

---

## 1. Data source mapping (every datum → source → status)

| Data | oipulse pages using it | Source | Status |
|---|---|---|---|
| Option chain OI / LTP / volume per strike (CE+PE) | Chain, OI Analysis, Spurt, Trending, Active Strikes, Big OI, Premium, Connecting Dots | **Kite quote()** on chain instruments → `option_chain_snapshots` | **BUILD (Phase 1)** |
| Option IV | Active Strikes IV | **`black76-math`** from chain LTP | Have lib; wire up |
| Futures OI / LTP / volume | Futures OI Analysis/Chart/Spurt/Buzz, Market Movers, Banks | **Kite quote()** on futures → `futures_oi_snapshots` | **BUILD (Phase 1)** |
| India VIX | Vix&Index, Connecting Dots, chain header | Kite/NSE VIX quote | Mostly have (verify symbol) |
| Spot/index LTP, candles, VWAP/RSI/Supertrend | every chart, Connecting Dots TA inputs | Existing candle store + TA compute | Have candles; **build TA calc** |
| PCR, Max Pain | OI Stats, chain header, Trending | Derived from chain snapshot | **BUILD (Phase 2)** |
| FII/DII, participant-wise OI, FII L/S ratio, capital market | FII/DII pages, Connecting Dots? | **NSE EOD files** → `nse_eod_*` | **BUILD (Phase 1b)** |
| Delivery data, sector stats, equity returns, index contribution | Equity pages | NSE bhavcopy/EOD | **BUILD (Phase 1b)** |
| Dow / world indices | World Indices, Connecting Dots Dow input | Free global feed | **BUILD (small)** |

---

## 2. Schema (Phase 1 migrations — marketdata lineage)

New suffix-versioned Flyway migrations under `deploy/.../marketdata/` (never edit applied ones):

- `option_chain_snapshots` — hypertable: `(ts, underlying, expiry, strike, opt_type, oi, oi_chg, ltp, ltp_chg, volume, iv, delta, gamma, theta, vega)` — partition by `ts`, index `(underlying, expiry, ts)`. IV/Greeks nullable (filled by analytics or at write).
- `futures_oi_snapshots` — hypertable: `(ts, symbol, expiry, oi, ltp, volume, day_high, day_low)`.
- `nse_eod_fii_dii`, `nse_eod_participant_oi`, `nse_eod_delivery` — daily tables keyed by `(date, …)`.
- Continuous aggregates for 5/15/30/60-min downsample of `option_chain_snapshots` (mirrors the `candles_<iv>` cagg pattern).

**Verify:** `flyway validate` green; hypertable + caggs created; a manual insert + cagg refresh returns rows.

---

## 3. Phased roadmap

> Each phase = its own working, testable increment. Write the bite-sized TDD sub-plan at the START of each phase.

### Phase 1 — Foundation: intraday capture pipeline ⟵ *everything depends on this*
- **1a Chain/futures snapshot job** (`market-data-service`): resolve chain instrument tokens per index+expiry; scheduled 1-min `quote()` during market hours (use `market-calendar` session check); write `option_chain_snapshots` + `futures_oi_snapshots`; compute IV/Greeks via `black76-math` at write.
- **1b NSE EOD ingestion job**: daily polite fetch → `nse_eod_*`. Cache-first.
- **Deliverable:** snapshots accruing live; EOD tables populating daily.
- **Verify:** during market hours, `SELECT count(*) … WHERE ts > now()-interval '10 min'` > 0 across strikes; IV within sane band; EOD job writes today's FII/DII after market close. JaCoCo ≥60%.

### Phase 2 — Analytics backend (the 3 primitives + derived)
- **Primitive #1 OI-Interpretation** (price-dir × OI-dir → L.B./S.B./S.C./L.U.).
- **Primitive #2 Active-Strike tracking** (peak-OI strike → its OI/IV series; **Active Strike Sentiment %**).
- **Primitive #3 Buildup classifier** (4-bucket spurt across instruments).
- **Derived:** PCR (+chart), Max Pain, straddle/strangle premium series, Greeks aggregation, OI-spurt %, big-OI-move detector, O=H/O=L probability, market movers, banks-analysis grid.
- **Endpoints:** `/api/v1/options/{chain,oi-analysis,spurt,trending,active-strikes,big-oi,premium,oi-stats}`, `/api/v1/futures/{oi-analysis,spurt,buzz,movers,banks,eod}`, `/api/v1/fii-dii/*`. All accept `Mode·Name·Date·Expiry·Interval` (the universal control-bar contract).
- **Verify:** unit tests per metric vs hand-computed fixtures; `ContractCaptureTest` re-captured; contract gen + `tsc --strict` green.

### Phase 3 — Frontend foundation (shared components)
Build ONCE, reuse across all 40 pages (`frontend-ui/src/app/shared/`):
- `<ay-tool-controls>` — Mode·Name·Date·Expiry·Interval·Go (the universal bar).
- **Symbol-context store** — extend `SessionStore`: selected instrument/expiry/interval persisted across routes (oipulse's "carries everywhere").
- `<ay-oi-int-badge>` — 4-state PrimeNG `p-tag` (L.B. green / S.B. red / S.C. blue / L.U. orange) + arrow.
- `ay-data-bar` cell template — in-cell horizontal magnitude bar, sign-colored.
- `<ay-context-header>` — VIX · PCR · underlying+chg+asof band.
- Chart chrome via existing `ay-echart` / lightweight-charts wrapper — standard legend + range slider + export toolbar.
- Collapsible icon **sidebar nav** + pinned favorites.
- Semantic theme tokens in `.ay-dark`: `--ay-bull/--ay-bear/--ay-neutral` (AA-contrast checked).
- **Verify:** `npm run lint` + `test:ci` + `build`; axe pass on a harness page; badge/bar render under zoneless prod build (watch the virtualScroll/zoneless gotchas).

### Phase 4 — Pages by archetype (11 components)
Implement one archetype component, then config-instantiate its pages. Order = cheapest coverage first:
1. **Data-table** (color-arrow grid) → Connecting Dots*, Trending OI(+PA), Futures/Options OI Analysis, Interval-wise OI, EOD Analyzer *(table part)*
2. **Options-chain** (mirrored + data bars + badges) → Options Chain
3. **Buildup multi-table** → Futures/Options OI Spurt, Big OI Movement
4. **Single chart** → Options/OI Chart, Premium, Vix&Index
5. **Multi-chart panel** → OI Stats (Cumulative/Individual/PCR)
6. **Heatmap** → OI Buzz, Sector Heatmap
7. **Gainers/Losers** → Market Movers
8. **EOD table** → FII Derivative Stats, Participant-wise OI, FII L/S Ratio, Capital Market, Delivery, Equity Returns, Index Contribution
9. **Custom dashboard** → Multiple Window (compose N archetype components in a grid)
10. **Calculator** → Risk Calculator
11. **Reference** → World Indices, Event Days, Market Holidays, Update Logs *(reuse `market-calendar`)*

\*Connecting Dots final fusion lands in Phase 5.
- **Verify per archetype:** Playwright e2e drives the page vs mock stack; visual matches oipulse layout; data matches a backend fixture.

### Phase 5 — Proprietary signals (approximate + configurable)
- **Connecting Dots** — assemble 11 inputs (Dow, Vix, Volume, Active-Strike IV, Active-Strike OI, OI-Int, VWAP, Supertrend, RSI, Price, Daily-Trend) per interval → 5-state Trend via configurable weighted vote (v1 equal-weight).
- **Active Strike Sentiment %** — net directional OI-change at active strikes, normalized signed % (can exceed ±100).
- **Backtest the signal** against the existing golden/live-parity harness; tune weights.
- **Verify:** signal reproducible + deterministic (GoldenDeterminismTest-style); backtest run completes; weights documented.

### Phase 6 — Polish
Column chooser (PrimeNG column toggle), Graph/Table `p-selectButton`, sortable columns, rows-per-page incl All, sticky ticker, full a11y sweep (`ui-a11y-reviewer`), dark-mode hard-reload check.

---

## 4. Complete page checklist

> **Option-strategies suite (Straddle · Strangle · Open&High · OI Expiry · Strategy
> Builder · Calendar Spread · Multi-Leg Price) is CUT from oipulse parity** — needs
> Greeks/POP/payoff (own undefined schema). Not deferred-within, removed from scope.

**Futures:** OI Analysis · OI Chart · OI Spurt · OI Buzz · Pre-open · Market Movers · Banks Analysis · EOD OI Analyzer
**Options:** OI Analysis · OI Chart · Options Chain · Options Chart · OI Spurt · OI Stats · Option Premium · Trending OI · Trending OI-PA · Big OI Movement · Active Strikes OI · Active Strikes IV · Interval-wise OI · Multiple OI Chart
**Equity:** Pre-open · Open&High-low · Index Contribution · Sector Stats · Sector Heatmap · Equity Returns · Delivery Data · Announcement
**FII/DII:** Capital Market · FII Derivative Stats · Participant-wise OI · FII Long-Short Ratio
**Features:** Dashboard · Connecting Dots · World Indices · Vix&Index · Multiple Window · Risk Calculator · Event Days · Market Holidays · Advance/Multiframe Chart

Each maps to a Phase-4 archetype; tick on completion.

---

## 5. Risks / open items
- **R1 Capture reliability** — missed snapshots = gaps in OI series. Need auto-reconnect/retry + gap detection (mirror their auto-reconnect).
- **R2 Kite rate limits** — chain quote per index per minute × N indices; budget the call rate.
- **R3 NSE anti-bot** — EOD fetch may break; cache + graceful degrade.
- **R4 Signal fidelity** — ours ≠ oipulse exactly (D4/A2); set expectation: same inputs, our (better, backtestable) fusion.
- **R5 a11y vs density** — keep AA contrast + non-color cues; don't copy oipulse's low-contrast.
- **R6 Scope** — 40 pages is large; Phases 1-3 unlock most value; Phase 4 can ship archetype-by-archetype.

## 6. Rough effort (relative)
Phase 1 ≈ large (new pipeline) · Phase 2 ≈ large · Phase 3 ≈ medium · Phase 4 ≈ large but parallelizable per archetype · Phase 5 ≈ medium · Phase 6 ≈ small. Phases 1→3 are the critical path; 4 fans out.

---

## Execution handoff
Detailed bite-sized TDD plans are written **per phase at execution time** (this master intentionally stays at phase altitude to avoid churn before you start). When ready, begin with a Phase-1 sub-plan.
