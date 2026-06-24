# PHASE_GATES (A.15 / S5)

One page only: the **current-phase marker**, a **checkbox copy of the current
phase's acceptance criteria**, and a **"deferred" parking list**. The stage
files under `docs/design/` stay the single source of truth — this file is the
S5 **Friday gate ritual** input: walk the checklist **against the running mock
stack** at each phase boundary; an unchecked box extends the phase. No hard
calendar freeze dates; no on-push gate enforcement (the machine-checkable
subset is CI-enforced).

---

## Current phase

**Re-platformed 2026-06-19 to the OpenAlgo + React master plan.**
`docs/superpowers/plans/2026-06-19-openalgo-react-integration-master-plan.md` §16.1 is now the
forward-work authority (Phases 0–6). The legacy **Stage A–G** system in the sections below is the
**historical as-built record** — all merged to `main` (A 2026-06-12; B PR #2; C PR #4; D PR #5; E
PR #6 `a96c99b`; F/G via the market-data + oipulse-parity PRs through #41). Its exit-gate
checklists stay as the as-built reference; new phase boundaries are tracked by the map below.

**Current frontier = Phase 4 (React) + Phase 6 (backtest).** As of 2026-06-25 Phases 0–3.5 are
MERGED and Phase 4 is substantially built (cockpit + React cutover + oipulse W1/W2/W3 + Data Ops
Console #121). **2026-06-25 frontend pass (#158–#177) MERGED + DEPLOYED:** the look/UX **revamp**
(design tokens + self-hosted fonts + shadcn bridge + TanStack DataTable + signature `PageHeader`/
`QueryState`/motion) rolled out to 64/65 pages, two new Upstox oipulse pages (**World Indices** #174/#176,
**Pre-Open Market** #175), and the **nav restructure** ("All Menu" mega-dropdown → a per-section menu bar,
#177). The active open work is the **data-foundation value-verify** (render every OI/data page
in History mode on a real session vs oipulse — gated on the expired/OI backfill, which is RUNNING) and
**Part 2's** real-data value-verify (options now backtest on their own premium). Deploy the Data Ops
Console after the backfill finishes (a market-data restart kills the in-flight job).

**2026-06-24 session (#136–#156):** the **Upstox login-free live migration** (U1 OI capture / U2 quotes /
U3 v3-WS ticker / F&O key map / cutover-prep), the **scalper registry completed to 12/12** (#3 Market
Movers + #8 BTST/STBT + **#11 long-straddle via a new two-leg/neutral engine primitive**), the dormant
**`OpenAlgoOrderGateway`**, **higher-order greeks** (vanna/charm/vomma), the **SPAN `.spn` ingest+golden
harness**, three more oipulse pages (OI heatmap / OI expiry / Open & High), and the **Scalping Cockpit
paper-trade panel + scalp-signal alerts** all merged — all flag-gated / paper / default-off, **nothing
live changed**. The full pending list is `docs/DEFERRED_BACKLOG.md`.

**Phase 3 — Track-2 Siva options scalper (MERGED #42/#43/#44).** The index-option core (#1/#5/#6/#10)
is paper-complete + risk-railed + execution-boundaried, with the manual-verification-checklist
backend done. **Phase 3.5 is COMPLETE**: the Tier-2
OI-analytics fidelity gaps T2.1–T2.8 are closed (per-side ΔOI cross/widening/drastic, sentiment
slope, spurt OI%/price%, 6-strike IV pair → 18 confluence dots + the #5 ≥50% ΔOI hard pre-gate),
and the four feasible index-option intraday strategies are implemented + seeded — **#4 Gap Theory,
#12 Trend Change, #2 Open=High/Low (per-strike Table-1/Table-2 faithful grading), #9 Morning Trade
(opening-tick)**. Golden + Parity stay byte-identical (V009 side-channel). The S24 monthly-expiry OI
suppression (`isMonthlyIndexExpiryDay` → skip the chain-OI reads) and the #2 per-strike OH/OL faithful
grading (`/options/strike-session-stats`, branch `feat/open-high-per-strike`) are also done. **Registry
now 12/12 (2026-06-24):** #3 Market Movers + #8 BTST/STBT (#148) + #11 long-straddle on a new two-leg/
neutral primitive (#155) seeded as paper drafts; #7 Hero-Zero done (#130). The **ONLY remaining strategy
gap = the SHORT-premium SELL legs of #8/#11** (gated on SPAN sizing live + live orders) and the full
stock-universe #3 (→ Track-1/Phase-5). Still deferred elsewhere: the §2 OiPulse ≥90% AI badge, Tier-3 OI
history.

### Master-plan phase map (§16.1)

| Phase | Branch | State |
|---|---|---|
| 0 — OpenAlgo spine | `feat/openalgo-spine` | **MERGED** (PR #39) |
| 1 — Data inflow (routing + ExpiryTrack OI + daily) | merged #40/#41/#112–#116, #137–#149 | **MOSTLY** — §4 routing + EOD bhavcopy daily (#40/#41) + §5 expired OHLCV+OI backfill (#112–#116, full pull RUNNING) + **Upstox login-free live capture (OI/quotes/v3-WS, #137/#139/#141/#149) BUILT flag-gated default-Kite** — cutover = deploy + A/B + flip. **DEFERRED**: §15 200-day daily history (Upstox v3 historical-candle can serve it, see backlog) |
| 2 — Quant libs (greeks + indicators) | merged #40, #156 | **DONE** — §7 scalp indicators (#40); §6 **higher-order greeks vanna/charm/vomma DONE (#156)** on `black76-math` + the chain (FD-cross-checked) |
| 3 — Scalper engine (§12 + §8 SPAN) | merged #42/#43/#44, #126, #144/#148/#154/#155 | **MERGED — registry 12/12** — core #1/#5/#6/#10 + Tier-2 OI fidelity + #2(faithful)/#4/#9/#12 + #7 Hero-Zero (#130) + **#3/#8 (#148) + #11 long-straddle on a two-leg/neutral primitive (#155)**, all paper drafts. **SPAN appliance dormant (#126) + `.spn` golden harness (#144)**; **`OpenAlgoOrderGateway` dormant (#154)**; checklist UI (#125). Only **DEFERRED**: SHORT-premium SELL legs of #8/#11 (SPAN live + live orders), full stock-universe #3 (→Track-1), §2 OiPulse badge |
| 4 — React migration (§10 + §11) | merged #82–#110, #121, #146–#177 | **IN PROGRESS** — cockpit + React cutover + oipulse W1/W2/W3 + Data Ops Console (#121) + new pages OI-heatmap/OI-expiry/Open&High (#146/#150/#153) + cockpit paper-trade panel & scalp alerts (#151/#152) merged; **2026-06-25 frontend pass MERGED+DEPLOYED** — look/UX **revamp** (tokens+fonts+shadcn+DataTable+signature header/QueryState/motion, #158–#163) rolled out to 64/65 pages (#166–#173) + **World Indices** (#174/#176) + **Pre-Open Market** (#175) Upstox pages + **"All Menu"→per-section nav bar** (#177); remaining: **data-foundation value-verify** (gated on the backfill) + Data-Ops deploy, OiPulse badge, `/orders` live-verify (#131 dormant) |
| 5 — Minervini Track-1 screener (§13) | `feat/minervini-track1` | **NOT STARTED** — needs Phase-1 §15 200-day history |
| 6 — Backtest + forward wiring (§14) | merged #114–#119 | **PARTIAL** — Part 2 premium-as-primary replay landed (options trade their own 1m premium, golden-pinned); remaining: real-data value-verify (gated on backfill), forward-test wiring (the v1 simplifications — per-bar MTM + premium-leg costs + 422 pre-flight — are CLOSED #123); needs Phases 3 + 5 |

---

## Stage-F exit gate (plan §15.2 Phase-5 row — mirrored from the Stage-F design)

*(Legend: **impl** = implemented + unit/IT/build/lint green per phase; **walk** =
exercised on the running mock stack / Playwright e2e — runs via ci-e2e on the PR.)*

**Phase 5 acceptance criteria (demo-able):**

- [x] **Chain refreshes live via WS** — `/options` updates from the `options.chain` topic
      through the gateway STOMP bridge `[Phase 42]` (impl)
- [x] **Historical IV query over own snapshots** — history mode returns stored
      `options_chain_snapshots` rows via `GET /options/chain/history` `[Phase 42]` (impl)
- [x] **Accepting a signal opens a paper position whose P&L tracks ticks** —
      `signals/{id}/taken` (with qty) → paper position → mark-to-market from the last-tick
      map `[Phases 43/43A]` (impl; IT-proven taken→position)

**Key-deliverable checklist:**

- [x] Options chain UI (Black-76 IV/Greeks, PCR, wired filters) + snapshot-history `[42]` (impl)
- [x] Futures workbench (`/futures` term-structure, basis history, oi-buildup) `[42A]` (impl)
- [x] IV rank/percentile rollup (`iv_daily_summary`, `GET /options/iv-history`, badge/tab,
      honest insufficient-history floor) `[42B]` (impl; IT recompute-idempotent)
- [x] Paper-trading ledger on the **shared `ltp_slippage/v1` FillSimulator** with fill-audit
      columns + P&L UI — paisa-parity to the backtest vector proven `[43]` (impl)
- [x] Paper account + capital model + global risk limits + kill switch + `suggested_qty`
      at emission `[43A]` (impl; daily-loss trip pauses ENTRY only, IT-proven)
- [x] Derivative expiry settlement (intrinsic vs spot LTP, expiry STT leg, `close_reason`)
      + T-1 roll-or-close push + paper chart marks `[43B]` (impl; spot-LTP approximation
      documented in the manual guide / settlement caveat)
- [x] Universe pinning — submission-time resolve (REST-only) copied into `jobs.request`
      + checksum; publish guard lifted; editor "Published Universe (as of …)" label `[44]`
      (impl; `universe_checksum` now persisted onto `backtest_runs` rows + echoed in `/results`
      + the `/backtests/compare` universe-checksum mismatch banner beside `dataHash` — follow-on)
- [x] Trade journal — `journal_entries` + CRUD + drawer (signals surface) + `/journal`
      review route `[44A]` (impl; paper-position/closed-trade + backtest-trade drawer entry points
      now wired — follow-on)

**Cross-cutting invariants (impl):**

- [x] Resolution REST-only, submission-time, by-copy; strategy-signal holds no `marketdata`
      grant (Phase 44 FAIL guarded)
- [x] Paper fill == backtest fill **to the paisa** (shared JAR); no paper-local fill path
- [x] Unrealized P&L / equity computed on demand, never stored; risk limits on DB rows, never YAML
- [x] No cross-schema FK (journal/paper soft references only); same-schema FKs validated
- [x] Prices as decimal strings end-to-end (new exact `subtractDecimal`/`multiplyByInt`)
- [x] Chain/futures pages use per-symbol topics, never the tick firehose

---

## Stage-E exit gate (plan §15.2 Phase-4 row — mirrored from the Stage-E design Part 3)

*(Legend: **impl** = implemented + unit/build/lint green per phase; **walk** = exercised
on the running mock stack / Playwright E2E.)*

### Phase-4 key deliverables

- [x] Angular 21 SPA (zoneless, SignalStore per domain): **dashboard** `[Phase 35]` (impl)
- [x] **Monaco + monaco-yaml strategy editor with schema validation** `[Phase 36]` (impl)
- [x] **Version diff/publish UI** `[Phase 37]` (impl)
- [x] **Backtest runner + jobs monitor** `[Phases 35, 38]` (impl)
- [x] **ECharts 5.6 heatmap/parallel-coordinates trial explorer** `[Phase 39]` (impl)
- [x] **lightweight-charts ≥5.2 equity curves** `[Phases 36 drawer, 38 results]` (impl)
- [x] **lightweight-charts main chart page + toolbar/overlays** `[Phases 40, 40C]` (impl)
- [x] **Indicator-series endpoint (ta4j overlays)** — registry + series in backtest-service,
      golden-vector equality proven (IndicatorSeriesServiceTest) `[Phase 40B]` (impl)
- [x] **Leaderboard per-regime/degradation/fold panel** `[Phases 38/39]` (impl; per-regime/
      dataHash/folds-excluded surface in the fold drill-down + Pareto, not dedicated /best
      columns — backend gap, parking)
- [x] **Advisory stress-test panel in the publish dialog** `[Phase 37]` (impl)
- [x] **Chart-module lint boundary** (no-restricted-imports, CI-enforced; datafeed core has
      zero chart-library imports) `[Phase 40]` (impl; deliberate-violation-fails-lint verified)
- [x] **Signal notifier module** (ntfy/Telegram plain POST, opt-in outside the YAML, cooldown
      + hourly cap, editor controls + test-send) `[Phase 41]` (impl; FloodControl + Modularity
      green; NotifierIntegrationTest at the IT walk)
- [x] **INDIA VIX dashboard card + reserved Global-risk settings slot** `[Phase 35]` (impl)
- [x] **Monte Carlo tab + benchmark overlay + alpha/beta/IR/excess-CAGR columns** `[Phase 38]` (impl)
- [x] **1w resolution** (datafeed core + interval picker, `candles_1w`) `[Phases 40, 40C]` (impl)
- [x] **Trade/signal marks via createSeriesMarkers + deep links**, no new endpoints,
      containment preserved `[Phase 40A]` (impl)

### Phase-4 acceptance criteria (demo-able)

- [x] **Full Section-7 workflow clickable end-to-end on the mock stack** — walked
      2026-06-14; the 23-test Playwright suite is green on the rebuilt mock stack.
- [x] **Playwright E2E suite green** — 23/23 on the local mock stack (login, dashboard,
      charts + toolbar + marks, editor, versions, runner, sweep shell, signals MVP,
      notifier push, ws-reconnect, axe every route); ci-e2e mirrors it on the PR.
- [x] **Strategy edit → quick backtest → publish loop < 2 min** — create-from-template
      → validate → save → quick-backtest drawer walked; a fully-covered windowed run
      uses the guide's derived 1m window (the boot-rolling mock has thin 1m history).
- [x] **Opted-in strategy's signal arrives as a phone push** — the notifier pushes to
      the WireMock-stubbed ntfy on opt-in + test-send (walked); the in-process listener
      fires on emission.

### Cross-cutting invariants

- [x] Zero polling where a topic exists (only the 10 s system-status fallback) (impl)
- [x] No `markForCheck`; OnPush default; signal-driven re-renders (impl)
- [x] Large tables scroll within a fixed-height viewport (impl — plain `p-table` + `scrollHeight`;
      `virtualScroll` removed: the PrimeNG 21 scroller renders 0 rows / collapses to 0-height under
      zoneless CD — bounded per-view row counts make plain render fine; a zoneless-compatible
      virtualization is parking)
- [x] Bundle budgets: initial ~113 KB gz (no chart/editor lib in initial); Monaco editor route
      ~562 KB gz is the documented exception (parking) (impl)
- [x] No chart-library types outside the designated wrappers (lint-proven) (impl)
- [x] Prices as decimal strings — never `parseFloat` arithmetic (impl)
- [x] Equity/drawdown curves use the persisted downsampled curve (impl)
- [x] Notification settings on DB rows, never YAML; toggling perturbs no checksum
      (NotifierIntegrationTest checksum-invariance) (impl)
- [x] No ngx-monaco-editor wrapper; no ChartingService; no second main-chart renderer; no
      client-side indicator engine; no third-party bot SDK (impl)
- [x] WCAG 2.1 AA: `@axe-core/playwright` clean on every route (walked — light-theme
      palette/primary/toggle contrast + empty-table-header fixed in the walk)

---

## Stage-D exit gate (plan §15.2 Phase-3 row — mirrored at Phase 34; walked 2026-06-13 against the running mock stack)

*(Walk legend: **live** = exercised end-to-end on the running mock stack;
**IT** = gated by green Testcontainers/golden tests in CI, not separately
hand-walked on the mock stack — some paths can't be shown on a ~3-day rolling
mock window or without an options archive.)*

- [x] `POST /api/v1/backtests/run` → **`202 {jobId}`** → progress via
      `jobs.progress` WS (`/topic/jobs/{jobId}`). **(live: 202→completed→resultRef)**
      `[Phase 28]`
- [x] **Engine-parity test passes** — same YAML + candles ⇒ **identical trades
      live vs. backtest** (byte-identical signal lists incl. per-indicator
      breakdowns; the D15 headline gate). **(IT: TickwiseGoldenRunner replay half)**
      `[Phase 30]`
- [x] **A sweep completes and ranks configs** (grid/random/TPE/NSGA-II over
      `optimize.parameters`; leaderboard with **plateau-adjusted sort**; winner
      **promotable to a draft**). **(live: grid/TPE/NSGA-II runs, NSGA-II Pareto
      cagr+maxDrawdown, plateau `/best`, 30-trial ranking, promote→201 draft
      1.1.0; the 200-trial scale is the design target, mechanism proven at 30)**
      `[Phases 33–34]`
- [x] **S3 spike gate** (Phase 34 acceptance): pruner defaults
      (`n_startup_trials=5` / `n_warmup_folds=3`, `n_min_trials=2`) **run, recorded
      as a dated ADR amendment** (`docs/design/DECISIONS_LOG.md`, 2026-06-13) **and
      configured** — fold-fed `MedianPruner` is **enabled** (the "or pruning
      disabled" branch not taken). `[§D.13 / Phase 34]`
- [x] **A9 execution semantics green** [FP-5/6/7]: fill vectors pass for **futures
      cost legs**, **`at_close` fills**, and the **intra-bar exit-touch rule** (1m
      drill, worst-of/gap-through fallback; every closed trade records
      `touch_basis`); the **BTST pre-close bar view** assembles byte-identically
      live vs replay. **(IT: FillSimulator + replay fill-vector tests)**
      `[Phases 29–30]`
- [x] **Extended pre-flight demonstrated** [FP-1/3/19]: context-instrument
      coverage (422 `DATA_GAP` naming the context series), corporate-action window
      warning, lot/tick **as-of trade date** with the pre-accrual honesty flag.
      **(live: 422 `DATA_GAP` on missing NIFTY 50 benchmark; corp-action/lot-tick IT)**
      `[Phase 30]`
- [ ] **Options fidelity contract live** [FP-4]: an options run on mock snapshots
      records `premium_source=SNAPSHOT`; archive gap → 422 `DATA_GAP`; synthetic
      mode completes flagged `SYNTHETIC_B76` (never masquerading as snapshot-grade);
      market-data Greeks **byte-identical** after the `libs/black76-math` hoist.
      **(IT only: byte-identical Greeks + SNAPSHOT/SYNTHETIC tests green; live walk
      DEFERRED — needs an options archive + multi-month window, see parking list)**
      `[Phase 30A]`
      — **UPDATE 2026-06-24:** Part 2 (#114–#119) landed the **premium-as-primary** replay: an options
      backtest now trades the option's OWN 1m premium series (`premium_source=CANDLE_1M`), golden-pinned
      (`OptionsPremiumGoldenTest`), and the expired-contract archive that feeds it is now loading
      (#112–#116). The SNAPSHOT/SYNTHETIC live walk + the real-data value-verify remain (parking list).
- [x] **Run analytics live** [FP-31/32]: results carry
      alpha/beta/information-ratio/excess-CAGR + the benchmark buy-and-hold curve
      beside `equityCurve`; `GET /api/v1/backtests/{id}/montecarlo` returns seeded,
      reproducible bands and persists `montecarlo_summary`. **(live: analytics +
      seeded reproducible Monte Carlo verified)** `[Phase 32A]`

**Stage-end notes:** Stage E's leaderboard UI consumes the per-regime OOS columns,
the degradation badge, the "n folds excluded" flag and the fold-breakdown panel
produced here; the advisory stress-test panel consumes the Phase 32 backend.
Universe-pinning (`backtest_runs.universe_checksum`) and paper fill-audit columns
land in Stage F (the `FillSimulator` JAR itself is complete at Phase 29). The
optimizer (Phases 33–34) can overlap Stage E (different stack).

*(How Stage C was walked: Phases 18–27 implemented phase-per-commit with unit +
Testcontainers ITs + Vitest, then a full-stack Playwright E2E that drove the live
MVP through a real browser for the first time — it exposed and fixed 9
integration gaps unit/IT coverage could not reach (SPA auth-gating made login
unreachable; the STOMP `Sec-WebSocket-Protocol` echo missing failed every
browser WS handshake; a strict CSP blocked PrimeNG inline styles; the auth probe
trusted any 200 and admitted anonymous users; a `+05:30` candle warm-up query
encoding 500'd, leaving the engine cold). Then an 18-agent adversarial
spec-vs-impl audit (8 reviewers, independent verification of every finding)
confirmed 1 CRITICAL + 8 MAJOR gaps — headline: `candles.1m.*` conflated with
latest-value-wins (a dropped bar = permanent series gap + a possibly-skipped
exit), `.nan`/`.inf` 500s, duplicate YAML keys defeating the checksum,
score-breakdown decimals as rounding JSON numbers, registry filter-after-
pagination, phantom ARCHIVE audit rows, and a wall-clock `generated_at` breaking
live↔replay determinism — all fixed and regression-tested in the audit commit.
strategy-schema + strategy-engine + both services green; Playwright E2E 7/7.)*

---

## Stage-C exit gate (plan §15.2 Phase-2 row — the MVP gate — walked 2026-06-13 against the running mock stack)

- [x] **Golden-vector tests pin determinism** — same YAML + same candles ⇒
      identical signals/scores/breakdowns. `GoldenDeterminismTest` 5/5 byte-
      matches the frozen fixtures across two runs; the `ScoreBreakdown` writer is
      byte-stable (now exact-decimal strings); the replay half lands Stage D. `[Phase 23]`
- [x] **Publishing a YAML strategy → a live signal pushed over gateway STOMP,
      visible in the browser.** The Playwright MVP test publishes a strategy via
      the API and sees a live `RELIANCE`/`ENTRY` row stream onto `/signals` with
      its reasoning breakdown — the MVP statement, driven end-to-end through a
      real browser. `[Phases 23+26]`
- [x] `strategy-schema/v1` **complete + frozen** — 31-fixture corpus green;
      `slippage_bps`, `fees{}`, `objective.fold_aggregation`, `walk_forward`,
      `scoring.{optional_min_score, optional_gate_margin}` and the A7 additions
      (`1w`, `risk.session.{pre_close_at, fill_timing, exit_intrabar}`, indicator
      `instrument` override, `universe.mode: futures_of_underlying` + `futures{}`)
      all present + validated; indicator-name enum stays advisory (Q2). The
      loader now rejects non-finite scalars and duplicate keys (audit). `[Phase 18]`
- [x] strategy-engine JAR — `IndicatorVectorTest` 19/19 (ta4j matches the
      committed reference vectors exactly), `CompositeScorerTest` 9/9 (the
      normative A1 composite + optional-activation truth table),
      `BreakdownContractTest` 5/5 (byte-stable `ScoreBreakdown`); JaCoCo BRANCH
      ≥ 70 %. `[Phases 19–20]`
- [x] Registry — immutable JSONB versions + SHA-256; full
      draft→published→archived with publish/rollback/diff/validate; every
      mutating call writes an audit row (append-only BY GRANT);
      `index_constituents`-universe publish guard (422
      `STRATEGY_UNIVERSE_UNSUPPORTED`). `RegistryLifecycleIntegrationTest` 12/12,
      incl. the audit's filter-then-paginate + archive-idempotency fixes. `[Phase 21]`
- [x] `marketdata.index_constituents` — append-only with point-in-time REST
      resolution (latest-on-or-before, audit-confirmed); mock fixture path green;
      live NSE fetcher gated on source verification; no cross-schema FK;
      survivorship-bias caveat documented. `[Phase 22]`
- [x] OpenAPI 3.1 specs for the three running services committed and **diff-
      gated** in CI; each `ContractCaptureTest` green; generated TS client
      compiles under `tsc --strict` (ci-contracts). `[Phase 24]`
- [x] Angular 21 SPA (zoneless, signals-first) served **through the gateway,
      same origin, zero CORS**; login round-trip works; initial bundle **457 KB
      raw / 109 KB transfer** (≤ 500 KB budget enforced); no Zone.js, no
      hardcoded `localhost`. SPA-shell auth + CSP relaxation fixed (E2E). `[Phases 25–26]`
- [x] `WsClientService` reconnects with backoff + jitter and re-syncs the REST
      snapshot; `/signals` renders the reasoning breakdown obeying
      `composite = Σ contributions / weightDenominator`. STOMP subprotocol echo
      fixed so the browser socket opens (E2E). `[Phase 26]`
- [x] **Playwright E2E 7/7 green** on the full mock stack: login (deep-link,
      cookie flags, wrong/right password, axe), the live-signals MVP + breakdown,
      signals-page axe, and the WS-reconnect chaos test; axe reports no
      violations on login/signals. `ci-e2e` runs the same suite on every PR
      (green-on-main lands at merge). `PHASE_GATES.md` mirrors this row. `[Phase 27]`

**Stage-end notes:** `strategy-schema/v1` (Phase 18) and the `ScoreBreakdown`
contract (Phase 20) **freeze here** — Stage D's FillSimulator/replay consume both
unchanged, and the replay half of the golden parity pair asserts byte-identity
against the live half frozen in Phase 23. **Open items carried forward:** NSE
index-constituents CSV source verification (before the Phase 22 live fetcher);
statutory fee-schedule values (pinned at Stage-D Phase 29). Neither blocks the
MVP demo on the mock stack. Owner action: mint a brand-new 2.0 Kite API
key/secret for live-mode (the Stage-C manual-testing guide's live appendix).

---

## Stage-B exit gate (plan §15.2 Phase-1 row — walked 2026-06-13 against the running mock stack; merged to main via PR #2)

*(Stage B walk: 13 phases phase-per-commit + a 39-agent audit — 3 CRITICAL + ~20
MAJOR fixed (post-close ticks re-opening the flushed close bar, continuous=1 on
per-contract FUT fetches, CONT via POST /candles/refresh); 164 market-data + 20
gateway tests green.)*

- [x] **Live tick reaches Redis < 50 ms after Kite delivery.** Measured live:
      tick generation → published-on-Redis = **3 ms** (mock feed, B-6
      pipeline, same-tick comparison of the embedded producer timestamp vs the
      `ticks:last-at` publish marker).
- [x] **Historical fetch fills gaps idempotently at ≤ 3 req/s.** Cold fetch =
      exactly one gateway call; warm read = zero gateway-port invocations
      (asserted); partial coverage fetches only the missing sub-range; 50-burst
      limiter test ≥ 15 s end-to-end and never > 3/s in any window.
- [x] **The same flows pass on the mock profile** — every Stage-B phase is
      mock-green with zero Kite credentials (the whole IT battery runs without
      any Kite material; live impls are WireMock-pinned).
- [x] **Snapshots accruing every 5 min in market hours** — the Phase-15
      scheduler is calendar-gated; the IT drives a market-hours clock and the
      off-hours degradation (stale:true, zeroed book, EOD OI) separately.
- [x] **Raw quote rows persist from the first market day with IV gated on S1**
      — every row carries LTP/bid/ask/spot/OI/oi_change + `forward_price` +
      `risk_free_rate`; the 490-vector golden suite (S1, above) gates the
      computed columns via `artha.options.iv-enabled`; null-IV rows persist
      with reason codes, never skipped.
- [x] **Contract canary runs on the first LIVE transition and surfaces on
      `/auth/kite/status`** — WireMock-verified both drift directions + the
      Redis daily-once marker; mock runs no canary by design. *(Result key is
      `kite:contract:check` — documented deviation, parking list.)*
- [x] **Contract-spec history accrues from the first sync** — FIRST_SEEN rows
      on sync, as-of resolution at change boundaries, `spec_asof_estimated`
      honesty flag (Phase 9A ITs).
- [x] **Front/next/far FUT + INDIA VIX pinned; term structure from ONE batched
      quote** — single-invocation assertion; basis fixture (120.0000 /
      0.0608 @ 30d); CONTANGO/BACKWARDATION by near→next slope; per-bar FUT OI
      through to the 1d cagg's `last(oi)`.
- [x] **Continuous futures stitch deterministically** — exact 150.0000 fixture
      gap, idempotent re-runs, `adjust=back|none` verified at the API,
      roll-day divergence caveat documented in the roller javadoc + stage doc.
- [x] **Corporate-action job detects the planted split and rebuilds** —
      uniform-ratio guard rejects single-anchor and non-uniform noise;
      re-backfill rides the rate-limited gateway; `fetched_at` bump asserted;
      byte-stable post-rebuild series.

**Stage-end deliverables roll-up:**

- [x] Daily Kite contract canary (S2B) — fixture-derived manifests, recursive
      field-set diff, first-party ntfy.
- [x] Greeks golden-vector suite + S1 gating of IV persistence; raw quotes
      captured from day one (S4).
- [x] `docs/retention.md` (A2 ≥5y floor, 50 GB review trigger) +
      `docs/runbook-notes.md` (A3 minute-depth probe, S2 Tuesday-expiry note).
- [x] ~~Leaked-credential tripwire~~ — dropped per A6; the live fail-fast
      (key + secret + master key files) remains and is tested.
- [x] 2026-06-12 feature-selection additions all landed: 9A spec history, 15A
      futures slice + INDIA VIX, 15B CONT + roll_events, 16A corporate
      actions, `candles_1w`, `oi_buildup`/`rs_rank`.

**Owner actions carried forward:** minute-depth probe in live mode (A3);
NSE constituents CSV source verification before Phase 22 (S8); branch
protection clicks. **Forward dependencies (by design):** `jobs:summary`
zeros until Phase 28; `rs_rank` universe = active equities until Phase 22;
1w/futures_of_underlying validate at the Phase 18 freeze.

*(How Stage A was walked: Part 1 sections A.1–A.17 implemented
section-per-commit; Part 2 Phases 1–8 then audited one-by-one against their
Deliverables/Tests/Acceptance — Phases 1, 2, 3, 5, 6, 7 + the COMMON
conventions sweep came back clean; Phase 4 was missing the lint pre-commit
hook entry (fixed) and Phase 8 had a real `GATEWAY_WS_FLUSH_HZ` binding bug
plus two missing IT cases (fixed, tested); Part 3 exit gate walked against
the running mock stack, below. CI red→green iterations: mvnw exec bit,
gitleaks-action→pinned CLI, drift-check pending-vs-checksum semantics.)*

## Acceptance checklist (Part 1 sections)

- [x] A.9 — secrets hygiene: `.gitignore`, `.env.example`, secrets layout, gitleaks hook blocks a planted secret
- [x] A.12 — PR self-review checklist template
- [x] A.13 — golden-vector fixture-format freeze (`docs/golden-vectors.md`)
- [x] A.14 — `docs/dev-setup.md` tier table + port map (S6 corrections)
- [x] A.15 — this file: marker + checklist + parking list
- [x] A.16 — `docs/LEGAL.md` attribution record (A13) + A6 credentials record
- [x] A.1 — compose topology: pinned/healthchecked/capped timescaledb + redis, dev-tools profile, `ay` CLI, remote-access doc (Q3)
- [x] A.11 — db-backup sidecar: 00:30 IST `pg_dump -Fc` per schema, 14d+8w rotation, ntfy on failure
- [x] A.8 — Flyway one-shot init: admin + 3 per-service lineages from empty volume, idempotent
- [x] A.3 — Maven reactor + `common-web` (core / servlet adapter split)
- [x] A.4 — error-code taxonomy constants (COMMON §8.3 spellings)
- [x] A.5 — `market-calendar` (IST session, NSE 2026 holidays, Tuesday expiries)
- [x] A.6 — ECS JSON logging + `MaskingMessageConverter` (masking unit-tested)
- [x] A.2 — edge-gateway: Argon2id login, Redis sessions, route table, headers, rate limits, hash-password tool
- [x] A.7 — tick pipeline (mock feed → normalizer → Redis) + gateway STOMP WS bridge with 20 Hz conflation
- [x] A.10 — CI: ci-java + ci-migrations, gitleaks step in every workflow
- [x] A.17 — Stage-A exit-gate checklist recorded below and walked against the running mock stack

---

## Stage-A exit gate (plan §15.2 Phase-0 row — walked 2026-06-12)

**Deliverables present:**

- [x] Monorepo layout (COMMON §10.1); process docs committed (`README.md`, `PHASE_GATES.md`, `docs/golden-vectors.md`, `docs/remote-access.md`, `docs/dev-setup.md`, `docs/LEGAL.md`, PR template, 8-file design set under `docs/design/`).
- [x] Compose: timescaledb, redis, flyway-init, db-backup, edge-gateway, market-data-service + dev-tools profile — all with `mem_limit` + healthchecks + pinned tags + loopback binds. *(Remaining D7 app containers land in later stages.)*
- [x] Flyway 11 init job: 3 schemas + 3 roles + the single backtest→marketdata read-only grant from an empty volume (admin first), idempotent — `ay reset-db` twice green.
- [x] GitHub Actions `ci-java.yml` + `ci-migrations.yml` committed; gitleaks in every workflow; both Dockerfiles build locally; JaCoCo ≥60 % gates pass locally. *(First remote run + branch protection pend the first push — owner clicks protection in GitHub settings.)*
- [x] edge-gateway: Argon2id (m=19456/t=2/p=1) login + Spring Session Redis + route table + headers + 5/min login limit + 50 req/s valve.
- [x] Mock Kite feed (D13) publishing deterministic ticks; gateway WS bridge relays over STOMP-on-native-WS with 20 Hz conflation.
- [x] Review additions: PHASE_GATES (S5+P4), dev-setup tier table with S6 ports, LEGAL attribution record [A13], Tailscale-first remote-access doc (Q3). *(Day-zero rotation superseded by A6 — fresh keys, no tripwire.)*

**Acceptance (walked against the running stack, 2026-06-12):**

- [x] `ay up` green from a clean restart with **no Kite credentials** (9 healthy containers + flyway-init exit 0).
- [x] Login at `127.0.0.1:8080` works — 204 + HttpOnly SameSite=Strict cookie; authenticated session probe.
- [x] Service images build (edge-gateway, market-data-service) — CI image-build matrix mirrors the same Dockerfiles. *(CI runs on first push.)*
- [x] Mock ticks visible on Redis `ticks.*` (string decimals, `+05:30`, monotonic seq, deterministic seed) and **end-to-end via `e2e/tools/stomp-probe.mjs`** (10 frames).
- [x] Tier 2 verbatim: host-run market-data-service (`dev,mock`) connected to compose Redis published on loopback by `ay up dev-tools` — actuator health UP.

**Closed by the Part 2 verification pass (2026-06-12):** branch pushed; PR
[#1](https://github.com/prashantm912/artha-yantra-2/pull/1) opened; CI runs on
the PR; drift-check red path proven locally (edited applied migration →
checksum mismatch, exit 1); restore drill executed once via `ay restore`.

**Still owner-clickable:** branch protection on `main` (GitHub → Settings →
Branches); optional OneDrive sync of `./backups`; quarterly restore-drill
recurrence.

**Stage B parking list (seeds for the next branch):** instruments table +
candles hypertable + Kite OAuth/AES-GCM token store + live ticker + options
snapshots (Phases 9–17); Kite minute-depth probe (A3); NSE index-constituents
CSV source verification (before Phase 22); `tools/hash-password` may gain a
compose-escaped output mode (quality-of-life).

## S1 gate — Black-76 golden-vector acceptance (Phase 14, walked 2026-06-13)

The formal S1 record (B-10 / B-15): the Phase 15 snapshot job may enable its
computed IV/Greeks columns **only while this suite stays green**; raw-quote
capture is never blocked by it.

- [x] Grid covered: F/K 0.85–1.15, T ∈ {0.5, 2, 7, 30, 90} d, σ 8–60 %, CE+PE —
      **490 committed py_vollib vectors** (offline generator, A4 exception;
      never generated at test runtime).
- [x] Greeks vs reference: relative error ≤ 1e-6 across all vectors; absolute
      ≤ 1e-9 where |reference| < 1e-3 (far-OTM gamma/vega corners included).
- [x] IV solver round-trip: reprice |Black76(IV) − market price| ≤ ₹0.01 for
      every vector carrying ≥ 1 tick of time value (324/490; the 0.5 d/2 d
      far-OTM remainder has no recoverable vol by construction).
- [x] Expiry-day: T from 5 minutes to 0 returns finite greeks via the
      documented `T_MIN` clamp (5 calendar minutes, ACT/365).
- [x] Edge corpus: at/below-discounted-intrinsic and zero-quote inputs → null
      IV + reason code (`BELOW_INTRINSIC` / `ZERO_QUOTE` / `NO_CONVERGENCE`),
      never NaN/Infinity.
- [x] Model is Black-76 **on the forward** (PCP → monthly-futures-LTP →
      `S·e^{rT}` precedence implemented and tested); no Black-Scholes-on-spot
      shortcut anywhere.
- [x] Deterministic across runs (same inputs ⇒ identical `BigDecimal` outputs).
- [ ] Market sanity (informational, non-gating): solved IV within ±2 vol points
      of the NSE chain page for liquid ATM±2 strikes on one live capture —
      pends the first live-mode session with real Kite credentials.

## Parking list (deferred)

**From the Stage-B audit (2026-06-13)** — accepted deviations + deferred work,
each with its target:

- **B-9 binary-frame guard production wiring** — `KiteBinaryFrameParser` is
  fixture-pinned and registry-driven, but javakiteconnect's `KiteTicker`
  exposes no raw-frame hook, so the guard cannot intercept live frames through
  the SDK. Production coverage today = the daily contract canary + the
  fixture-pinned envelope tests + no-tick alerting. Full wiring requires
  replacing the SDK socket with a first-party WS client (revisit when Kite
  changes its wire format or at the Stage-C hardening pass).
- **`instruments.exchange_token` population** — column exists, never written
  (dump record drops it). Wire through `InstrumentRecord` + both dump parsers
  when anything consumes it (nothing in Stages B–D does).
- **Canary result Redis key** — result lands in `kite:contract:check` (JSON) +
  `GET /auth/kite/status`, not embedded in the plain-string
  `kite:session:status` the spec names (would break that key's existing
  readers). Documented deviation.
- **Recorded Kite binary-frame capture** — the mixed-frame fixture is
  synthesized from the documented envelope; commit one real capture during the
  first live session (closes the shared-misreading risk).
- **`candles_1h` IST alignment** — hourly cagg buckets align to UTC hours
  (= :30 IST boundaries). Deciding to re-anchor means dropping/recreating the
  cagg; revisit before the Stage-E chart page consumes 1h.
- **`kite.rateBudget` on system status** — field present, null until
  market-data-service publishes a budget key (limiter metrics exist; producer
  pends Stage C status work).
- **~5k-row mock dump fixture** — CD-14 names ~5k rows; the frozen fixture is
  ~1.1k. The ≤5 s sync budget is asserted at the committed size; regenerate at
  5k only as a deliberate fixture-freeze event.

**From Stage D (2026-06-13, merged via PR #5)** — deferred work:

- **Options fidelity live walk** — SNAPSHOT/SYNTHETIC_B76 premium-source path is
  IT-green but not hand-walked on the mock stack (needs an options snapshot
  archive + a multi-month window the rolling mock feed can't supply). Walk it in
  the first live-mode options session.
- **Walk-forward folds + fold-fed `MedianPruner` live walk** — can't be shown on
  the ~3-day rolling mock window (needs train+test trading days ≥ 3 warmup folds ×
  5 startup trials); verified by optimizer unit tests instead. Walk on a real
  multi-month dataset.
- **`requirements.txt` hash-pinning** — optimizer-service deps are version-pinned
  but not hash-locked; add `pip-compile --generate-hashes` in CI.

**From Stage E (2026-06-14)** — accepted deviations + deferred work:

- **Monaco editor-route chunk ~562 KB gz** exceeds the E-6 "≤ 400 KB gz per lazy
  chunk" budget — Monaco's irreducible floor (`editor.api` is the lean import; the
  yaml/editor workers are separate on-open chunks; the initial bundle stays ~113 KB
  gz). The budget's intent (no heavy lib in initial) holds; the editor route is the
  one justified large lazy chunk. Not CI-budget-enforced for that chunk.
- **Phase-40B indicator cache uses Caffeine (in-memory), not Redis** — the
  `StrategyVersionClient` precedent; single-instance backtest-service; unit-testable;
  functionally equivalent. Swap to Redis if the service ever scales out.
- **Optimizer `/best` omits guard COLUMNS** (per-regime OOS Sharpe/expectancy,
  regimes-covered badge, `dataHash` parity, "n folds excluded") — those guard outputs
  surface in the Phase-39 fold drill-down (regime chips + guard-7 degradation), the
  all-trials states (pruned/failed flagged), and the Pareto front, NOT as dedicated
  leaderboard columns. Adding the columns needs an optimizer `/best` enrichment.
- **Strategy list last-backtest summary** → RESOLVED (2026-06-14): the strategy list
  shows a "Last backtest" column (Sharpe + equity sparkline). Strategy versions carry
  `currentVersionId`/`publishedVersionId`; the frontend enriches via backtest-service
  `GET /api/v1/backtests/summary?strategyVersionIds=…` (latest run per version) — the
  cross-schema join stays client-side, no new cross-service backend dependency.
  `/strategies/compare` still shows configs only (no per-version compare metrics).
- **Backtest `TradeRow` symbol + SL/target** → RESOLVED (2026-06-14): each trade row
  denormalizes the run instrument (`exchange`/`tradingsymbol`) and persists entry-time
  `stop_loss`/`take_profit` levels (migration `V005`; levels computed parity-safe as a
  `SignalEvent` side-channel — golden vectors byte-identical). Results expose the run
  symbol so the "View on chart" deep-link carries the right instrument; the `/trades`
  endpoint accepts `symbol`/`from`/`to` filters. SL/target chart price-lines remain a
  display follow-on (data is now available; trades table shows Stop/Target columns).
- **Per-trade reasoning drill-down** shows the trade's `contributions` map (all the
  `TradeRow` persists), not the full `ReasoningBreakdownPanel`; compare-page
  trade-distribution histograms deferred (need per-run trade fetches).
- **Notifier payload** sends composite score (threshold included); paper-trade chart
  marks arrive in Stage F Phase 43B (slot reserved in the mark-source vocabulary).

*(other items deferred out of a section land here with their target)*

- Stage B seeds (recorded in the stage file, not deferred work): instruments
  table, candles hypertable, Kite OAuth/AES-GCM token store, live ticker,
  options snapshots (Phases 9–17); Kite minute-depth probe (A3); NSE
  index-constituents CSV source verification (before Phase 22).
