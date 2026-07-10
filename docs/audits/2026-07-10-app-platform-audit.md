# App-Platform Audit — Non-Backtest / Non-Livetest Modules (Prompt 1 of 2)

**Date:** 2026-07-10 · **Codebase:** main @ `cfe94280` · **Method:** 6 parallel read-only
code auditors (trading core / charts+options / futures+equity+FII-DII / strategies+features /
data-ops+events / FE-architecture+API surface), every claim cited `file:line`.

**Scope:** all app pages and workflows EXCEPT the backtest/live-test engines, which were
audited separately the same day in `docs/audits/2026-07-10-research-fidelity-audit.md`
("the fidelity audit"). Where a finding belongs to both, this report cites the fidelity
audit's ID (F1–F13 frontend, A1–A10 API, P0/P1/P2 gaps) instead of restating it.
This document is Prompt 1 of 2: §11 is the handoff contract for Prompt 2, which will
build intelligence, automation, prioritization, and decision-support on top of the
foundation defined here. Constraints honored: no intelligence/recommendation/scoring
design here, no backtest/live-module redesign, no portfolio/MF assumptions.

Path shorthands: `FE` = `frontend-react/src` · `MD` = `services/market-data-service/src/main/java/in/arthayantra/marketdata`
· `SS` = `services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal`
· `BT` = `services/backtest-service/src/main/java/in/arthayantra/backtest`
· `GW` = `services/edge-gateway` · `OPT` = `services/optimizer-service`
· `FLY` = `deploy/flyway` (4 lineages: admin / marketdata / strategy / backtest).

---

## 1. Architecture overview

### 1.1 Topology (as-built, verified)

Six compose services behind one ingress. `edge-gateway` (Spring Cloud Gateway, :8080) owns
auth (single-owner Argon2id + Redis session + XSRF), the WS bridge, and the SPA fallback;
`market-data-service` owns candles, OI capture, all options/futures/equity/FII-DII analytics,
screeners, instruments, watchlists, and the Data-Ops admin surface (41 controllers);
`strategy-signal-service` owns the strategy registry, live signals + rejections, paper books,
risk governor, journal, orders read-through, and graduation (13 controllers);
`backtest-service` owns jobs/results/compare data (8 controllers); `optimizer-service`
(Python FastAPI) owns sweeps; `margin-service` (Python) is the dormant SPAN appliance.
TimescaleDB with 4 Flyway lineages; Redis for pub-sub, streams, `ticks:last`, sessions.

**Route truth:** all 77 owner-listed pages exist as routes (`FE/App.tsx:194-301`,
`FE/components/MegaMenu.tsx:19-146`) — zero missing, zero dead menu links. The per-family
Signals/Paper trios are param routes (`/signals/:book`, `/paper/:book`) over one page each;
"Paper Ticket" is the `/scalper` route. Nine extra routes exist beyond the list, all
detail/plumbing (`/backtests/:id`, `/optimizations/:sweepId`, screener candidate pages,
strategy editor/versions, login/redirects).

### 1.2 Data planes

1. **Live capture (market hours):** Kite WS ticks → `RedisTickPublisher` → 1m bars
   (`BarWriter`, pub `candles.1m.*`) · full options chain every **2 min** (cron
   `0 */2 * * * *`, `MD/options/OptionsSnapshotService.java:97` — docs still say 3-min,
   stale) → `options_chain_snapshots` · futures OI every 1 min (23 underlyings = indices +
   17 banks, `MD/futures/FuturesOiSnapshotService.java:33`) → `futures_oi_snapshots` ·
   pre-open equity scan 09:09:30 → `preopen_equity_snapshots`.
2. **EOD batch chain:** 19:00 NSE FII/DII + participant-OI + FII-derivative
   (`MD/nse/NseEodScheduler.java:50-65`) · 19:30 NSE+BSE bhavcopy → EOD tables + 1d candles
   + CA events, publishes in-process `BhavcopyBackfillCompleted` · which triggers 19:50
   Minervini + 19:55 Manas screeners (event-chained + fallback crons) · 20:00/20:05 swing
   batches · 21:00 graduation evaluator (flag-off).
3. **Virtual history:** pre-capture OI derived read-time from per-contract candles
   (`CandleDerivedChainReader`), provenance `derived`, iv/greeks null (fidelity audit owns
   the fidelity implications).
4. **Static reference data (a hidden fourth plane):** 4 classpath JSONs — index
   constituents (3 indices), 502-symbol sector map, index weights, Upstox key map — hand
   -seeded, frozen since 2026-06-23, no maintenance surface (`MD/constituents/StaticIndexConstituents.java:27-43`).
   A DB `index_constituents` accrual table exists but its live fetcher is a stub returning
   empty (`MD/constituents/PendingLiveIndexConstituentsFetcher.java:22-29`).

### 1.3 Event fabric (complete inventory)

- **Redis pub-sub (fire-and-forget, 7 channels):** `ticks.{exch}.{sym}`, `candles.1m.*`,
  `options.chain`, `kite.status`, `signals`, `strategy.changed`, `jobs.progress`. The WS
  bridge (`GW/.../ws/StompWebSocketHandler.java:274-292`) exposes exactly these to the SPA
  as a closed allowlist.
- **Redis streams (transport; PG authoritative):** `jobs.backtest`, `jobs.backtest.trials`,
  `optimizations.results` with consumer groups; boot PEL hygiene is ACK-and-drop then
  re-dispatch from the `jobs` table.
- **In-process Spring events:** `SignalEmitted` → notifier/auto-paper; `SignalTaken` →
  paper open + live order; `SignalExited` → paper close; `PaperPositionClosed` →
  auto-journal + taken-signal resolver; `BhavcopyBackfillCompleted` → screeners;
  `InstrumentMasterUpdated` → subscription re-arm; alert records → ntfy listeners.
- **Durable event/audit tables:** `strategy_audit_log`, `signal_rejections`,
  `notification_events`, `shadow_positions`, `bot_commands`, `strategy_graduations`,
  `swing_batch_runs`, `risk_audit`, `backfill_jobs`, `roll_events`,
  `corporate_action_events`. (What is NOT captured: §7.)

### 1.4 Systemic properties that shape everything below

- **Pull-dominant UI.** Only signals/ticks/jobs are WS-pushed. Every OI/analytics page is
  Go-button + 30 s staleTime; no OI page auto-polls (cockpit's 45 s fan-out is the lone
  exception, `FE/pages/scalper/CockpitPage.tsx:116-124`). The `options.chain` push is
  bridged to STOMP but has **zero FE subscribers**.
- **Compute-on-read analytics.** PCR, max-pain, heatmap, trending, sentiment, sell-decisions
  are all folded per request; no caching on the OI read path; no materialized analytics.
  Correct-by-construction, cost paid per click.
- **Three async-job models** (PG-backed backtest jobs vs in-memory backfills vs orphanable
  optimizer threads) with three status vocabularies — blocks any unified jobs surface (§5.6).
- **Contract-gate blind spot:** 71 of ~203 REST handlers return `Map<String,Object>`
  (ratchet-frozen ceilings; market-data at exactly 31/31 = zero headroom), invisible to
  springdoc + TS codegen; generated types are bound to the FE only for strategy-signal
  (`FE/api/contracts.bridge.ts:13`).
- **Single-owner trust model.** One `ROLE_OWNER`, no RBAC, downstream services trust the
  gateway session; audit trails exist for strategy/risk/bot/graduation but not for paper
  reset, watchlist/journal CRUD, exports, or query-console SQL.

---

## 2. Product and workflow audit

Verdict per workflow area. "Works" = verified in code; gaps carry evidence.

### 2.1 Signal generation → review → rejection (strong core, broken last mile)

Works: signals list with status/day/book scoping + STOMP live merge; detail renders the
full frozen ScoreBreakdown gate tree AND the scalper side-channel (option leg, confluence
dots, `manual_checks` checklist gating the Take button) (`FE/pages/signals/SignalsPage.tsx:93-97,257-268`).
Rejections page is best-in-class forensics: rail-count chips, shadow-variant league strip,
per-row blocking rail/operand/threshold/margin, 3-panel expand with every rail ✓/✗
(`FE/pages/signals/RejectionsPage.tsx:96-341`).

Gaps:
- **No symbol search, no sort, no strategy filter** on /signals — backend supports
  `exchange`/`tradingsymbol`/`strategyVersionId` params the FE never sends
  (`SS/signals/SignalsController.java:43-45`).
- **Signal status transitions are never re-published** — TAKEN/DISMISSED/EXPIRED mutate the
  row only (`SS/signals/SignalsController.java:93-105`); a second tab/cockpit stays stale
  until refetch. No `signals.status` frame exists.
- **Dot-health endpoint has no UI** (`SS/signals/SignalRejectionsController.java:42-45`;
  FE only type-asserts it). Rejections are poll-only (no WS push).
- **No rejected-vs-fired comparison** view; no per-strategy rejection filter in the UI
  (backend supports both).
- **Take from /signals is suggestedQty-only** — no qty/price override, no book selector;
  the full ticket exists only on /scalper and /cockpit.
- Chart deep-link carries `signalId` that no chart page reads (`ChartsPage` overlays all
  signals for the symbol instead) — dead param.

### 2.2 Paper ticketing → trading → orders → journal (engine-grade core, unsafe manual edge)

Works: four entry paths (auto-paper per book, manual take, prefilled/manual ticket, engine
exits) all converge on one CAS-guarded lifecycle; fills ride the engine JAR's
`ltp_slippage/v1` simulator (backtest parity by construction); books charged by
strategy-family tag; brackets poll 15 s; expiry settlement + T-1 roll prompts; MTM via WS
ticks; auto-journal stub on every close.

Gaps (severity order):
1. **Manual orders bypass every risk governor.** `RiskService.entryAllowed` (kill switch,
   max-open, daily-loss, profit-target, deployment, heat) is consulted only at ENGINE
   emission (`SS/paper/PaperEmissionGuard.java:45-48`); `PaperService.openOrder` never
   calls it — a /scalper ticket fills with the kill switch ON (`SS/paper/PaperService.java`).
2. **No idempotency key on `POST /paper/orders`** — a network retry doubles qty into the
   averaged position; only the FE `isPending` disable guards it.
3. **No order-event model.** `paper_orders` rows are born `status='FILLED'` (OPEN/CANCELLED
   enum values dead, `FLY/strategy/V005:18-19`); `quote_bid`/`quote_ask` are `null, null`
   at both call sites (`PaperService.java:198-200,338-340`). No fill-event stream exists
   for anything downstream to consume.
4. **F9 risk telemetry invisible**: advised_lots, margin_snapshot/margin_pct, margin-heat
   endpoint (`SS/paper/PaperMarginController.java:48-67`), and the `risk_audit` tail
   (returned by `GET /risk/settings`, dropped by the FE type `FE/api/paper.ts:88-92`) all
   have zero UI. `heat_cap_pct` isn't even API-editable (`RiskController.java:28-35`).
5. **Journal chain is write-only**: create form has no link pickers (can't reference a
   signal/trade); the PUT endpoint is FE-unused so auto-entries' discipline/emotion ratings
   are permanently null; positions don't expose `opening_signal_id` in `PositionDto`, so
   position→signal→reasoning navigation is impossible.
6. Open-position SL/TP and closed-trade `close_reason` are in the payloads but not rendered
   on /paper (`FE/pages/paper/PaperPage.tsx:293-301,341-349`); no bracket edit after open
   (no PATCH position endpoint).
7. Orders page is a permanent empty state without a broker (DisabledOrderGateway) — fine by
   design, but there is no paper-vs-broker reconciliation view for when OpenAlgo is armed.
8. Watchlists: BE supports rename/reorder/delete; FE exposes none of them, and the "no tick
   bridge" comment (`FE/api/watchlists.ts:3-5`) is stale — the bridge exists; watchlist rows
   simply never got live quotes.

### 2.3 Charting (solid MVP, thin power features)

Works: 3 LWC pages + echarts widgets everywhere; cache-first candle reads; signal markers
(/charts), paper-trade markers (/advance-chart), backtest-trade markers via `?runId`;
scroll-back lazy loading; audio price alert; OI histogram toggle; the 1d sparse-cagg trap
does NOT affect chart pages (`/market/candles` routes 1d to the dense base table,
`MD/candles/CandleQueryService.java:101-110`).

Gaps: indicators are a fixed client-side set (VWAP/VWMA/VolMA/SuperTrend/RSI — no
MACD/EMA/BB, not user-selectable, `FE/core/indicators.ts`); backtest-service's server
indicator engine is unused by charts; no drawing tools (explicitly deferred); layout
persistence only for AdvanceChart via localStorage; Multiframe = fixed 2×2, no crosshair
sync, no persistence, raw text symbol input; screener events not plottable; 3m is client
-rolled from 1m (consistent for display; the runner's missing 3m option is §2.7).

### 2.4 Options analytics — 19 pages (oipulse-faithful; freshness + drill-down debt)

Works: every page implemented with the documented formulas server-side (spurts, trending
folds, heatmap grids, max-pain, expiry EOD rollups, open=high patterns, active-strike
sentiment/IV, straddle/calendar candle synthesis from real 1m leg candles); live+history
via one `OiQuery` convention; ATM windows bound payloads.

Gaps:
1. **Freshness invisible on 11 of 19 pages** — only Options Chain has a true stale badge;
   Trending OI / Interval-wise receive `asOf` and drop it. Combined with no auto-poll,
   an open page silently ages.
2. **Unconsumed built backends:** `/premium-series` (ATM straddle decay), `/chain/history?at=`
   (chain time-travel), `/strike-session-stats` — all coded, zero FE consumers.
3. **No strike drill-down** (chain/heatmap/spurt cell → that strike's premium chart);
   no IV smile/surface view (`/iv-history` backend exists, no options-section page);
   no cross-expiry or day-over-day comparison beyond Calendar Spread (premium-only).
4. **No CSV export anywhere in options** (echarts PNG toolbox on 5 widgets only).
5. Capture-cadence docs drift: code = 2-min cron; V006 header, application.yml comment,
   master plan all say 3-min.

### 2.5 Futures + Equity + FII/DII (context pages: real, but shallow + silently gappy)

- Futures: universe = indices + 17 banks (oipulse scans all F&O; pages self-document the
  reduction); **EOD OI Analyzer is a depth illusion** — it rolls intraday snapshots
  (forward-only from ~2026-06), not an EOD F&O source, disclosed only in a code comment.
  5 futures endpoints have no page (`/term-structure`, `/banks-grid`, `/movers-screen`…)
  — consumed service-to-service.
- Equity: breadth is single-date (no A/D history series despite bhavcopy depth being the
  ONE deep dataset); news is single-symbol manual input with zero persistence;
  announcements = live NSE pass-through, 7-day default, no filters.
- **FII/DII: the weakest ingestion in the platform.** Cash flows = latest-only NSE JSON
  (no backfill possible — history only accrues forward); participant-OI = newest CSV only
  (a missed 19:00 run is a permanent hole); **FII derivative stats are dead by default**
  (fetcher bean requires `artha.upstox.analytics.enabled=true`, default false — page shows
  an empty chart with no diagnostic, `MD/upstox/UpstoxFiiDerivativeFetcher.java` +
  `application.yml:85`). All failures are `log.warn`-swallowed: no retry, no ntfy, no run
  ledger (`MD/nse/NseEodScheduler.java:68-107`), in contrast to bhavcopy which alerts.

### 2.6 Screeners (Minervini/Manas — strong per-name forensics, no time dimension in UI)

Works: event-chained EOD runs with watermark dedup; **every scanned name persisted with
per-gate booleans** (fails are explainable); candidate drill-down pages with gate chips,
geometry reject reasons, daily chart; funnel buckets (immediatelyBuyable/onDeck/watch);
RS-rank computed cross-sectionally and persisted.

Gaps: **no date picker** — per-day history accrues in DB but the UI can only see the
latest run; no day-over-day diff (entered/exited names); no funnel stage-attrition counts
(funnel returns passers only); Manas RS-rank displayed nowhere; no watchlist-add / chart
deep-link from screener rows; `POST /run` recompute has no auth annotation beyond the
gateway session; **Minervini backtest endpoints exist with no page** (Manas has one).
The CA-adjustment (H6) and universe live-vs-backtest mismatches are fidelity-audit
territory — confirmed still unfixed (`MD/screener/minervini/TrendTemplateService.java:106-110`).

### 2.7 Strategies / Graduation / Sell decisions / Backtest UI

- Strategy registry: validated-at-write YAML (invalid can never persist), publish
  re-validation, checksum dedup, append-only `strategy_audit_log` — but **the audit log has
  no read endpoint and no UI**; **`strategies.enabled` has no API and no UI toggle**
  (grep: no `SET enabled` — DB-only), so the list cannot show which strategies the live
  engine actually loads; no clone; diff = two raw `<pre>` panes + op list; schema endpoint
  served but unconsumed; archive/delete hooks exported but no buttons.
- Graduation: read-only board with correct V026 attribution; **GRADUATED stage invisible**
  (`GET /strategies/graduation/promotions` has zero FE consumers); no owner actions.
- Swing sell decisions: recomputed-on-read verdicts (adoptions keep holdings visible);
  **zero actionability** — no acknowledge, no deep-link to the paper position, nothing
  persisted (no `sell_decision` rows), so no history of what the page advised.
- Backtest UI (pages only): Runner lacks `3m` in the interval list (engine supports it),
  lacks per-parameter overrides (`params_override` exists in the schema), and the sweep tab
  cannot send a `walkForward` block — making the fold-aggregation select decorative and the
  UI incapable of launching an OOS-fold sweep. Jobs page never shows `jobs.error` for failed
  runs. Compare guards only dataHash + universe checksum: different windows/intervals/
  seeds/capital compare silently, and no engine SHA exists in results (fidelity P0-2).
  No rerun/clone/export/saved-comparisons (fidelity F2/F3/F5 confirmed intact).

### 2.8 Features + Data Ops

- Features: Connecting Dots faithful (11 factors, live+history); Vix&Index = client-side
  overlay only (no correlation stat); Holidays fine (no year selector, horizon not
  surfaced); World Indices solid (Upstox global master + quotes, never 500s); **Risk
  Calculator is notional-only** — no tie-in to `POST /api/v1/market/margin` (F9 SPAN), so
  deployment% ignores real margin; Multiple Window persists layouts but panes are
  whitelisted to 8 context widgets (no signals/paper/jobs panes), single unnamed workspace.
- Data Ops: wizard drives only expired-options backfill (OI + equity-daily kinds are
  endpoints without wizard support); Coverage = expired-contracts only (no candle-interval,
  chain-snapshot-day, or bhavcopy-day coverage); Query console has strong safety
  (READ ONLY txn + allowlist + timeout) but **zero audit of what was run**; Export is
  synchronous with **silent 100k-row truncation** (`MD/backfill/BackfillExportService.java:82-84`);
  Collection Status omits bhavcopy/FII/OI-capture/instrument-sync collectors; backfill
  progress has no denominator and no WS channel (2 s polling).

---

## 3. Data model and storage audit

### 3.1 What is stored well

Capture history (`options_chain_snapshots`, `futures_oi_snapshots`, bhavcopy tables,
`preopen_equity_snapshots`) is hypertable-managed with airtight duplicate-day guards
(PK + `ON CONFLICT` everywhere: `MD/nse/NseEodBhavcopyRepository.java:45`,
`FuturesOiSnapshotRepository.java:43,64`). Screener history stores per-gate booleans per
scanned name per day — the single best decision-support substrate in the platform.
Domain event tables (rejections, shadow, notifications, graduations, swing batches, risk
audit) are append-only with proper grants.

### 3.2 Normalization defects

1. **Two disjoint index-membership systems**: static classpath JSONs (drive ALL analytics)
   vs the `index_constituents` DB table (live fetcher = stub, accrues nothing on live).
   Seed drift already present: NIFTY 200 file has 202 names vs 200 weights; NIFTY BANK
   carries 14 (real index = 12). Sector map does raw `map.get` with no case normalization
   while the Upstox key map uppercases — asymmetric.
2. **Sector master is data, stored as code**: 502-symbol JSON, changes require PR+redeploy,
   frozen since 2026-06-23, no staleness alarm. Should be `marketdata.reference_*` tables
   with seed migrations + admin CRUD (§9.4).
3. **`StrikePoint` carries no provenance field** — derived-vs-real OI is only inferable
   from null iv/greeks (`MD/options/analytics/OptionsSnapshotReader.java:42-51`).
4. **Screener rows carry no engine-version column** (`FLY/marketdata/V031`) — re-running
   after a formula change silently rewrites what history means.
5. **Backfill identity split**: the in-memory jobId UUID is never persisted into
   `backfill_jobs` (BIGINT identity PK) — a status poll cannot be joined to its ledger row.
6. `paper_account.cash` is vestigial (equity derived on read — correct, but the column
   misleads).

### 3.3 Store-vs-compute (current placement + what should move)

| Concern | Today | Verdict |
|---|---|---|
| OI folds (PCR/max-pain/heatmap/trending/spurt) | computed per request, no cache | Keep on-demand (correctness first); add optional 30–60 s in-process cache per (endpoint, query) key only if latency complaints appear — NOT a Prompt-2 dependency |
| Breadth | single-date SQL per request | **Store**: nightly `equity_breadth_daily` row (adv/dec/unch, above-MA counts) appended by the bhavcopy job — enables the missing history chart at zero read cost |
| Screener day-over-day diff | not computed anywhere | **Compute-on-read** SQL view over `(screen_date, symbol)` — no new writes needed |
| Sell decisions | recomputed on read, never persisted | **Store one row per (family, date, symbol) verdict** at the evaluation moment (append-only) — required for §7 events and any later decision tracking; keep the read-time recompute as the live view |
| Sector/constituents/weights | classpath JSON | **Store** (reference tables + effective-date) |
| FII/DII/participant | stored, latest-only pulls | Keep; add ingest-run ledger (§8) |
| News/announcements | zero persistence | Optional store-on-read cache table for announcements (dedup by NSE id) so the page has history and watchlist filtering has a substrate; news can stay pass-through |
| Futures EOD OI | intraday rollup only | If real depth wanted: NSE F&O bhavcopy backfill into `futures_oi_snapshots`-shaped daily rows (separate `source` label). Owner call — cost is an ingest job, not a schema change |
| Chain snapshots retention | none (manual `prune_options_snapshots(5y)` unscheduled) | Schedule the prune (ops decision; 1.12B-row lesson says never materialize more, and never let it grow unbounded either) |

### 3.4 History-depth truths the UI must stop hiding

Forward-only datasets (futures OI ~2026-06, chain snapshots 2026-06-15, FII/DII since
capture start, preopen 60 sessions) vs deep datasets (bhavcopy ≥365 d, candles ~11 y 1d).
Today only FuturesEodPage self-documents its shallowness. The freshness/source-quality
contract (§11.17) must carry `historyStart` so every page can render its true depth.

---

## 4. Frontend usability audit

### 4.1 Strengths (verified, keep)

`DataTable` (TanStack Table v8: sort/filter/pagination/column-visibility/sticky/pin +
**card mode below `md`**) adopted by 30 pages; `QueryState` 4-way loading/error/empty
convention; D8 error envelope → typed `ApiError` → global toast with 422-DATA_GAP silenced
to empty states; `BAD_CONTENT_TYPE` fail-loud SPA-fallback tripwire; universal OI
`FilterBar` + localStorage-persisted symbol context; WS pill with reconnect gap-heal
(debounced invalidate); 5 themes; a11y gated by axe + Playwright.

### 4.2 Systemic gaps (ranked by trader impact)

1. **Freshness is a per-page accident.** 1/19 options pages has a stale badge; 7/~25
   futures/equity pages show `asOf`; several endpoints return `asOf` that the FE drops.
   No standard freshness component, no page-level "capture stalled" warning (DataHealth
   exists as an ops endpoint only).
2. **Cadence inconsistency** (fidelity F10, wider than fidelity scope): WS push (signals/
   jobs/ticks) vs 4–60 s polls (dashboard/paper/preopen/world) vs Go-button-only (all OI
   pages) vs hand-rolled 45 s interval (cockpit). No per-page policy, no user-visible
   refresh indicator except the cockpit LiveDot.
3. **19 research pages roll raw `<table>`** instead of DataTable (SignalsPage,
   RejectionsPage, PaperPage, JournalPage, StrategiesListPage, GraduationPage, screeners,
   results/sweeps, dataops) — exactly the pages losing mobile card-mode and shared
   sort/filter (fidelity F9 overlap; full list in agent evidence).
4. **No saved views/filters anywhere**; page filters are in-memory (lost on refresh); OI
   control-bar state is localStorage-persisted but **not URL-shareable**; only /charts,
   /compare, and advance-chart carry URL state.
5. **No command palette / global search** — cmdk is a dependency with `ui/command.tsx`
   scaffolded and imported by nothing. Navigation is menu-only across 80+ routes.
6. **Drill-down dead-ends**: strike cells aren't links; screener rows don't reach
   watchlists/charts; positions don't reach signals; signals' chart deep-link param is
   dropped; sell-decision rows don't reach positions.
7. **No keyboard shortcuts** beyond Esc; no bulk actions (dismiss-all, close-all/flatten
   is Telegram-only).
8. Duplicated typeahead markup per page instead of one instrument-picker component;
   two symbol-picker patterns (FilterBar select vs search typeahead).

---

## 5. Backend service and API audit

### 5.1 Surface inventory

~203 endpoints: market-data 133 (41 controllers), strategy-signal 51 (13), backtest 15
(8), edge-gateway 4 (2), optimizer 7 FastAPI routes, margin 2. Pagination = `limit/offset`
with server caps where present. Error shape = D8 envelope everywhere (ProblemDetail
unused). Envelope: `{items:[...]}` respected, with 6 bare-array exceptions —
`instruments/search|expiries|strikes|underlyings`, `graduation/promotions`, `pcr-series`
(CLAUDE.md's "only search + underlyings" understates by four).

### 5.2 Contract visibility (the biggest API-quality lever)

71/203 handlers return `Map<String,Object>` (ratchet ceilings: MD 31 — **at exact
capacity**, SS 28, BT 10, GW 2). Contract-invisible surfaces include: all four FII/DII
range reads, announcements, futures oi-analysis/chart/eod, 8 options endpoints
(oi-analysis, strike-series, multiple-oi, options-chart, oi-expiry, open-high, chain,
iv-rollup), pre-open, world-indices. Consequence: FE types for these are hand-maintained;
a backend rename breaks silently at runtime ("—" cells) while ci-contracts stays green.
Additionally `contracts/gen/*.d.ts` is type-bound only for strategy-signal
(`FE/api/contracts.bridge.ts`), so even TYPED market-data/backtest records have no
compile-time FE binding.

### 5.3 Unconsumed/orphan endpoints (built value, zero UI)

`/premium-series` · `/chain/history?at=` · `/strike-session-stats` · `/market/breadth/live`
· `/signal-rejections/dot-health` · `/strategies/graduation/promotions` · `/paper/margin-heat`
· `PUT /journal/{id}` · `POST /strategies/{id}/archive` + `DELETE` (hooks exported, no
buttons) · 5 futures endpoints (`/term-structure`, `/banks-grid`, `/movers-screen`,
`/banks`, `/buzz` FE-side) · Minervini swing-backtest family · `GET /strategies/schema/v1`.
Prompt 2 should treat these as free inputs — they exist and are tested.

### 5.4 Validation posture

Zero bean-validation in market-data (hand-rolled parseDate → 400; absence → 422 DATA_GAP —
consistent but ad-hoc). Screener `POST /run` and equity pre-open `POST /capture` are
owner-session-guarded only (fine single-owner; flag if RBAC ever arrives). `NseHttpClient`
has no retry/backoff/rate-limit (78 lines; browser-UA + cookie dance only).

### 5.5 Gateway + auth

Allowlist covers every discovered prefix (tripwire test `GatewayRouteAllowlistTest`), with
two blind spots: margin-service is excluded ("no committed spec yet") and a brand-new
sibling prefix still 200s index.html until spec recapture (mitigated by the FE
BAD_CONTENT_TYPE loud error). One global 50 rps bucket (fidelity A9); 300 s response
timeout sized for inline auto-warm (fidelity A10).

### 5.6 Async jobs (three irreconcilable models today)

| Dimension | backtest/optimizer | expired/equity backfill | OI backfill / bhavcopy / export |
|---|---|---|---|
| Store | PG `jobs` + Redis stream | in-memory + fail-soft ledger | in-memory only / in-memory / none |
| Status enum | queued/running/completed/failed/cancelled | NEVER_RUN/RUNNING/OK/FAILED (memory) vs RUNNING/COMPLETED/FAILED (ledger) | ad-hoc |
| Progress | 0-100 + `jobs.progress` WS | counters, no denominator, poll-only | none |
| Crash recovery | requeue + PEL hygiene (BACKTEST/TRIAL only — a died optimizer thread leaves `running` forever, fidelity P2-1) | hourly self-heal (expired only) | none |
| Cancel | yes (checkpoint) | no | no |
| Audit | jobs row | ledger row (uuid not stored); **OI backfill writes NO row** | none |

Per-kind `AtomicBoolean` locks allow expired+equity+OI to run concurrently against the one
shared Upstox limiter. Exports/query-console leave zero trace (§8).

---

## 6. Missing screens and decision-support views

Ranked; each names its data source and whether backend work is needed. (No intelligence —
these are display/workflow surfaces over existing or newly-evented data.)

1. **Notification center** — `notification_events` table exists (channel/status/attempts);
   needs a read endpoint + page (filter by status=FAILED to spot rot). Today pushes are
   fire-and-forget to ntfy/Telegram with no in-app trace.
2. **Unified jobs console** — one page over backtest jobs + sweeps + backfills + exports
   once §9.2's job envelope lands; replaces 3 partial surfaces (Jobs page, Data-Ops status,
   nothing for exports).
3. **EOD ingest health board** — per-source (bhavcopy NSE/BSE, FII/DII cash, participant,
   FII-derivative, screeners, swing batches) last-run/status/row-counts/missing-days,
   fed by the `ingest_runs` ledger (§9.1). Kills the "silent permanent hole" class.
4. **Position detail pane** — opening signal provenance (needs `opening_signal_id` in
   `PositionDto`), brackets shown + editable (needs PATCH endpoint), advised-vs-actual lots,
   margin snapshot, journal link. All columns already exist in `paper_positions`.
5. **Trade-chain view** — signal → take/ticket → order legs → position → close → journal
   entry as one navigable thread. Requires only the provenance exposure above + journal
   link pickers (endpoints exist).
6. **Rejections analytics** — rejected-vs-fired per strategy/day; dot-health panel
   (endpoint exists); a "why did nothing fire today" one-pager built on rail-count series.
7. **Screener time machine** — date picker over persisted `screen_date` history;
   day-over-day entered/exited diff; funnel attrition counts (scanned→per-gate→buyable —
   needs a small aggregation endpoint over existing per-gate booleans).
8. **Chain time-travel** — "chain at 10:30" slider on history mode; backend
   `/chain/history?at=` already built.
9. **IV smile/surface + cross-expiry OI compare** — smile per expiry from chain IV columns;
   surface = smile × expiries; OI compare = two `/trending` folds side-by-side. Data exists.
10. **Breadth history** — A/D line + above-MA series chart; needs the `equity_breadth_daily`
    materialization (§3.3) or an on-read window fold.
11. **Minervini backtest page** — endpoints exist; mirror the Manas page.
12. **Strategy ops view** — enabled/armed toggle (needs the missing endpoint), audit-log
    timeline (needs read endpoint over `strategy_audit_log`), GRADUATED badge from the
    unconsumed promotions endpoint, clone action.
13. **Reference-data manager (Data Ops)** — sector map / constituents / weights CRUD once
    §9.4 tables exist; staleness display.
14. **Alert rules page** — user-defined OI/PCR/price threshold alerts routed through the
    existing notifier. (Rule EVALUATION is mechanical threshold-checking — the intelligent
    prioritization of what to alert on is Prompt-2 territory.)
15. **Saved views + command palette** — cross-cutting shell features (cmdk already vendored).
16. Backtest-vs-paper comparison — fidelity F1; listed here only for completeness of the
    screen inventory; design belongs to the fidelity roadmap.

---

## 7. Missing events and state transitions

### 7.1 Capture matrix (prompt checklist × current truth)

| Domain action | Durable row | Push (Redis/WS) | Gap |
|---|---|---|---|
| Signal generated | `signals` | `signals` → `/topic/signals` | — |
| Signal rejected | `signal_rejections` | none | no push; forensics is poll-only |
| Signal taken/dismissed/expired | status UPDATE only | **none** | no transition event at all — stale tabs, nothing downstream can react |
| Paper ticket created (manual) | `paper_orders` (born FILLED) | none | no PENDING state, no book on ticket, no idempotency |
| Paper fill/close | position row + exit order row | none | no `paper.events` channel; MTM is tick-derived client-side |
| Broker order events | n/a (read-through) | n/a | fine until OpenAlgo arms; then an order-event ingest is required |
| Journal CRUD | rows | none | no edit UI; no linkage events |
| Watchlist CRUD | rows | none | acceptable; no audit row (single-owner) |
| Strategy builder changes | `strategy_audit_log` + version rows | `strategy.changed` | enable-flag changes NOT in the audit action enum; log unreadable (no endpoint) |
| Graduation | `strategy_graduations` + ntfy | none | no UI consumer; TAKE_ELIGIBLE↔PAPER transitions unlogged (board recomputes) |
| Sell-decision verdicts | **nothing** | none | recompute-on-read only; SELL verdicts leave no history (§3.3 fix) |
| Chart interactions affecting state | localStorage only | — | acceptable (client prefs); price-alert rules are client-side only, lost per browser |
| Options/futures analytics refresh | snapshot rows | `options.chain` (no FE sub) | no durable capture-run marker; per-underlying staleness not derivable without scanning |
| Equity/FII-DII refresh | data rows only | none | **no run ledger** — success/failure/row-count untracked (§8) |
| Data-ops jobs | partial (`backfill_jobs`; OI backfill NONE) | none | no progress events; uuid↔ledger uncorrelated |
| Exports / query-console | **nothing** | none | zero trace of operator SQL/exports |
| Risk setting flips/trips | `risk_audit` (deduped 1/day/cap) | ntfy on trips | dedup means it is an alarm log, not a change history; heat-cap not settable via API |
| Paper reset / capital edit | **nothing** | none | destructive action with zero audit |
| Kite session events | `kite_sessions` + `kite.status` | yes | — |

### 7.2 Required additions (event model only — schemas in §11)

1. **`signals.status` push frames** on take/dismiss/expire (publisher beside
   `SignalPublisher`; WS bridge allowlist + FE cache patch). Smallest change with the
   widest UX payoff.
2. **`paper.events` channel + rows**: emit OPENED/CLOSED/BRACKET_HIT/SETTLED with
   position id, book, reason. The in-process Spring events already exist
   (`PaperPositionOpened/Closed`) — this is serialization + publication, not new logic.
3. **`ingest_runs` ledger** (marketdata): one row per scheduled/triggered ingest
   (source, window, status, rows, error, started/finished). Writers: NseEodScheduler (3
   sources), bhavcopy, screeners (they already have rows — stamp run ids), snapshot-capture
   session summary (1 row/day), instrument sync. This is THE prerequisite for the ingest
   health board and for Prompt-2's data-trust decisions.
4. **`sell_decisions` append-only rows** at evaluation time (family, date, symbol, verdict,
   reason, entry/stop/trail snapshot).
5. **Paper reset / capital-change audit rows** (extend `risk_audit` or a small
   `paper_admin_audit`).
6. **Job envelope events** for backfills/exports on the existing `jobs.progress` channel
   (frame already generic: `{jobId,status,progress}`).
7. **Event-schema registry**: one `contracts/events/*.md` (or AsyncAPI YAML) per channel,
   pinned by the existing ITs — today channel payloads are ad-hoc Jackson with no spec
   (grep asyncapi = 0).

---

## 8. Missing validations and reconciliation checks

| # | Check | Today | Needed (implementation pointer) |
|---|---|---|---|
| V1 | Manual paper order vs risk governor | **bypassed entirely** (`PaperEmissionGuard` engine-only) | call `RiskService.entryAllowed` inside `PaperService.openOrder`; 422 on veto with rail name. HOLD-tier (changes live paper behavior) |
| V2 | Idempotency on `POST /paper/orders` | FE isPending only | optional `clientOrderId` unique per book; 409 replay returns the original order |
| V3 | Tick freshness on fills/MTM/brackets | `LastTickReader` no staleness check; bracket eval silently skips no-tick symbols; no-tick close settles at breakeven | max-age param (e.g. 15 s) → DATA_STALE on fills; bracket-eval starvation counter + ntfy after N skips; forbid breakeven fallback when any tick exists (explicit-price path already covers swing) |
| V4 | Lot-size multiple on paper qty | `>0` only | validate against instrument lot (master has it); Upstox margin already 400s on non-multiples |
| V5 | Position ↔ order-leg reconciliation | none | nightly job: Σ order legs == position qty/realized per closed position; report to ingest health board |
| V6 | OI outlier guard | none — raw prints flow into spurt/heatmap/sentiment | capture-time plausibility (ΔOI z-score or ±X% vs prior bucket) → quarantine flag column, folds skip flagged rows |
| V7 | Snapshot-OI vs candle-OI cross-source | never compared | weekly canary over the overlap window (both exist since 2026-06-15); alert on divergence > threshold |
| V8 | Bhavcopy close vs Kite 1d close | CA job diffs Kite-vs-Kite only, excludes bhavcopy-only names | extend `CorporateActionJob` sweep with a bhavcopy-close comparison for symbols having both |
| V9 | EOD missed-day detection | bhavcopy self-heals (can't distinguish error from holiday); FII/DII/participant/screeners: nothing | `ingest_runs` + a T+1 canary: expected-source × trading-day matrix, ntfy on holes (respect `MarketCalendar`) |
| V10 | Screener/analytics engine-version stamps | absent | add `engine_version` (git SHA or semver) columns to screen/setup rows; join it in the UI date picker |
| V11 | Reference-JSON staleness | frozen silently | once in tables (§9.4): `effective_date` + a 90-day staleness warning on the health board; until then, a startup log-warn with file ages |
| V12 | Backfill uuid ↔ ledger row | uncorrelated | persist the jobId UUID into `backfill_jobs`; add OI kind + audit row |
| V13 | Optimizer orphaned `running` rows | forever-running (fidelity P2-1) | reaper: `running` OPTIMIZATION older than N h with no live thread → `failed` (fidelity owns the durable-queue fix; the reaper is the stopgap) |
| V14 | Export/query-console audit | zero trace | append-only `admin_audit` rows (sql-hash/params/rows/duration for queries; contract-set/row-counts for exports) |
| V15 | Notifier delivery health | FAILED rows recorded, nothing alerts | daily failure-rate check over `notification_events` → ntfy (the irony is intentional: alert via the OTHER channel too — Telegram) |

---

## 9. Target architecture for these modules

No service is added, split, or replaced. The stack stays: Java 21/Spring Boot (+Modulith),
React 19/Vite/Tailwind v4/TanStack Query/Zustand, TimescaleDB, Redis, FastAPI, Compose.
Four planes get deliberate contracts instead of accidents:

### 9.1 Ingest/observability plane

`marketdata.ingest_runs` ledger (§7.2.3) + the T+1 coverage canary (V9) + notifier-health
check (V15) + the EOD ingest health board (§6.3). DataHealthCanary gains per-underlying
granularity (today table-wide `max(ts)` masks a single stalled underlying). Collection
Status page becomes a thin view over `ingest_runs` + existing status endpoints.

### 9.2 Jobs plane

Adopt the backtest `jobs` envelope as the platform job contract: `{id: UUID, kind, status:
queued|running|completed|failed|cancelled, progress 0-100, request JSONB, error, timestamps}`.
Concretely: extend `backfill_jobs` to store the UUID + progress + OI kind; exports become
async over the same table when >N rows (keeping sync fast-path); optimizer durability is
fidelity P2-1 (not re-scoped here). One aggregate read — either a gateway-composed
`GET /api/v1/jobs` or an FE aggregator over the three services with one normalized client
type — feeds the unified jobs console. All job families publish the existing
`jobs.progress` frame shape.

### 9.3 Event plane

Everything in §7.2, plus the event-schema registry under `contracts/events/` with IT pins.
Redis pub-sub remains the transport (no Kafka — wrong scale); durability comes from the
tables, not the wire. WS bridge allowlist grows by exactly two channels
(`signals.status`, `paper.events`).

### 9.4 Reference-data plane

`marketdata.reference_sectors`, `reference_index_constituents` (unify with the existing
V008 table + build the real NSE fetcher or an admin upload), `reference_index_weights`,
`reference_upstox_keys`: seed migrations from today's JSONs, `effective_date` columns,
admin CRUD endpoints (Data-Ops), classpath JSONs demoted to bootstrap seeds. Analytics
services read through one `ReferenceDataService` with the same in-process caching they
effectively have now.

### 9.5 API/contract plane

Burn down Map returns opportunistically (every touched endpoint converts to a typed record
— ratchet ceilings only ever decrease); extend `contracts.bridge.ts` key-assertions to
market-data's highest-traffic DTOs; standardize the freshness envelope (§11.17) on every
analytics response; adopt `@Validated` + jakarta constraints on new/touched market-data
controllers. Money-as-string stays (documented convention).

### 9.6 Frontend platform plane

One `FreshnessBadge` fed by the standard envelope; a per-page data-policy declaration
(`push | poll(interval) | manual`) rendered in the page header so cadence is visible;
DataTable adoption for the 19 raw-table pages; URL-encode the OI control-bar state
(share/bookmark); server-side `user_prefs` table (single-owner: trivial) for saved
views/filters/layouts — localStorage stays as cache, not source of truth; activate cmdk
palette over the route table + instrument search.

**Swap suggestions (allowed by the brief, deliberately minimal):** none for the core.
Rejected on cost/benefit: Kafka/Redpanda (Redis pub-sub + tables suffice at this scale),
GraphQL (REST + typed records + codegen already gated), server-driven UI. The two
worth taking: (a) `openapi-typescript` output actually bound in FE API modules
service-by-service as Maps convert (turns silent runtime breaks into compile errors);
(b) TanStack Query `persistQueryClient` for offline-tolerant review flows on mobile —
optional, after user_prefs.

---

## 10. Phased implementation roadmap

Merge-policy tiers per house rules: **clean** (auto-merge on green), **HOLD** (build +
adversarial review, owner merges), **owner-gated** (decision first). Effort: S <½ day,
M ~1 day, L multi-day.

### Phase 1 — Safety + silent-failure kill (1–2 weeks)

| Item | Tier | Effort |
|---|---|---|
| V1 manual-order risk-governor enforcement | HOLD | S |
| V2 idempotency key + V4 lot-size validation | clean | S |
| V3 tick-freshness guards + bracket-starvation alarm | HOLD | M |
| `ingest_runs` ledger + writers (NseEod, bhavcopy, screeners, capture, sync) | clean | M |
| V9 T+1 ingest canary + V15 notifier-health check | clean | S |
| FII-derivative "disabled" diagnostic on the page (empty-state explains the flag) | clean | S |
| V12 backfill uuid persist + OI-backfill audit row | clean | S |
| Jobs page renders `jobs.error`; failed-job detail fetch | clean | S |
| V14 admin audit (query console + exports) + export truncation made explicit (413 or `truncated:true`) | clean | S |
| Schedule `prune_options_snapshots` (owner picks horizon) | owner-gated | S |

### Phase 2 — Workflow chains (2–3 weeks)

Signal-status push frames + FE cache patch (clean, M) · `paper.events` channel + rows
(clean, M) · position detail pane w/ provenance + PATCH brackets (HOLD — bracket edit
changes live paper, M) · ticket book selector + qty override on /signals take (clean, S) ·
journal link pickers + edit UI (clean, M) · notification center endpoint + page (clean, M)
· strategy `enabled` endpoint + toggle + audit-log read endpoint + timeline UI (HOLD —
arming surface, M) · graduation promotions display (clean, S) · `sell_decisions`
persistence + acknowledge/deep-link (clean, M) · dot-health panel on rejections (clean, S).

### Phase 3 — Analytics depth + freshness (2–3 weeks)

Freshness envelope + `FreshnessBadge` rollout across options/futures/equity/FII pages
(clean, L — mechanical) · per-page data-policy header + market-hours auto-poll for the
top OI pages (owner picks which pages poll; the wiring is clean, M) · chain time-travel UI
(clean, S — backend exists) · IV smile view + `/premium-series` decay chart (clean, M) ·
strike drill-down links chain/heatmap/spurt → Options Chart (clean, S) · screener date
picker + day-diff + funnel attrition endpoint (clean, M) · breadth daily materialization +
history chart (clean, M) · Minervini backtest page (clean, S) · CSV export standard
(shared util + endpoints on chain/trending/screeners/signals/trades/journal, clean, M) ·
risk-calculator margin tie-in to `POST /market/margin` (clean, S) · V6 OI outlier
quarantine + V7 cross-source OI canary + V8 bhavcopy-close check (clean, M each).

### Phase 4 — Platform planes (3–4 weeks, parallelizable)

Unified job envelope + aggregate read + jobs console (clean, L) · reference-data tables +
admin CRUD + real constituents path + V11 staleness (clean, L; constituent SOURCE choice is
owner-gated) · `user_prefs` + saved views + URL-encoded OI state + cmdk palette (clean, L)
· DataTable adoption wave for the 19 raw-table pages (clean, L — mechanical) · Map-return
burn-down + bridge extension to market-data (clean, ongoing ratchet) · event-schema
registry + IT pins (clean, M) · alert-rules page over the notifier (clean, L) · multi-window
pane whitelist extension (signals/paper/jobs panes) + named workspaces (clean, M).

Explicitly deferred to Prompt 2 (not built here): any ranking/scoring of signals or
screeners, prioritization queues, recommendation surfaces, automation policies, and the
intelligence-driven use of the events/ledgers this roadmap creates.

---

## 11. Inputs required by Prompt 2

The exact artifacts Prompt 2 must consume. **EXISTS** = usable today at the cited source;
**PARTIAL** = exists with named gaps Prompt 2 must respect or Phase 1–4 closes;
**ADD** = created by this roadmap (phase noted). All list endpoints are `{items:[...]}`
enveloped, `limit/offset` paginated unless noted; money rides as strings; timestamps ISO
with offset (key cross-source maps by instant, never offset).

1. **Signal schema** — EXISTS. `strategy.signals` (V003 + V006 `suggested_qty` + V009
   `scalper_detail` JSONB); `GET /signals` (filters: status/from/to/book/exchange/
   tradingsymbol/strategyVersionId), `GET /signals/{id}` (score_breakdown = frozen
   ScoreBreakdownDto; scalperDetail side-channel), WS `/topic/signals` (live frame omits
   detail — hydrate by GET). Gap for Prompt 2: status transitions only via §7.2.1 frames
   (Phase 2).
2. **Rejection schema** — EXISTS. `strategy.signal_rejections` (blocking_rail, operand,
   threshold, margin, composite score/threshold, full `diagnostic` JSONB, bar_time);
   `GET /signal-rejections` + `/rail-counts` + `/shadow-summary` + `/dot-health`.
   Richest decision substrate in the platform; no push (poll or Phase-2 events).
3. **Paper ticket schema** — PARTIAL. Ticket == `POST /paper/orders` request
   `{exchange, tradingsymbol, side, quantity, price?, stopLoss?, takeProfit?, signalId?}`;
   Phase 2 adds `book`, Phase 1 adds `clientOrderId` idempotency + governor veto (422 with
   rail). No draft/pending ticket state exists or is planned — tickets fill or reject.
4. **Paper trade schema** — EXISTS. `paper_positions` (book, avg entry, brackets,
   advised_lots, margin_snapshot/margin_pct, opening_signal_id, close_reason, realized_pnl)
   + `paper_orders` legs (born FILLED; quote_bid/ask null — treat as absent);
   `GET /paper/positions|trades|pnl|account`, `GET /paper/margin-heat`. Gap: fill-quality
   fields are null; MTM is tick-derived, not persisted per-bar.
5. **Order schema** — PARTIAL. Paper legs per #4. Broker orders = read-through
   `GET /orders/{orderbook,positions,tradebook,funds}` (OpenAlgo gateway; empty when
   unconfigured, shape = OpenAlgo's). Prompt 2 must NOT assume a broker order-event
   stream exists — none does until OpenAlgo arms + an ingest is built (out of scope here).
6. **Journal schema** — EXISTS. `journal_entries` (note, tags[], discipline/emotion 1-5,
   FKs signal_id/paper_position_id, soft backtest_run_id/trade_id, `auto`+`win|loss`
   tagging); full CRUD incl. the FE-unused PUT. Phase 2 makes links/ratings actually
   populated — until then expect `auto` entries with null ratings.
7. **Watchlist schema** — EXISTS. `marketdata.watchlists` + `watchlist_items`
   (PK list+exchange+symbol, instrument-validated); CRUD + rename/reorder endpoints;
   screener presets via `GET /market/screener?preset=`.
8. **Strategy schema** — EXISTS. `strategies` (slug unique, tags[] — first tag = book,
   enabled, published_version_id, notifications) + `strategy_versions` (immutable YAML +
   checksum, status draft/published/archived lowercase) + `strategy_audit_log`
   (append-only; Phase 2 adds read endpoint + enabled-toggle actions); `GET/POST/PUT
   /strategies`, `/validate`, `/{id}/versions`, `/{id}/diff`, `/publish`, `/rollback`,
   `/archive`, `/schema/v1`, `/{id}/universe`; pub-sub `strategy.changed`.
9. **Graduation schema** — EXISTS. `GET /strategies/graduation` board rows (stage
   PAPER|TAKE_ELIGIBLE, trades/net/win%/PF/expectancy/maxDD + per-criterion pass map,
   thresholds echoed) + `strategy_graduations` snapshots + `GET /graduation/promotions`
   (bare array today). V026 attribution guarantees per-strategy P&L identity.
10. **Backtest job schema** — EXISTS (UI-scope). `backtest.jobs` (kind BACKTEST|
    OPTIMIZATION|TRIAL, parent_job_id, status, progress, request JSONB, error, resultRef);
    `GET /backtests/jobs` (+status/strategy filters), WS `jobs.progress`. Fidelity §13
    owns run/trial/fold result contracts — do not duplicate.
11. **Comparison schema** — PARTIAL. Client-side matrix over N× `GET /backtests/{id}/results`
    (cap 6), guards = dataHash + universe checksum only; URL `?ids=` is the persistence.
    Server-side compare endpoint + saved comparisons = fidelity A3/F5 ADDs; Prompt 2
    should target that shape, not the client fold.
12. **Options analytics schema** — EXISTS. 21 endpoints under `/market/options/*`
    (chain-table typed; spurt/trending/heatmap/premium/active-strikes/oi-stats typed;
    oi-analysis/strike-series/multiple-oi/options-chart/oi-expiry/open-high Map-returning —
    hand-typed in `FE/api/types.ts`); all accept `mode=live|history&date=`; StrikePoint =
    bucket/strike/type/ltp/oi/oiChange/iv/spot/volume. Gaps Prompt 2 must respect:
    **no provenance flag on derived rows** (infer from null iv/greeks until §3.2.3 lands);
    no auto-refresh — consumers must poll or trigger.
13. **Futures analytics schema** — EXISTS. `/market/futures/{spurt,movers,oi-analysis-series,
    oi-chart,banks-analysis,eod,oi-buzz-*,pre-open,term-structure,banks-grid,movers-screen}`;
    universe = indices + 17 banks ONLY; EOD endpoint = intraday rollup, `historyStart` ≈
    2026-06 (do not treat as long-horizon EOD).
14. **Equity analytics schema** — EXISTS. `/market/equity/{returns,delivery,sector-heatmap,
    sector-stats,index-contribution,open-high-low,news,announcements,pre-open-scan}` +
    `/market/breadth` (single-date; daily series = Phase-3 ADD) + screener families
    `/market/screener/{minervini,manas-arora}/*` (screen rows carry per-gate booleans +
    rs_rank; funnel returns passers only; attrition endpoint = Phase-3 ADD; engine-version
    stamp = Phase-3 ADD V10). CA-adjustment caveat: live screen rows are raw-price-based
    until fidelity H6 lands — Prompt 2 must carry that flag per row-date.
15. **FII/DII analytics schema** — EXISTS with trust caveats. `/market/fii-dii/{cash,
    derivative-stats,participant-oi,long-short}` (all Map-returning, unbounded range
    reads); history = forward-accrual only; derivative-stats empty unless Upstox analytics
    flag on; gap-days possible and today undetectable — **Prompt 2 must consume the
    `ingest_runs` ledger (#16) as the data-trust oracle for this family.**
16. **Data-ops job schema** — PARTIAL→ADD. Today: `backfill_jobs` ledger (EXPIRED|
    EQUITY_DAILY; uuid uncorrelated), in-memory statuses, `GET /market/admin/{coverage-summary,
    backfill-jobs,*-backfill/status,upstox-quota-status}`. Phase 1 adds uuid+OI kind+audit;
    Phase 4 adds the unified job envelope `{id, kind, status, progress, request, error,
    startedAt, finishedAt}` + aggregate read + **`ingest_runs`**
    `{id, source, window_start, window_end, status, rows_written, error, started_at,
    finished_at}` — the single most important NEW input for Prompt 2.
17. **Freshness and source-quality schema** — ADD (Phase 3; partial precedents: chain
    `stale` flag, `asOf` fields, EodBadge, `/market/health/data`). Target envelope on every
    analytics response: `{asOf, source, historyStart, staleSeconds, complete: bool,
    provenance: live|derived|eod|static}` — plus `ingest_runs` for batch sources and
    per-underlying DataHealth granularity.
18. **UI state and workflow metadata** — ADD (Phase 4). `user_prefs` table
    `{key, value JSONB, updated_at}` (single-owner) for saved views/filters/layouts/
    palette recents; URL-state convention for shareable analysis (`?name&expiry&interval&
    mode&date` on OI pages). Existing localStorage keys (`ay.theme`, `ay.oi.*`,
    `ay.advanceChart`, `ay-multi-window`, `ay.chainDensity`) migrate to cache-of-prefs.
19. **Snapshot and history schema** — EXISTS. `options_chain_snapshots` (PK ts+underlying+
    expiry+strike+type; iv/greeks at capture; source LIVE|BACKFILL|UPSTOX_1M),
    `futures_oi_snapshots`, `preopen_equity_snapshots`, bhavcopy tables, `iv_daily_summary`/
    `iv_history`, `expired_contracts` (+coverage cols), screener per-day rows, `swing_batch_runs`,
    `shadow_positions`. Retention: none anywhere (manual prune fn unscheduled) — Prompt 2
    must not assume infinite forward growth is safe; Phase-1 owner decision pending.
20. **Permissions and audit schema** — EXISTS (thin, single-owner). Auth = one ROLE_OWNER
    session (12 h Redis, XSRF); no RBAC, no API tokens (fidelity A8 — any Prompt-2
    automation principal needs that ADD). Audit tables: `strategy_audit_log`,
    `risk_audit`, `bot_commands`, `notification_events`, `strategy_graduations`,
    `backfill_jobs`, + Phase-1 `admin_audit` (query/export) and paper-admin audit.
    Prompt 2 must route any state-changing automation through endpoints that write these
    trails — never direct DB writes.
