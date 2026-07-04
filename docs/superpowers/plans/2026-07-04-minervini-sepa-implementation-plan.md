# Minervini SEPA — full implementation plan (Track 1 momentum equities)

**Status:** ACTIVE · **Created:** 2026-07-04 · **Owner:** solo (prashantm912)
**Source spec (authority):** [`strategy-documents/mark-minervini-operative/MomentumTradingMarkMinervini_Consolidated_Strategy.md`](../../../strategy-documents/mark-minervini-operative/MomentumTradingMarkMinervini_Consolidated_Strategy.md) — SEPA, 6 setups (`sepa`/`vcp`/`cheat_3c`/`power_play`/`primary_base`/`selling`), §6 machine-readable appendix is the per-setup contract.
**Prior design authority this consolidates + extends:** master-plan [§13 Track-1](2026-06-19-openalgo-react-integration-master-plan.md) (screener), §9 (fundamentals appliance), §5 (equity daily backfill), §14 (backtest), and the overriding §17.1 (Flyway allocation) + §17.7 (data-grain). Ledger row [`phase5-minervini-trend-template`](2026-07-02-remaining-items.md) is subsumed by this plan.

> **Scope of THIS plan vs the master-plan.** Master-plan §13 scoped *only* the price-gate daily screener and **explicitly deferred** VCP/Cheat/Power-Play/Primary-base/Stage detection, entries, paper, and live ("owner reads the chart manually"). This plan covers the **complete workflow the owner now asked for** — database, data-load, Upstox/vendor APIs, screener, fundamentals, analyzers, VCP/base geometry, per-setup entry signals, swing paper-trade, swing backtest/forward-test, live operation, and selling discipline — organized so each layer ships independently. The screener (Phases 1–4) is the master-plan scope; Phases 5–10 are the net-new extension.

---

## How to use this document (tracking protocol — READ FIRST)

This plan is the **single source of truth for Minervini build state**. It is built to survive across sessions without false positives.

- Every work item has a **stable ID** (e.g. `MV-2.3`), a **Status**, a **Verify** (the exact command / test / endpoint that *proves* it is done), and an **Evidence** cell (PR# + SHA + the verify output).
- **Status tokens:** `TODO` (planned, not started — *includes* sequenced Track-B work that comes after Track A) · `WIP` (in progress) · `DONE` (verified) · `DEFERRED` (optional or owner-gated — see §9; this is NOT the same as "scheduled for later") · `BLOCKED` (waiting on a dependency/owner) · `N/A`.
- **The one rule that prevents false positives:** *never* set a row to `DONE` without pasting the Verify output (or PR#+SHA) into Evidence. A future session that reads `DONE` must be able to re-run the Verify and reproduce it. If you cannot, the row is not done.
- **Grounding column semantics:** `REUSE` = existing code serves this, do not rebuild · `EXTEND` = existing code is close, additive change · `NEW` = net-new.
- When an item ships, update its row **and** the §11 changelog in the same PR. Do not start the next item until the current row is `DONE`/`DEFERRED` with evidence.
- **Before building any migration**, re-read §5 and re-check the live Flyway heads — version numbers below are correct as of 2026-07-04 but drift.

---

## 0. Executive summary

Minervini SEPA is a **long-only, cash-equity, swing-to-position** momentum method (hold days→months) for **Indian equities only** (owner-locked: never US — tracked as OD-5's sibling constraint in §9). The source books are US-equity; this is a **market/instrument swap, not a methodology change** — every rule, threshold, and setup transfers to NSE cash equities unchanged, with the India equivalents of the book's US data feeds (bhavcopy + Upstox equity-daily for prices; a computed cross-sectional RS-rank for IBD's; Screener.in for EDGAR-style fundamentals). It is a fundamentally different animal from the platform's existing **intraday index-option scalpers**: daily bars not 3-minute, cash stock not option premium, multi-week holds not same-session square-off, and a **cross-sectional daily screen over the whole equity universe** rather than a per-symbol live signal.

The build decomposes into two independently-shippable tracks:

- **Track A — the daily screener (Phases 1–4).** Already largely designed (master-plan §13). Produces a ranked candidate shortlist: 8-gate Trend Template + Minervini cross-sectional RS-rank, optional fundamentals confirmation, persisted daily, surfaced as a React screener page + per-candidate analyzer. **This is the load-bearing 80/20** — it delivers the owner's core workflow (find the names) even if nothing else is built; the owner reads the chart for the entry.
- **Track B — automated setups, entries, paper, backtest, live (Phases 5–10).** Net-new: VCP/base geometry + Stage detection, the six §6 setups as strategy definitions, a **swing** position lifecycle (new `session.style=swing`, daily-bar primary, staggered stops, sell-into-strength), a swing paper book, a screener-hit-rate + swing-replay backtest, live operation, and the §3.6 selling discipline + alerts.

**Fundamentals was the plan's presumed biggest risk — the grill dissolved it.** The **Upstox Company Fundamentals API** (8 ISIN-keyed endpoints on the analytics token already funded) supplies financials, shareholding, ratios, corporate actions, and sector — so EPS/sales/margins (Code-33), ROE, P/E, **free-float %**, and **market cap** are all sourceable without a scraper (this reverses master-plan §9 — see [ADR-0004](../../adr/0004-minervini-fundamentals-via-upstox-api.md) and §0.5). Only **earnings-surprise + analyst estimate-revisions** stay unmodeled (no Indian consensus feed). Fundamentals still layers strictly *after* the price gates. The **remaining real risks** are: (1) VCP/base-geometry false positives (§5) — mitigated by Track-B paper-proving before trust; (2) a **shallow daily-history depth** (~1.2y today) that a deep backfill must fix *before* any backtest (§0.5 decision 11). See §3-D and §9.

---

## 0.5 Grilling decisions — LOCKED 2026-07-04

A `/grill-with-docs` session with the owner resolved every open point. These are authoritative; the phase items, §5 data model, and §9 below are updated to match. Hard/surprising calls are captured as [ADR-0004](../../adr/0004-minervini-fundamentals-via-upstox-api.md) (Upstox fundamentals) and [ADR-0005](../../adr/0005-minervini-universe-low-cap-equities.md) (universe + low-cap gates).

**Configurable defaults (all ride config/DB rows — "tuning rides DB, never Java", CLAUDE.md):**

| Config key | Default | Q |
|---|---|---|
| `artha.minervini.capital` | ₹1,50,000 | Q3 |
| `artha.minervini.pilot_position_pct` | 5–6% | Q4 |
| `artha.minervini.max_name_pct` | 20–25% | Q4 |
| `artha.minervini.max_concurrent` | 4–8 | Q4 |
| `artha.minervini.single_ceiling_pct` | 50% | Q4 |
| `artha.minervini.liquidity_multiple` | 100 (×) | Q3 |
| `artha.minervini.min_price` | ₹30 | Q1 |
| `artha.minervini.max_free_float_mcap_cr` | 5000 | Q2 |
| `artha.minervini.max_free_float_pct` | 35% | Q2 |
| `artha.minervini.exclude_fno` | true | Q2 |
| `artha.minervini.rs_min` | 70 | Q6 |
| `artha.minervini.pct_above_52w_low` | 25% | Q6 |
| `artha.minervini.within_52w_high` | 25% | Q6 |
| `artha.minervini.sma200_rising_sessions` | 21 | Q6 |
| `artha.minervini.rs_weights` | 0.4/0.2/0.2/0.2 @ 63/126/189/252 | Q6 |
| `artha.minervini.initial_stop_pct` | 7–8% (hard cap 10) | Q7 |

**Decisions:**

1. **Universe (Q1) → ADR-0005:** full NSE EQ; RS-rank computed across the liquidity-filtered set. NIFTY-500 = optional toggle, **default OFF**.
2. **Low-cap gates (Q2) → ADR-0005 — ALL HARD:** free-float mcap **< ₹5,000 cr** AND free-float **< 35%** of total AND **not F&O-listed** AND price **> ₹30** AND avg-50d turnover **≥ 100 × (capital × max_name_pct) = ₹37.5L/day** AND **≥ 200 sessions**. "Low market cap is the edge; no large caps, no derivatives."
3. **Fundamentals + market-cap source (Q2) → ADR-0004:** the **Upstox Company Fundamentals API** — 8 endpoints, **ISIN-keyed, on the analytics token already funded**: Income Statement (sales/margins), Key Ratios (EPS/ROE/P·E), Share Holdings (promoter/FII/DII/public → free-float%), Company Profile + Competitors (sector/peers), Corporate Actions. **Reverses master-plan §9** (Screener.in scraper). Earnings-surprise + estimate-revisions stay **UNMODELED** (no consensus feed). `openscreener` → fallback only.
4. **Corporate-action adjustment (Q2, newly surfaced):** daily closes must be **split/bonus-adjusted** before MAs / 52w-hi-lo (bhavcopy CA-adjusts per #41 → add a VERIFY the screener reads the adjusted series; Upstox Corporate Actions cross-checks).
5. **Sizing / concentration (Q3/Q4):** pilot **5–6%** → pyramid to **20–25%** per name (add-on-strength, never average down); **4–8** concurrent names; single ≤ **50%**. Minervini concentration, not flat 5%.
6. **Build depth (Q5):** Track A hands candidates **+** Track B runs **ALL** setups per candidate → **pass/fail + per-rule reasoning** shown alongside, **+ paper-traded** to measure reliability. Owner verifies on chart, executes **manually** until Track B proven. No VCP-first sequencing — all setups first-class.
7. **Execution (OD-4):** manual now; semi-auto (OpenAlgo `OrderGateway`) a later decision gated on Track-B reliability + a safety/latency gate.
8. **Screener gates + RS (Q6):** the 8 Trend-Template gates per §4.2; gate-6 = **25%**; RS = the weighted formula above, percentile **1–99** across the filtered universe.
9. **Setups (Q7):** VCP / Cheat-3C / Power-Play / Primary-Base / SEPA-funnel per §6 defaults — all first-class, all configurable.
10. **Exit (Q7):** single **7–8%** stop (cap 10%); breakeven at ~3×R; then **50-day-MA close-below trail**; sell-into-strength on climax; Stage-3/4 exit. **Staggered stops DEFERRED** (add after single-stop proves out).
11. **Backtest (Q8):** a **DEEP daily backfill (~3–5y, CA-adjusted, Upstox historical API, `market-calendar` extended) is a PREREQUISITE** — build it *before* the backtest. Then the screener hit-rate harness + swing replay. Survivorship accepted + documented.
12. **Reliability bar (Q8):** a Track-B setup flips **watch → trusted** after **≥ 30–50 forward paper trades** with **positive expectancy (avg win ≥ ~2× avg loss)** at a **~45–55% hit-rate**.
13. **Cadence (Q9):** one daily EOD screen after the ~19:00–19:30 IST bhavcopy pull + a boot one-shot. Swing = daily; no intraday equity feed needed.
14. **Output (Q9):** screener **list** = per-candidate setup **pass/fail chips**; **analyzer** = full per-rule reasoning + a daily chart (50/150/200-MA overlays + volume + annotated VCP contractions/pivot) for the manual chart check. Reasoning **persisted** via the `signal_rejections`/`score_breakdown` forensics pattern (every fail explainable + queryable).
15. **Alerts (Q9):** candidate-turns-buyable + pivot pre-alert + stop alert via the notifier (event-listener pattern, ntfy/Telegram), flag-gated, default off.
16. **Lineage (OD-5):** screen-results in **marketdata** (writer-aligned — the scheduler lives in market-data-service). §17.1 deviation recorded in ADR-0005.

---

## 1. Source → build map

The §6 machine-readable appendix keys map to build items as follows. "Screener-expressible" = computable from daily OHLCV + fundamentals in a batch scan. "Needs geometry" = requires base/contraction/pivot detection (Phase 5). "Needs position engine" = entry/stop/exit orchestration (Phase 7).

| §6 key | What it is | Primary layer | Screener? | Geometry? | Position engine? |
|---|---|---|---|---|---|
| shared §4.2 | **Trend Template (8 gates)** | Phase 2 | ✅ core | — | — |
| shared §4.10 | **RS-rank (IBD 1–99 cross-sectional)** | Phase 2 | ✅ core | — | — |
| shared §4.7/§4.8 | **Fundamentals (EPS/sales/margin/Code 33)** | Phase 3 | ✅ optional | — | — |
| shared §4.1 | **Stage Analysis (1–4)** | Phase 2 | ✅ derived label | — | — |
| shared §4.6 | **Base & correction geometry** | Phase 5 | — | ✅ | — |
| shared §4.11 | **Industry group + catalyst** | Phase 6 | ✅ group / ⚠️ catalyst manual | — | — |
| shared §4.12 | **Market direction / timing** | Phase 2+6 | ✅ breadth/regime | — | — |
| `vcp` | **VCP + pivot buy** | Phase 5+6 | — | ✅ | ✅ |
| `cheat_3c` | **The Cheat (3-C)** | Phase 6 | — | ✅ | ✅ |
| `power_play` | **Power Play (high-tight flag)** | Phase 6 | — | ✅ | ✅ |
| `primary_base` | **Primary Base / IPO** | Phase 6 | — | ✅ | ✅ |
| `sepa` | **SEPA master playbook** (funnel convergence) | Phase 6 | ✅ (funnel) | ✅ | ✅ |
| `selling` | **Offensive/defensive exits** | Phase 7+9 | — | — | ✅ |
| §2 | **Risk framework** (stops, expectancy, sizing) | Phase 7 | — | — | ✅ |

**Design authority reconciliation (locked):**
- §17.7 said the screener reads `nse_eod_bhavcopy`; the later `EquityDailyBackfillService` writes native `candles`@1d. **Unresolved in code → Phase 0 VERIFY (MV-0.2) picks the canonical dense daily source.** Do not write MA SQL before that gate closes.
- §17.1 (authoritative) put `minervini_screen_results` in the *strategy* lineage for two reasons: to free `marketdata/V018` for the `candles.source` enum, and because "screen results are a strategy artifact." **Reason 1 is now obsolete** — the `candles.source` enum already shipped as `marketdata/V020`. **Reason 2 conflicts with the D10 single-writer rule**: §13.5 itself places the producer `MinerviniScheduler` in *market-data-service*, which writes the *marketdata* schema — a strategy-schema table would force a cross-schema write. Because §17.1 is authoritative, this plan does **not** silently re-decide: the placement is escalated to **OD-5** (§9). Working assumption = **marketdata** (writer-follows-single-writer); do not write the migration until OD-5 is answered.
- Master-plan `V010`/`V018` version numbers are **stale** — current heads are marketdata V030, strategy V019, backtest V007 (see §5).

---

## 2. Grounding — what already EXISTS (verified 2026-07-04) + false-positive guard

**REUSE (do not rebuild):**
- `ScreenerService` + `ScreenerController` (`services/market-data-service/.../screener/`) — preset dispatch (`momentum`/`long_term`/`rs_rank`/`oi_buildup`), parameterized SQL, `{items,limit,offset}` envelope, `Row(exchange,tradingsymbol,latestClose,pastClose,value,avgVolume,distanceFromHigh52w,label)`. Add a Minervini path here, do not fork.
- `nse_eod_bhavcopy` (V014) + `bse_eod_bhavcopy` (V021) — dense daily EOD, ~3.2k NSE symbols/day, no retention (≥5y floor), captured by `NseEodScheduler`. Deep-history daily source.
- `EquityDailyBackfillService` — pulls ~200–300 daily candles/equity from Upstox into native `candles`@1d source=BACKFILL for ~3500 NSE equities (`POST /api/v1/market/admin/equity-daily-backfill`, live-verified).
- `fundamentals` table (V017) — tall EAV `(symbol, statement, period_end, granularity, metric, value, is_percent, source)`. Covers EPS/Revenue/margins/ratios from Screener.in CSV backfill.
- `instruments` master (V002) — ~3500 active NSE equities; `InstrumentRepository.activeEquities()` filters `instrument_type='EQ'`, excludes indices/synthetic.
- `index_constituents` (marketdata) — point-in-time membership; useful (when populated) to scope the universe to NIFTY 500 / F&O.
- Signal-engine **core** (`libs/strategy-engine`): `GateEvaluator` (all/any/not + expression + crossover), `CompositeScorer` (weighted normalized composite, required+optional tiers), `ExitEvaluator` (stop_loss→trailing_stop→take_profit→time_stop→signal_exit precedence, ATR-at-entry locking), `IndicatorRegistry` (EMA/SMA/RSI/VWAP/ADX/MACD/SUPERTREND/ATR/VWMA/PSAR + session-level + context indicators incl. `RS_VS_INDEX`), position-hold tracking (`barsInPosition`) with **no hard intraday cap**.
- Registry/versioning (`RegistryService`, `StrategyRepository`, V002) — slug-immutable, one `published` version, `published_version_id`, `tags[]`, hot-swap on `strategy.changed`.
- Signal persistence (V003 `signals` + V009 `scalper_detail` side-channel), paper ledger (V005 `paper_orders`/`paper_positions`), shadow book (V016/V017/V018), shared `LtpSlippageV1` FillSimulator (parity across backtest/paper/live).
- Backtest `ReplayEngine` + `TickwiseGoldenRunner` — generic bar-by-bar replay; `TickwiseGoldenRunner` already aggregates 1m→5m/15m/1h and handles a daily pre-close (btst) bar.
- React frontend patterns — `WatchlistsPage.tsx` (screener+list tabs, preset selector), `BreadthPage.tsx` (date + table + chart), `BacktestResultsPage.tsx` (tabbed detail drill-down), `DataTable` (TanStack Table v8), `MegaMenu` section nav, lightweight-charts overlays, `{items}` envelope hooks.

**⚠️ FALSE-POSITIVE GUARD — looks done, is NOT:**
1. **`rs_rank` preset ≠ Minervini RS-rank.** The existing `ScreenerService.rsRank()` is a *return percentile relative to the NIFTY-50 benchmark* over an *interim active-equities* set. Minervini's RS-rank is an **IBD-style cross-sectional 1–99 percentile of a weighted trailing relative strength across the whole ranked equity universe** (§4.10). Different definition, different universe. Must build the Minervini variant (MV-2.2); do **not** assume the preset satisfies gate 8.
2. **`fundamentals` table exists but has ZERO Java readers** and only CSV-backfilled rows. There is no live refresh, no `FundamentalsReader`, and it lacks **earnings-surprise and estimate-revision** entirely (Screener.in has neither — §9.5). Fundamentals presence in the schema ≠ fundamentals available to the screener.
3. **`candles_1d` cagg is SPARSE.** It aggregates the 1m `candles` hypertable, which holds only ~200 subscribed/pinned instruments — it does **not** contain the ~3.2k equity universe, and native `candles`@1d (the equity backfill target) does **not** flow into the cagg (they diverge for 1d per CLAUDE.md). Screening off `candles_1d` would silently return almost nothing. → MV-0.2 resolves the true dense source.
4. **No `session.style=swing`.** All 12 Siva scalpers are `intraday`/`expiry_day`; the paper account square-off (15:12 IST) force-closes positions. A multi-day hold has no lifecycle today (MV-7.1).
5. **Scalper infra is options-only.** `ScalperConfig`/`ScalperConfluenceGate`/`scalper_detail` are armed by `tags=['scalper']` + `universe.mode=options_of_underlying`. Minervini must **not** be tagged `scalper` and gets none of this — it needs its own equity path.
6. **Backtest engine is options-premium / intraday-shaped.** `OptionsPremiumReplay`, premium_pct exits, session square-off, `market-calendar` covers only 2024–2026. Swing-equity daily replay needs additive work + a calendar extension + golden re-baseline (MV-8).
7. **Equity is not subscribed live.** Kite feed is index/F&O-primary; there is no live intraday equity tick stream. The screener runs EOD; live swing entries fire on the daily bar / next-open, not intraday ticks (MV-7, MV-9).

---

## 3. Architecture decisions (with recommendations + open owner-decisions)

**A. Canonical dense daily source (BLOCKS all MA SQL).** Candidates: `nse_eod_bhavcopy` (deep, ongoing, ~3.2k/day) vs native `candles`@1d (backfilled ~200–300d, ~3500 equities). **Recommendation:** standardize on **`nse_eod_bhavcopy`** (per §13/§17.7 — deepest history, no retention cap, ongoing capture, the canonical Minervini input) and treat native `candles`@1d as a secondary warm store. **Confirm empirically in MV-0.2** (coverage counts); if bhavcopy lacks ≥252 sessions for the target universe, union with native `candles`@1d or extend the bhavcopy archive backfill first.

**B. Where the screener lives + its output schema (OD-5).** The producer `MinerviniScheduler` lives in **market-data-service** (per master-plan §13.5, alongside `ScreenerService`/`NseEodScheduler`), reading marketdata daily data. By the D10 single-writer rule its output table therefore belongs in the **marketdata** schema (`marketdata.minervini_screen_results`, with the CD-1 backtest SELECT grant). This **contradicts the authoritative §17.1** (strategy lineage), whose two reasons no longer hold (see §1). Rather than silently re-decide, the placement is escalated to **OD-5** (§9): either follow §17.1 (move the scheduler into strategy-signal-service too → strategy lineage) **or** ratify the marketdata placement (recommended — keeps writer + schema aligned). Do not write the migration until OD-5 is answered.

**C. Where entries/positions live.** Strategy-signal-service, as **strategy definitions** (registry rows, `category='MomentumTradingMarkMinervini'`, `tags=['equity','swing','minervini']`, never `scalper`), evaluated by the existing engine on a **daily primary bar**, persisted via the standard `signals` table with a new **`minervini_detail` side-channel** (mirrors the `scalper_detail` V009 pattern: setup type, stage, VCP footprint, pivot, gate booleans). Parity-safe additive. **Alerts (Phase 9) use the in-process event + `@EventListener` pattern** — the signals slice publishes a `MinerviniEntryFired` / candidate-buyable event and notifier listens; signals code **never imports notifier** (Modulith cycle rule; `DotInputAlert`/`DotAlertListener` is the template).

**D. Fundamentals + market-cap sourcing — RESOLVED via the Upstox Fundamentals API (ADR-0004).** The earlier "Upstox has none" claim was wrong. The **Upstox Company Fundamentals API** (8 endpoints, ISIN-keyed, on the analytics token we already fund) is the primary source:
- **Company Profile / Competitors** → sector classification + peers (§4.11 industry group).
- **Income Statement** → revenue, operating/net profit → sales growth + margin expansion.
- **Key Ratios** → EPS, **ROE** (§4.8 cutoff), **P/E** (§4.9 expansion), P/B (→ market cap).
- **Share Holdings** → promoter/FII/DII/public % → **free-float %** (your low-cap gate) + free-float market cap.
- **Corporate Actions** → dividends/bonus/splits/rights → CA-adjustment cross-check.

Wire a `UpstoxFundamentalsClient` (hand-rolled REST, ADR-0002 anti-corruption pattern, parallel to `UpstoxAnalyticsClient`) + a `FundamentalsService`/`FundamentalsReader` over the existing `fundamentals` tall table. ISIN comes free from the Upstox equity key `NSE_EQ|<ISIN>` (`UpstoxEquityMasterClient`). **Fallbacks:** the `openscreener` Screener.in scraper (master-plan §9) and the CSV backfill — kept as backup only. **Locked consequence:** earnings-surprise (§4.8) and estimate-revisions (§4.7 #6) stay **UNMODELED** (no Indian consensus feed) — fundamentals implements the **Code-33 spirit** (EPS+Sales+margin accel) + ROE only; missing data → `UNKNOWN` label, never a silent exclusion. Because the **low-cap gate depends on this feed**, fundamentals moves from "optional/default-off" to a **first-class required feed** (the EPS/sales *confirmation* filter can still be toggled; the market-cap/free-float *gate* is always on).

**E. Point-in-time / lookahead (prescriptive).** `fundamentals` is latest-restatement, not as-reported → **fundamentals data is NEVER used in any backtest.** It is **watchlist-only** (the Phase 3 filter feeds the Phase 4 UI + the live screen). The screener hit-rate harness (MV-8.1) and all swing backtests (Phase 8) use **price gates ONLY**. Lifting this ban would require a point-in-time fundamentals snapshot — out of scope.

**F. Parity.** All engine-path additions (new indicators, VCP gate, swing exits) compute deterministically inside `libs/strategy-engine` — no network hop in replay. Live-only cross-sectional RS-rank is injected as a **context value seeded from a fixture in replay** (same pattern as the scalper confluence gate being NEUTRAL in replay). Golden vectors for swing setups are a **new** fixture set (`golden-minervini/`), never mixed with the frozen scalper goldens.

**Open owner-decisions (do not block the plan; recommendation first) — tracked in §9:**
- OD-1 Universe scope: full NSE EQ (~3.5k) vs NIFTY 500 vs F&O-list. *Rec: NIFTY 500 when `index_constituents` populated, else full EQ with a liquidity pre-filter.*
- OD-2 Fundamentals source: openscreener appliance vs skip. *Rec: build openscreener behind the default-off flag; screener ships without it.*
- OD-3 Build depth now: Track A only, or A+B. *Rec: ship Track A end-to-end first (owner's core need), then reassess B against live screener value.*
- OD-4 Execution: manual (owner places orders) vs semi-auto via `OrderGateway`/OpenAlgo. *Rec: manual/paper first; live order routing is a separate gated deliverable (master-plan §17.3).*
- OD-5 Screen-results lineage (§3-B): marketdata (writer-aligned, recommended) vs strategy (per authoritative §17.1). *Rec: marketdata + explicitly ratify the §17.1 deviation; alternative = move the scheduler into strategy-signal-service and use the strategy lineage.*

---

## 4. Phased implementation plan

Legend: **Grounding** = REUSE/EXTEND/NEW. Ship phases in order; within a phase, items may parallelize unless `Depends` says otherwise.

### Phase 0 — Foundations & VERIFY gates (unblocks everything; mostly reads, little code)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-0.1 | Confirm live Flyway heads before allocating any version | REUSE | — | TODO | `ls deploy/flyway/{marketdata,strategy,backtest}` matches §5 (V030/V019/V007) | |
| MV-0.2 | **Pick canonical dense daily source** (§3-A). Run coverage counts on `nse_eod_bhavcopy` vs native `candles`@1d vs `candles_1d` cagg; standardize the MA SQL `FROM` | REUSE | — | TODO | `SELECT count(DISTINCT symbol), min(trade_date), max(trade_date) FROM nse_eod_bhavcopy` ≥1.5k symbols & ≥252 sessions; decision recorded in this row | |
| MV-0.3 | Universe-coverage audit: how many EQ symbols have ≥252 daily sessions (the SMA200 floor is 200; 52w window is 252) | REUSE | MV-0.2 | TODO | count of symbols with ≥252 sessions printed; drives OD-1 | |
| MV-0.4 | `market-calendar` multi-year extension IF any backtest predates 2024 or postdates 2026 (currently 2024–2026 bundled) | EXTEND | — | TODO | `MarketCalendarTest` asserts `coveredYears() ⊇` the backtest span; else the horizon-canary/500 fires | |
| MV-0.5 | Resolve OD-1..OD-5 with owner (record answers in §9) | — | — | BLOCKED | owner answers pasted into §9 | |
| MV-0.6 | **Upstox API inventory** for the equity pipeline — record which endpoints feed the data-load (equity daily OHLCV via `UpstoxEquityMasterClient` `NSE_EQ` keys + `UpstoxExpiredInstrumentsClient.activeCandles`); confirm Upstox exposes **no** quarterly-fundamentals/estimates endpoint (§3-D) | REUSE | — | TODO | `POST /api/v1/market/admin/equity-daily-backfill` on 3 symbols returns ≥252 daily candles; endpoints + the confirmed fundamentals gap recorded in Evidence | |

### Phase 1 — Data layer (equity daily coverage + fundamentals + RS precompute foundation)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-1.1 | Ensure the chosen dense daily source has the full target universe with ≥252 sessions (extend `EquityDailyBackfillService` history depth or bhavcopy archive backfill if MV-0.3 short) | EXTEND | MV-0.2/0.3 | TODO | coverage query ≥ target-universe count; no symbol in the universe below 252 sessions except genuine new listings | |
| MV-1.2 | `FundamentalsReader.java` in market-data-service — the missing reader over `fundamentals` (latest N quarters of EPS/Sales/OPM per symbol) | NEW | — | TODO | unit test seeds ≥5 quarters for 2 symbols → reader returns ordered latest-first EPS/Sales/OPM | |
| MV-1.3 | (OD-2) `openscreener` appliance (`tools/fundamentals-refresh/`, master-plan §9.1) — Playwright Screener.in scraper → `fundamentals` (`source='OPENSCREENER'`), single-threaded, politeness-throttled, `--dry-run` | NEW | OD-2 | DEFERRED | `python refresh.py --symbols RELIANCE,TCS --dry-run` prints normalized long rows with EPS/Sales/OPM; then a real run writes `source='OPENSCREENER'` rows | |
| MV-1.4 | Optional partial index on `fundamentals(symbol,metric,period_end DESC)` for scale reads (skip at single-owner scale) | NEW | — | DEFERRED | `flyway validate` green; only if screener reads are slow | |

### Phase 2 — Trend-Template + RS-rank daily screener (Track A core)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-2.1 | `TrendTemplateService.screen(asOf, Filters)` — the 8 gates as SQL window functions over the MV-0.2 source (SMA50/150/200, 52w hi/lo, slope-of-200 via `lag(sma200,21)`, `sessions>=200` guard, liquidity pre-filter) | NEW (SQL like `ScreenerService`) | MV-0.2, MV-1.1 | TODO | IT seeds ~210 daily rows for 3 synthetic symbols → 8 gate booleans + `gatesPassed` match hand-computed values; ZERO gateway-port calls | |
| MV-2.2 | **Minervini RS-rank** (§4.10, IBD-style): weighted trailing RS `0.4·r63+0.2·r126+0.2·r189+0.2·r252`, percentile 1–99 **cross-sectional across the EQ universe** (NIFTY excluded from the ranked set); gate 8 = `rsRank≥70` | NEW (distinct from `rsRank` preset — see FP-guard #1) | MV-2.1 | TODO | IT with a known return spread asserts the top decile ranks ≥90 and a 70-cutoff count; explicitly differs from `ScreenerService.rsRank` | |
| MV-2.3 | `vol_ratio` (today vs 50d avg) as a display/optional-filter field (not a gate) | NEW | MV-2.1 | TODO | field present; `minVolRatio` filter honored | |
| MV-2.4 | `minervini_screen_results` table (V031, §5) + `MinerviniScreenRepository.upsertAll/latest` | NEW | MV-2.1 | TODO | `\d minervini_screen_results`; `upsertAll` then `latest` round-trips; `flyway validate` green | |
| MV-2.5 | `MinerviniScheduler` — boot one-shot + `@Scheduled` after the 19:00 IST bhavcopy pull (fail-soft, never fatal), computes + persists the day's screen | NEW (mirror `NseEodScheduler`) | MV-2.4 | TODO | boot mock stack → log `minervini screen upserted N rows`; `SELECT count(*) FILTER (WHERE passes_all)` > 0 on a backfilled date | |
| MV-2.6 | `MinerviniController` — `GET /api/v1/market/screener/minervini` (persisted, params asOf/passesAllOnly/minRsRank/minGatesPassed/limit/offset) + `POST /run` (recompute); typed record, `{items,screenDate,coverage,limit,offset}`; decimals as JSON strings | NEW | MV-2.5 | TODO | PowerShell `Invoke-WebRequest` login→XSRF→GET returns ranked items with 8 gate booleans, decimals as strings | |
| MV-2.7 | **Gateway route allowlist** — add `/api/v1/market/screener/minervini` prefix to edge-gateway `Path=` allowlist; rebuild edge-gateway | EXTEND | MV-2.6 | TODO | live GET through the gateway returns JSON (not SPA index.html) | |
| MV-2.8 | Contract re-capture — `ContractCaptureTest -Dcontracts.capture=true`, regen `contracts/gen/*.d.ts`, `tsc --strict`; new endpoint returns a **typed record** (not `Map`) so `MapReturnRatchetTest` stays green | REUSE | MV-2.6 | TODO | ci-contracts diff non-breaking; `MapReturnRatchet` count unchanged | |
| MV-2.9 | **Stage 1–4 label** (§4.1) — derived from the Trend-Template gates (all 8 pass → Stage 2; price < declining 200d → Stage 4; else Stage 1/3); a cheap derived field, not geometry (moved here from Phase 5 per audit) | NEW | MV-2.1 | TODO | passing candidates classify Stage 2; a below-declining-200d name classifies Stage 4 | |

### Phase 3 — Fundamentals confirmation filter (optional, layered AFTER price gates)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-3.1 | Post-gate fundamentals filter in `TrendTemplateService` behind `artha.minervini.fundamentals.enabled=false`. **Code-33 (§4.8):** 3 consecutive quarters of *simultaneous* acceleration in EPS **and** Sales **and** net-margin → `code33=TRUE`; any-two accelerating → `PARTIAL`; else `FALSE`. Missing fundamentals → `UNKNOWN` (never excluded). Watchlist-only — never in backtest (§3-E) | EXTEND | MV-1.2, MV-2.1 | DEFERRED | IT seeds 4 quarters of an all-accelerating symbol → `code33=TRUE`; a decelerating one → `FALSE`; flag off → output byte-identical | |
| MV-3.2 | Surface fundamentals columns in the response + note surprise/estimate-revisions are UNMODELED | EXTEND | MV-3.1 | DEFERRED | response carries the fundamentals column group; doc note present | |

### Phase 4 — Frontend: screener page + candidate analyzer (Track A UI)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-4.1 | `MinerviniScreenerPage.tsx` (`pages/strategies/`) — clone `WatchlistsPage`/`BreadthPage`: asOf picker, `passesAllOnly` toggle, `minRsRank` slider, Run→`POST /run`; `DataTable` cols (Symbol, Close, %fromHigh, %aboveLow, SMA50/150/200 badges, RS-rank, vol×, 8 gate chips + `gatesPassed`); decimals via `core/decimal`; sortable by RS-rank | NEW (clone) | MV-2.6 | TODO | run on a recent date; 3–5 top names visibly above rising 50/150/200 MAs & near 52w high in a chart | |
| MV-4.2 | `api/minervini.ts` TanStack hooks (`useMinerviniScreen`, `useMinerviniCandidate`) + route + MegaMenu "Strategies → Minervini SEPA Screener" entry | NEW | MV-4.1 | TODO | nav entry routes; hook consumes `{items}` | |
| MV-4.3 | Row → add-to-watchlist (reuse `/api/v1/watchlists/{id}/items`) so a candidate flows into a tracked list | REUSE | MV-4.1 | TODO | add a candidate → appears in the watchlist | |
| MV-4.4 | `MinerviniCandidatePage.tsx` (analyzer, clone `BacktestResultsPage`) — tabs: Trend-Template (8 gates), Stage, VCP geometry, Fundamentals; lightweight-charts daily with 50/150/200 MA overlays + volume underlay; depends on the analyzer endpoint (MV-5.5) | NEW | MV-5.5 | TODO | per-candidate detail renders with MA overlays; gate/stage/VCP panels populated | |

### Phase 5 — VCP / base geometry + Stage detection (net-new detectors; Track B foundation)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-5.1 | Daily swing-high/low (zig-zag) extractor over a symbol's daily series — the primitive every base detector needs | NEW | MV-1.1 | TODO | unit test on a synthetic series returns expected pivots at set thresholds | |
| MV-5.2 | **VCP detector** (§4.5/§4.6/§6.2): 2–6 contractions each ~½ the prior, volatility+volume contract left→right, final-contraction volume < 50d avg with 1–2 very-low days, pivot = tightest-contraction high; emits Technical Footprint `[W] [deep%/tight%] [count]T` | NEW | MV-5.1 | TODO | detector reproduces the doc's canonical `40W 31/3 4T` on a constructed fixture; rejects a V-shaped/no-right-side base | |
| ~~MV-5.3~~ | Stage label **moved to MV-2.9** (Phase 2 — it is a derived label, not geometry) | — | — | N/A | see MV-2.9 | |
| MV-5.4 | Persist per-candidate geometry — extend `minervini_screen_results` (or a sibling `minervini_setups` table) with stage (MV-2.9), footprint, pivot, base depth/duration, shakeout flags, base-count | NEW | MV-5.2, MV-2.9 | TODO | geometry columns populate for passing candidates | |
| MV-5.5 | Analyzer endpoint `GET /api/v1/market/screener/minervini/candidate/{symbol}` — Trend-Template booleans + Stage + VCP footprint + pivot + fundamentals grade (feeds MV-4.4) | NEW | MV-5.4 | TODO | endpoint returns the full analyzer payload for a passing symbol; typed record; gateway-allowlisted | |

### Phase 6 — Per-setup entry signals (the six §6 setups as strategy definitions; Track B)

Each setup is a registry strategy (`category='MomentumTradingMarkMinervini'`, `tags=['equity','swing','minervini','<key>']`) whose gate tree encodes §6's entry_conditions, referencing the Phase-5 geometry + Phase-2 gates as engine inputs.

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-6.1 | New engine indicators: `WEEK52_HIGH`/`WEEK52_LOW`(252), a `VCP_PIVOT`/`VCP_STAGE` context value seeded from Phase-5, and an `RS_RANK_PCT` context value (cross-sectional, seeded from the screener; NEUTRAL in replay) | EXTEND `IndicatorRegistry` | MV-2.2, MV-5.2 | TODO | `IndicatorVectorTest`-style frozen vectors for the new indicators; parity holds | |
| MV-6.2 | `session.style='swing'` in the strategy schema (daily primary, no square-off, multi-day hold) + engine handling | EXTEND (schema + `TickwiseGoldenRunner`/`SignalEngine`) | — | TODO | a swing strategy holds a position across ≥2 sessions in a golden replay without square-off | |
| MV-6.3 | `vcp` setup definition (breakout above pivot on expanding volume, don't-chase >~10% extended, 20d-MA post-breakout health) | NEW YAML | MV-6.1, MV-6.2 | TODO | one-signal golden fixture fires the entry at the pivot breakout on a constructed base | |
| MV-6.4 | `cheat_3c` setup (A→B→C→D turn, buy above the pause high, tight stop, partial start) | NEW YAML | MV-6.3 | TODO | golden fixture fires above the cheat pause high | |
| MV-6.5 | `power_play` setup (thrust +100%<8wk, tight ≤20–25% flag 3–6wk, VCP-mandatory, buy the turn) | NEW YAML | MV-6.3 | TODO | golden fixture fires on the flag breakout | |
| MV-6.6 | `primary_base` setup (post-IPO first base, ≥3–5wk, ≤25–35% depth, new-high breakout) | NEW YAML | MV-6.3 | TODO | golden fixture fires on the primary-base breakout | |
| MV-6.7 | `sepa` funnel meta-strategy — convergence of Trend-Template pass + RS-rank + a valid base + market-regime gate (MV-6.9) + *optional* fundamentals; emits the ranked "immediately buyable / on-deck / watch" 3-list (§2.10) | NEW | MV-6.3, MV-6.9 | TODO | funnel produces the 3-list on a screen date | |
| MV-6.8 | `minervini_detail` side-channel (strategy lineage) — setup type, stage, footprint, pivot, gate booleans (mirrors `scalper_detail` V009) | NEW | MV-6.3 | TODO | a fired signal carries `minervini_detail`; non-minervini signals carry null | |
| MV-6.9 | **Market-regime + industry-group + catalyst gates** (§4.11/§4.12): (a) market-direction context from `BreadthService` (new-52w-high vs new-low trend + up/down-volume ratio) + index Stage → a favorable/hostile regime value; (b) leading-industry-group membership via `index_constituents`/a sector map as a screener label/gate; (c) catalyst as **optional manual metadata** (no structured Indian catalyst feed exists) | EXTEND (reuse `BreadthService`) | MV-2.9 | TODO | regime value flips hostile when new-lows lead; a leading-group candidate is labelled; catalyst is a free-text/manual field | |

### Phase 7 — Swing position lifecycle: paper-trade + risk framework (Track B)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-7.1 | Swing position lifecycle — paper positions that hold across days without square-off (relax/disable the 15:12 square-off for `session.style=swing`); overnight mark-to-market | EXTEND paper ledger | MV-6.2 | TODO | a swing paper position survives EOD and re-prices next session; no forced square-off | |
| MV-7.2 | §2 risk framework — initial stop ≤½ avg-gain capped 10% (7–8% practical), breakeven move at ~3× risk, position sizing (ROTE 1.0–1.5%/2.5% max), pyramid-up/never-average-down | EXTEND (position sizing mode = capital%/notional with stop-distance) | MV-7.1 | TODO | sizing test: 25%×5% stop = 1.25% ROTE; stop moves to breakeven at 3×R in a scripted path | |
| MV-7.3 | **Staggered stops** (§2.2) — partial closes at tiered stops (e.g. 4% half + 8% half ≈ 6%); a staggered-stop executor (multi-leg partial close, not single-precedence full close) | EXTEND `ExitEvaluator` | MV-7.2 | TODO | a scripted decline partially closes at each tier, not all-at-once | |
| MV-7.4 | **Sell-into-strength / scaled profit exit** (§2.6/§3.6) — new exit rule type (tiers `[{profit_pct, qty_pct}]`), free-roll (sell half at 2–3R), 50-day-MA trailing stop | EXTEND schema + `ExitEvaluator` | MV-7.2 | TODO | scaled-exit test books partial profit at the tier and trails the remainder on the 50d MA | |
| MV-7.5 | Auto-paper the fired swing signals into the swing book (mirror the existing auto-paper wiring) | REUSE | MV-7.1, MV-6.3 | TODO | a fired `vcp` signal opens a swing paper position | |

### Phase 8 — Backtest / forward-test (Track B validation)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-8.1 | **Screener hit-rate harness** (§13.8) — point-in-time `screen(asOf)` over a historical range (weekly), forward returns at +5/+10/+21/+63 sessions vs NIFTY, hit-rate + excess-return per horizon; **price-gates only** (no lookahead fundamentals — §3-E) | NEW (read-only SQL, market-data-service) | MV-2.1 | TODO | run over 1–2y → hit-rate + excess-return table; plausibly >50% in a trending window; survivorship caveat surfaced | |
| MV-8.2 | Swing-position backtest — feed the Phase-6 setups through `ReplayEngine` on a **daily-bar primary** (new `Duration.ofDays(1)` aggregation), holding days→months; equity-class costs | EXTEND `ReplayEngine`/`TickwiseGoldenRunner` | MV-6.3, MV-0.4 | TODO | a `vcp` strategy backtests over a date range producing multi-day trades; determinism + parity green | |
| MV-8.3 | New golden fixture set `golden-minervini/` for swing setups (separate from frozen scalper goldens); `BacktestParityTest` extended | NEW | MV-8.2 | TODO | golden determinism + byte-string parity for a swing setup | |
| MV-8.4 | Equity-class `CostConfig` for daily-bar swing (brokerage/STT/stamp for delivery equity; slippage bps) | EXTEND `CostConfigResolver` | MV-8.2 | TODO | run reflects delivery-equity charges, not option lots | |

### Phase 9 — Live operation + selling discipline + alerts (Track B)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-9.1 | Live swing signal generation on the daily bar (screener → sepa funnel → published swing strategies → daily-bar entry eval); no intraday equity tick needed | REUSE engine | MV-6.7, MV-7.1 | TODO | on a live session, a qualifying candidate fires a swing ENTRY at/after the daily close/next-open | |
| MV-9.2 | **Selling discipline** (§3.6) — defensive (stop, close<20d-MA post-breakout, Stage-3/4 exit) + offensive (climax sell-into-strength, P/E-expansion + late base-count cues) as exit rules/gates | NEW YAML/gates | MV-7.4 | TODO | a scripted Stage-3 top triggers a defensive reduce; a climax run triggers sell-into-strength | |
| MV-9.3 | Daily sell-decision list (§2.10 triad / §3.6.D) — for every open swing position, daily "would I buy now / why holding / where am I a seller" report | NEW | MV-9.1 | TODO | daily report lists each open position with the triad | |
| MV-9.4 | Notifier alerts — pivot pre-alert + stop alert + "candidate now immediately-buyable" push (reuse the notifier event→listener pattern; never import notifier from signals) | REUSE | MV-6.7 | TODO | a candidate crossing into buyable pushes a notification (flag-gated) | |
| MV-9.5 | (OD-4) Live order routing via `OrderGateway`/OpenAlgo — separate gated deliverable; manual/paper until latency-gated | DEFERRED | OD-4 | DEFERRED | (out of scope until owner opts in) | |

### Phase 10 — Analyzers & reporting (Track B polish)

| ID | Item | Grounding | Depends | Status | Verify | Evidence |
|---|---|---|---|---|---|---|
| MV-10.1 | Post-analysis / trade grading (§2.11 Report Card: entry 60/20/15/5, exit-profit 70/15/15, exit-loss 65/20/15) over closed swing trades | NEW | MV-7.5 | TODO | closed swing trades produce a Report-Card distribution | |
| MV-10.2 | Self-measurement (§2.9) — per-strategy avg win/loss, batting average, expectancy, avg hold-time; RBA stop calibration | NEW | MV-7.5 | TODO | stats computed strategy-specific (never blended) | |
| MV-10.3 | Screener/analyzer frontend polish — Stage/VCP/base-count visualizations, footprint notation, RS-line chart | NEW | MV-5.5, MV-4.4 | TODO | analyzer renders the footprint + RS-line | |

---

## 5. Data model (new tables/columns + lineage)

**Live Flyway heads (2026-07-04):** admin `V001` · marketdata `V030__backfill_jobs` · strategy `V019__bot_commands_audit` · backtest `V007__runs_unique_job`. **Re-verify before writing (MV-0.1).** Applied migrations are checksum-locked → new suffix-versioned files only.

| New object | Lineage / next-free | Purpose | Item |
|---|---|---|---|
| `minervini_screen_results` | **marketdata / V031** | daily screen output: `(screen_date, symbol)` PK, close, sma50/150/200, high/low_52w, pct_from_high, pct_above_low, rs_rank, vol_ratio, gate1..8, gates_passed, passes_all, computed_at; index `(screen_date, passes_all, rs_rank DESC)` | MV-2.4 |
| geometry columns / `minervini_setups` | marketdata / V032 | stage, footprint (`weeks/deep%/tight%/count`), pivot, base depth/duration, shakeout flags, base_count | MV-5.4 |
| `fundamentals` partial index | marketdata / V033 *(optional)* | `(symbol, metric, period_end DESC)` for scale reads | MV-1.4 |
| `minervini_detail` side-channel | **strategy / V020** | per-signal setup detail (setup type, stage, footprint, pivot, gate booleans) — mirrors `scalper_detail` V009 | MV-6.8 |
| swing position support | strategy / V021 *(if a schema change is needed)* | `session.style='swing'` position lifecycle fields / no-square-off flag | MV-7.1 |
| swing backtest goldens | (test resources, not DB) | `golden-minervini/` fixture set | MV-8.3 |

> `fundamentals` (V017) and `nse_eod_bhavcopy`/`bse_eod_bhavcopy` (V014/V021) already exist — **no new table** for daily prices or fundamentals; reuse them.
>
> **OD-5 alternative (strategy-lineage) numbering:** the allocations above assume the recommended marketdata placement. If OD-5 chooses the strategy lineage instead, `minervini_screen_results` takes `strategy/V020` and the rest shift down one (`minervini_detail`→V021, swing support→V022) — re-verify the strategy head (currently V019) before writing to avoid a collision.

---

## 6. API surface

| Method + path | Returns | Item | Notes |
|---|---|---|---|
| `GET /api/v1/market/screener/minervini` | `{items,screenDate,coverage,limit,offset}` | MV-2.6 | persisted fast path; typed record; gateway-allowlisted |
| `POST /api/v1/market/screener/minervini/run` | fresh screen result | MV-2.6 | recompute-now |
| `GET /api/v1/market/screener/minervini/candidate/{symbol}` | analyzer payload (gates+stage+VCP+fundamentals) | MV-5.5 | feeds the analyzer page |
| `POST /api/v1/market/screener/minervini/backtest` | hit-rate + excess-return per horizon | MV-8.1 | price-gates only (no lookahead) |
| (reuse) `POST /api/v1/watchlists/{id}/items` | — | MV-4.3 | candidate → watchlist |
| (reuse) `/api/v1/strategies` + publish | — | Phase 6 | swing strategy registry |

Every new endpoint returns a **typed record** (never `Map<String,Object>` — `MapReturnRatchetTest`), drifts the springdoc snapshot (re-capture, MV-2.8), and needs the edge-gateway `Path=` allowlist (MV-2.7).

---

## 7. Cross-cutting concerns

- **Parity:** engine-path additions compute in `libs/strategy-engine`; cross-sectional RS-rank + live-only signals are NEUTRAL/fixture-seeded in replay; swing goldens are a separate frozen set (never mix with scalper goldens).
- **Contracts/CI:** re-capture springdoc on each new endpoint; typed records only; market-data + strategy CI shards already exist (no new shard needed — but a new service would need one).
- **Build:** full reactor `-am` (`./mvnw -pl services/<svc> -am ...`), never bare `-pl`.
- **Testing:** ITs named `*Test`/`*IntegrationTest` (no failsafe); share the singleton DB with no per-method cleanup → unique symbols/dates per method; hand-computed fixtures for the 8 gates + RS percentile.
- **IST/time:** filter daily rows by explicit `+05:30` bounds; SMA/52w windows over `trade_date`; in-container `now()`/`::date` is UTC (off-by-one across IST midnight).
- **Migrations:** checksum-locked; new suffix-versioned files; use the `new-migration` skill.
- **Risk / correctness:** the plan touches money/rounding (decimals as JSON strings, `core/decimal`), point-in-time lookahead (fundamentals in backtest), and survivorship bias (bhavcopy has no membership history) — each flagged at the relevant item.

---

## 8. Testing & acceptance strategy

- **Track A acceptance (the owner's core win):** run the screener on a recent date; independently confirm 3–5 top-ranked names are visibly above rising 50/150/200-day MAs and near their 52-week high on a chart (the manual-chart-reading fallback doubles as the acceptance check). Hit-rate harness (MV-8.1) shows >50% in a trending window.
- **Track B acceptance:** each setup has a one-signal golden fixture; swing backtest produces multi-day trades with determinism+parity; paper book holds positions across sessions; selling discipline exits on scripted Stage-3/climax paths.
- **Every phase is independently demoable** — Track A (Phases 1–4) delivers standalone value even if Track B never ships.

---

## 9. Deferred / out-of-scope / owner-gated (explicit — prevents re-flagging)

**Owner decisions (answer, then unblock):**
- **OD-1 Universe scope** — **ANSWERED 2026-07-04:** full NSE EQ; RS-rank across the liquidity-filtered set; NIFTY-500 toggle default OFF. Low-cap-only (see OD-2). → §0.5 #1, ADR-0005.
- **OD-2 Fundamentals source** — **ANSWERED 2026-07-04:** Upstox Company Fundamentals API (analytics token), *not* the Screener.in scraper. Also sources the market-cap / free-float low-cap gate. → §0.5 #3, ADR-0004.
- **OD-3 Build depth** — **ANSWERED 2026-07-04:** Track A candidates **+** Track B all-setups pass/fail+reasoning alongside, + Track-B paper for reliability; manual execution until proven. → §0.5 #6.
- **OD-4 Execution** — **ANSWERED 2026-07-04:** manual now; semi-auto (OpenAlgo) a later gated decision. → §0.5 #7.
- **OD-5 Screen-results lineage** — **ANSWERED 2026-07-04:** **marketdata** (writer-aligned; scheduler lives in market-data-service). §17.1 deviation ratified in ADR-0005. → §0.5 #16.

**Structurally deferred (documented, not gaps):**
- Earnings-surprise + estimate-revisions (§4.8/§4.7) — **no free Indian source**; unmodeled; fundamentals filter is Code-33-spirit only.
- Short side / Stage-4 shorting — method is long-only; not built.
- US equities — owner-locked never.
- Live intraday equity ticks — not subscribed; swing operates on daily bars / next-open.
- Live order execution — manual/paper until OD-4 + a latency gate.

**Gated follow-on (planned, NOT out-of-scope):**
- **Phase 9 (live operation + selling discipline)** is `TODO` (planned) but must not go LIVE until Phase 8 backtest validation + an owner hit-rate sign-off. Track A (screener) and Phase 7 (paper) ship first; live is the last gate.

---

## 10. Critical path & sequencing

```
MV-0.2 (dense source) ─┬─> MV-2.1 (8 gates) ─> MV-2.2 (RS-rank) ─> MV-2.4/2.5/2.6 (persist+API) ─> MV-2.7/2.8 (gateway+contract) ─> MV-4.1/4.2/4.3  [TRACK A SHIPPABLE]
                       └─> MV-1.1 (coverage)
MV-1.2 (fund reader) ─> MV-3.1 (fund filter, optional)
MV-2.9 (Stage label, in Phase 2) ─┐
MV-5.1 (zig-zag) ─> MV-5.2 (VCP) ──┴─> MV-5.4/5.5 ─> MV-4.4 (analyzer)
MV-6.1/6.2 (indicators + swing style) ─> MV-6.3 (vcp entry) ─> MV-6.4..6.8 ─> MV-7.x (paper) ─> MV-8.2 (swing backtest) ─> MV-9.x (live+selling) ─> MV-10.x (analyzers)
```

**Minimum viable slice:** MV-0.2 → MV-2.1 → MV-2.2 → MV-2.4/2.5/2.6 → MV-2.7/2.8 → MV-4.1 = a working daily Minervini screener the owner uses to find names. Everything else is additive.

---

## 11. Changelog / progress log

| Date | Item(s) | PR / SHA | Note |
|---|---|---|---|
| 2026-07-04 | plan created | (this doc) | Full plan drafted. No code yet. |
| 2026-07-04 | 4-critic audit applied | (this doc) | Grounding + version/path critics: **0 findings** (factual base verified against code). Applied completeness + design-authority fixes: MV-0.6 (Upstox API inventory), MV-2.9 (Stage label moved from Phase 5→2), MV-6.9 (market-regime + industry-group + catalyst, reuses `BreadthService`), sharpened Code-33 (MV-3.1), **OD-5** (screen-results lineage vs authoritative §17.1, engaged not silently overridden), notifier event-listener wiring, prescriptive no-fundamentals-in-backtest, US→India scope note; reclassified sequenced Track-B rows `DEFERRED`→`TODO` (parked ≠ later). |
| 2026-07-04 | `/grill-with-docs` — OD-1..OD-5 + 9 decisions locked | (this doc) | Owner grill resolved every open point → new §0.5 decision block + config-defaults table; OD-1..OD-5 answered in §9. Key changes: **fundamentals + market-cap now via the Upstox Fundamentals API** (reverses master-plan §9 → [ADR-0004]) — dissolves the "biggest gap"; **universe = full NSE EQ, low-cap-only** hard gates (free-float mcap <₹5,000cr, free-float% <35%, no F&O, price>₹30, turnover ≥₹37.5L/day → [ADR-0005]); Minervini concentration (pilot 5–6% → 20–25%/name, 4–8 names); Track B = all setups pass/fail+reasoning + paper reliability bar (≥30–50 trades, 2:1, ~50%); **deep 3–5y CA-adjusted backfill is a prerequisite**; corporate-action adjustment surfaced; single 7–8% stop + 50d-MA trail. |

<!-- Append one row per shipped item. Update the item's Status + Evidence in place in §4. -->
