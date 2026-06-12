# ArthaYantra 2.0 — Stage E: Frontend UX

**Stage letter / name:** E — Frontend UX
**Plan macro-phase:** Phase 4 (frontend dashboard + strategy/backtest UX)
**Phases covered:** 35–41 + 40A/40B/40C (dashboard/jobs/watchlists/settings · strategy editor + quick backtest · versions/diff/publish + stress advisory · backtest runner/results/comparison · sweep explorer + leaderboard · 40B (indicator-series endpoint [A13, 2026-06-12]) · 40 (lightweight-charts charts page + containment) · 40C (chart toolbar, overlays & persistence [A13, 2026-06-12]) · 40A (chart-context drill-down: signals & trades on charts [FP-67, owner selection 2026-06-12]) · signal notifier). Build order within the stage: 35 … 39, 40B, 40, 40C, 40A, 41.
**Prerequisite stages:** A (foundations: gateway, WS bridge, mock feed), B (market-data spine: candles, instruments, options, system status), C (strategy engine + signals + Angular scaffold/app-shell/login/WS client/E2E harness — the MVP gate), D (backtest + optimizer: jobs, results, folds, regime/stress guards, leaderboard, promote)
**Common reference:** [ARTHAYANTRA_2_COMMON_REFERENCE.md](ARTHAYANTRA_2_COMMON_REFERENCE.md) — cite COMMON for ADR D-decisions, the canonical stack-version table, the error-code taxonomy (COMMON §8.3), repo layout, the CD-1..CD-17 defaults (COMMON §4), the phase index (COMMON §5), the §17.3 revisit-trigger table, and the timeline ledger.

**Stage goal.** Make the whole platform usable from a browser. Stage C already shipped the Angular scaffold, app shell, login, the typed WS client, and a deliberately minimal signals page (the MVP). Stage E builds out every remaining daily-driver surface on top of the already-existing backend endpoints and WS topics (plus one new Stage-E backend deliverable: the Phase 40B indicator-series endpoint [A13, 2026-06-12]) — dashboard widget grid, jobs monitor, watchlists/screener, settings, the Monaco strategy editor with schema validation + quick backtest, the version/diff/publish lifecycle UI with the S1C advisory stress panel, the backtest runner/results/comparison drill-downs with the walk-forward fold panel, the live ECharts sweep explorer and the guard-complete optimization leaderboard, the chart surface — 40B (indicator-series endpoint [A13, 2026-06-12]) · 40 (lightweight-charts charts page + containment) · 40C (chart toolbar, overlays & persistence [A13, 2026-06-12]) · 40A (chart-context drill-down: trade/signal marks + deep links [FP-67, owner selection 2026-06-12]) — behind a lint-enforced containment boundary, and finally the ntfy/Telegram signal notifier (a backend Modulith module in strategy-signal-service plus its UI controls). Build order within the stage: 35 … 39, 40B, 40, 40C, 40A, 41. The 2026-06-12 owner feature selection adds Phase 40A — chart-context drill-down: trade/signal marks + deep links on the charts page [FP-67, owner selection 2026-06-12] — and extends Phases 35/38/40+40C (INDIA VIX card + reserved global-risk settings slot, Monte Carlo + benchmark-relative analytics, 1w resolution — datafeed core in Phase 40, interval picker in Phase 40C [A13, 2026-06-12]) [FP-8, FP-14, FP-31, FP-32, FP-42, owner selection 2026-06-12]. Every phase here is verifiable end-to-end on the credential-free mock stack. Stage E is the plan's Phase 4; its exit gate is the plan §15.2 Phase-4 row, mirrored as a checklist in `PHASE_GATES.md` (inlined at the end of this file).

---

# Part 1 — Design reference (inlined source content this stage needs)

> Provenance breadcrumbs in brackets, e.g. `[plan §4.5]`, point at the four now-deleted source docs. The content is reproduced here in full; no source doc need be opened. ADR decision texts (D1–D18) and amendments A1–A13, the error taxonomy, the CD-defaults, and the §17.3 revisit table live in [COMMON](ARTHAYANTRA_2_COMMON_REFERENCE.md) and are cited rather than repeated except where a phase consumes them directly.

## E-1. Frontend architecture baseline (carried from Stage C, restated for this stage) [plan §4.1–§4.3]

The framework decision (ADR **D1** — COMMON §6) is **Angular 21.x, standalone components only, signals-first, zoneless change detection, TypeScript 5.9 with `strict` + `strictTemplates`**, built with Angular CLI 21's esbuild `application` builder (D5). What failed in v1 was not Angular but how it was used; Stage E inherits the mandated idioms:

| Concern | 2.0 mandate (binding on every Stage-E phase) |
|---|---|
| Change detection | Zoneless by design; all template state is `signal()`/`computed()`; **zero manual CD calls** — `cdr.markForCheck()` anywhere is a FAIL |
| State | `@ngrx/signals` 21.x SignalStore per domain (D3); polling only where no WS topic exists |
| PrimeNG | Real component usage: DataTable (virtual scroll), Select, DatePicker, InputNumber, Toast, ConfirmDialog, Tabs, Skeleton, Tag, Splitter, Dialog, Drawer (D2) |
| Typing | DTO interfaces generated from the OpenAPI 3.1 specs (Stage C §24, `openapi-typescript`); `any` banned by ESLint `no-explicit-any` |
| Environments | `environment.ts` + CLI dev proxy to `127.0.0.1:8080`; relative `/api/v1/...` in production — never hardcoded `localhost:8080` |
| Transport | STOMP over **native WebSocket** (D9); SockJS and `window.global` polyfills deleted |
| Decimal | Prices arrive as JSON **strings** from the DTO layer; formatted/compared via a thin decimal utility — **never `parseFloat` for arithmetic** |

**State management architecture (D3) [plan §4.2].** One `@ngrx/signals` 21.x **SignalStore per domain**, provided at root, consumed via `inject()`. RxJS 7.8 survives only at the WebSocket edge and inside `HttpClient`; everything a template reads is a signal. The store catalog (Stage-E phases complete the ones marked here):

| Store | Server-cache state | Client/UI state | Live WS feed | Completed in |
|---|---|---|---|---|
| `MarketStore` | Instrument search results, candle metadata, market/Kite connection status | Selected instrument, interval, watchlist layout | `ticks.{exchange}.{tradingsymbol}` (per-symbol topics); `/topic/system` connection-status deltas | Phase 35 |
| `OptionsStore` | Chain snapshot for selected underlying+expiry (IV, OI, Greeks, PCR) | Underlying/expiry/strike-window filters, ITM/OTM highlighting | `options.chain` deltas | Stage F |
| `StrategiesStore` | Strategy list, versions, diffs, JSON Schema for `strategy-schema/v1` | Monaco draft buffer, dirty flag, validation markers, editor mode (YAML vs form) | — | Phase 36 |
| `SignalsStore` | Signal history pages (limit/offset) | Filters (type, symbol, date), selected signal for reasoning drill-down | `signals` topic (live unshift, bounded ring buffer) | Stage C (extended by reasoning panel reuse here) |
| `BacktestsStore` | Backtest results, trade lists, metric sets, comparison selections | Runner form state, selected runs for comparison (max 4 — §7.7 screen 5 raises the compare cap to 6) | — | Phases 36 (runner slice), 38 (completed) |
| `JobsStore` | Job list from Postgres-backed `/api/v1/backtests/jobs` & `/api/v1/optimizations` | Panel collapse state, dismissed toasts | `jobs.progress` (progress bars without polling) | Phase 35 |
| `PaperStore` | Paper positions, ledger, P&L curve data | Column layout, P&L period selector | `ticks.*` for selected positions (mark-to-market) | Stage F |
| `SessionStore` | Auth status (gateway session), Kite token health (`GET /api/v1/system/status`, 10 s fallback poll) | Theme (dark/light), sidebar state, last-visited route | `kite.status` via `/topic/system` push deltas | Stage C (settings UI here in Phase 35) |

**Rules of the architecture [plan §4.2]:**

- **Server-cache vs client state is explicit.** Server-cache slices carry `{ data, status: 'idle'|'loading'|'loaded'|'error', error, fetchedAt }` and are populated only by store methods calling the typed API client (`/api/v1/...` per D8, standard `{ code, message, details }` envelope — COMMON §8.3 — mapped to a Toast by one HTTP interceptor). Client state never round-trips.
- **WebSocket-fed stores subscribe once.** A single `WsClientService` (E-3 below) exposes typed RxJS streams; each store bridges its stream into signals in its constructor scope. Components never touch STOMP.
- **No polling where a topic exists — Kite status included.** Kite/connection status is push-first via `kite.status` → `/topic/system`. The **only** poll that remains is a low-frequency **10 s fallback** against the gateway's aggregated `GET /api/v1/system/status` (Caffeine-cached 5 s server-side) — it heals missed deltas across WS reconnects and seeds initial state. v1's 30 s Kite-status poll, 10 s signals poll, and 2 s connection poll are all deleted.
- **Derived data is `computed()`**: PCR from chain rows, composite-score breakdown percentages, unrealized P&L from last tick × position — computed, memoized, never stored.

**Design system / theming (D2) [plan §4.3].** PrimeNG 21.x **Aura preset**, actual component usage as above. One consolidated `--ay-*` token palette layered on PrimeNG design tokens; ESLint + Stylelint **forbid raw hex** in component styles. Tokens cover surface/border/text scales, semantic `--ay-bull` (green) / `--ay-bear` (red) / `--ay-warn`, and chart-specific tokens consumed by both chart libraries (lightweight-charts, ECharts) so charts re-theme with the app [A13, 2026-06-12]. Dark default, light optional: theme toggled by `SessionStore` writing a `.ay-light` class on `<html>`, persisted to `localStorage`, respecting `prefers-color-scheme` on first run. Strategy editor: Monaco + monaco-yaml bound to `strategy-schema/v1` served by strategy-signal-service. Typography/density: compact density scale for data surfaces (chain, trials), comfortable elsewhere; tabular-numerals font feature for all price columns.

## E-2. Layout pattern & component hierarchy [plan §4.4]

Desktop-first **widget grid** for the dashboard: CSS Grid with a 12-column track, widgets as standalone components implementing a small `AyWidget` contract (title, settings, collapse). Layout (positions/spans) is plain JSON in `localStorage` via `MarketStore` — **no heavyweight drag-grid dependency in v1** of the rebuild; CDK DragDrop can be added later for rearranging. Non-dashboard pages use a fixed `p-splitter` two-pane pattern (master list / detail).

Component hierarchy (the Stage-E surfaces are F–N below the shell):

```
AppShell (zoneless root)
├─ TopBar: instrument search, market clock IST, WS status, theme toggle
├─ SideNav (collapsible rail)
├─ RouterOutlet (lazy routes)
│  ├─ DashboardPage (widget grid)                                    [Phase 35]
│  │  ├─ MarketOverviewWidget: NIFTY/BANKNIFTY/SENSEX cards + sparklines
│  │  ├─ ActiveSignalsWidget: live signal feed
│  │  ├─ JobsWidget: running backtests/sweeps with progress
│  │  ├─ KiteStatusWidget: token health, OAuth popup
│  │  ├─ WatchlistWidget: virtualized tick table
│  │  └─ PaperPnlWidget: lightweight-charts equity sparkline
│  ├─ ChartsPage                                                     [Phase 40]
│  │  └─ LwcChartComponent (lightweight-charts ≥5.2)
│  │     └─ ArthaYantraDatafeed (library-agnostic core + LwcChartBinding)
│  ├─ OptionsChainPage  ── (Stage F)
│  ├─ StrategiesPages
│  │  ├─ StrategyListPage                                            [Phase 36]
│  │  ├─ StrategyEditorPage: Monaco YAML + form mode + score preview [Phase 36]
│  │  └─ VersionsPage: timeline, diff viewer, publish/rollback       [Phase 37]
│  ├─ BacktestsPages
│  │  ├─ RunnerPage                                                  [Phase 38]
│  │  ├─ JobsPage                                                    [Phase 35]
│  │  ├─ ResultsPage: metrics, trades, equity/drawdown curves        [Phase 38]
│  │  └─ ComparePage + SweepExplorer: ECharts heatmap & parallel-coords [Phase 38/39]
│  ├─ SignalsPage: live + history + ReasoningBreakdownPanel  ── (Stage C; panel reused here)
│  ├─ PaperTradingPage  ── (Stage F)
│  ├─ WatchlistsScreenersPage                                        [Phase 35]
│  └─ SettingsPage                                                   [Phase 35]
└─ ToastHost + ConfirmDialog
```

## E-3. Real-time update strategy [plan §4.6]

- **Client library:** `@stomp/stompjs` 7.x over **native WebSocket** to `wss?://…/ws` on the gateway — SockJS and both `window.global` polyfills deleted (D9). Heartbeats 10 s/10 s; reconnect with exponential backoff (1 s → 30 s cap, jitter) built into the client; on reconnect each store **re-subscribes and re-fetches its REST snapshot** to heal gaps (at-least-once display semantics).
- **Per-symbol topics, not firehose:** subscriptions follow `ticks.{exchange}.{tradingsymbol}` so the options page no longer receives every tick for every symbol. The datafeed's refcounted subscribe/unsubscribe pattern is generalized into `WsClientService` for all consumers.
- **Message conflation:** tick handlers write into a per-symbol "latest value" map; a single `requestAnimationFrame` loop flushes the map into store signals at most once per frame (≈16 ms). Under burst load the UI renders the newest price, never a queue of stale ones — this, plus zoneless signals, is what keeps the k6 tick-to-browser ≤ 150 ms p99 target (COMMON §8.4) achievable in the browser leg.
- **Rendering:** zoneless + signals means only components whose read signals changed re-render; `OnPush` is the default for every component. No `markForCheck`, no Zone patching cost.
- **Virtualized tables:** options chain (up to ~1,500 rows), signal history, backtest trade lists, watchlist tick lists all use `p-table` virtual scroll (CDK-based) with fixed row height; **only ~30 DOM rows exist at any time**. Price-change flashes are CSS-class pulses keyed off signal updates, **not** row re-creation.
- **Job progress:** `jobs.progress` topic drives `JobsStore`; progress bars update without any polling (D12). Per the §7.4 progress transport, services publish `{jobId, parentJobId?, status, progress, bestSoFar?}` deltas to Redis `jobs.progress`; the gateway relays them as STOMP frames on `/topic/jobs/{jobId}` over `/ws`. Polling `GET .../jobs/{jobId}` remains the fallback and the source of truth (the Postgres `jobs` table is authoritative; Redis pub/sub is fire-and-forget).

## E-4. Charting strategy (D4, amended by A13) [plan §4.7; ADR A13]

Library split (ADR **D4** — COMMON §6; D4's main-chart-renderer clause superseded by **A13, 2026-06-12** — lightweight-charts replaces TradingView Advanced Charts):

| Surface | Library | Notes |
|---|---|---|
| Main chart page | **lightweight-charts ≥5.2** (pinned `'>=5.2 <6'`; Apache-2.0 + NOTICE attribution, `attributionLogo` on — E-9) [A13, 2026-06-12] | `ArthaYantraDatafeed` core + `LwcChartBinding` (full contract in E-10); **indicator overlays/oscillators are engine-computed ta4j series** served by the Phase 40B indicator-series endpoint — **no client-side indicator engine, ever** (S7; explicit Phase 40 FAIL criterion), retiring v1's 13 hand-rolled indicators and its three duplicate live-candle aggregators |
| Equity/drawdown curves, dashboard sparklines, paper P&L | **lightweight-charts ≥5.2** (same pinned dep) | Small, fast, theme-token driven |
| Sweep heatmaps, parallel-coordinates trial explorer, IV smile, OI profile | **Apache ECharts 5.6** | Canvas renderer; brushing on parallel-coordinates filters the trial table |

Chart state (symbol, interval, overlay set + params, pane layout) persists via first-party chart-state persistence to `localStorage` (Phase 40C deliverable, replacing TV save/load) — single user, no server round-trip [A13, 2026-06-12].

**Containment boundary (cheap insurance, not an abstraction layer) [A13, 2026-06-12].** The lightweight-charts page **IS** the primary main-chart renderer — there is no fallback renderer and no common `ChartingService` interface (the S7 disposition, E-9, stands). The datafeed core stays **library-agnostic**: REST/WS candle access, internal paging, IST bucketing, timestamp normalization, live tick→bar aggregation, refcounted subscriptions — typed against internal candle DTOs with **zero chart-library imports**; the `LwcChartBinding` maps it onto lightweight-charts (E-10.2). `lightweight-charts` imports are confined to **designated chart-wrapper components** — the lazy `/charts` module **plus** the existing shared sparkline/equity-curve wrapper components (dashboard sparklines, Phase 38 equity curves and the paper P&L curve legitimately import LWC outside `/charts`, so a naive "no LWC outside `/charts`" rule is WRONG) — via an ESLint `no-restricted-imports` boundary, CI-enforced. No second main-chart renderer may be introduced: **TV Advanced Charts was dropped by A13 (license unobtainable for private single-user use); reintroduction requires a new ADR** (E-9; COMMON §17.3).

## E-5. Mobile & responsive approach [plan §4.8]

Desktop-first (trading happens at a desk), responsively degrading rather than mobile-designed. Breakpoints:

- **≥ 1440 px** — full widget grid.
- **1024–1439 px** — two-column grid; splitter panes stack.
- **768–1023 px** — single column; side-nav collapses to icons.
- **< 768 px** (phone, read-only monitoring use-case) — dashboard, signals, jobs, and paper P&L are usable: cards stack; chain/trial tables switch to a **per-row expandable card layout**; the chart page's first-party toolbar collapses responsively (Phase 40C) [A13, 2026-06-12].

The `overflow: hidden` body lock from v1 is removed. No native mobile app; the SPA over Tailscale/LAN to the gateway covers the "check signals from the couch" need (the gateway binds 127.0.0.1 — remote access is the owner-level Tailscale-serve decision, Q3, out of scope for these phases).

## E-6. Performance techniques & budgets [plan §4.9]

| Technique | Detail |
|---|---|
| Bundle budgets (CI-enforced via `angular.json` + the D16 pipeline) | **Initial ≤ 500 KB gz** (PrimeNG tree-shaken, no chart lib in initial); **any lazy chunk ≤ 400 KB gz**; warning at 80% |
| Heavy libs lazy by route | lightweight-charts is a **pinned npm dependency** — no dynamic script load, no global `<script>` (none ever existed for it) [A13, 2026-06-12]; the **"no chart lib in the initial bundle" rule survives** with changed enforcement: import hygiene (the E-9 lint boundary) + CI bundle budgets are the guard (an eager LWC import in a shared eager module would silently land it in initial); the `/charts` lazy chunk ≤ 400 KB gz budget is trivially met (LWC ~61 KB gz full / ~35 KB tree-shaken, already shipped for sparklines, near-zero marginal); **Monaco only on the editor route**; **ECharts only on compare/options-analytics chunks** |
| `@defer` blocks | Below-the-fold dashboard widgets and analytics tabs render on viewport/idle |
| Virtualization | E-3 — all large tables |
| Conflation + zoneless signals | E-3 — bounded render rate under tick bursts |
| Memoization | `computed()` for all derived values (PCR, score breakdowns, P&L); pure pipes for formatting; stable `trackBy` keys = `exchange:tradingsymbol` (stable-key convention) |
| Decimal handling | Prices arrive as JSON strings from the DTO layer and are formatted/compared via a thin decimal utility — **never `parseFloat` for arithmetic**, preserving the exact-decimal convention end-to-end |
| Images/fonts | Self-hosted variable font with `font-display: swap`; primeicons subset |
| nginx (frontend-ui container) | Pre-compressed assets (gzip+brotli), immutable cache headers on hashed files, 32 MB `mem_limit` |

## E-7. Accessibility — WCAG 2.1 AA checklist [plan §4.10]

| Requirement | Implementation |
|---|---|
| 1.4.3/1.4.11 Contrast | Both `--ay-*` palettes validated ≥ 4.5:1 text, ≥ 3:1 UI; bull/bear colors paired with ▲/▼ glyphs and sign prefixes (**never color-only** — also covers 1.4.1) |
| 1.3.1 Info & relationships | Real `<table>` semantics from `p-table`; chain grid labels calls/puts column groups with `scope`/`headers` |
| 2.1.1/2.1.2 Keyboard | All flows keyboard-operable (PrimeNG components are); focus trap in dialogs; visible `:focus-visible` ring token; no keyboard traps in Monaco (Esc-to-tab-out enabled) |
| 2.4.x Navigation | Skip-to-content link; `aria-current` nav; unique page titles via route `title` |
| 4.1.3 Status messages | Toasts and WS connection changes announced via `aria-live="polite"`; live tick cells are **not** in live regions (would be unusable) — a per-page "announce updates" toggle uses a rate-limited summary region instead |
| 1.4.4/1.4.10 Reflow & zoom | Layout functional at 200% zoom / 320 px width (E-5 breakpoints) |
| 2.3.1 / motion | Price-flash animations honor `prefers-reduced-motion` |
| Charts | Each chart paired with an accessible data-table toggle ("View as table"); ECharts `aria.enabled` descriptions on. [A13, 2026-06-12] All chart chrome (toolbar, pickers, legend) is **first-party and must itself pass axe/keyboard checks**; for the main chart the "View as table" toggle is the **sole accessible representation of chart data** (OHLCV + active overlay values + marks) and an **explicit Phase 40C deliverable** |
| Testing | `@axe-core/playwright` assertions in E2E for **every** route |

## E-8. Pages & routes (FULL table) [plan §4.5]

All routes lazy (`loadComponent`/`loadChildren`); guarded by an auth guard against the gateway session (D13) except `/login`. The "Built in" column maps each route to its Stage-E phase (routes built in earlier stages noted as such).

| Route | Purpose | Key components / stores | Built in |
|---|---|---|---|
| `/login` | Gateway form login (single owner) | PrimeNG form, `SessionStore` | Stage C |
| `/dashboard` | At-a-glance: market cards (incl. the INDIA VIX card [FP-14, owner selection 2026-06-12]), live signals, running jobs, Kite health, watchlist, paper P&L | Widget grid (E-2); `MarketStore`, `SignalsStore`, `JobsStore`, `SessionStore` | **Phase 35** |
| `/charts` | Full charting: lightweight-charts ≥5.2, engine-computed overlays/oscillators (Phase 40B endpoint), first-party instrument search + interval picker (1m–1w incl. `candles_1w` [FP-8, owner selection 2026-06-12]); trade/signal marks + deep-link drill-down (Phase 40A [FP-67, owner selection 2026-06-12]) [A13, 2026-06-12] | `LwcChartComponent` + `LwcChartBinding` + datafeed core | **Phase 40** (toolbar/overlays: **Phase 40C**; marks: **Phase 40A**) |
| `/options` | Live options chain grid with IV/OI/Greeks, PCR, IV smile, OI profile; filters wired to the API | `ChainTable` (virtual scroll), ECharts analytics tabs, `OptionsStore` fed by `options.chain` | Stage F |
| `/strategies` | List published/draft/archived strategies with tags, status, semver | `p-table`, `StrategiesStore` | **Phase 36** |
| `/strategies/:id/edit` | Author YAML against `strategy-schema/v1`: Monaco with schema validation; form mode for indicator weights/optional flags (CD-11 scope); live composite-score preview; "Quick backtest" (`202 {jobId}`) | Monaco + monaco-yaml, `StrategiesStore`, `JobsStore` | **Phase 36** |
| `/strategies/:id/versions` | Immutable version timeline, checksum, side-by-side diff (`/diff`), publish/rollback | Diff viewer (Monaco diff editor), `StrategiesStore` | **Phase 37** |
| `/strategies/compare` | Compare two strategy versions' configs and their latest backtest metrics | Diff viewer + metric table | **Phase 37** |
| `/backtests/run` | Runner: strategy version, instrument, interval, date range → `POST /backtests/run`; sweep launcher → `POST /optimizations/run` | Forms, `BacktestsStore` | **Phase 38** |
| `/backtests/jobs` | All jobs (queued/running/completed/failed/cancelled) with live progress bars, cancel | `JobsStore` ← `jobs.progress` WS | **Phase 35** |
| `/backtests/:id` | Result detail: returns, Sharpe, max DD, win rate, trade count; trade table; equity & drawdown curves | lightweight-charts ≥5.2 curves, virtualized trade table | **Phase 38** |
| `/backtests/compare` | Up to 6 runs side-by-side; sweep explorer: parameter heatmap + parallel-coordinates over trials, "promote winner to draft" | ECharts 5.6, `BacktestsStore` | **Phase 38/39** |
| `/signals` | Live feed + history with filters; click → reasoning breakdown | `SignalsStore`, `ReasoningBreakdownPanel` (horizontal bar viz), virtualized history table | Stage C (panel reused in 38/39) |
| `/paper` | Paper positions, ledger, realized/unrealized P&L, equity curve, mark-to-market from live ticks | `PaperStore`, lightweight-charts equity curve | Stage F |
| `/watchlists` | Named watchlists with live tick rows; screeners (momentum, long-term filters) running server-side queries | Virtualized tick table, `MarketStore` | **Phase 35** |
| `/settings` | Kite OAuth (popup + `postMessage`, preserved from v1), token health, theme, data-sync triggers, mock-mode banner | `SessionStore`, `MarketStore` | **Phase 35** |

## E-9. Chart containment final design (S7, executed as primary by A13) + license posture (Q5 → A13) [review §2.2 S7, §2.5 Q5; ADR A13; COMMON §17.3]

**S7 — Pluggable chart abstraction. REVISED (abstraction REJECTED, containment ADOPTED); escape hatch EXECUTED as the primary design [A13, 2026-06-12].** The vendor/license concern was real (it was Q5) — and it resolved against TradingView **pre-build** (see the Q5 outcome below), so the documented escape hatch (lightweight-charts page + server-side ta4j indicator series) is now the executed primary design, not a contingency. The proposed common `ChartingService` remains a textbook leaky abstraction and stays rejected; the claimed "indicator library we're building anyway" still does not exist — indicator math lives in the Java engine JAR, and building a TS copy would create a **third** implementation of the parity-critical math (the S7 rule — no client-side indicator engine, ever — is now an explicit Phase 40 FAIL criterion). **The dual implementation and runtime DI fallback stay rejected.** Final design — three parts:

1. **The datafeed core stays library-agnostic** — internal candle DTOs, **zero chart-library imports**; the `LwcChartBinding` maps it onto lightweight-charts (full contract in E-10).
2. **`lightweight-charts` imports are confined to designated chart-wrapper components** — the lazy `/charts` module **plus** the existing shared sparkline/equity-curve wrapper components (dashboard sparklines, Phase 38 equity curves and the paper P&L curve legitimately import LWC outside `/charts`; a naive "no LWC outside `/charts`" rule is WRONG) — under a **lint-enforced** `no-restricted-imports` boundary (CI-enforced — a deliberate violation must fail CI).
3. **No second main-chart renderer.** Reintroducing TradingView (or any renderer swap) requires a new ADR amendment. The COMMON §17.3 rows under A13: (i) the near-permanently-dormant **reverse-TV trigger** — if the platform ever becomes a public, company-offered service (the only class TradingView licenses), Advanced Charts becomes the upgrade path behind the same `/charts` seam; (ii) the **drawings trigger** — interactive drawings promoted to must-have AND hand-building on LWC primitives proves too costly → re-evaluate KLineCharts.

Rejected-for-cause (do not reintroduce): a runtime `try/catch` fallback is dead code (bundling is a build-time fact; `Injector.get` fails on provider registration, never on renderer availability); a TypeScript interface as a DI token is erased at runtime (does not compile). The old objection that "studies/drawings/multi-pane have no fallback equivalent" no longer holds and is corrected [A13, 2026-06-12]: **multi-pane is native** in lightweight-charts v5 (`addPane`/`moveToPane`/`setStretchFactor`); **studies are server-served** engine-computed ta4j series (Phase 40B); **drawings are de-scoped** for v1 (recorded Future item — COMMON §21.1).

**Q5 — license posture. RESOLVED BY A13: TradingView Advanced Charts DROPPED [A13, 2026-06-12].** TV's published eligibility excludes this project: the TV FAQ states Advanced Charts is "not provided for personal use, hobbies, studies, or testing"; licenses go "only to companies for use in public web projects and/or applications" (tradingview.com/free-charting-libraries). The Free Advanced Charts Agreement v.0325.FAC: §2.4 restricts use to a public-access service ("not for private, personal or internal uses") and requires giving TradingView free unlimited verification access; §2.11 requires a public partnership blog post 14 days pre-launch; §2.1 makes the grant non-transferable — so the old premise that "the v1 approval/vendored bundle carries over" has no legal basis; §§4.2(b)/4.4 make any grant revocable on 60 days' notice with immediate discontinuation on termination. The old §17.3 revisit trigger ("license untenable") was therefore satisfied **pre-build**, and the escape hatch is executed now as the primary design. Replacement posture: **lightweight-charts, Apache-2.0** — NOTICE attribution + a tradingview.com link; the built-in `attributionLogo` chart option stays **ON** (satisfies the link requirement); recorded in `docs/LEGAL.md`, whose scope changes from signed-agreement record to this attribution record. The Q5 Phase 0 checklist tasks are deleted. **Private repo + private GHCR remain BY CHOICE** (trading-strategy IP, Kite-credential hygiene, single-user posture) — the redistribution mandate is gone; the CSP tightens to self-only (no vendored-bundle exception); the `frontend-ui` image ships Angular `dist/` only.

**CD-9 — Chart renderer containment [A13, 2026-06-12] (COMMON §4, redefined — keeps its ID).** lightweight-charts ≥5.2 is the sole main-chart renderer (Apache-2.0 + NOTICE attribution, `attributionLogo` on); the datafeed core has zero chart-library imports; `lightweight-charts` imports are confined to designated chart-wrapper components (lint-enforced, CI); no second main-chart renderer — reintroducing TradingView requires a new ADR amendment. There is no vendored bundle and no "bundle missing" notice: lightweight-charts is a pinned npm dep, so chart E2E is **unconditional** — every "skipped gracefully when the bundle is absent" clause is deleted (an improvement: every gate is now unconditional). The E2E mock seam is **network-layer stubbing** (REST candle endpoints + WS ticks); there is no `IBasicDataFeed` object-injection seam anymore.

## E-10. Chart datafeed — library-agnostic core + lightweight-charts binding — standalone spec (NO-V1) [plan §4.1, §4.7; review S7; ADR A13]

The datafeed is the riskiest piece of the frontend and the one v1 left untested. It must be **written from scratch** to this spec; no v1 file is available. Two layers, with a hard seam between them:

### E-10.1 Library-agnostic datafeed core

The core has **zero chart-library imports** [A13, 2026-06-12] and is typed entirely against **internal candle DTOs** (the same string-decimal/IST conventions as the rest of the SPA). It exposes:

- **REST candle access.** Fetches historical bars from the market-data REST surface for an instrument key `(exchange, tradingsymbol)` + interval, returning internal candle DTOs `{ time, open, high, low, close, volume }` with prices as decimal strings (formatted via the decimal utility — never `parseFloat` for arithmetic). Intervals map 1m–1d onto the continuous-aggregate caggs (5m/15m/1h/1d) the market-data spine maintains; `1w` is additionally served from the `candles_1w` cagg (IST trading-week buckets, Stage B) [FP-8, owner selection 2026-06-12].
- **Internal paging contract [A13, 2026-06-12].** The TV pull contract is removed; `countBack` paging becomes an **internal paging contract**: initial load fetches and `setData`s N bars; backward fill is driven by `subscribeVisibleLogicalRangeChange` (fetch the older page, prepend, `setData`). The core still guarantees **at least N bars per page request** (paging backward across the requested range as needed), never fewer, until the series is exhausted. This remains the single most error-prone piece of the v1 adapter; the old `countBack` unit test is renamed/re-scoped to this internal-pagination contract (Phase 40).
- **IST bucket flooring.** Every bar timestamp is floored to its IST bucket boundary for the interval (e.g. a 1m bar at 09:15:37 IST floors to 09:15:00 IST), and bar times are emitted in the chart's expected unit (timestamp normalization below). All bucketing is IST (`Asia/Kolkata`) — the platform-wide time convention — with no UTC drift.
- **Timestamp normalization [A13, 2026-06-12].** Server timestamps arrive as Java/epoch-millis-style values; the normalization target is lightweight-charts': **intraday bars use `UTCTimestamp` epoch-SECONDS with a constant +05:30 IST display shift; daily/weekly bars use `BusinessDay` date objects and are NOT shifted** (per the LWC time-zone docs) — so historical and live bars share one time axis. The shift is **bidirectional**: every time value read back from the chart (visible-range events, crosshair/click params, `setVisibleRange` inputs, marker times) must convert back. This is the new countBack-class error magnet and carries a **dedicated unit test** (Phase 40).
- **Live tick → bar aggregation.** Subscribes to `ticks.{exchange}.{tradingsymbol}` via `WsClientService` and aggregates incoming ticks into the in-progress bar for the active interval (open on first tick of the bucket, high/low/close updated per tick, volume accumulated), emitting bar updates to the chart. Bucket rollover is driven by IST bucket boundaries, not wall-clock arrival.
- **Refcounted subscriptions.** Subscribe/unsubscribe are **reference-counted per `(instrument, interval)`**: multiple chart consumers of the same stream share one upstream WS subscription; the upstream `ticks.*` subscription is torn down only when the last refcount drops to zero. This is generalized into `WsClientService` (E-3) for all live consumers, and carries a dedicated refcounting unit test (Phase 40).
- **`getServerTime`.** Kept as an app utility [A13, 2026-06-12]: exposes the server's current time (IST-aligned) so app surfaces align with the backend clock rather than the browser clock.

### E-10.2 `LwcChartBinding` [A13, 2026-06-12]

`TvDatafeedAdapter` is deleted; its replacement is **`LwcChartBinding`** — the binding between the library-agnostic core and lightweight-charts. It:

- **Owns the `IChartApi`/`ISeriesApi`/pane lifecycle** — chart creation/destruction, the candlestick series, native v5 sub-panes for oscillators.
- **Loads + streams bars** — `setData` on load from the core's REST fetch; `series.update(bar)` from the core's refcounted live subscriptions; the `update(bar, true)` `historicalUpdate` path for late/amended bars.
- **Pagination** — `subscribeVisibleLogicalRangeChange` events drive the core's internal paging contract (E-10.1): fetch the older page, prepend, `setData`.
- **`priceFormat`** — `precision`/`minMove` from the instrument master; the `resolveSymbol`-equivalent core instrument-metadata lookup survives.
- **Decimal-string→number conversion at the render boundary ONLY** — prices stay decimal strings throughout the core and stores; numbers exist only at the lightweight-charts API surface.
- **Volume histogram** on an overlay price scale.
- **Theming** — reads the `--ay-*` tokens via `getComputedStyle` + `applyOptions` on theme switch; the `attributionLogo` chart option stays on (E-9 license posture).
- **Deep-link range orchestration** — for a deep-linked target time T the binding must **load bars around T first**, then `timeScale().setVisibleRange` — explicit range-orchestration logic (TV's pull model did this implicitly; LWC's push model does not).

The binding contains **all** lightweight-charts-typed main-chart code; it lives inside `/charts` under the E-9 lint boundary. Component naming: `TvChartComponent` → `LwcChartComponent`; "TvDatafeedAdapter" → "LwcChartBinding". Chart state (symbol, interval, overlay set + params, pane layout) persists first-party to `localStorage` (Phase 40C, replacing TV save/load; single user, no server round-trip).

### E-10.3 Marks extension — trades & signals on the chart (Phase 40A) [FP-67, owner selection 2026-06-12]

The marks capability follows the same hard seam as the rest of the datafeed:

- **Library-agnostic marks fetch in the core.** The core (still zero chart-library imports) exposes a marks fetch keyed by instrument + time range that returns **internal mark DTOs** — entry/exit/SL/target marks for **backtest trades (selected by `runId`)** and **emitted signals (selected by symbol + time window)**. Marks are sourced via **read-only query params on the EXISTING trades/signals endpoints** (e.g. `GET /api/v1/backtests/{id}/trades?symbol=&from=&to=`, `GET /api/v1/signals?symbol=&from=&to=`) — **no new service, no new endpoint**. Mark timestamps go through the same Java-timestamp normalization and IST conventions as bars (E-10.1); prices stay decimal strings.
- **Marker rendering via `createSeriesMarkers` [A13, 2026-06-12].** TV `getMarks`/`getTimescaleMarks` are replaced: the binding maps internal mark DTOs onto lightweight-charts **`createSeriesMarkers`** — entry = `arrowUp` `aboveBar` in `--ay-bull`, exit = `arrowDown` `belowBar` in `--ay-bear` (paired glyphs, never color-only — E-7); SL/target = **price-positioned markers** (`atPriceTop`/`atPriceBottom`/`atPriceMiddle` with explicit `price`). `createPriceLine` renders SL/target **only in single-trade/signal focus mode** (deep-linked `runId`+`tradeId` or `signalId`), **never in the multi-trade view** (series-wide full-width lines would stack into noise). The **timescale-mark lane is DROPPED** (no LWC equivalent — explicit de-scope). Mark timestamps get the same IST bucket-flooring as candles — a marker's `time` must coincide with a bar time at the active interval. Hover tooltip (price/qty/P&L) **and** click-through (marker → trade/signal detail) are built on a custom crosshair overlay; Phase 40A includes a **short hit-test spike** (v5.2 `hoveredItem` may not surface marker identity from the markers plugin; fallback = own hit-region math from marker time/price). All marker code lives inside `/charts` — the E-9 lint boundary covers it exactly like every other lightweight-charts import.
- **Deep links.** `/charts?symbol=…&interval=…&runId=…` (or `…&signalId=…`) centers the chart on the referenced trade/signal and pre-filters marks to that run/signal; the Phase 38 results trade table and the signals page link here (Phase 40A deliverable).
- **Sources.** v1 mark sources are backtest trades and signals; **paper-trade marks arrive as a one-line deliverable in Stage F Phase 43B** (after the paper ledger lifecycle exists) — the mark-source vocabulary reserves the slot.

## E-11. UI workflow — all screens (1–7) [plan §7.7]

Expanding the owner's 8-step flow into concrete screens (component stack: Angular 21, PrimeNG 21 Aura, `@ngrx/signals` stores `strategies`/`backtests`/`jobs`). These are the normative screen specs the Stage-E phases implement.

1. **Strategy list** (`/strategies`) — *Phase 36*. PrimeNG DataTable: name, status tag (draft/published/archived color-coded), published version, tags as chips, last-backtest Sharpe + a 90-day equity sparkline (lightweight-charts ≥5.2), `updatedAt`. Row actions: edit, quick backtest, optimize, history. Header: *Create strategy*, *Import YAML* (file/paste → `POST /validate` before save).

2. **Strategy editor** (`/strategies/:id/edit`) — *Phase 36*. Dual-mode, always in sync: a **form pane** and a **Monaco YAML pane**. Per **CD-11 (COMMON §4) the form-mode scope is reduced for v1 to metadata + indicator weight/optional toggles ONLY** — an explicit scope reduction vs the full §7.7 screen-2 description (which lists universe pickers with instrument-master-backed expiry/strike selectors, a rule builder, and risk fields). Those richer form controls **stay YAML-only in v1** and are parked in `PHASE_GATES.md` at Phase 36; they are authored directly in the Monaco YAML pane. The Monaco pane uses monaco-yaml bound to `GET /strategies/schema/v1` for autocomplete, hover docs, and squiggles; server-side `POST /validate` runs debounced and surfaces semantic errors with line anchors. *Save as draft* shows the auto-bumped version. Dirty-state guard against navigation loss. *(Full screen-2 form is restated here so the parked scope is unambiguous: form pane = metadata, universe pickers w/ expiry/strike selectors, indicator cards w/ weight sliders + `optional` toggles, rule builder, risk fields — of which only metadata + weight/optional ship in v1.)*

3. **Quick-backtest panel** — *Phase 36*. Docked drawer inside the editor: prefilled from `backtest.defaults` (or last run), one click fires `POST /backtests/run` against the **current draft**, progress bar driven by `/topic/jobs/{jobId}`, and on completion renders headline metrics plus an equity/drawdown curve (lightweight-charts) without leaving the editor — the validate-tweak-rerun loop the owner asked for.

4. **Tuning job monitor** (`/jobs`, surfaced at `/backtests/jobs`) — *Phases 35 (jobs table) + 39 (sweep detail)*. Jobs table (type, strategy, status, progress, started, cancel button) plus a live sweep detail view: ECharts 5.6 **trial scatter** (trial # vs objective with best-so-far step line, pruned trials greyed), **parallel-coordinates** plot of parameter dimensions colored by objective, and a **heatmap** for 2-parameter grids. All update live from `/topic/jobs/{sweepJobId}` deltas; cancel issues the DELETE and shows the `cancelling` interim state. Trial drill-down adds a **fold breakdown panel** (see screen-4 detail and E-12): an ECharts grouped bar (x = fold index; paired train vs OOS objective bars; OOS-mean reference line; pruned-at-fold markers — pruned trials render as partial-coverage and, per guard 6, are never compared on truncated fold sets) above a fold table with date ranges, both metric sets, per-fold degradation (guard-7 thresholds), and regime-mix chips (guard 6; omitted cleanly when regime attribution is unavailable). The same panel renders for any standalone walk-forward backtest from the results page.

5. **Results comparison** (`/backtests/compare`) — *Phase 38*. Multi-select **up to 6 runs/trials** → metric matrix table (best value per column highlighted), overlaid normalized equity curves and drawdown curves, trade-distribution histograms; **`dataHash` mismatch banner** when comparisons aren't like-for-like. Entry points: leaderboard rows, strategy history.

6. **Publish / diff / rollback dialogs** — *Phase 37*. Version history timeline per strategy; *Compare* opens a split view — **structured diff list** (`path: before → after`) above a Monaco **side-by-side YAML diff** from `GET /diff`. *Publish* confirms with notes and shows what the signal engine will hot-swap; *Rollback* picks a version, previews the diff against current, and creates the copy-forward draft (§7.2). The *Publish* confirmation additionally shows the version's latest `purpose: stress_test` run (§7.4) — headline metrics, the stress-vs-sweep-OOS Sharpe degradation as a **difference** (rendered with the guard-7 badge where the guard-7 score is available), the **holdout-reuse count**, or a **"never stress-tested on unseen data"** notice with a one-click *Run stress test* action (suggested window prefilled from `GET /backtests/stress-window`). **Advisory only: publishing is never blocked.** The S1C advisory's full UI contract is in E-13.

7. **Signal reasoning breakdown** — *Stage C component, reused in Phases 38 & 39*. For every live signal (dashboard) and every backtest trade (results drill-down), the same component renders: the **gate-rule checklist** (each leaf rule with pass/fail and the actual values), a **horizontal stacked bar of per-indicator `weight × score` contributions** (optional indicators visually distinct, greyed when not activated), and a **composite-vs-threshold gauge** showing the emitted signal strength. Because live and backtest persist identical contribution records from the same engine JAR, this view doubles as the day-to-day parity check. The `ReasoningBreakdownPanel` consumes the score-breakdown contract (E-12.1).

## E-12. Score-breakdown contract, overfitting-guard outputs the UI renders [plan §7.1, §7.5; ADR A1; review BPB/S1A/S1B/BPC]

### E-12.1 Composite formula (ADR A1, governing) + score-breakdown record [plan §7.1; review BPB]

The normative composite (ADR amendment **A1**, COMMON §6 — supersedes D18's literal `sum(weight × normalized_score)` phrasing):

`composite = ( Σ_required wᵢ·sᵢ + Σ_activated-optional wⱼ·sⱼ ) / ( Σ_required wᵢ + Σ_activated-optional wⱼ )`

An **optional** indicator *activates* (counts in numerator and denominator) only when (a) its own score ≥ `optional_min_score` (default 0.6) **and** (b) the required-only composite ≥ `threshold − optional_gate_margin` (default 0.15). Optional indicators can only activate, never gate or carry a signal alone. Entry fires when all gates pass **and** `composite ≥ threshold`.

The per-indicator breakdown the reasoning panel (screen 7) renders is a **single record type in the strategy-engine JAR**, serialized identically by the live signal engine and the backtest engine (one shape, two producers; pinned by a golden-vector byte-identity assertion — Stage C/D). The UI binds these exact fields:

| Field | Meaning |
|---|---|
| `composite`, `threshold`, `passed` | Emitted signal strength (0–1), config threshold, `composite ≥ threshold` |
| `requiredComposite` | Required-only composite — the value tested against `threshold − optional_gate_margin` for optional activation |
| `optionalMinScore`, `optionalGateMargin` | Echoed config values; the panel explains activation without re-fetching the version |
| `weightDenominator` | `Σ required wᵢ + Σ activated-optional wⱼ`; renderer invariant: `composite = Σ contributions / weightDenominator` |
| `gate` | Recursive `all`/`any`/`not` tree mirroring the YAML; leaves carry `{rule, passed, operands: {alias → rawValue}}` — the pass/fail checklist with actual values |
| `indicators[]` | `{alias, name, timeframe, score s∈[0,1], weight, contribution = w·s, optional, activated, activationReason, rawValue, params}`; `activated` is `true` for every required indicator; `activationReason ∈ REQUIRED \| ACTIVATED \| SCORE_BELOW_MIN \| MARGIN_NOT_MET` |

The renderer enforces the invariant `composite = Σ contributions / weightDenominator`. Timestamps in the enclosing signal DTO carry the `+05:30` offset; prices stay NUMERIC-backed strings; the DTO retains the engine-pin triple `(strategyId, version, checksum)`.

### E-12.2 Regime attribution (guard 6) [plan §7.5; review S1A]

**S1A — Multi-regime walk-forward validation. REVISED.** Regimes are **computed, never declared** (hand-labeled calendar regimes, a regime-filter DSL, and in-sample `multi_regime` partitions were all rejected). `backtest-service` derives daily regime labels from a configurable benchmark index (`backtest.defaults.benchmark`, default `NSE:"NIFTY 50"`): **trend** = close vs 200-day SMA (up/down) × **volatility** = 20-day realized vol vs its trailing 1-year median (quiet/turbulent) → four labels, deterministic functions of persisted candles. The label for day T is computed from benchmark data **through the prior session's close (T−1)** — never the entry day's own close (which would be same-session look-ahead). Every walk-forward OOS fold records its regime mix; every closed trade is tagged with its entry-day regime.

The UI surfaces this (Phase 39 leaderboard, Phases 38/39 fold panel):
- **Per-regime OOS columns** — Sharpe/expectancy per regime label.
- **Regimes-covered badge** — flags sweeps whose data window never contained a regime; *a strategy may only be called robust across regimes it was actually tested in.*
- **`fold_aggregation` knob** — `optimize.objective.fold_aggregation: mean | min | mean_minus_std` (default `mean`) over OOS fold objectives; `min` is the conservative worst-fold (maximin) choice. Folds failing `min_trades` are **excluded from `min`** and the exclusion surfaces as an explicit **"n folds excluded"** flag on the leaderboard row, never a silent drop. (Rejected for cause: `trial.suggest_objective(...)` is a fabricated Optuna API; a `min/max(fold Sharpe)` robustness score classifies two all-negative folds as "robust".)

### E-12.3 Train→OOS degradation diagnostic (guard 7) [plan §7.5; review S1B]

**S1B — Overfitting score per trial. REVISED.** Persist `sharpe_degradation = train_sharpe − oos_sharpe` — a **difference, not a ratio** (stable for zero and negative Sharpes; the review's `(train − oos)/train` divides by zero and sign-flips). The UI renders it as a **traffic-light badge** with these exact bands (Phase 37 publish advisory, Phase 39 leaderboard, fold panel):

| Band | Meaning |
|---|---|
| `< 0.3` | consistent (green) |
| `0.3 – 1.0` | degrades (amber) |
| `> 1.0` | distrust (red) |
| n/a | suppressed — **"n/a — weak train signal"** when `train_sharpe < 0.5` or the trial is invalid under `min_trades` |

Display-only: it never feeds the pruner (the fold-fed MedianPruner on OOS fold objectives remains the sole early-stopping mechanism — divergence is a diagnostic, not an objective).

### E-12.4 Walk-forward fold reporting (BPC) [plan §7.4/§7.7; review BPC]

**BPC — Walk-forward fold reporting. REVISED.** `backtest_runs` carries a bounded `fold_metrics` JSONB array (train/test ranges, both metric sets per fold) plus `oos_fold_mean`/`oos_fold_std`; fold endpoints exist on both backtest- and optimizer-facing routes (`GET /backtests/{id}/folds`, `GET /optimizations/{sweepId}/trials/{trialId}/folds`). The UI renders a **grouped-bar train-vs-OOS fold panel** (Phases 38/39): x = fold index; paired train vs OOS objective bars; OOS-mean reference line; pruned-at-fold markers. Above a fold table with date ranges, both metric sets, per-fold degradation (guard-7 thresholds), and **regime-mix chips** (guard 6). The `regimeMix` key is **optional/nullable** so BPC degrades cleanly if S1A is ever de-scoped — the fold panel renders without regime chips when `regimeMix` is null. **Radar charts are REJECTED** (a bigger drawdown literally draws a bigger, better-looking polygon; the proposed ECharts radar config was invalid) — the FAIL criterion for Phase 39 explicitly forbids them. Server-side "assessment" prose is dropped; the UI derives labels from the guard-7 thresholds.

### E-12.5 Metrics catalog [plan §7.6]

Computed once by `backtest-service` per run/trial, persisted in `backtest.backtest_runs`/`backtest.optimization_trials`, all money math in NUMERIC/BigDecimal. The results page (Phase 38) and leaderboard (Phase 39) render this set; all values arrive as decimal strings (no client-side `parseFloat` arithmetic):

| Metric | Definition (in words) |
|---|---|
| Total return | Final equity minus initial capital, as % of initial capital, net of configured costs |
| Annualized return (CAGR) | Constant yearly growth rate compounding initial → final equity over the tested span |
| Sharpe ratio | Mean periodic return minus risk-free (default 6.5% Indian T-bill, configurable) ÷ std-dev of returns, scaled to annual (√252 daily, √(252×375) for 1m bars) |
| Sortino ratio | As Sharpe but denominator uses only the std-dev of *negative* returns |
| Max drawdown | Largest peak-to-trough equity decline, as % of the peak |
| Max drawdown duration | Longest stretch (calendar days / bars) between an equity peak and the first new higher peak |
| Win rate | Closed trades with positive net P&L ÷ total closed trades |
| Profit factor | Gross profit of winners ÷ gross loss of losers |
| Expectancy | Avg net P&L per trade: win rate × avg win − loss rate × avg loss (₹ and R-multiple forms) |
| Average trade | Mean net P&L per closed trade; reported with avg win, avg loss, avg holding time |
| Exposure | % of tested bars during which at least one position was open |
| Trade count | Total closed trades (the denominator that gives every other metric credibility) |

**Benchmark-relative & Monte Carlo additions [FP-31, FP-32, owner selection 2026-06-12].** Stage D Phase 32A additionally persists on `backtest_runs` (additive NULL-able columns per D17): `alpha`, `beta`, `information_ratio`, `excess_cagr` (vs the configured benchmark — default NIFTY 50, the same series the E-12.2 regime labeler already reads), `benchmark_curve` JSONB (downsampled buy-and-hold curve), and `montecarlo_summary` JSONB (seeded trade-sequence resampling: 5/50/95 equity bands, drawdown distribution, risk-of-ruin) served via `GET /api/v1/backtests/{id}/montecarlo`. Phase 38 renders all of these (benchmark overlay + relative-metric columns + Monte Carlo tab); the same relative-metric columns surface on the Phase 39 leaderboard. All values arrive as decimal strings; every surface degrades cleanly (hidden tab/overlay, em-dash columns) when the columns are NULL for runs predating Phase 32A.

`GET /api/v1/optimizations/{sweepId}/best?top=N` returns the metric matrix; the leaderboard (Phase 39) renders a sortable PrimeNG DataTable with **every §7.5 guard surfaced** — the explicit Phase-39 PASS criterion is *every guard 1–7 output is visible somewhere on this screen*:

- **Plateau-adjusted default sort** (guard 4): each top-K trial re-scored as the median objective of its parameter-space neighbors (±1 grid step / normalized ε-ball, using already-computed trials — no extra backtests); a sharp spike sinks, a broad ridge rises. **Raw-objective sort is one click away.**
- **Per-regime OOS Sharpe/expectancy columns + regimes-covered badge** (guard 6 / S1A).
- **"n folds excluded" flag** where `min` aggregation dropped folds (guard 6 / S1A) — explicit, never silent.
- **Sortable train→OOS degradation badge** with its n/a state (guard 7 / S1B).
- **`dataHash` parity badges** — rows with differing `dataHash` are visually flagged as not like-for-like.
- **Pareto framing for nsga2** (guard 5): multi-objective sweeps render a front scatter, never collapse to one winner.
- **Min-trades invalidation** (guard 3): under-trading trials marked invalid, flagged not hidden.
- Per-row links to full results, trades, equity curve, and folds.

**Pruned/invalid trials are flagged, never hidden** (the Phase-39 FAIL criterion forbids hiding them); pruned trials render partial-coverage and are never compared on truncated folds.

**Promote-winner flow** [plan §7.6]. `POST /api/v1/optimizations/{sweepId}/promote {trialId}` applies the trial's parameter values onto the source version's config and creates a **new draft version** (minor bump) with provenance in the notes/audit (`source: sweep 91f3, trial 217, objective sharpe=1.84 OOS`). The UI shows a **diff preview → new-draft confirmation**; the optimizer never silently changes a live strategy. The owner then reviews the diff, optionally quick-backtests, and publishes.

## E-13. Stress-test advisory UI (S1C) [plan §7.4/§7.7; review S1C]

**S1C — Pre-publication stress test. REVISED (advisory-plus-honest-accounting; not a gate).** A stress test is an ordinary backtest job tagged `purpose: stress_test`; on submission `backtest-service` validates the window against **all prior jobs of the strategy lineage — sweeps *and* manual backtests** (manual quick-backtests leak information exactly as sweeps do) and refuses the label (`422 WINDOW_CONTAMINATED`, COMMON §8.3) on overlap, listing the intersecting jobs. Enforcement against the owner is impossible (the human is the leak), so the posture is advisory. The publish dialog (screen 6, Phase 37) renders:

- The latest `purpose: stress_test` result for the version — **headline metrics**.
- The **guard-7 degradation badge** for the stress-vs-sweep-OOS Sharpe degradation (a difference; bands per E-12.3; n/a suppressed below train Sharpe 0.5 or under `min_trades`).
- A **holdout-reuse counter** — e.g. *"3rd stress test against this window — treat as contaminated."* Each re-tune-after-failure cycle contaminates the holdout through the owner's decisions; the system makes that leakage visible.
- Or a **"never stress-tested on unseen data"** notice with a one-click *Run stress test* action; the suggested clean window is prefilled from `GET /api/v1/backtests/stress-window?strategyId=` (day after the latest tested `to`, through the latest cached candle).
- A **clone-launder limitation note in the dialog footer** — re-creating a strategy under a new id resets lineage tracking, so the check is honest accounting, not tamper-proof prevention.

**Advisory only: publishing is never blocked** (the Phase-37 PASS criterion is *publish works with zero stress runs*; the FAIL criterion is *publish blocked by stress state*). Runs closing fewer than `constraints.min_trades` trades render **"insufficient sample — extend the window,"** never a pass/fail verdict.

## E-14. Signal notifier — full design (Q6) [plan §12.7, §5.2.3; review §2.5 Q6]

**Q6 — Alert delivery. REVISED.** A signal at 09:47 the owner sees at 15:25 is worthless, so a phone-push notifier is high-ROI — but landing in **Phase 4 (this stage)**, not Phase 2: it was part of the pre-MVP effort concentration the timeline ledger disallows; MVP signals remain visible on the signals page.

**Module & placement.** A **`notifier` Modulith module in strategy-signal-service** (per D7 and the §17.1 recommendation) — *not* a shared library. It subscribes **in-process** to emitted signals of strategies with `notifications_enabled`. It is distinct from the first-party ops-alert POSTs and the backup sidecar, which keep their own documented 5-line plain HTTP POSTs unchanged.

**Channels (severity/routing) [plan §12.7].** Signal pushes are a **separate concern** from ops alerts:
- **ntfy PRIMARY** — the same topic infrastructure as ops alerts, but **its own random-suffixed topic, distinct from the ops topic**, so signals can be muted without muting ops alerts (and a chatty strategy can never bury a Kite-disconnect *critical*). The notifier POSTs to ntfy directly (`NTFY_URL`).
- **Telegram ALTERNATIVE** — the Telegram Bot API as the authenticated alternative: **one plain HTTPS POST to `sendMessage`; no third-party bot library, no bot SDK.** (The review's cited artifact `com.github.eljaiek:telegram-bots:5.7.1` does not exist on Maven Central — hence plain POST.)

**Privacy upgrade (documented).** For signal privacy beyond an unguessable topic name, a self-hosted ntfy container reachable over the tailnet (plan §11.6 — the Q3 Tailscale posture, COMMON §18.1) is the documented upgrade.

The ops-alert severity routing (for context; the notifier does not own these) routes **critical** first-party alerts — Kite ticker disconnected in market hours, token expired/login missing, Kite contract drift (missing/changed fields), nightly pg_dump failed — and **warning** Grafana alerts (job failed, Optuna stalled, disk filling, Redis memory ≥ 0.85, container restart loop) to the **ops** ntfy topic. Signal pushes are **personal-app severity** and go to the **signal** topic only.

**Delivery mechanics.** In-process **async with bounded retry** (3× exponential backoff). The proposed Redis Streams queueing is **rejected as overkill at sub-1-per-minute push volume**.

**Opt-in & flood control.**
- **Opt-in per strategy** — driven by DB columns (`notifications_enabled`, channel choice), **never the strategy YAML**.
- **Mandatory cooldown/dedup** per strategy, plus a **global hourly cap** that emits a single **"N suppressed"** summary push when exceeded — so a chatty strategy can neither flood the phone nor bury a critical ops alert.

**Payload.** Direction, entry/SL/target, composite score vs threshold — **never credentials**. (No Sharpe ratio on a live signal — that was a review error.)

**Audit.** A `strategy.notification_events` row per attempt — `(signal FK, channel, status ∈ SENT|FAILED|SUPPRESSED, attempts, detail)` — every attempt audited, same-schema FK. (No email-semantics `BOUNCED` status.)

**The checksum invariant (binding).** **Notification settings live on DB rows, never in the strategy YAML — toggling alerts never mints a version or perturbs a D18 checksum.** This is asserted by an integration test (Phase 41) and is the Phase-41 FAIL line if violated.

**Test path.** `POST /api/v1/strategies/{id}/notifications/test` (422 when disabled/unconfigured). For E2E/IT, ntfy and Telegram are **WireMock-stubbed**; an ntfy WireMock container is added to the e2e/test compose overlay with `NTFY_URL` pointed at it under mock, reused by Stage G's alert verification.

---

# Part 2 — Phase specs (35–41 + 40A/40B/40C)

> Each phase below is reproduced near-verbatim from the implementation-phases doc, with cross-references rewritten to point at Part 1 of this file or [COMMON](ARTHAYANTRA_2_COMMON_REFERENCE.md). Phase 40A is a 2026-06-12 feature-selection addition [FP-67, owner selection 2026-06-12]; Phases 40B and 40C are A13 additions [A13, 2026-06-12]. Build order within the stage: 35 … 39, 40B, 40, 40C, 40A, 41 — Phase 40B (backend indicator-series endpoint) precedes Phase 40, which depends on it. Phases are otherwise independent within the stage except where a deliverable explicitly reuses a component built in an earlier Stage-E phase (e.g. the fold panel built in 38 is reused in 39; the `ReasoningBreakdownPanel` from Stage C is reused in 38/39).

## Phase 35 — Dashboard, jobs monitor, watchlists + settings UI

**Objective**
Build the dashboard widget grid (market cards, active signals, jobs, Kite status, watchlist), the jobs monitor page with live progress, and the `/watchlists` + `/settings` routes — the daily-driver surfaces.

**Why this phase is independent**
All feeding topics/endpoints exist (Phases 17, 23, 28 — market/system status, signals, jobs spine). Pure frontend.

**Deliverables**
- `MarketStore` completed (instrument search, watchlist layout, per-symbol tick subscriptions); `JobsStore` (job list + `jobs.progress` live updates, no polling). (Store catalog: E-1.)
- `/dashboard` — CSS-grid widgets implementing the `AyWidget` contract (E-2): **MarketOverview** (NIFTY/BANKNIFTY/SENSEX cards + lightweight-charts sparklines, **plus an INDIA VIX card** — level/day-change/sparkline off the Phase 15A (Stage B) registry pin + history backfill; an ordinary pinned index through `MarketStore`, no new store, served by the mock feed like any other pinned index [FP-14, owner selection 2026-06-12]), **ActiveSignals**, **Jobs** (progress bars + cancel), **KiteStatus** (token health + OAuth popup trigger + mock banner), **Watchlist** (virtualized tick table, CSS-pulse price flashes); `@defer` below-the-fold; layout JSON in `localStorage`.
- `/backtests/jobs` page: full job table (type/status/progress/cancel) with live updates (screen 4 jobs table — E-11).
- `/watchlists` page — named watchlists with live tick rows, add/remove instruments, plus the **screener tab** (the Phase 17 endpoint's UI consumer; E-8 `/watchlists`).
- `/settings` page — Kite OAuth popup + token health, theme, data-sync triggers, mock-mode banner (E-8 `/settings`). **NOTE — reserved Global-risk slot [FP-42, owner selection 2026-06-12]:** the settings page reserves a **Global risk** section slot (pause-all kill switch + global risk-limit controls); only the UI slot and a one-line cross-reference placeholder ship in this phase — the controls, the `strategy.risk_settings` DB rows and the trip audit land in **Stage F Phase 43A**.
- Vitest: store reducers, conflation flush, widget rendering.
- E2E: dashboard journey appended (live tick visible, job progress bar moves during a mock backtest).

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
cd frontend-ui && npm test && npm run build && cd ../e2e && npx playwright test
```

**Tests & Verification**
- On mock stack: dashboard ticks update smoothly under burst (rAF conflation — E-3); job submitted via curl shows live progress without refresh.

**Acceptance criteria**
- PASS: zero polling loops where a topic exists (the 10 s system-status fallback is the only poll — E-1); virtualized tables keep ~30 DOM rows.
- FAIL: per-tick re-render of whole tables; `markForCheck` anywhere.

**Commit message**
`feat(frontend): dashboard widget grid and live jobs monitor`

**PR title**
`Phase 35: dashboard + jobs monitor`

**Time estimate**
120–150 min

**Token size target**
≤ 35k output tokens.

**If phase too big**
(a) dashboard widgets; (b) jobs page + E2E; (c) watchlists + settings pages.

---

## Phase 36 — Strategy editor (Monaco + schema) + quick backtest

**Objective**
Ship the strategy authoring loop: Monaco YAML editor bound to `strategy-schema/v1` via monaco-yaml, debounced server validation, draft saves, and the docked quick-backtest drawer (E-11 screens 2–3).

**Why this phase is independent**
Registry + validate + backtest endpoints all exist (Stage C §21 registry/diff, §23 signals; Stage D backtest run); the editor is verifiable end-to-end on the mock stack.

**Deliverables**
- `/strategies` list page (E-11 screen 1): PrimeNG table — status tags, published version, tags, last-backtest summary; create/import-YAML actions with pre-save `POST /validate`.
- `/strategies/:id/edit` (E-11 screen 2) — Monaco + monaco-yaml against `GET /strategies/schema/v1` (autocomplete, hover, squiggles); debounced `POST /validate` with line-anchored semantic errors; **metadata + weights/optional form pane (CD-11 scope — E-11 screen 2)** two-way synced with YAML; save-as-draft showing auto-bumped version; dirty-state navigation guard. *Universe pickers / rule builder / risk fields stay YAML-only in v1 (CD-11) — parked in `PHASE_GATES.md` at Phase 36.*
- Quick-backtest drawer (E-11 screen 3): prefilled from `backtest.defaults`; fires `POST /backtests/run` against the **current draft**; progress via `/topic/jobs/{jobId}`; headline metrics + equity/drawdown (lightweight-charts) inline.
- `StrategiesStore`, `BacktestsStore` (runner slice) — E-1.
- Monaco **lazy-loaded on the editor route only** (bundle budget — E-6).
- E2E: create → schema error shown → fix → save draft → quick backtest → results render.

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
cd frontend-ui && npm test && npm run build && cd ../e2e && npx playwright test
```

**Tests & Verification**
- Vitest: YAML/form sync, dirty guard, store flows. E2E journey green.

**Acceptance criteria**
- PASS: edit→quick-backtest→results loop < 2 min of user effort on the mock stack; invalid YAML never saveable.
- FAIL: Monaco in the initial bundle; client-side-only validation (the server `POST /validate` is authoritative — E-8).

**Commit message**
`feat(frontend): monaco strategy editor with schema validation and quick-backtest drawer`

**PR title**
`Phase 36: strategy editor + quick backtest`

**Time estimate**
120–150 min

**Token size target**
≤ 35k output tokens.

**If phase too big**
(a) list + editor + validation; (b) form-pane sync + quick-backtest drawer.

---

## Phase 37 — Versions/diff/publish UI + stress-test advisory

**Objective**
Complete the lifecycle UI: version timeline, Monaco side-by-side diff, publish dialog with the advisory stress-test panel, and rollback (E-11 screen 6; S1C UI — E-13).

**Why this phase is independent**
All endpoints exist (Stage C §21 registry/diff/publish/rollback; Stage D §32 stress-test backend). Pure frontend.

**Deliverables**
- `/strategies/:id/versions` — timeline (semver, status, checksum, author, notes), **structured diff list** (`path: before → after`) above Monaco diff from `GET /diff` (E-11 screen 6).
- **Publish dialog** (E-11 screen 6 + S1C advisory E-13): notes; hot-swap notice; **stress advisory** — latest `purpose: stress_test` result with the guard-7 degradation badge (bands per E-12.3), holdout-reuse count ("3rd stress test against this window — treat as contaminated"), or "never stress-tested" notice + one-click *Run stress test* (window prefilled from `GET /backtests/stress-window`); **advisory only — publishing never blocked**; clone-launder limitation noted in the dialog footer.
- Rollback flow: pick version → preview diff vs current → copy-forward draft (optional publish).
- `/strategies/compare` — two versions' configs side-by-side + their latest backtest metric table (E-8 `/strategies/compare`).
- E2E: publish → diff vs prior → rollback journey; stress advisory renders for a contaminated fixture.

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
cd frontend-ui && npm test && cd ../e2e && npx playwright test
```

**Tests & Verification**
- Vitest: badge thresholds (`< 0.3` / `0.3–1.0` / `> 1.0`, n/a suppression — E-12.3), reuse-count rendering. E2E green.

**Acceptance criteria**
- PASS: publish works with **zero stress runs** (advisory, not gate); diff renders both structured and YAML views.
- FAIL: publish blocked by stress state; `ngx-monaco-editor` wrapper introduced (D2 deliberately omits it — use Monaco directly).

**Commit message**
`feat(frontend): version timeline, monaco diff, publish dialog with stress-test advisory and rollback`

**PR title**
`Phase 37: versions/diff/publish UI`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) timeline + diff + rollback; (b) publish dialog + stress advisory.

---

## Phase 38 — Backtest runner, results + comparison UI

**Objective**
The runner/sweep-launcher form, results drill-down (metrics, trades, equity/drawdown curves), and multi-run comparison with like-for-like guards.

**Why this phase is independent**
Results/trades/folds endpoints exist (Stage D §30 results, §31 folds), as do the benchmark-relative columns and the Monte Carlo endpoint (Stage D Phase 32A [FP-31, FP-32, owner selection 2026-06-12]); pure frontend.

**Deliverables**
- `/backtests/run` runner page (E-8): full-parameter backtest submission (strategy version, instrument, interval, date range, universe override, costs, seed) **and the sweep launcher** (method, maxTrials, objective incl. `fold_aggregation` — E-12.2, constraints, walk-forward) → `202 {jobId}` flows into the jobs monitor (E-3 / E-11 screen 4).
- `/backtests/:id` (E-11; metrics set E-12.5) — metric panel, virtualized trade table with **per-trade reasoning drill-down (reuses `ReasoningBreakdownPanel`** — E-11 screen 7 / E-12.1), equity + drawdown curves (lightweight-charts), **fold panel for walk-forward runs** (grouped train-vs-OOS bars + fold table with date ranges, degradation, regime chips when present — degrades cleanly when `regimeMix` null — E-12.4).
- `/backtests/compare` (E-11 screen 5) — **up to 6 runs/trials**: best-per-column metric matrix, overlaid normalized equity/drawdown, trade-distribution histograms, **`dataHash` mismatch banner**.
- **Monte Carlo tab** on `/backtests/:id` [FP-31, owner selection 2026-06-12]: fan chart of the 5/50/95 equity bands + drawdown-distribution histogram from `GET /api/v1/backtests/{id}/montecarlo` (`montecarlo_summary` persisted by Stage D Phase 32A; seeded resampling — deterministic on the mock stack); bands via lightweight-charts, histogram via ECharts on the compare/analytics chunk (bundle budget — E-6); tab hidden cleanly when the summary is NULL (runs predating Phase 32A — E-12.5).
- **Benchmark-relative surfaces** [FP-32, owner selection 2026-06-12]: buy-and-hold **benchmark overlay** on the `/backtests/:id` and compare equity curves (from the persisted `benchmark_curve` — never client-recomputed, same rule as the equity curve), plus **alpha / beta / information-ratio / excess-CAGR columns** in the compare metric matrix; the same columns surface on the Phase 39 leaderboard (E-12.5). Decimal strings throughout; NULL-safe for runs predating Phase 32A.
- `BacktestsStore` completed (E-1).
- E2E: submit a backtest **and launch a sweep from the UI** → results → compare two runs.

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
cd frontend-ui && npm test && cd ../e2e && npx playwright test
```

**Tests & Verification**
- Vitest: metric formatting (decimal strings, **no `parseFloat` arithmetic** — E-6), fold panel null-handling (`regimeMix` null — E-12.4), Monte Carlo tab / benchmark overlay NULL-handling (runs predating Phase 32A render without the tab/overlay/columns [FP-31, FP-32, owner selection 2026-06-12]). E2E green.

**Acceptance criteria**
- PASS: comparing runs with different `dataHash` shows the banner; fold panel renders for walk-forward and hides otherwise.
- FAIL: equity curve recomputed client-side from trades (must use the **downsampled persisted curve** from `GET /backtests/{id}/results`).

**Commit message**
`feat(frontend): backtest results drill-down with fold panel and multi-run comparison`

**PR title**
`Phase 38: backtest results + compare UI`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) runner + results page; (b) compare page.

---

## Phase 39 — Sweep explorer + leaderboard UI (regime cols, badges, folds)

**Objective**
Make optimization output actionable: live ECharts trial explorer, the leaderboard with every §7.5 guard surfaced (E-12.6), and promote-winner — completing the anti-overfitting block's UI half.

**Why this phase is independent**
Optimizer endpoints + `jobs.progress` exist (Stage D §33–§34: sweeps, leaderboard, promote); pure frontend.

**Deliverables**
- **Sweep detail view** (from `/backtests/jobs`; E-11 screen 4): ECharts 5.6 **trial scatter** (objective vs trial #, best-so-far step line, pruned greyed), **parallel-coordinates** colored by objective (brushing filters the table), **heatmap** for 2-param grids — all updating live from `/topic/jobs/{sweepJobId}`.
- **Leaderboard** (`GET /optimizations/{id}/best`; full presentation E-12.6): plateau-adjusted default sort (raw one click away), per-regime OOS Sharpe/expectancy columns + regimes-covered badge, guard-7 degradation badge (with n/a state), "n folds excluded" flag, `dataHash` parity badges, links to results/trades/folds.
- **Trial fold drill-down**: the Phase 38 fold panel (E-12.4) fed by `/trials/{id}/folds`; pruned trials render partial-coverage, never compared on truncated folds.
- **Promote-winner action** → diff preview → new-draft confirmation (E-12.6).
- E2E: sweep → explorer populates live → promote → draft visible in strategy list.

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
cd frontend-ui && npm test && cd ../e2e && npx playwright test
```

**Tests & Verification**
- Vitest: plateau-sort display logic, badge states, excluded-folds flag. E2E green on a 10-trial mock sweep.

**Acceptance criteria**
- PASS: a Pareto (`nsga2`) sweep renders a **front scatter, never a single winner**; **every §7.5 guard 1–7 output is visible somewhere on this screen** (E-12.6).
- FAIL: **radar charts** (rejected in BPC — E-12.4); leaderboard hiding invalid/pruned trials instead of flagging them.

**Commit message**
`feat(frontend): live echarts sweep explorer and guard-complete optimization leaderboard with promote`

**PR title**
`Phase 39: sweep explorer + leaderboard`

**Time estimate**
120–150 min

**Token size target**
≤ 35k output tokens.

**If phase too big**
(a) explorer charts; (b) leaderboard + fold drill-down + promote.

---

## Phase 40B — Indicator-series endpoint (ta4j overlays) [A13, 2026-06-12]

**Objective**
Serve engine-computed indicator series (overlays/oscillators) for the chart page from the ta4j engine — the server-side studies design (E-4/E-9): a registry endpoint plus a series endpoint, so the frontend never computes indicator math (S7 rule — no third implementation of parity-critical math).

**Why this phase is independent**
The engine indicator registry + golden vectors exist (Stage C); cached candles/caggs exist (Stage B). backtest-service is the only service that already embeds the strategy-engine JAR **and** holds `marketdata` read-only grants — no new service, no new grants. Computing an indicator series over a candle window is a **bounded replay**: served on the **web threadpool, never the backtest worker pool**.

**Deliverables**
- `GET /api/v1/indicators` — registry list: `id`, `label`, params with defaults, output series names, render hints (`line`|`histogram`), pane hint (`price`|`sub`) — driven by the engine indicator registry, with no per-indicator endpoint code.
- `GET /api/v1/indicators/{id}/series?symbol=&interval=&from=&to=&params=` (`params` = URL-encoded JSON) returning named `{time, value}` series — multi-output indicators (e.g. Bollinger) return several named series; values as **decimal strings** (platform convention).
- **Server-side warm-up over-fetch**: e.g. EMA(200) fetches 200 lookback bars before `from` and returns values aligned to the requested range.
- **Redis cache** keyed `(indicator, params, symbol, interval, range)`.
- **422 `DATA_GAP`** on missing candle coverage, per the error taxonomy; **OpenAPI** documented.
- **Gateway route**: `/api/v1/indicators/**` → backtest-service (D8).
- Contract note for the frontend (Phases 40/40C): v1 refresh is **closed-bar only** — active overlays re-fetch on the existing closed-bar/candle WS event; **no per-tick recompute in v1**.

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
./mvnw -pl services/backtest-service -am verify
```

**Tests & Verification**
- Unit tests assert series values **exactly equal the engine golden-vector outputs** (S7 — no second/third implementation of the math).
- Warm-up correctness at range edges (first in-range value already converged; no leading-null drift).
- Cache-key behavior: distinct keys per (indicator, params, symbol, interval, range); a cache hit serves without recompute.

**Acceptance criteria**
- PASS: golden-vector equality proven; **registry-driven** — adding an indicator requires no per-indicator endpoint code; series served on the web threadpool, **outside the backtest worker pool**.
- FAIL: any TypeScript/client-side indicator math (S7 — E-9); a new service for indicators; worker-pool coupling.

**Commit message**
`feat(backtest): indicator-series endpoint serving ta4j-computed chart overlays (A13)`

**PR title**
`Phase 40B: indicator-series endpoint (A13)`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) registry + series endpoint; (b) cache + gateway route + contract tests.

---

## Phase 40 — lightweight-charts main chart page + containment boundary (S7) [A13, 2026-06-12]

**Objective**
Build the `/charts` page on lightweight-charts ≥5.2 with the library-agnostic datafeed core and `LwcChartBinding`, and enforce the lint containment boundary (S7 — E-9/E-10).

**Why this phase is independent**
Candle/instrument endpoints + WS topics exist (Stage B); the Phase 40B indicator-series endpoint exists (built first — stage build order). There is **no owner-supplied bundle**: lightweight-charts is a pinned npm dep, so every deliverable — including E2E — is unconditional (CD-9 — E-9).

**Deliverables**
- `lightweight-charts` npm dep pinned **`>=5.2 <6`** — the design depends on the v5.0.4+ marker-performance fix, v5.1 data conflation, v5.2 series hit-testing, and v5 native panes (E-4); Apache-2.0 + NOTICE attribution recorded in `docs/LEGAL.md` (E-9).
- `/charts` lazy route hosting `LwcChartComponent`.
- **Datafeed core** (library-agnostic, internal candle DTOs — full spec E-10.1): REST/WS candle access (resolutions 1m–1w; `1w` from the `candles_1w` cagg [FP-8, owner selection 2026-06-12]), the **internal paging contract**, IST bucket flooring, **bidirectional** timestamp normalization (`UTCTimestamp` epoch-seconds + constant +05:30 IST shift intraday; `BusinessDay` unshifted daily/weekly), live tick→bar aggregation, refcounted subscriptions, the `getServerTime` app utility.
- **`LwcChartBinding`** (E-10.2): `IChartApi`/`ISeriesApi`/pane lifecycle; `setData` on load + `series.update(bar)` from live subscriptions + the `update(bar, true)` `historicalUpdate` path for late/amended bars; pagination from `subscribeVisibleLogicalRangeChange`; `priceFormat` (`precision`/`minMove`) from the instrument master; decimal-string→number conversion at the render boundary only; volume histogram on an overlay price scale; `--ay-*` theming via `getComputedStyle` + `applyOptions`; **`attributionLogo` on** (E-9 license posture).
- **ESLint `no-restricted-imports` boundary**: `lightweight-charts` importable only inside **designated chart-wrapper components** — the lazy `/charts` module **plus** the existing shared sparkline/equity-curve wrappers (a naive "no LWC outside `/charts`" rule is WRONG — E-9) — **CI-enforced**.
- E2E via **network-layer stubs** (REST candle endpoints + WS ticks — there is no object-injection seam): chart loads on the mock stack; `ws_disconnect`-style gateway restart → reconnect recovery. **Unconditional — no skip clauses** (CD-9).

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
cd frontend-ui && npm test && npm run build   # /charts chunk ≤ 400 KB gz (trivially met under lightweight-charts — E-6)
```

**Tests & Verification**
- Vitest: internal-pagination contract (the renamed/re-scoped `countBack` test), IST bucket flooring, refcounting, and **bidirectional time-shift conversion** — the new countBack-class error magnet (E-10.1). Lint boundary verified by a deliberate violation failing CI locally.

**Acceptance criteria**
- PASS: chart-library types confined to the designated chart-wrapper components (lint-proven); **datafeed core has zero chart-library imports** (E-10.1); no chart lib in the initial chunk.
- FAIL: hand-rolled TS indicator math (S7 — E-9); a second main-chart renderer; **any TradingView/`charting_library` artifact** (A13); chart lib in the initial chunk.

**Commit message**
`feat(frontend): lightweight-charts main chart page with library-agnostic datafeed core and lint containment boundary (A13)`

**PR title**
`Phase 40: lightweight-charts charts page + containment (S7, A13)`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) datafeed core + binding + tests; (b) `/charts` route + lint boundary + E2E.

---

## Phase 40C — Chart toolbar, overlays & persistence [A13, 2026-06-12]

**Objective**
Ship the first-party chart chrome that replaces TV's built-in UI: interval picker, instrument search, engine-computed overlays/oscillators over the Phase 40B registry, crosshair legend, the accessible table view, and chart-state persistence (E-4/E-5/E-7).

**Why this phase is independent**
The `/charts` page, datafeed core and `LwcChartBinding` exist (Phase 40); the Phase 40B registry + series endpoints exist; instrument search reuses the existing instrument-search endpoint. Pure frontend.

**Deliverables**
- **Interval picker**: 1m/5m/15m/1h/1d/1w over the existing caggs; `1w` from the `candles_1w` cagg [FP-8, owner selection 2026-06-12].
- **Instrument search**: PrimeNG autocomplete on the existing instrument-search endpoint, reusing the TopBar/`MarketStore` search.
- **Overlay/oscillator picker** over the Phase 40B registry — v1 param editing = registry defaults + period override only; every value fetched from the indicator-series endpoint, **never computed client-side** (S7).
- **Oscillators in native v5 sub-panes** (`addPane`/`moveToPane`/`setStretchFactor`).
- **Overlay back-fill on pagination**: per-active-overlay range-aligned re-fetch as candles are prepended.
- **Closed-bar overlay refresh**: active overlays re-fetch on the existing closed-bar/candle WS event — no per-tick recompute in v1 (Phase 40B contract).
- **Crosshair OHLCV + active-overlay-values legend** (`subscribeCrosshairMove` + DOM overlay).
- **Chart-state persistence** to `localStorage` (symbol, interval, overlay set + params, pane layout) — replaces TV save/load (E-10.2).
- **"View as table" toggle** — the **sole accessible representation of chart data** (OHLCV + active overlay values + marks); an explicit deliverable (E-7).
- **Responsive toolbar collapse** (< 768 px — E-5); all chrome is first-party and must itself pass axe/keyboard checks (E-7).

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
cd frontend-ui && npm test && npm run build && cd ../e2e && npx playwright test
```

**Tests & Verification**
- Vitest: chart-state persistence round-trip (save → reload → restore); overlay range alignment on pagination back-fill (overlay series stays aligned to prepended candles); legend values match the series values at the crosshair time.
- `@axe-core/playwright` assertions on `/charts` (toolbar, pickers, legend, table view).

**Acceptance criteria**
- PASS: every overlay value on screen sources from the Phase 40B endpoint — **no client-side indicator math** (S7); chart state restores across a reload.
- FAIL: client-side indicator computation anywhere; overlay misalignment after pagination back-fill.

**Commit message**
`feat(frontend): chart toolbar, engine-computed overlays and chart-state persistence (A13)`

**PR title**
`Phase 40C: chart toolbar, overlays & persistence (A13)`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) toolbar + search + persistence; (b) overlays + sub-panes + legend + accessible table.

---

## Phase 40A — Chart-context drill-down: signals & trades on charts [FP-67, owner selection 2026-06-12; A13, 2026-06-12]

**Objective**
Connect the chart page to the strategy surfaces: render entry/exit/SL/target marks for backtest trades and emitted signals via lightweight-charts **series markers** (`createSeriesMarkers`) — plus price lines in single-trade/signal focus mode only — with hover tooltip and click-through, and deep-link from the Phase 38 results trade table and the signals page straight to `/charts` centered on the trade — the "replay my losers on the chart" workflow (full contract: E-10.3). Includes the short marker hit-test spike.

**Why this phase is independent**
The datafeed core + `LwcChartBinding` and the `/charts` route exist (Phase 40; toolbar 40C); trades/signals REST endpoints exist (Stage D §30, Stage C §23) and gain only **read-only query params** — no new service, no new endpoint, no schema change. lightweight-charts is a pinned npm dep — the marks E2E is unconditional (CD-9 — E-9).

**Deliverables**
- **Marks fetch in the library-agnostic datafeed core** (E-10.3): instrument + time-range fetch returning internal mark DTOs — backtest-trade marks **by `runId`**, signal marks **by symbol + time window** — via read-only query params on the **existing** trades/signals endpoints (e.g. `GET /api/v1/backtests/{id}/trades?symbol=&from=&to=`, `GET /api/v1/signals?symbol=&from=&to=`); zero chart-library imports in the core (E-10.1 rule); mark timestamps through the same IST/Java-timestamp normalization as bars; prices as decimal strings.
- **Marker rendering via `createSeriesMarkers`** (E-10.3) [A13, 2026-06-12]: entry = `arrowUp` `aboveBar` in `--ay-bull`, exit = `arrowDown` `belowBar` in `--ay-bear` — paired glyphs, never color-only (E-7); SL/target = **price-positioned markers** (`atPriceTop`/`atPriceBottom`/`atPriceMiddle` with explicit `price`); `createPriceLine` for SL/target **only in single-trade/signal focus mode** (deep-linked `runId`+`tradeId` or `signalId`), **never in the multi-trade view** (series-wide full-width lines would stack into noise); the **timescale-mark lane is dropped** (no LWC equivalent — explicit de-scope); marker `time` values get the same IST bucket-flooring as candles (a marker's time must coincide with a bar time at the active interval); tooltips carry price, qty, P&L.
- **Hover tooltip + click-through** (marker → trade/signal detail) via a custom crosshair overlay; includes a **short hit-test spike** — v5.2 `hoveredItem` may not surface marker identity from the markers plugin; fallback = own hit-region math from marker time/price (E-10.3).
- **Deep links**: `/charts?symbol=…&interval=…&runId=…` (or `…&signalId=…`) centers the chart on the trade/signal timestamp — the binding **loads bars around the target time T first, then `timeScale().setVisibleRange`** (explicit range orchestration — E-10.2) — with marks pre-filtered to that run/signal; "View on chart" row actions added to the **Phase 38 results trade table** and the **signals page** (Stage C surface).
- **Containment**: ALL lightweight-charts-typed marker code lives inside the lazy `/charts` module under the Phase 40 ESLint `no-restricted-imports` boundary (E-9); the marks REST fetch stays in the library-agnostic core.
- **Paper-trade marks are NOT in this phase** — they arrive as a one-line deliverable in Stage F Phase 43B, after the paper ledger lifecycle exists (E-10.3).
- E2E (mock stack): run a backtest on a fixture strategy → open the results trade table → click a trade row's "View on chart" → assert the chart navigates, centers on the trade, and renders its entry/exit markers — **unconditional** (CD-9).

**Minimal code/config**
none.

**DB changes**
none

**Build & Run**
```
cd frontend-ui && npm test && npm run build && cd ../e2e && npx playwright test
```

**Tests & Verification**
- Vitest: core marks fetch (runId vs symbol+window query-param mapping; IST bucket-flooring + bidirectional timestamp conversion of mark times — E-10.1 conventions), binding mark-DTO→`createSeriesMarkers` mapping (glyph/position/color by direction; price-positioned SL/target), focus-mode gating of `createPriceLine`, deep-link query-param parsing + load-range-then-`setVisibleRange` orchestration. Hit-test spike outcome recorded (`hoveredItem` vs own hit-region math). Lint boundary: a deliberate lightweight-charts-typed marks import outside the designated chart wrappers fails CI locally.
- E2E journey green on the mock stack (zero Kite credentials) — no skip path.

**Acceptance criteria**
- PASS: marks for a fixture backtest render on the chart and deep links land centered on the trade; marks are fetched **without any new endpoint or service** (read-only params on existing trades/signals endpoints); hover tooltip and marker click-through work; chart-library marker types appear nowhere outside `/charts` (lint-proven); datafeed core still has zero chart-library imports.
- FAIL: price lines in the multi-trade view; TV mark types (`Mark`/`TimescaleMark`/`getMarks`/`getTimescaleMarks`) anywhere; a new marks service/endpoint; marks assembled by client-side joining of full trade dumps where the query params exist.

**Commit message**
`feat(frontend): trade and signal marks on the lightweight-charts page with deep-link drill-down (A13)`

**PR title**
`Phase 40A: chart-context drill-down — signals & trades on charts (FP-67, A13)`

**Time estimate**
90–120 min (raised from 60–90 under A13 — tooltip/click-through + the hit-test spike)

**Token size target**
≤ 25k output tokens.

**If phase too big**
(a) core marks fetch + `createSeriesMarkers` mapping + unit tests; (b) tooltip/click-through + deep links from trade table/signals page + E2E.

---

## Phase 41 — Signal notifier: ntfy/Telegram + UI controls (Q6)

**Objective**
Push opted-in strategies' signals to the owner's phone: notifier Modulith module in strategy-signal-service (ntfy primary, Telegram alternative, plain HTTP POSTs), flood control, delivery audit, and the per-strategy UI controls. (Full design: E-14.)

**Why this phase is independent**
Signals flow since Phase 23 (Stage C); ntfy/Telegram are WireMock-stubbed in tests and E2E. Settings columns already exist (Phase 21 — Stage C registry).

**Deliverables**
- **`notifier` module** (E-14): subscribes in-process to emitted signals of strategies with `notifications_enabled`; payload = direction/entry/SL/target/composite-vs-threshold (**never credentials**); ntfy POST (own random-suffixed topic, **distinct from the ops topic**) / Telegram `sendMessage` plain HTTPS POST — **no bot library**; in-process async, bounded retry 3× expo backoff.
- **Flood control**: per-strategy cooldown dedup + global hourly cap emitting one "N suppressed" summary push.
- **Migration**: `notification_events` (signal FK, channel, `SENT/FAILED/SUPPRESSED`, attempts, detail) — every attempt audited (E-14).
- `POST /api/v1/strategies/{id}/notifications/test` (422 when disabled/unconfigured).
- **ntfy stub** (WireMock container) added to the e2e/test compose overlay; `NTFY_URL` points at it under mock — reused by Phase 45's (Stage G) alert verification.
- **UI**: notifications toggle + channel picker on strategy list/editor (operational columns — **toggling mints no version, perturbs no checksum** — E-14): asserted in an IT; test-send button.
- E2E: opt-in strategy → mock signal → WireMock-stubbed ntfy receives the push ≤ 5 s.

**Minimal code/config**
none.

**DB changes**
`strategy/V004__notification_events.sql`

**Build & Run**
```
./mvnw -pl services/strategy-signal-service -am verify
cd e2e && npx playwright test
```

**Tests & Verification**
- IT: send/fail/suppress paths audited; **checksum-invariance test** (toggle ⇒ same version checksum — E-14); hourly-cap summary.
- E2E push journey green.

**Acceptance criteria**
- PASS: a chatty strategy cannot flood (cap + dedup proven); ops alerts and signal pushes use **distinct topics**; **Stage-E exit — `PHASE_GATES.md` mirrors the plan §15.2 Phase-4 row** (Part 3 below).
- FAIL: notification settings inside the versioned YAML; any third-party bot SDK dependency.

**Commit message**
`feat(strategy-signal): ntfy/telegram signal notifier with flood control, delivery audit and ui opt-in`

**PR title**
`Phase 41: signal notifier (Q6)`

**Time estimate**
90–120 min

**Token size target**
≤ 30k output tokens.

**If phase too big**
(a) notifier module + audit + test-send; (b) UI controls + E2E.

---

# Part 3 — Stage exit gate (plan §15.2 Phase-4 row)

This is the input to the **S5 Friday gate ritual** (COMMON §4 CD-5 process / plan §15.6): at the Stage-E boundary, `PHASE_GATES.md` mirrors this Phase-4 row, and the checklist is walked against the running mock stack. An unchecked box extends the stage. Phase 41's last PASS line — *`PHASE_GATES.md` mirrors the plan §15.2 Phase-4 row* — is this gate.

### Phase-4 key deliverables (plan §15.2) — completion checklist

- [ ] Angular 21 SPA (zoneless, SignalStore per domain): **dashboard** (Phase 35).
- [ ] **Monaco + monaco-yaml strategy editor with schema validation** (Phase 36).
- [ ] **Version diff/publish UI** (Phase 37).
- [ ] **Backtest runner + jobs monitor** (Phases 35 jobs monitor, 38 runner).
- [ ] **ECharts 5.6 heatmap/parallel-coordinates trial explorer** (Phase 39).
- [ ] **lightweight-charts ≥5.2 equity curves** (Phases 36 drawer, 38 results).
- [ ] **lightweight-charts main chart page (Phase 40) + toolbar/overlays (Phase 40C)** [A13, 2026-06-12].
- [ ] **Indicator-series endpoint (ta4j overlays)** — registry + series API in backtest-service, golden-vector equality proven (Phase 40B) [A13, 2026-06-12].
- [ ] *Review additions:* **leaderboard per-regime OOS columns, degradation badge + fold breakdown panel** (§7.5–§7.7; S1A/S1B/BPC, +2 d) — Phases 38/39 (E-12.2/E-12.3/E-12.4/E-12.6).
- [ ] *Review additions:* **advisory stress-test panel in the publish dialog** (§7.7 screen 6; S1C, +1 d) — Phase 37 (E-13).
- [ ] *Review additions:* **chart-module lint boundary** — `lightweight-charts` imports confined to designated chart-wrapper components (the lazy `/charts` module **plus** the shared sparkline/equity-curve wrappers) via ESLint `no-restricted-imports`, CI-enforced; library-agnostic datafeed core with zero chart-library imports (§4.7; S7, +1 d; reframed by A13, 2026-06-12) — Phase 40 (E-9/E-10).
- [ ] *Review additions:* **signal notifier module** in strategy-signal-service — ntfy primary/Telegram alternative via plain HTTP POSTs (no shared library, no bot SDK), opt-in per strategy outside the versioned YAML, cooldown dedup + hourly cap, editor UI controls + test-send (§5.2.3/§12.7; Q6, +2.5 d) — Phase 41 (E-14).
- [ ] *Feature-selection additions (2026-06-12):* **INDIA VIX dashboard card** in MarketOverview [FP-14] + **reserved Global-risk settings slot** (controls/`strategy.risk_settings`/audit land in Stage F Phase 43A) [FP-42] — Phase 35.
- [ ] *Feature-selection additions (2026-06-12):* **Monte Carlo tab** (fan chart + drawdown histogram, `GET /api/v1/backtests/{id}/montecarlo`) [FP-31] and **benchmark buy-and-hold overlay + alpha/beta/IR/excess-CAGR columns** on results/compare [FP-32] — Phase 38 (E-12.5).
- [ ] *Feature-selection additions (2026-06-12):* **1w resolution** on the datafeed core (Phase 40) and the interval picker (Phase 40C) (`candles_1w` cagg) [FP-8; A13, 2026-06-12] — E-10.1/E-10.2.
- [ ] *Feature-selection additions (2026-06-12):* **trade/signal marks via lightweight-charts `createSeriesMarkers` + deep links** from the Phase 38 trade table and signals page, containment boundary preserved, no new endpoints [FP-67; A13, 2026-06-12] — Phase 40A (E-10.3).

### Phase-4 acceptance criteria (plan §15.2, demo-able) — gate checklist

- [ ] **Full Section 7 workflow clickable end-to-end on the mock stack** (the E-11 screens 1–7).
- [ ] **Playwright E2E suite green in CI.**
- [ ] **Strategy edit → quick backtest → publish loop < 2 min of user effort** (Phases 36–37).
- [ ] **An opted-in strategy's signal arrives as a phone push ≤ 5 s after emission** (mock stack: WireMock-stubbed ntfy endpoint) (Phase 41).

### Cross-cutting Stage-E acceptance invariants (carried from Part 1, must hold across all phases)

- [ ] **Zero polling where a topic exists** — the 10 s `GET /api/v1/system/status` fallback is the only poll (E-1); v1's 30 s/10 s/2 s polls all deleted.
- [ ] **No `markForCheck` anywhere; `OnPush` default; only signal-driven re-renders** (E-1/E-3).
- [ ] **Virtualized tables keep ~30 DOM rows** under all data volumes (E-3).
- [ ] **Bundle budgets met**: initial ≤ 500 KB gz (no chart lib in initial — import hygiene + CI bundle budgets are the guard); any lazy chunk ≤ 400 KB gz; Monaco only on the editor route; ECharts only on compare/analytics chunks; `/charts` lazy chunk ≤ 400 KB gz, trivially met under lightweight-charts (E-6) [A13, 2026-06-12].
- [ ] **No chart-library types outside designated chart-wrapper components** — the lazy `/charts` module plus the shared sparkline/equity-curve wrappers (lint-proven); datafeed core has zero chart-library imports (E-9/E-10) [A13, 2026-06-12].
- [ ] **Prices handled as decimal strings — never `parseFloat` for arithmetic** (E-6).
- [ ] **Equity/drawdown curves use the downsampled persisted curve**, never client-recomputed from trades (Phase 38).
- [ ] **Notification settings on DB rows, never in YAML; toggling perturbs no D18 checksum** (E-14, Phase 41 IT).
- [ ] **No `ngx-monaco-editor` wrapper; no `ChartingService` abstraction; no second main-chart renderer — reintroducing TradingView (or any renderer swap) requires a new ADR amendment [A13, 2026-06-12]; no client-side indicator engine (S7); no third-party bot SDK** (D2 / S7 / Q6 rejections).
- [ ] **WCAG 2.1 AA**: `@axe-core/playwright` assertions pass on every route; bull/bear never color-only (E-7).

### Stage-end notes

- **Timeline ledger (review §5).** Stage E carries the Phase-4 review additions **+6.5 d** (S1A 0.5 · S1B 0.5 · S1C 1.0 · BPC 1.0 · S7 1.0 · Q6 2.5) on top of the §15.2 Phase-4 baseline (4–5 FT weeks / 13–17 PT weeks). These are **additions, never silently absorbed**. Per-session estimates are in COMMON §5 (Phases 35–41: 90–150 min each).
- **A13 ledger (2026-06-12) — chart surface re-costed, never silently absorbed [A13, 2026-06-12].** Under A13 the chart-surface total is **~12.5–17.5 FT d** (Phase 40B 2.5–3.5 · Phase 40 2–3 · Phase 40C 5–6.5 · Phase 40A 2.5–3 · attribution 0.1) vs ~5–7 FT d for the old TV plan — a **net +1.5 to +2 FT weeks**, recorded as explicit A13 ledger entries (COMMON §16); the Q5 Phase 0 license task (**+0.25 d**) is returned. Drawings, if ever promoted off the Future list (COMMON §21.1), add ~+5 FT d.
- **Feature-selection additions (2026-06-12) [FP-8, FP-14, FP-31, FP-32, FP-42, FP-67, owner selection 2026-06-12].** Stage E gains **Phase 40A (90–120 min under A13 — raised from 60–90 for tooltip/click-through + the hit-test spike)** plus the Phase 35 (INDIA VIX card, reserved Global-risk slot), Phase 38 (Monte Carlo tab, benchmark overlay + relative-metric columns) and Phase 40/40C (1w resolution — datafeed core in 40, interval picker in 40C [A13, 2026-06-12]) extensions. Recorded in the COMMON §5 phase index and §16 stage ledger — additions, never silently absorbed (same convention as the review-ledger budgets).
- **De-scope seam.** The anti-overfitting block's UI half (S1A/S1B/BPC leaderboard + fold panel, S1C advisory) is part of the single §15.6 lever-1 de-scope unit (S1A + S1B + S1C + BPC + S8-pinning). `regimeMix` is the one nullable seam that lets BPC's fold panel survive if S1A is ever pulled (E-12.4). If the whole unit is de-scoped, Phases 38/39 still ship the base results/leaderboard without regime columns, degradation badge, fold panel, and stress advisory.
- **CD-11 parked scope.** The editor form-mode reduction (universe pickers / rule builder / risk fields YAML-only) is parked in `PHASE_GATES.md` at Phase 36 as a deliberate scope reduction, not a bug — those controls are a post-v1 enhancement (E-11 screen 2).
- **Handoff to Stage F.** Stage F (Options/Paper/Universe) reuses Stage-E infrastructure: `OptionsStore`/`PaperStore` follow the E-1 store pattern; the options chain table and IV-smile/OI-profile tabs use the same `p-table` virtual scroll + ECharts (E-3/E-4); the paper P&L equity curve uses lightweight-charts; the editor's "Published Universe (as of …)" label (S8 rest, Phase 44) extends the Phase-36 editor. Stage F **Phase 43A** fills the Phase 35 reserved Global-risk settings slot [FP-42, owner selection 2026-06-12], and **Phase 43B** adds paper-trade marks to the Phase 40A chart-marks surface (lightweight-charts series markers — `createSeriesMarkers` [A13, 2026-06-12]) [FP-67, owner selection 2026-06-12].
- **Handoff to Stage G.** The Phase-41 ntfy WireMock stub and `NTFY_URL` mock wiring are reused by Stage G Phase 45's obs-profile alert verification; the k6 WS fan-out gate (Stage G Phase 46) validates the E-3 conflation design's tick-to-browser ≤ 150 ms p99 target (COMMON §8.4).







