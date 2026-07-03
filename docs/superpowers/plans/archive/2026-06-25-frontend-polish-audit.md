# Frontend polish audit — hero-standard coverage + beautification plan

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


**Date:** 2026-06-25 · **Method:** 11 parallel section auditors → synthesis → completeness critic
(workflow `frontend-polish-audit`, 13 agents). Coverage cross-checked against a `git grep` ground truth.

> **STATUS: SHIPPED + DEPLOYED 2026-06-25.** All 5 batches A–E merged (PRs #180–#189, every one
> ci-react + e2e + axe green) + deployed. Coverage moved **QueryState 31→51**, **Motion (LoadBeat)
> 5→65** (every routed page; the ~6 non-page panels/login excepted by design). Added `EmptyCard` +
> `WarningBanner` atoms, radial-mesh empties, global card/row hover (via `index.css`), responsive
> chart heights (11 charts), 8 mobile-input fixes, tab cross-fade, sticky-thead scroll-shadow. Also
> fixed the mis-scoped Prettier hook (#181). **Deferred (note-only):** migrating the ~5 hand-rolled
> `<table>`s (BacktestCompare matrix, Jobs, PaperBookPanel, ScalperCockpit positions, Signals) to
> `DataTable` for mobile card-mode — left to avoid columnheader-e2e risk. The completeness-critic
> items (all-5-themes contrast, keyboard-nav, bundle/CLS) remain un-audited — a possible future pass.

## 1. Coverage scorecard (71 routed pages + panels)

| Dimension | Count / Total | Verdict |
|---|---|---|
| **PageHeader** signature | 66 / 71 | **Done** — the 5 "missing" are embedded panels/modals (PaperBookPanel, SignalTakeTicket, SignalsFeedPanel, ReasoningBreakdown, FoldDrilldownModal) + LoginPage, all header-less by design. Effectively 66/66 routed pages. |
| **QueryState** (loading/error/empty) | 31 / 71 | **Weak** — pages without it render an upstream 500 as "empty" (the operator can't tell dead-service from no-data). |
| **Skeleton** (cold-load) | 32 / 71 | Weak — tracks QueryState; missing → layout-shift / bare "Loading…" text. |
| **Motion** (LoadBeat / Beat*) | 5 / 71 | **Critical gap** — only Dashboard, Paper, Orders, FiiDii-Capital, OptionsChain animate. 66 pages snap in. |
| **LiveDot** | 4 / 71 | OK — only live surfaces need it. |

## 2. Rollout gaps (the hero treatment NOT applied everywhere — consistency)

### 2a. QueryState (correctness, not just polish) — highest severity
Without it, a 500 falls through to the empty branch → silent blank. Worst offenders (no QueryState AND no
skeleton): `dataops/StatusPage` (oiQ/quotaQ unwrapped), `equity/NewsPage` (3 ad-hoc `if`s, no error UI),
`scalper/CockpitPage` + `ScalperCockpitPage` (per-panel `chain==null && !isLoading`), all 5 options-charts +
7 options-stats pages, `futures/FuturesOiChartPage` + `OiBuzzPage`, `backtests/{Compare,Runner,Jobs}` +
`ChartsPage`. Fix = swap each `if`-gate for `<QueryState query={q} skeleton={…}>`. Mechanical, S–M/page.

### 2b. Motion / LoadBeat (66 pages)
Biggest *visible* inconsistency. Fix by archetype: metric-strip pages → `<LoadBeat>` + `<BeatStrip><BeatItem>`;
dual-table/quadrant → BeatStrip around the grid; single chart/table → one `<BeatBlock>` fade-up; multi-chart
grids (`IntervalWiseOiPage` 6-card, `FiiDerivativeStatsPage` 4-card, `VixIndexPage`) → per-card BeatItem stagger
(highest payoff). Note: `BacktestResultsPage` has QueryState+Skeleton but NOT the LoadBeat wrap — a "half-hero",
finish it.

### 2c. Skeleton fidelity (39 without)
Content-shape every skeleton (`metric-strip cols=N`, `chart-block height=H`, `table-rows`). Replace bare
"Loading…" text (`JobsPage` L146, `SignalsFeedPanel` L83, `ExportWizardPage`). Rides along with 2a.

### 2d. Metric-strip card framing (~15 pages)
Bare `<Metric>` atoms with no card/shadow vs the hero `card shadow-e1` tile. Wrap each strip
(`div.card.shadow-e1`). S effort, big cohesion payoff; bundle with 2b.

## 3. Net-new beauty / layout / responsive / animation

1. **Dense-table mobile variants** — ~14 tables (7–16 col) only horizontal-scroll at 480px. Build ONE reusable
   `DataTable` mobile card-mode + `hideOnMobile` column-priority. Highest responsiveness debt.
2. **Empty-state treatment** — bare `<p>` empties everywhere. One `EmptyCard(icon,title,hint)` with a subtle
   radial-mesh bg + actionable hint; split ambiguous empties (e.g. "EOD only" vs "pick an underlying").
3. **Card depth + hover micro-interactions** — `hover:shadow-e2 transition-shadow` + `focus-visible:ring` on
   KPI/stat cards; `tr:hover bg-surface-2` on every data table. App-wide affordance upgrade, cheap.
4. **Responsive chart heights** — fixed `h-[440px]`/`520px`/`460px` overflow S24; convention `h-64 sm:h-80
   lg:h-[Hpx]`. Wrap bare charts in `card shadow-e1`; drop the dup manual legend on `OptionsPremiumPage`.
5. **Page-enter + list-stagger + tab cross-fade** — list-stagger on feed/row surfaces; opacity cross-fade on
   tab switch (BacktestResults overview↔trades↔folds↔mc, Runner backtest↔sweep). All reduced-motion-gated, ≤200ms.
6. **Sticky thead + scroll-shadow** on tall tables (FuturesEod, PaperBookPanel, BacktestResults trades, Jobs).
7. **Section-heading + responsive-grid conventions** — `text-h3` left-aligned (not `text-sm text-center`) +
   add the missing `md:` grid step on ~8 options-stats/equity pages.
8. **Fixed-width input → `w-full sm:w-56`** — ~9 pages have inputs that overflow at 480px (visible mobile bug).
9. **Token hygiene** — `SectorHeatmapPage` raw `#ffffff` chart label; extract a `WarningBanner` atom; `nums`
   class consistency.

## 4. Prioritized plan

**Consistency (close the gap to hero):**
- **PR-A — QueryState + Skeleton rollout** (fixes "500 looks empty" on ~40 pages; lead with the crash-risks
  StatusPage / NewsPage / scalper cockpits). High (correctness). Ship first — it's also the prereq for clean skeletons.
- **PR-B — Motion + card-framing + section-headings** ("everything now matches the heroes"). High. Do multi-chart grids first.

**Net-new beauty:**
- **PR-C — EmptyCard + radial-mesh + input responsiveness + hover + token hygiene** (the "mobile + delight" pass).
- **PR-D — `DataTable` mobile card-mode + responsive chart heights** (heaviest net-new; one primitive, 14 adopters).
- **PR-E — list-stagger / tab cross-fade / sticky-thead polish**.

## 5. Caveats (completeness critic — verify these before/while implementing)

- **e2e / axe regression risk** — adding section headers, card-wraps, and aria-affecting motion can break
  Playwright `getByRole({name,exact})` + axe (CLAUDE.md's exact known trap). **Re-run ci-e2e + axe per PR**;
  keep every `data-testid` / asserted role+name; preserve one `<h1>`/page.
- **Shared primitives un-audited** — the audit scored *pages* only. Audit `QueryState`/`Skeleton`/`LoadBeat`/
  `DataTable`/`Metric`/`LiveDot`/`PageHeader` themselves first — a bug there propagates to all 71 pages.
- **All 5 themes + WCAG contrast** — not scored; verify hardcoded colours + `text-warn`/muted on each theme shell.
- **Reduced-motion** — assert the gate actually holds across the 66 motion rollouts.
- **CLS / bundle** — dimension-match skeletons to avoid layout-shift; watch motion bundle weight.
