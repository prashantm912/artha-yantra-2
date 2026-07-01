# Full Codebase Audit 2026-07-02 — Complete Findings Register

Companion to [2026-07-02-full-codebase-audit.md](2026-07-02-full-codebase-audit.md). That report ranks and
compresses; THIS file is the complete inventory: every Phase-2 finding (all severities, verbatim evidence,
failure mode, fix) with its adversarial-verification verdict where one ran, plus Phase-1-only risks that were
not re-examined in Phase 2, and the explicit unverifiable lists.

Verdict legend: **CONFIRMED** = adversarial verifier re-read the cited code and the claim held.
**PARTIAL** = core claim held, details/severity corrected (correction quoted). **REFUTED** = claim did not
survive verification (retained here for the record, marked clearly). *(no verify pass)* = medium/low finding,
finder self-verified against cited lines only.

Phase-2 totals: **90 findings** — 2 critical, 21 high, 39 medium, 28 low. 23 adversarial verifications (16 CONFIRMED / 6 PARTIAL / 1 REFUTED).

---

## 1. FRONTEND (frontend-react)

### [HIGH / bug] `mock-tag-never-renders` — verdict: **PARTIAL** (severity corrected high → medium)

**MOCK mode indicator can never render — gateway maps kite session 'MOCK' to 'VALID' before the frontend checks for 'MOCK'**

- **Evidence:** services/edge-gateway/src/main/java/in/arthayantra/gateway/status/SystemStatusController.java:69-74 — `String session = switch (kiteRaw...) { case "MOCK", "CONNECTED", "LIVE" -> "VALID"; ... }` then `kite.put("session", session)` (line 85): the raw 'MOCK' value written by market-data (MockKiteSessionService.java:35 returns "MOCK"; SessionGateway.java:13 documents the MOCK/LIVE key) is rewritten to 'VALID' in the /api/v1/system/status response. frontend-react/src/stores/session.store.ts:82 sets `mockMode: status?.kite?.session === 'MOCK'` — a condition the gateway makes unsatisfiable. Consumers: AppShell.tsx:83-87 (topbar MOCK tag) and SettingsPage.tsx:53-54 (MOCK MODE notice) both gate on mockMode. The dashboard Kite tile (DashboardPage.tsx:124) likewise shows 'VALID' in mock mode.
- **Failure mode:** Running the mock stack (synthetic feed, artha_mock DB), the operator's UI shows NO mock marker anywhere — topbar tag absent, Settings notice absent, dashboard Kite tile reads 'VALID' in bull green. Mock/synthetic prices, signals and paper P&L are visually indistinguishable from live. This is the #2-ranked priority (live-vs-mock isolation) with a certain trigger: it is broken 100% of the time in mock mode, and the store's own doc comment (session.store.ts:15 'kite.session === MOCK from /api/v1/system/status') states an invariant the gateway does not deliver.
- **Fix:** Expose the raw session in the status payload (e.g. kite.put("raw", kiteRaw) or a top-level `mode: MOCK|LIVE`) and point session.store.ts at that field; keep the VALID/EXPIRED/ABSENT rollup for health. One added Map key does not drift the springdoc contract (Map returns are not enumerated).
- **Verifier correction:** The designed mock-mode indicators are indeed unreachable — the gateway intentionally rolls 'MOCK' up to 'VALID' (asserted in its own IT), so the topbar MOCK tag (AppShell.tsx:83-87), the Settings MOCK MODE notice (SettingsPage.tsx:53-55), and the mockMode store flag (session.store.ts:82) can never activate, and the dashboard Kite tile reads 'VALID' in bull green in mock mode. However, 'NO mock marker anywhere' is overstated: the Settings page's Kite Connect panel renders the raw tickerState from /api/v1/auth/kite/status, which is 'MOCK' in mock mode (SettingsPage.tsx:69-70 ← KiteAuthController.status() ← MockKiteSessionService.java:35), so one incidental 'Ticker: MOCK' string remains visible. Live/mock data isolation itself (separate DB/Redis) is intact — this is a dead-indicator/UI-trust defect, not an isolation breach — so severity is medium, not high.

### [HIGH / ux] `cockpit-errors-render-as-empty` — verdict: **CONFIRMED**

**Scalping Cockpit and Connecting Dots render backend FAILURES as calm 'no data' copy, and ALL OI-endpoint errors are toast-silenced**

- **Evidence:** frontend-react/src/api/oiAnalytics.ts:86 — oiGet passes `silenceToast: true` on EVERY request; client.ts:76-82 tags the thrown ApiError silenced for ANY status (500s and network failures included, not just the intended 422 DATA_GAP), and main.tsx:23 `if (error instanceof ApiError && error.silenced) return;` skips the global toast. CockpitPage.tsx:147-151 (`chain == null && !chainQ.isLoading` → 'No chain — pick an underlying + expiry with a live option chain.'), 165-168 ('No matrix…'), 188-193 ('No straddle candles…'), 212-214 ('No OI-change grid…') and ConnectingDotsPage.tsx:46-50 use data==null && !isLoading — on error (retry:false, isLoading false) this renders the EMPTY copy. Neither page uses QueryState (grep confirms; QueryState.tsx:8-10's own comment says it exists to kill exactly this bug).
- **Failure mode:** market-data-service crashes/restarts mid-session (has happened: OOM incidents on record). The operator's primary live trading screen shows 'No chain — pick an underlying + expiry with a live option chain', 'No matrix', 'No OI-change grid' — indistinguishable from a genuinely quiet/uncaptured session — with no toast, no error card, no retry affordance, while the WS pill still reads connected (gateway is up). Decisions get made on the belief that OI context is absent rather than that the data source is down.
- **Fix:** Two small changes: (1) in oiGet, silence only the 422 path — catch the 422 and return empty as today, but re-throw non-422 errors WITHOUT silenceToast (pass silenceToast:true per-request only after checking status is impossible pre-flight, so instead construct: try apiFetch without silence, catch 422 → empty, non-422 → rethrow; the 422 never reaches the toast because it is swallowed); (2) wrap the Cockpit panels and ConnectingDots table in QueryState (or at minimum branch on q.isError with the existing error card).

### [MEDIUM / bug] `no-global-401-handling` — *(no verify pass)*

**Session expiry leaves the app on a dead page: no 401 handler, no redirect, polling queries toast 'Authentication required' forever**

- **Evidence:** client.ts:69-83 throws ApiError(401, 'AUTH_REQUIRED', ...) — no status-specific handling anywhere (grep for 401/AUTH_REQUIRED in src finds no handler). Gateway confirms the shape: SecurityConfig.java:101-110 returns 401 + {code:AUTH_REQUIRED} JSON for any unauthenticated /api/** call. main.tsx:22-31 QueryCache/MutationCache onError only toasts. RequireAuth.tsx:13-19 probes ONLY when auth==='unknown'; session.store.ts never flips auth on a failed data call, so once 'authenticated' the guard renders the tree forever. Polling hooks fire continuously: dashboard.ts:28-29 (5s/4s), paper.ts:80,102 (MTM_REFETCH_MS), scalper.ts:34,51 (5s/3s), orders.ts:67-91.
- **Failure mode:** Redis session expires (idle > TTL, e.g. overnight laptop sleep) or the operator switches mock↔live stacks (separate Redis logical DBs invalidate the session). The open cockpit/dashboard keeps rendering the last-good (now stale) positions/P&L/status while every 3-5s poll 401s, emitting an endless 'Authentication required' toast stream; the WS also fails auth and loops reconnect (SecurityConfig /ws/** authenticated). The user is never redirected to /login and must diagnose it themselves; behind the toasts the trading data silently froze.
- **Fix:** In apiFetch (or QueryCache.onError), on status===401 call useSession.setState({auth:'anonymous'}) and let RequireAuth Navigate to /login with returnUrl (it already re-renders on auth change); dedupe the toast.

### [MEDIUM / bug] `text-html-returned-as-typed-data` — *(no verify pass)*

**apiFetch returns text/html bodies as typed data — a gateway allowlist misroute renders as a silent empty state**

- **Evidence:** client.ts:85-88 — after res.ok, if content-type is not application/json it returns `(await res.text()) as unknown as T`. No content-type guard exists anywhere in the module. The gateway serves the SPA index.html with HTTP 200 for any /api/v1 path missing from the edge-gateway route allowlist (documented, previously bitten: sibling prefixes do not match — signals/** ≠ signal-rejections/**). Downstream, listItems (client.ts:92-94) on an HTML string yields [] and QueryState's defaultIsEmpty (QueryState.tsx:23-31) returns false for a string, passing raw HTML into children(data).
- **Failure mode:** Next new backend endpoint whose prefix is forgotten in the gateway allowlist: the page fetches, gets index.html with 200 text/html, and either renders a permanent 'No data for this selection' empty state (list envelopes) or crashes/blanks reading properties off a string — with zero error signal, no toast, ok status. Diagnosis requires opening devtools; unit tests and tsc cannot catch it (typed as T).
- **Fix:** In apiFetch, when the caller expects a body and content-type does not include application/json, throw ApiError(res.status, 'BAD_CONTENT_TYPE', `expected JSON, got ${contentType}`) instead of returning text. (Keep the text path only for explicitly text endpoints if any exist — grep shows none.)

### [MEDIUM / bug] `daily-chart-date-off-by-one` — *(no verify pass)*

**Daily/weekly candle charts label every bar with the PREVIOUS calendar date (IST-midnight buckets rendered on a UTC axis with no shift)**

- **Evidence:** CandleChart.tsx:114-117 — the component's own comment states 'lightweight-charts renders the axis in UTC'; the +19800s IST shift is applied ONLY when intraday=true, and ChartsPage.tsx:139 / AdvanceChartPage.tsx:131 / MultiframeChartPage.tsx:38 all pass `intraday={interval !== '1d' && interval !== '1w'}` — so 1d/1w get istShift=0. Daily buckets are IST midnight instants: deploy/flyway/marketdata/V004__candles_continuous_aggregates.sql:57 buckets candles_1d with time_bucket(INTERVAL '1 day', bucket, 'Asia/Kolkata'), and native daily writes use tradeDate.atStartOfDay().atOffset(IST) (BhavcopyCandles.java:40). An IST-midnight instant is 18:30 UTC of the PREVIOUS day.
- **Failure mode:** On /charts, /advance-chart and /multiframe-chart at 1d or 1w, the bar for the Monday 2026-07-06 session carries time = 2026-07-05T18:30Z and the UTC axis/crosshair labels it '5 Jul' (a Sunday). Every daily bar, every symbol reads one day early — the operator cross-referencing a session date (earnings day, expiry day, signal date) against the chart picks the wrong bar; signal/trade marks placed by nearestTime land on the same shifted bar so the inconsistency is invisible from within the chart.
- **Fix:** Apply the same +19800 shift to daily/weekly bars (an IST-midnight instant +19800s = IST-date midnight UTC, so the UTC axis then shows the correct trade date), i.e. drop the `intraday ? 19800 : 0` conditional to a constant shift — or shift daily by computing the IST calendar date and using LWC's BusinessDay time type.

### [MEDIUM / bug] `calendar-spread-expiry-dead-end` — *(no verify pass)*

**Calendar Spread page depends on the shared expiry but opts out of the FilterBar expiry heal — fresh or stale-expiry sessions dead-end**

- **Evidence:** CalendarSpreadPage.tsx:37 calls useChainTable() (strike list + ATM source), which requires ctx.expiry — oiAnalytics.ts:77-80 satisfiable(ctx,true) returns false when expiry is null, and sends the persisted expiry otherwise. But line 78 mounts `<FilterBar showName showExpiry={false} showInterval={false}/>`, and FilterBar's default-and-heal effect is gated on showExpiry (FilterBar.tsx:111-120), so on this page a null expiry is never defaulted and a stale one (localStorage ay.oi.expiry, symbolContext.store.ts:15-16 — shared across mock/live on the same origin) is never healed.
- **Failure mode:** (a) Fresh browser profile navigating directly to /options/calendar-spread: expiry null → chainQ disabled → strikes never load → Strike select disabled and QueryState (line 151) shows a loading skeleton indefinitely — page unusable until the user first visits some other chain page. (b) Persisted expiry rolled over (weekly expired) or left over from the other stack profile: chain-table queried with the dead expiry → 422 → silently null → same dead strike list, or dead-session strikes.
- **Fix:** Either mount FilterBar with showExpiry (the page already ignores the shared expiry for its own near/far legs, so showing it is harmless), or add a page-local default: `if (!expiry && expiriesQ.data?.length) setExpiry(expiriesQ.data[0])` mirroring FilterBar's heal.

### [MEDIUM / ux] `live-cockpit-fetch-once-no-as-of` — *(no verify pass)*

**The 'live' Scalping Cockpit never auto-refreshes its market panels and shows no as-of timestamp**

- **Evidence:** None of the oiAnalytics.ts hooks the Cockpit composes (useChainTable, useConnectingDots, useStraddleChart, useOiHeatmap) set refetchInterval (grep confirms only useVix at oiAnalytics.ts:323 polls); global defaults are staleTime 30s, retry:false, refetchOnWindowFocus:false (main.tsx:28). CockpitPage.tsx:101-109 refreshes only on the manual Go button. The chain-table response carries asOf and a stale flag — OptionsChainPage.tsx:92 renders LiveDot stale/asOf — but CockpitPage renders spot/PCR/ATM/sentiment (lines 121-141) with no as-of anywhere on the page.
- **Failure mode:** Operator opens the cockpit at 09:20, watches signals stream in over WS (giving the impression of liveness), but the spot, PCR, ATM strike, OI-confluence matrix and heatmap are frozen at their last Go press — potentially 30+ minutes old with zero visual indication. Manual trades keyed off the panel sentiment/PCR use stale context while the adjacent paper book (polling 5s) IS live, mixing fresh and frozen numbers on one screen.
- **Fix:** Add refetchInterval (~30-60s, market-hours-gated if desired) to the four cockpit hooks or a cockpit-level interval calling refetchAll; render the chain asOf + stale flag in the header strip like OptionsChainPage already does.

### [LOW / bug] `oi-422-swallow-by-status` — *(no verify pass)*

**oiGet maps ANY 422 to the empty state by status alone, not the DATA_GAP code**

- **Evidence:** oiAnalytics.ts:88 — `if (err instanceof ApiError && err.status === 422) return emptyOn422;` never inspects err.code, though the ApiError carries it (client.ts:76-78). The backend does emit non-DATA_GAP 422s: ScreenerService.java:65 throws 422 ErrorCodes.VALIDATION_FAILED ('window must be one of…'), establishing 422-as-validation in the codebase; today the oiGet-hit endpoints' 422s are DATA_GAP, but a dead-expiry request also 422s DATA_GAP ('no snapshot for NIFTY 50 <expiry>') and renders as a legitimate 'no data'.
- **Failure mode:** Any future 422 VALIDATION_FAILED added to an OI endpoint (or a malformed param the FE starts sending after a refactor) renders as the illustrated empty card — 'No data for this selection' — instead of an error, silently masking a frontend/contract bug exactly the way the stale-expiry case is masked today.
- **Fix:** Narrow the guard to `err.status === 422 && err.code === 'DATA_GAP'`; let other 422s flow to QueryState's error card.

### [LOW / ux] `signals-hard-cap-200-silent-drop` — *(no verify pass)*

**Signals and rejections lists hard-cap at 200 rows with no pagination or 'showing N of total' — overflow rows silently invisible**

- **Evidence:** signals.ts:135 hardcodes limit=200&offset=0 (ring cap SIGNAL_RING_LIMIT=200 at line 116/189); signalRejections.ts:108 defaults limit=200, offset '0'; neither page (SignalsPage.tsx, RejectionsPage.tsx:69) offers paging. Backend orders generated_at DESC, id DESC and caps limit at 500 (SignalRepository.java:109, SignalsController.java:51) and returns items/limit/offset but no total count, so the FE cannot even detect truncation.
- **Failure mode:** A day with >200 rejections (plausible: ~30-rail gate evaluated per scalper per 3m bar across 12 live strategies; the rejections feature exists precisely because volume is expected) shows only the newest 200 for the picked day — morning-session rejections vanish from the list with no indicator, skewing the owner's why-did-the-gate-block analysis (the rail-counts rollup at /rail-counts remains complete, which partially mitigates). Same shape for signals on a >200-signal day.
- **Fix:** Return a total count from the controllers (Map return — no contract drift) and render 'showing 200 of N' with a Load-more that pages offset; or raise the rejections default toward the 500 backend cap for the single-day view.

### [LOW / performance] `ws-reconnect-global-invalidation` — *(no verify pass)*

**Every WS reconnect invalidates the ENTIRE query cache (plus a redundant second signals invalidation)**

- **Evidence:** main.tsx:34 — `wsClient.onReconnect(() => void queryClient.invalidateQueries())` with no filter refetches every ACTIVE query and marks all inactive ones stale; signals.ts:193-195 registers a second onReconnect invalidating [SIGNALS_KEY] (already covered by the global one). wsClient reconnects fire on every socket close after the first connect (wsClient.ts:71).
- **Failure mode:** A flapping network or a gateway restart cycle triggers repeated full-cache refetch storms: on a heavy page (Cockpit ~7 queries; equity returns screener over the full EQ universe if mounted) each reconnect refires everything simultaneously against services that may still be cold — amplifying recovery load and, combined with the silenced-OI-error finding, cycling panels through skeleton/empty flashes. Bounded: single-owner, loopback, and it IS the intended gap-heal.
- **Fix:** Scope the invalidation to WS-fed query keys (signals, ticks-seeded views, system status) or debounce reconnect bursts (e.g. only invalidate if connected-state holds for 2s); drop the redundant per-hook invalidation in signals.ts.

### [LOW / ux] `format-decimal-truncates` — *(no verify pass)*

**formatDecimal truncates instead of rounding the last displayed digit**

- **Evidence:** decimal.ts:21-26 — `const padded = (frac + '0'.repeat(fractionDigits)).slice(0, fractionDigits)` slices the fraction without rounding ('string-safe rounding-free' by design). Used for every displayed price/score: e.g. SignalsPage.tsx:216 formatDecimal(compositeScore, 4), DashboardPage.tsx:168 winRate to 4dp.
- **Failure mode:** A composite score of 0.66666667 displays 0.6666 (vs 0.6667), a winRate 0.58999 shows 0.5899 — always biased toward zero. For 2dp exchange prices (tick 0.05) the input scale matches and nothing is lost, so impact is confined to higher-precision derived values (scores, ratios); worst case the last digit reads one unit low, which can flip a displayed value across a mental threshold (0.6999→0.6999 shows 0.69 vs 0.70).
- **Fix:** Round half-up in formatDecimal by inspecting the first dropped digit (string arithmetic, still parseFloat-free), or document truncation and format scores with their native scale.

### [LOW / data-integrity] `tick-overlay-prefers-stale-tick` — *(no verify pass)*

**Paper-book MTM overlay prefers a possibly-frozen WS tick over the fresher 5s server mark with no staleness check**

- **Evidence:** PaperBookPanel.tsx:72-83 — `const mark = live[key] ?? p.markPrice` then recomputes unrealized from that mark; api/ticks.ts:18-63 keeps the last received LTP per symbol in component state indefinitely — no timestamp on frames is checked, no expiry, and the map is not cleared on WS reconnect (only the REST seed on symbol-set change refreshes it). The server mark (p.markPrice, polled every MTM_REFETCH_MS via paper.ts:80) is silently outranked.
- **Failure mode:** The Kite ticker upstream freezes while the gateway WS stays connected (a documented historical incident: frozen stale-token feed) — per-position Mark and uP&L pin to the last pre-freeze tick and stop tracking, while the server's 5s mark (computed from its own feed state) may differ; the operator watches a flat 'live' P&L. Paper-only money, but it is the cockpit's displayed book.
- **Fix:** Carry the tick timestamp in the frame (or record receipt time) and fall back to p.markPrice when the tick is older than ~2× the server-mark cadence; clear the tick map on wsClient reconnect.

### [LOW / tech-debt] `handwritten-dtos-unbound-to-contracts` — *(no verify pass)*

**Frontend DTOs have zero compile-time binding to contracts/gen — 'tsc --strict against generated types' does not constrain the app**

- **Evidence:** No file under frontend-react/src imports from contracts/gen (grep for 'contracts/gen' and 'gen/' finds only the package.json gen:api script at frontend-react/package.json:15); tsconfig.app.json includes only ["src"]. All wire shapes are hand-written (api/types.ts ~900 lines, signals.ts:12-113, etc.). Many endpoints are Map<String,Object> returns which the spec doesn't enumerate anyway (per project docs), so even a wired-up gen layer would only cover the typed subset.
- **Failure mode:** A backend rename/retype of a response field (on a typed endpoint) ships with green tsc and green ci-contracts gen-drift warnings: the field reads undefined at runtime and renders as '—' (or a wrong branch) on trading pages, discovered only by live inspection. The strict-tsc gate creates a false sense of end-to-end type safety.
- **Fix:** For the highest-trust surfaces (signals, paper, backtest results), add a small assignability test file: `const _check: components['schemas']['SignalDto'] = {} as SignalDto` style bridges importing from contracts/gen — compile-time drift alarms with no runtime cost; accept the Map-return endpoints as untypeable and rely on live-verify there.

### Unverifiable in this dimension

- Actual color-contrast ratios of the --ay-* tokens across the 5 themes (bull/bear/warn on surface-1 etc.) — requires rendering each theme and running axe; the token indirection is sound but numeric AA compliance was not computed statically.
- The exact rendered daily-chart label ('5 Jul' for the 6 Jul session) — the code chain (IST-midnight buckets, istShift=0 for 1d/1w, LWC UTC axis per the component's own comment) is fully verified, but I did not run the app to screenshot the axis; a live /charts 1d view would confirm in seconds.
- Runtime behavior of the session-expiry toast storm (frequency/dedup) — deduced from the polling intervals and the unconditional QueryCache onError toast; a live repro (delete the SESSION redis key with the cockpit open) would confirm.
- Whether any Playwright e2e asserts the topbar MOCK tag (which would have caught finding #1) — e2e/ specs were not read; given the tag can never render and e2e runs green on the mock stack, presumably none does.
- Whether /market/candles interval=1d serves candles_1d (cagg) or native candles@1d in every code path — both bucket at IST midnight (V004 cagg with 'Asia/Kolkata'; BhavcopyCandles atStartOfDay IST), so the daily-label finding holds either way, but the exact reader routing (CandleReader.read vs readDailyWithWarmup) per endpoint was not traced.

---

## 2. BACKEND part A

### [CRITICAL / bug] `taken-signals-exit-orphaned` — verdict: **PARTIAL** (severity corrected critical → high)

**Auto/manual-TAKEN entries lose every engine exit path (structural stop, confluence-flip, ExitEvaluator, brackets) and the engine re-enters/averages onto the same position**

- **Evidence:** services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/signals/SignalRepository.java:128-140 — activeEntry filters status='ACTIVE' only. AutoPaperListener.java:53 transitions the signal to 'TAKEN' synchronously on emission when auto_paper_trade is ON (SignalsController.java:84 does the same for a manual take). All engine exit passes anchor on activeEntry: structural stop SignalEngine.java:426-438, confluence-flip L445-452, ExitEvaluator L453-466, intrabar levels L533-555 — none run once TAKEN. PaperSignalListener.java:67-72 opens the paper position with null stopLoss/takeProfit (the signal's persisted stop_loss/target are discarded), and PaperBracketEvaluator.java:38-40 skips null-bracket positions. Grep of the paper package confirms NO component closes a paper position from an engine EXIT signal (only brackets/straddle-monitor/expiry/15:45-MTM/manual exist). Re-entry: with activeEntry empty, evaluateAtBarClose L467-477 re-runs EntryEvaluator every bar; scalper gates are level conditions (scalp-connect-the-dots-nifty.yaml:41 'close > vwap'), so a trend re-fires entries repeatedly, and PaperService.upsertPosition L190-201 AVERAGES each auto-take onto the same open (exchange,tradingsymbol,side) row. The YAML max_positions: 1 has no live enforcement other than activeEntry — defeated by TAKEN. The 5-account freeze can never engage because it counts CLOSED trades (ScalperAccountModel.java:69-93) and nothing closes them intraday (see intraday-mtm-crash). The owner's own runbook (docs/superpowers/plans/2026-06-30-live-signal-analysis-runbook.md:95-99) expects close_reason attribution across TAKE_PROFIT/trail/structural-stop/time_stop — impossible with this code, proving the behavior is NOT intended advisory semantics.
- **Failure mode:** Owner has auto_paper_trade ON (#367) to gather the E9 live dataset. A scalper entry fires at 10:00, is auto-taken instantly, and the position then has NO stop-loss, NO take-profit, NO trailing/time exit; each subsequent qualifying 3m bar emits another ENTRY that averages more qty onto it. A losing morning position rides unmanaged all day (and overnight — see intraday-mtm-crash). Every recorded trade's P&L reflects 'hold until forced close', not the strategy's exit design; the entire E9 keep/cut/tune analysis is built on garbage. If artha.scalper.execution=live were armed, real broker orders would be entry-only with no exit management.
- **Fix:** (a) Include TAKEN in the exit anchor: activeEntry WHERE status IN ('ACTIVE','TAKEN') so structural-stop/flip/ExitEvaluator passes and re-entry suppression keep running after a take; on engine EXIT, transition the entry and close the linked paper position (new small listener keyed on paper_orders.signal_id). (b) Pass signal.stopLoss()/target() into the OrderRequest in PaperSignalListener.openSingle so PaperBracketEvaluator provides a backstop.
- **Verifier correction:** Once a signal is TAKEN (auto-paper or manual take), every engine exit path (structural stop, confluence-flip, ExitEvaluator, intrabar levels) permanently stops for that position because the exit anchor only matches status='ACTIVE', and the opened paper position carries null SL/TP brackets (the signal's stop_loss/target are discarded), so nothing manages it until the 15:45 INTRADAY_MTM forced close — every hands-off trade's close_reason is INTRADAY_MTM, making the runbook's E9 exit-attribution analysis (TAKE_PROFIT/trail/structural-stop/time_stop) structurally impossible and leaving the 5-account first-loss freeze inert intraday. Re-entry suppression is also lost, but duplicate entries/averaging are bounded (≈0-2 adds) by the seeded max_deployment_pct 20% cap and the unrealized-inclusive daily loss/profit caps, and positions are closed same-day by the 15:45 sweep (overnight carry only if that sweep fails — a separate claim).

### [CRITICAL / bug] `intraday-mtm-crash` — verdict: **CONFIRMED**

**15:45 mark-to-close crashes every day there is an open intraday position — intradayOpen() SELECT omits stop_loss/take_profit but maps rows with the 13-column mapper**

- **Evidence:** services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/paper/PaperPositionRepository.java:174-188 — intradayOpen()'s hand-written SELECT lists 11 columns (p.id … p.close_reason) and maps via PaperPositionRepository::map, which reads rs.getBigDecimal("stop_loss") and rs.getBigDecimal("take_profit") (L58-59). PgJDBC throws PSQLException ('column name stop_loss was not found in this ResultSet') on the FIRST row, so jdbc.query throws before PaperService.markToCloseIntraday's per-position try/catch (PaperService.java:313-324) is ever reached — the whole 15:45 sweep aborts. Regression from PR #110 (git show df66555: COLUMNS and map() gained the two columns; intradayOpen's inline list was not updated). No test covers intradayOpen/markToCloseIntraday (grep of src/test: zero matches). The join conditions themselves are correct (config->'risk'->'session'->>'style' matches the YAML shape, verified against StrategyCompiler.java:82 and scalp-connect-the-dots-nifty.yaml:56-57), so with any auto-taken scalper position the result set is non-empty — which is exactly when it throws.
- **Failure mode:** With auto-paper ON, the first scalper take of the day guarantees ≥1 open intraday position at 15:45; PaperScheduler.intradayMarkToClose throws (only a scheduler-level error log), zero positions get INTRADAY_MTM-closed, and intraday scalp positions carry overnight indefinitely — until option/future expiry settlement weeks later (PaperExpiryService) or a manual close. Combined with taken-signals-exit-orphaned this means auto-taken positions currently have NO automatic exit at all; day-P&L, win/loss tallies, the 5-account freeze and the daily-loss cap all read a ledger that never realizes.
- **Fix:** Add p.stop_loss, p.take_profit to the intradayOpen SELECT (one line). Add an IT that opens a signal-linked intraday position and asserts markToCloseIntraday closes it.

### [HIGH / data-integrity] `paper-simulates-future-not-option` — verdict: **CONFIRMED**

**Paper trades the INDEX FUTURE the signal is keyed on, not the picked option — PE-side scalps are direction-INVERTED and hero-zero qty (sized off option premium) is applied to future notional**

- **Evidence:** PaperSignalListener.openSingle (L67-72) passes null exchange/tradingsymbol/side; PaperService.openOrder falls back to signal.exchange()/signal.tradingsymbol()/signal.side() (PaperService.java:146-148) — the index future the scalper signal is keyed on. Grep confirms nothing in the paper package reads tradeable_exchange/tradeable_tradingsymbol (only StraddleLegs for the 2-leg straddle path, which correctly opens the option symbols). The live arm DOES route the option (LiveOrderService.java:65-67) — paper and live diverge on the traded instrument. Inversion: every scalper YAML declares direction: long → side='BUY' (SignalEngine.java:720-721), including the -pe variants whose gate is 'close < vwap' (scalp-connect-the-dots-nifty-pe.yaml:42-45); a bearish read picks a PE, yet paper opens a LONG future position. The engine itself knows PE = SHORT-on-future (scalperPositionDirection, SignalEngine.java:1102-1114) — the ledger contradicts the engine. Hero-zero: emitEntry L753-765 overrides qty with heroZeroSuggestedQty (PaperEmissionGuard.java:94-105, budget ≈ ₹2,500 / premium×lot → e.g. 450 units at premium 5, lot 75), then the take opens 450 units of a ~25,000-point NIFTY future (~₹1.1cr notional) instead of a ₹2.5k option outlay.
- **Failure mode:** Every PE-variant auto-paper trade books P&L with the SIGN INVERTED (market falls → real PE wins, paper long-future loses); CE trades book delta-1 future P&L instead of ~0.4-delta option P&L with no theta/IV; hero-zero trades book ~1000× the intended notional. The owner's forward-paper A/B (niftyoi vs sensexoi, keep/cut decisions) is drawing conclusions from a ledger that structurally cannot represent the strategies being validated — a winning PE strategy can look like a loser and be cut.
- **Fix:** In PaperSignalListener.openSingle, when the signal carries scalper_detail, open tradeable_exchange/tradeable_tradingsymbol side BUY at scalper_detail.option_ltp (mirror the existing straddle-leg path, which already does exactly this).

### [HIGH / bug] `premium-pct-exits-dead-live` — verdict: **PARTIAL**

**premium_pct stop/target rules are computed against the index-future price live but against real option premium in backtest — 50%-of-premium stops and 35% targets can never fire on the live path**

- **Evidence:** SignalEngine.levelFromRules (L1147-1163) and ExitEvaluator.levelDistance (libs/strategy-engine/.../ExitEvaluator.java:237-238) compute the premium_pct distance off position.entryPrice() — live, that is the index FUTURE close (~25,000 for NIFTY). scalp-hero-zero-*.yaml:75-76 declares 'stop_loss premium_pct 50' (live level = future −50% ≈ 12,500 — unreachable intraday); scalp-gap-theory-*.yaml and scalp-market-movers-*.yaml declare 'take_profit premium_pct 35' (future +35% — unreachable); scalp-btst-stbt-*.yaml 'stop_loss premium_pct 50' likewise. Backtest's OptionsPremiumReplay runs the same rules against the actual option premium series (services/backtest-service/.../OptionsPremiumReplay.java uses the premium as primary), so the identical YAML produces real 50%/35% exits in backtest and inert rules live. Note the §0B hard-SL loader check (SignalEngine.java:212-217, ScalperRisk.hasBoundingExit) is SATISFIED by these never-firing rules — the safety invariant is nominally met but not actually enforced live.
- **Failure mode:** A live hero-zero or BTST position's designed hard stop ('SL = 50% of premium') never triggers — the option can go to zero with no exit signal; gap-theory/market-movers never emit their 35% take-profit. Backtest and live exit behavior for the same published YAML diverge irreconcilably, poisoning the E9 exit-band analysis and any backtest-vs-live comparison.
- **Fix:** Live path: resolve premium_pct rules against the picked option's premium (scalper_detail.option_ltp at entry as basis, current option LTP via LastTickReader for the check), or reject premium_pct rules at publish time for live scalpers and convert those YAMLs to index_points; document the chosen semantics in the schema.
- **Verifier correction:** Live premium_pct stop/target rules ARE computed against the index-future entry price and never fire (confirmed at SignalEngine.java L699/L1147-1163 and ExitEvaluator.java L237-238), while backtest applies the same rules to real option premium (OptionsPremiumReplay.exitRules L293-327 → PremiumExitEvaluator) — and HeroZeroGate.java L57-58 documents the inert rule as "the primary live stop". However: affected positions cannot ride to zero unbounded — hero-zero carries a reachable index-future structural stop (opposite session extreme, checked via structuralStopHit) plus a 16-bar time stop and 15:20 square-off; btst has a 1-day time stop; gap-theory/market-movers have VWAP signal-exit, supertrend trailing, and 20-bar time stops. The real defect is exit-semantics divergence (designed/backtested premium-band exits vs live structural/time exits), poisoning E9 and backtest-vs-live comparison, with the optimizer tuning a live-inert take_profit knob. Exclude the straddle family: its premium_pct rule is explicitly annotated as a backtest proxy and StraddleExitMonitor provides a real live combined-premium stop. The §0B bounded-exit check (stop_loss OR time_stop) is genuinely satisfied live via the time stops — only the "hard SL" leg is nominally met by an inert rule.

### [HIGH / bug] `no-http-timeouts-single-eval-thread` — verdict: **CONFIRMED**

**Zero HTTP timeouts anywhere — every RestClient (MarketOiClient ~15-call fan-out, candle warm/refresh, instrument meta) uses the JDK HttpClient with infinite connect/read timeout, all on the single signal-eval thread**

- **Evidence:** All clients build from the auto-configured RestClient.Builder with only a baseUrl (MarketOiClient.java:56-64, MarketDataCandlesClient.java:31-37, RestInstrumentMetaClient, RestMarketDataInstrumentClient, NotifierClient). No spring.http.client.connect-timeout/read-timeout anywhere (grep of application*.yml: only base-urls) and no .requestFactory customization. The fat jar contains no Apache HttpClient5/Jetty/Reactor-Netty (unzip -l verified), so Boot 3.5's detection falls to JdkClientHttpRequestFactory whose JDK HttpClient default is NO connect timeout and NO response timeout. These calls run on the single eval thread: gate fan-out via scalperEntry (SignalEngine.java:500-503), refreshFromRest at every coarse bucket boundary (L520-528), reload-time ensureWarm (L253) and futures resolution, and the paper open's instrument-meta fetch (synchronous SignalTaken listener).
- **Failure mode:** One half-open TCP connection to market-data (container restart mid-request, network stall) blocks the eval thread FOREVER: no entries, no structural-stop/exit evaluation for any strategy, bars accumulate unbounded in the pending queue, and nothing self-heals (the 20s reconcile also queues onto the same dead executor). The live engine is silently down until a human restarts the service.
- **Fix:** Set spring.http.client.connect-timeout: 2s and read-timeout: 10s (applies to the auto-configured builder for all clients), or pass ClientHttpRequestFactorySettings per builder. One-line application.yml change.

### [MEDIUM / bug] `resubscribe-gap-drops-1m-bars` — *(no verify pass)*

**resubscribe() stops the old Redis container before starting the new one — 1m bars published in the gap are lost permanently (the 1m series is never re-fetched after warm-up)**

- **Evidence:** SignalEngine.resubscribe (L317-349): container.stop() at L318-320, new container built/subscribed/started at L321-347. Redis pub/sub is fire-and-forget, so any candles.1m.* message published between stop() and start() is gone. LiveSeriesStore warms a 1m series only once (ensureWarm, computeIfAbsent — LiveSeriesStore.java:37-51) and refreshFromRest is only invoked for non-1m keys at bucket boundaries (SignalEngine.java:520-528), so a dropped 1m bar is a permanent session gap. Reloads happen intraday on every strategy publish (hot-swap) and on reconcile drift, not just at 08:40.
- **Failure mode:** Owner publishes a strategy edit at 11:02; the 11:01 close bar for the NIFTY front future lands in the stop/start window and is lost. exit_intrabar 1m level checks and the pre-close dayBars view silently skip that bar; a stop that would have fired on it is missed (the primary 3m series self-heals via REST, the 1m series does not).
- **Fix:** Start the new container BEFORE stopping the old one — duplicate delivery is already safe because EngineSeries.append rejects non-increasing buckets (appendQuietly). Alternatively re-warm 1m series from REST after each resubscribe.

### [MEDIUM / bug] `transition-no-state-guard` — *(no verify pass)*

**Signal lifecycle transition() has no state precondition — a signal can be taken twice (double paper fill), taken after dismissal/expiry, or dismissed while a paper position is open**

- **Evidence:** SignalRepository.transition (L143-145) is an unconditional UPDATE signals SET status=? WHERE id=?. SignalsController.taken (L80-93) calls transition then ALWAYS publishes SignalTaken regardless of prior status; AutoPaperListener does the same (L53-55). PaperService.upsertPosition averages onto the existing open position (L190-201), so a second SignalTaken for the same signal doubles the qty. The stage-C design (ARTHAYANTRA_2_STAGE_C_STRATEGY_ENGINE_MVP.md:385) specifies the lifecycle 'active → taken/dismissed/expired' — a one-way machine the code does not enforce.
- **Failure mode:** Auto-paper takes signal #123 at emission; the owner, seeing it in the UI, clicks Take with a qty → a second SignalTaken opens/averages a second fill on the same position (double exposure in the ledger, charged to a second sub-account pick). A stray POST /taken on an EXPIRED signal from yesterday opens a position at a stale price.
- **Fix:** Make the transition conditional: UPDATE ... WHERE id=? AND status='ACTIVE' (return rows-updated); 409 and skip the SignalTaken publish when 0 rows changed.

### [MEDIUM / data-integrity] `emit-entry-not-transactional` — *(no verify pass)*

**emitEntry/emit perform 2-3 dependent autocommit writes with no transaction — a mid-sequence failure leaves an ENTRY without its option leg/qty, or an EXIT emitted with the entry still ACTIVE**

- **Evidence:** SignalEngine has no @Transactional; emitEntry runs signals.insert (L729-733), stampSuggestedQty (L767) and stampScalperDetail (L774-777) as three separate autocommit statements; emit() (EXIT, L999-1018) runs insert then transition(anchor,'EXPIRED') (L1010) separately. A failure between statements is caught per-strategy in onClosedBar (L408-412) after partial commits.
- **Failure mode:** DB hiccup after the insert but before stampScalperDetail: the ENTRY row exists ACTIVE with no tradeable option and no suggested qty — auto-paper skips it, the UI shows a scalper signal with no leg, and the exit side later anchors on a row whose scalper_detail is null (scalperPositionDirection silently falls back to the definition direction — wrong side for a PE scalp). On the EXIT path, failure after insert leaves the entry ACTIVE and a duplicate EXIT is emitted next bar.
- **Fix:** Wrap each emit path's statements in a TransactionTemplate.execute (the JdbcTemplate datasource already has a transaction manager via spring-boot-starter-jdbc).

### [MEDIUM / performance] `oi-fanout-uncached-per-strategy` — *(no verify pass)*

**The confluence gate re-fetches the full ~15-call OI/macro/chain REST fan-out for every scalper strategy on every firing bar — no per-bar cache, all sequential on the eval thread**

- **Evidence:** ScalperConfluenceGate.evaluateWithDiagnostic fetches client.chain() (L319) then MarketOiClient.context() → oi() = 5 GETs (spurt, futures/banks, active-strikes, trending, term-structure — L271-341) + macro() = 9 GETs (iv-history, breadth, fii long-short, chain AGAIN, index-contribution, active-strikes AGAIN, vix, dow, fii-dii/bias — L394-493). No memoization keyed on (underlying, bar) exists anywhere in the class. All published CE variants share the same 'close > vwap' chart gate, so on one trending 3m bar N scalpers each pay the full fan-out sequentially on the single eval thread; confluenceFlipExit (SignalEngine.java:1122-1137) additionally re-runs the whole fan-out every bar per open position for oi-confluence-exit strategies.
- **Failure mode:** 12 CE variants pass the chart gate on the same bar → ~180 sequential HTTP round-trips; at 100ms each that is ~18s of eval-thread stall during exactly the hottest market moment — queued 1m bars (including bars that should trigger structural stops for other strategies) evaluate late; combined with the no-timeout finding, one slow endpoint multiplies across every strategy.
- **Fix:** Memoize chain/oi/macro per (underlying, barInstant) in MarketOiClient (a 1-entry TTL cache is enough — all callers in a drain share the same bar clock).

### [LOW / bug] `bar-eval-failure-drops-entry` — *(no verify pass)*

**A signal-insert/DB failure during bar evaluation permanently consumes the bar — the ENTRY for that bar is never retried**

- **Evidence:** drain() polls the bar off the queue before evaluation (SignalEngine.java:379-384); onClosedBar catches RuntimeException per strategy and only logs (L408-412). The series append (L388) has already happened, so indicator state is intact, but a failed signals.insert during a transient DB outage loses that bar's ENTRY decision permanently. EXIT decisions self-heal partially: the anchor stays ACTIVE (transition happens after insert in emit()), so the stop re-evaluates next bar — but a touch-and-reverse bar's stop hit is gone.
- **Failure mode:** A 2-second Postgres failover at 10:15 coincides with the one bar a scalper entry fires on; the entry is logged as 'evaluation failed' and never emitted — a silent missed trade with no retry or dead-letter, invisible except in logs.
- **Fix:** Acceptable for entries (next qualifying bar re-fires) but log at ERROR with a counter metric (ay_signal_eval_failures_total) so a burst is alertable; optionally re-queue the bar once on DataAccessException.

### Unverifiable in this dimension

- Redis pub/sub recovery after a Redis restart (lead 9): RedisMessageListenerContainer over Lettuce is expected to auto-reconnect and re-SUBSCRIBE (Lettuce re-issues channel subscriptions on reconnect), and the 20s reconcile loop would NOT help (it only reloads on published-set drift, not on a dead subscription). Confirming requires a live docker restart test, which was out of bounds for this audit.
- Empirical confirmation of findings 1-2 in the live DB (open paper_positions rows with close_reason IS NULL persisting across days since auto-paper went ON via #367, and absence of any INTRADAY_MTM close_reason rows) — would need a read-only SQL query against the live artha DB (docker/DB commands disallowed). The code path is decisive without it.
- Whether the frontend manual-Take dialog passes the OPTION premium as fillPrice (TakenRequest.fillPrice) — if it does, PaperService opens the future-keyed position at an option-scale price, producing absurd mark-to-market; frontend-react is outside this dimension's scope.
- JDK HttpClient as the selected request factory was verified by classpath elimination (no httpclient5/jetty/reactor-netty in the built fat JAR) plus Boot 3.5 detection order; a runtime bean dump (actuator) would confirm the concrete ClientHttpRequestFactory class.

---

## 3. BACKEND part B

### [HIGH / data-integrity] `optimization-job-requeue-collision` — verdict: **CONFIRMED**

**backtest-service restart hijacks a live OPTIMIZATION sweep row and replays it as a plain backtest**

- **Evidence:** Both services share ONE jobs table (deploy/flyway/backtest/V002__jobs.sql:8-23; optimizer INSERTs OPTIMIZATION/TRIAL rows into it, services/optimizer-service/app/repos.py:29-48, default status 'queued', then set_status 'running' for the sweep's whole duration, app/service.py:140). backtest-service's crash recovery has NO kind filter: requeueStaleRunning() is "UPDATE jobs SET status='queued' ... WHERE status='running'" (backtest/jobs/JobRepository.java:148-151), findQueuedIds() selects ALL queued rows (154-158), StreamBootstrap re-dispatches every one onto jobs.backtest (dispatch/StreamBootstrap.java:58-65), and WorkerPool.claim/BacktestRunner.run never check job.kind() except for the TRIAL publish branch (WorkerPool.java:150, BacktestRunner.java:107-135,289). A sweep's request carries strategyId/from/to, so BacktestRunner runs it as a real full replay.
- **Failure mode:** Owner starts a multi-hour sweep (parent OPTIMIZATION row status='running'); a routine backtest-service redeploy/crash-restart mid-sweep re-queues that row, re-dispatches it, and a worker replays the SWEEP job as a plain backtest of the base config — inserting a bogus backtest_runs row keyed to the sweep's job id and marking the sweep 'completed' while the optimizer thread is still running (whose set_status then flips it back to 'running'). The research record shows an OPTIMIZATION job with a total_return, and the sweep's status/progress flip-flops between two writers.
- **Fix:** Add a kind filter to the recovery path: requeueStaleRunning ... AND kind IN ('BACKTEST','TRIAL') and the same predicate in findQueuedIds (or a kind guard in WorkerPool before claim). One-line SQL change in each.

### [HIGH / bug] `optimizer-shared-consumer-result-stealing` — verdict: **PARTIAL** (severity corrected high → medium)

**Concurrent sweeps steal each other's trial results via one shared cg-optuna consumer + XACK-on-read + drop-unknown, hanging both**

- **Evidence:** One TrialDispatcher (consumer 'optimizer-1') is shared by every sweep thread (app/main.py:35,44-50; app/service.py:117-133 one thread per sweep, same self._dispatcher). read_results XACKs each entry immediately on read (app/streams.py:39-55). run_sweep drops any result whose trialId is not in ITS OWN pending map ('if key not in pending: continue', app/sweep.py:144-147) — the entry is already acked, so the owning sweep can never receive it. _reconcile_dead only resolves pending trials whose job is failed/cancelled — a COMPLETED trial whose result was stolen stays pending forever (sweep.py:163-164,234-246, _TERMINAL_NO_RESULT at :27).
- **Failure mode:** Owner runs two sweeps concurrently (the API accepts it). Sweep A's thread consumes+acks trial results belonging to sweep B and discards them; B's completed trials never resolve, 'completed < planned' never becomes true, and B (often both) spins forever in the 2s xreadgroup loop — status stuck 'running', trials permanently un-told to the study. There is also no cancel check on that no-result path (see sweep-lifecycle finding), so the only recovery is restarting the optimizer.
- **Fix:** Either serialize sweeps (reject a second submit while one runs) or route results per sweep: include sweepId in every entry (already emitted by TrialResultPublisher) and have each sweep read with its own consumer group/name filtered by sweepId, XACKing only entries it owns; resolve completed-but-stolen trials in _reconcile_dead by also treating job status 'completed' as reconcilable via the persisted run metrics.
- **Verifier correction:** Core claim stands in full (shared consumer + XACK-on-read + drop-unknown + reconcile gap + no cancel escape ⇒ concurrent sweeps steal and destroy each other's trial results and hang; a stuck sweep's zombie thread also poisons all SUBSEQUENT sweeps until the optimizer is restarted). Severity should be medium, not high: no wrong trades or persistent data corruption — backtest runs persist in backtest_runs, the hang is owner-visible as a never-completing job, and restart + re-run fully recovers; blast radius is bounded to the optimizer research pipeline.

### [HIGH / data-integrity] `upstox-quota-fragmentation` — verdict: **REFUTED**

**The Upstox 2000/30min per-token cap is enforced by 3 INDEPENDENT limiter instances plus 3 unlimited clients on the same token**

- **Evidence:** UpstoxRateLimiter's javadoc pins the invariant ('shared across the backfill workers ... 2000 req/30 min is the binding constraint', libs source upstox/UpstoxRateLimiter.java:9-13) but three clients each construct their OWN instance: UpstoxExpiredInstrumentsClient.java:41, UpstoxGlobalInstrumentsClient.java:68, UpstoxMarketStatusClient.java:70 — a combined allowance of 3×1800=5400/30min against the real 2000 cap. UpstoxQuoteClient, UpstoxOptionChainClient and UpstoxAnalyticsClient use NO limiter at all (grep for 'limiter'/'acquire' matches only the three files above) yet run on the SAME analytics token (properties.resolveToken() in all). Live cadences that stack when the Wave U1/U2 source flags are flipped: options snapshot every 5min × every expiry ≤90d per underlying (options/OptionsSnapshotService.java:85-99, ~14+ chains/underlying) + 30s chain broadcast per underlying (:103-115) + futures OI every 3min (futures/FuturesOiSnapshotService.java:64-65) + quote batches — on top of an expired-contract backfill legitimately consuming its full private 1800/30min.
- **Failure mode:** A manual expired-backfill trigger (or the hourly self-heal finding real work) during market hours while source.optionchain/quotes=upstox: the aggregate exceeds 2000/30min, Upstox 429s, and the UNLIMITED chain/quote clients have no retry — each failed 5-min snapshot pass is a permanently lost options_chain_snapshots interval (intraday OI is forward-capture-only and irreplaceable; the platform's own doctrine says OI strategies are judged on this captured data).
- **Fix:** Make UpstoxRateLimiter a singleton Spring bean sized to the token cap and inject it into every Upstox client (including quote/chain/analytics); optionally reserve a priority budget for the live capture path so backfill can never starve it.

### [HIGH / bug] `calendar-2027-cliff` — verdict: **CONFIRMED**

**Live scalper evaluation throws from Tue 2026-12-29 and OI capture silently halts on 2027-01-01 — the calendar look-ahead crosses the coverage edge before year-end**

- **Evidence:** nse-trading-holidays.csv covers 2024-2026 only (verified by cut/sort on the resource). isTradingDay throws for uncovered years (libs/market-calendar/.../MarketCalendar.java:116,236-245); isMonthlyIndexExpiryDay(date) calls nextWeeklyIndexExpiry(date.plusDays(1)) (:225-229) which walks nextOrSame(TUESDAY) into 2027 — for tradeDate 2026-12-29 (the last Tuesday of Dec 2026) that queries 2027-01-05 and THROWS. MarketOiClient.oi()/trend60mDir() call it on EVERY scalper OI fetch (strategy-signal scalper/MarketOiClient.java:272,353) and ScalperConfluenceGate calls isWeekly/isMonthly for hero-zero (:826). SignalEngine catches per-evaluation RuntimeException and just logs (signals/SignalEngine.java:408-412). From 2027-01-01, OptionsSnapshotService.isOpenSafe catches the IllegalArgumentException and returns false — capture is silently treated as market-closed (options/OptionsSnapshotService.java:191-197).
- **Failure mode:** On 2026-12-29/30/31 every scalper evaluation errors out ('evaluation failed ...' log spam) — zero signals, zero rejection rows, no alert, during live trading days. From 2027-01-01 the 5-min OI snapshot capture and 30s broadcast silently stop until the calendar PR lands — every missed session is an irreplaceable OI-capture gap (candle-derived history has muted Dow/IV factors by design).
- **Fix:** Land the 2027 holiday CSV now (CD-2), and add a canary: alert when today is within N weeks of max(coveredYears) year-end. Optionally make nextWeeklyIndexExpiry degrade to weekday-only math past coverage instead of throwing mid-look-ahead while 'today' is still covered.

### [HIGH / bug] `sensex-expiry-weekday-model` — verdict: **CONFIRMED**

**SENSEX scalpers use the NSE-Tuesday weekly-expiry model — hero-zero fires on the wrong weekday and monthly-expiry OI suppression misses the BSE expiry**

- **Evidence:** There is exactly ONE calendar in the platform: MarketCalendar.nse(), Tuesday weekly expiries hard-coded (MarketCalendar.java:33-35,205-217); ScalperOiConfig.java:23 returns it and both ScalperConfluenceGate (:826, feeding HeroZeroGate expiry-day flags) and MarketOiClient (:272,353 monthly-expiry OI suppression) consume it with tradeDate regardless of the strategy's option root (NIFTY vs SENSEX). Since Sept 2025 BSE SENSEX weekly expiry is Thursday (external exchange fact; NSE=Tuesday per SEBI single-expiry-day — the javadoc itself documents only the NSE move). No BSE calendar or per-root selection exists anywhere in the repo.
- **Failure mode:** A SENSEX hero-zero (#7) scalper evaluates expiryDay=true on TUESDAY (NIFTY's expiry, a normal day for SENSEX options — no expiry-day premium decay/short-covering regime) and expiryDay=false on the actual SENSEX Thursday expiry, so it can enter on the wrong day and never on the right one. Symmetrically, SENSEX chain-OI reads are suppressed on the NSE monthly Tuesday (harmless) but NOT on the BSE monthly expiry when the expiring-series writer unwind actually corrupts them — corrupt OI feeds the confluence dots that day.
- **Fix:** Parameterize the weekly-expiry weekday per exchange/option root (BSE→Thursday) — a small MarketCalendar variant selected by the scalper's option-root exchange — and route the monthly-OI suppression through the same selection.

### [MEDIUM / data-integrity] `datahash-partial-coverage` — *(no verify pass)*

**data_hash covers only the primary 1m tuple while options runs also consume premium candles, context series, strikeRef series and (when armed) live Connecting-Dots REST — the 'same triple ⇒ byte-identical trades' claim is false**

- **Evidence:** BacktestRunner hashes only (signal exchange/symbol, '1m', from, to, primary1m.size(), maxFetchedAt) (replay/BacktestRunner.java:256-264); DataHash's javadoc claims 'Same triple ⇒ byte-identical trade list' (replay/DataHash.java:9-13). But an options run's trades are priced off expired-contract premium candles (OptionsPremiumReplay), the strikeRef series (:151-155) and context series (:146-147) — none hashed. When backtest.oi_confluence_gate is armed, entry filtering additionally calls market-data's REST at replay time and a transport failure silently degrades to 'no filtering' (OptionsPremiumReplay.java:108-109,218-259; MarketDataClient.connectingDots returns CdResponse.EMPTY on ANY failure, client/MarketDataClient.java:80-96) with nothing recorded about whether the gate saw data.
- **Failure mode:** Two runs of the same strategy/window get identical data_hash yet different trades: e.g. an expired-contract backfill fills more premium coverage between runs (legs that previously skipped now fill), or market-data was briefly down during run 2 so the armed OI gate filtered nothing. The leaderboard's like-for-like comparison flag — the exact thing data_hash exists for — reports them as identical-data.
- **Fix:** Fold the other actually-read inputs into the hash (per-series tuple set: strikeRef, each context, the selected option contract series' bar counts + maxFetchedAt) and persist an oiGateCoverage flag (sessions fetched vs empty) on the run metrics when the gate is armed.

### [MEDIUM / bug] `sweep-lifecycle-cancel-restart` — *(no verify pass)*

**Sweep cancel only takes effect when a result arrives, a cancelled sweep is then overwritten to 'failed', and optimizer restarts orphan 'running' sweeps forever**

- **Evidence:** Cancellation is checked ONLY in _progress (app/service.py:158-169), which run_sweep calls only after consuming a result (app/sweep.py:157-159); the no-result path (results empty, pending non-empty) loops with no cancel check (sweep.py:163-164,126). cancel() sets DB status 'cancelled' (service.py:295-306); when the thread later hits _progress it raises ApiError, caught by 'except Exception' which does jobs.set_status(sweep_id, 'failed') (service.py:152-153) — and set_status has NO terminal-state guard ('UPDATE jobs SET status=%s WHERE id=%s', app/repos.py:50-59), so 'cancelled' is overwritten by 'failed'. main.py wires no startup recovery for running OPTIMIZATION rows (app/main.py:27-61) and sweeps run on daemon threads.
- **Failure mode:** (a) Owner cancels a sweep whose trials are stuck → the thread spins forever, status says 'cancelled' but trials keep dispatching next iteration if slots free. (b) Owner cancels a healthy sweep → final status reads 'failed', misrepresenting the research record. (c) optimizer-service restart mid-sweep → the sweep row stays 'running' forever (backtest-service's requeue would then hijack it — see the collision finding).
- **Fix:** Check self._cancelled inside the read loop (pass a cancel-probe callable into run_sweep); make set_status a conditional UPDATE ... WHERE status NOT IN ('completed','cancelled','failed') for terminal writes; on optimizer boot mark stale 'running' OPTIMIZATION rows failed ('optimizer restarted').

### [MEDIUM / bug] `jobpruner-fk-violation` — *(no verify pass)*

**JobPruner's DELETE has no NOT-EXISTS-runs guard (contradicting its own javadoc) — the FK makes the monthly prune throw and prune NOTHING once any 180-day-old job has runs**

- **Evidence:** JobRepository.pruneStaleTerminal is a bare DELETE FROM jobs WHERE status terminal AND finished_at < now()-N days (jobs/JobRepository.java:246-251) while its javadoc AND JobPruner's claim 'Phase 30 adds the NOT EXISTS backtest_runs guard' (JobRepository.java:243-245, jobs/JobPruner.java:9-11). backtest_runs.job_id REFERENCES jobs(id) with no ON DELETE action (deploy/flyway/backtest/V003__runs_trades.sql:11). Also self-FK jobs.parent_job_id (V002:11) blocks deleting a pruned-window sweep whose trials are younger.
- **Failure mode:** From ~180 days after the first completed backtest (first bite ≈ 2026-12-10 given Stage-D landed 2026-06-13): the monthly 03:00 prune hits the FK, the whole statement rolls back (statement-level failure — even runless failed/cancelled jobs aren't pruned), and the @Scheduled method throws every month. Jobs-table hygiene is permanently dead; the only saving grace is the FK accidentally protecting the research record.
- **Fix:** Add the documented guard: DELETE ... AND NOT EXISTS (SELECT 1 FROM backtest_runs r WHERE r.job_id=jobs.id) AND NOT EXISTS (SELECT 1 FROM jobs c WHERE c.parent_job_id=jobs.id).

### [MEDIUM / data-integrity] `duplicate-runs-no-unique-jobid` — *(no verify pass)*

**No UNIQUE(job_id) on backtest_runs + crash-requeue re-execution can persist two runs (and double trial-tells) for one job**

- **Evidence:** backtest_runs.job_id is NOT NULL REFERENCES jobs(id) with no UNIQUE (V003__runs_trades.sql:11,73). BacktestRunner persists runs.insert → trades.insertAll → trialResults.publish in separate non-transactional statements (BacktestRunner.java:266-297; no @Transactional), and markCompleted happens later in WorkerPool (WorkerPool.java:170-172). requeueStaleRunning re-queues any 'running' row on restart (JobRepository.java:148-151) → full re-execution → second run row for the same job_id, second trades set, second OPT_RESULTS publish.
- **Failure mode:** Process dies (OOM/redeploy) in the window between runs.insert commit and markCompleted (which includes a potentially large trades.insertAll): restart re-runs the job and the job now owns two backtest_runs rows. resultRef picks the latest (RunRepository.java:163-170 ORDER BY completed_at DESC) and the optimizer drops the duplicate tell (pending.pop dedupe), but the phantom first run + its trades pollute strategy-lineage aggregates, per-strategy leaderboards, and any query that joins runs without dedupe.
- **Fix:** CREATE UNIQUE INDEX ON backtest_runs(job_id) in a new migration and convert runs.insert to INSERT ... ON CONFLICT (job_id) DO UPDATE (or delete-then-insert on re-run) so a crash-rerun replaces rather than duplicates.

### [MEDIUM / bug] `replay-legs-silent-index0-fallback` — *(no verify pass)*

**Signal→bar pairing falls back to bar index 0 on a timestamp-key miss — a missing first minute of a coarse primary bucket silently opens the trade at the window start**

- **Evidence:** ReplayEngine builds indexByTs keyed by 1m bucketStart().toString() (ReplayEngine.java:99-102) and legs() uses indexByTs.getOrDefault(entryTs, 0) / getOrDefault(exitTs, bars-1) (:241-242,258); OptionsPremiumReplay.pairLegs mirrors it (OptionsPremiumReplay.java:128-131,141-142,151). For a 3m/5m/15m-primary strategy the signal timestamps are COARSE bucket starts (TickwiseGoldenRunner rolls 1m up), and a coarse bar exists whenever ANY of its 1m constituents exists — so if the bucket's FIRST 1m bar is missing (a routine 1m gap), the coarse bucketStart matches no 1m key and the entry index silently becomes 0.
- **Failure mode:** A 3m-primary scalper backtest over a series with a missing 09:15/10:33-style first-minute bar: the entry signal at that bucket maps to bar 0 — the trade opens at the very first bar of the whole window at that bar's price, producing a wrong trade (potentially a huge phantom P&L) with no error, warning, or provenance flag.
- **Fix:** Fail loud (throw DATA_GAP) or resolve to the first 1m index >= the signal timestamp (a NavigableMap floor/ceiling lookup) instead of getOrDefault(...,0); log any fallback.

### [MEDIUM / tech-debt] `candles-3m-policy-lineage-drift` — *(no verify pass)*

**V019 ships an active candles_3m refresh policy that the runtime deliberately bypasses and doctrine says must not run — lineage vs live drift, resurrected on every reset-db**

- **Evidence:** V019__candles_3m_cagg.sql:29-31 adds add_continuous_aggregate_policy('candles_3m', start_offset 1 day, schedule 3 minutes); no later migration alters or removes it (grep candles_3m over deploy/ matches only V019). The runtime never reads the cagg — 3m is read-time rolled from 1m (CandleQueryService.java:31-33,104-105; CandleRepository.rangeRolledFromOneMinute) — and CLAUDE.md pins 'candles_3m exists but is empty/unwired (refreshing it OOMs)', which contradicts a live 3-minutely refresh policy.
- **Failure mode:** Any fresh rebuild (ay reset-db, CI, disaster restore) re-arms a background Timescale job that materializes a view nothing reads, competing for the 1GB instance's memory every 3 minutes and accumulating invalidation-log entries whenever the expired-contract backfill writes historical 1m rows; meanwhile the live DB (where it was presumably dropped or is failing, per 'empty') diverges from what the checksummed lineage produces — the next rebuild does not reproduce production.
- **Fix:** New suffix-versioned migration: SELECT remove_continuous_aggregate_policy('candles_3m', if_exists=>true) (and optionally drop the cagg) so the lineage matches the read-time-rollup reality.

### [MEDIUM / data-integrity] `fetchsource-profile-not-gateway` — *(no verify pass)*

**Candle provenance 'source' is derived from the Spring profile, not from which gateway actually fetched — OpenAlgo-fetched rows would persist as 'KITE'**

- **Evidence:** CandleQueryService.java:65: fetchSource = environment.matchesProfiles("live") ? "KITE" : "MOCK", stamped on every gap-fetch upsert (:144,179). The HistoricalCandleGateway port has three implementations selected by artha.marketdata.source.candles (openalgo/live/OpenAlgoConfig.java:52-61; kite/live/LiveHistoricalCandleGateway; mock) — the label ignores the selection.
- **Failure mode:** The planned §17.11 cutover flips source.candles=openalgo: every backfilled row is permanently mislabeled source='KITE'. Any later data-quality forensics (e.g. diagnosing a bad-bars incident per source, or the BACKFILL-vs-TICK_AGG style provenance queries this platform leans on) attributes OpenAlgo/Upstox-fetched data to Kite with no way to distinguish after the fact.
- **Fix:** Have the gateway report its own source tag (add a sourceLabel() to HistoricalCandleGateway, or bind the label from the same artha.marketdata.source.candles property that selects the bean).

### [MEDIUM / observability] `ticker-status-connected-lie` — *(no verify pass)*

**kite:ticker:status=CONNECTED is written before the socket connects and never corrected on connect failure**

- **Evidence:** FeedPipeline.startFeed writes 'CONNECTED' at feed/FeedPipeline.java:76 BEFORE marketFeed.start() at :80; LiveTickerFeed.start swallows connect failures with a warn log (kite/ticker/LiveTickerFeed.java:97-102); the only other writer of the key is stop() → 'DISCONNECTED' (FeedPipeline.java:135). Nothing transitions the key on the actual onConnected/onDisconnected callbacks (LiveTickerFeed.java:91-94 only log).
- **Failure mode:** The known failure class (stale-token 403 at the 09:10 start — it has happened live) leaves the B-13 status surface asserting CONNECTED all day while zero ticks flow; the owner's status panel/health view lies exactly when it matters, and detection falls back to noticing stale charts (ticks:last-at age) instead of the status field built for this.
- **Fix:** Write CONNECTED from the onConnected callback (and DISCONNECTED from onDisconnected), seeding the key as CONNECTING in startFeed.

### [LOW / bug] `tick-pipeline-redis-coupled-bar-loss` — *(no verify pass)*

**Candle building is fan-out-coupled to Redis publish success, and BarWriter swallows closed-bar persist failures — intraday 1m loss heals only at the 15:45 EOD pass**

- **Evidence:** normalizerLoop runs lastTickStore.update → publisher.publish → listeners (CandleBuilder is the only NormalizedTickListener; CandlesConfig.java:31-43) inside ONE try — any RuntimeException (RedisTickPublisher catches ONLY JsonProcessingException, RedisTickPublisher.java:44-53; a Redis connection failure propagates) skips CandleBuilder for that tick (feed/FeedPipeline.java:111-126). BarWriter.onClosedBar catches Exception and only logs, dropping the bar (candles/BarWriter.java:47-57).
- **Failure mode:** A brief Redis or Postgres hiccup during market hours silently drops 1m bars from the store; the live SignalEngine's series (and any intraday chart read before 15:45) has gaps until EodBackfillJob's gap-aware prefetch heals them — bounded because Redis-down degrades the whole live loop anyway and EOD self-heal exists, hence low.
- **Fix:** Call the CandleBuilder listener BEFORE (or independently of) publisher.publish with its own try/catch; optionally have BarWriter retry once or enqueue failed bars for the next flush sweep.

### [LOW / architecture] `pel-inline-drain-blocks-startup` — *(no verify pass)*

**StreamBootstrap drains the PEL by running pending backtests INLINE and serially on the startup thread before the worker pool starts**

- **Evidence:** drainPending() reads the consumer's pending entries and calls workerPool.process(record) directly (dispatch/StreamBootstrap.java:93-125, inline call at :115); process → claim → runClaimed → runner.run is fully synchronous (WorkerPool.java:146-183). workerPool.start() only runs after the drain completes (StreamBootstrap.java:71).
- **Failure mode:** Restart after a crash that left several long-running jobs delivered-but-unacked: the ApplicationRunner thread replays them one-by-one (a multi-hour serial pipeline on one thread) before the pool starts consuming new work; queued jobs re-dispatched at :64-65 wait behind it and the service looks alive-but-stuck.
- **Fix:** XCLAIM/collect the pending records and submit them to the worker pool (or simply XACK them and rely on the requeue+re-dispatch path, which already covers the same jobs via the authoritative table).

### [LOW / ux] `cancellation-checkpoint-gaps` — *(no verify pass)*

**Job cancellation is only observed at progress 10/40/80 — options replays and the whole fold/benchmark/persist phase are uncancellable**

- **Evidence:** checkpoint() (which alone throws JobCancelledException) is called at 10, 40 and 80 only (BacktestRunner.java:119,171,199,512-518); the candle replay's progress lambda (:198) and the premium replay (no callback at all, :178-186) never check the cancel key; after checkpoint(80) the fold walk-forward (2 replays per fold, :219-235), benchmark analytics and persistence run with zero cancel checks.
- **Failure mode:** Owner cancels a fold-context options trial right after it passes 40%: the cancel key sits in Redis while the run replays every fold to completion and persists results anyway; the job then flips cancelled at no checkpoint — WorkerPool only sees the cancel if a later checkpoint fires, so the run frequently completes as if never cancelled.
- **Fix:** Thread the cancelled BooleanSupplier into the replay progress callback (throw from the throttled progress lambda) and add a per-fold checkpoint in WalkForwardRunner.run.

### Unverifiable in this dimension

- Live .env source flags (ARTHA_MD_SOURCE_OPTIONCHAIN/QUOTES/CANDLES): whether the production stack has flipped chain/quote capture to upstox — determines whether the Upstox quota-fragmentation finding currently threatens live OI capture or 'only' the backfill+analytics surfaces. Needs the live .env or `docker exec` env dump (out of scope: no docker commands).
- Live DB state of the candles_3m refresh policy: the lineage creates it (V019) and doctrine says the cagg is 'empty/unwired' — confirming the drift direction needs SELECT * FROM timescaledb_information.jobs on the live artha DB (DB commands out of scope).
- Actual aggregate Upstox request rate over a live 30-min window (depends on runtime overrides: artha.options.snapshot-underlyings, snapshot-interval-ms, futures interval, UI-driven analytics traffic) — I summed the code-default cadences only.
- BSE SENSEX weekly expiry = Thursday is an external exchange fact (post-Sept-2025 SEBI single-expiry-day regime) that I could not verify from the repo — the repo's calendar documents only the NSE Tuesday move (MarketCalendar.java:33-35); the code-side claim (one NSE-Tuesday calendar used for all roots) is fully verified regardless.
- BhavcopyBackfillService nightly anti-join cost (sweep item): the reconcile window is config-bounded (reconcileLookbackDays) and I did not profile the query — no evidence of a defect found, left unassessed.

---

## 4. DATA & MIGRATIONS

### [HIGH / data-integrity] `compose-mock-defaults-live-db` — verdict: **CONFIRMED**

**Compose defaults pair the MOCK profile with the LIVE database (artha) and live Redis db0 — the stated mock/live isolation invariant is enforced only by ay.ps1, with zero detection if breached**

- **Evidence:** deploy/docker-compose.yml:185,214,338,381 default SPRING_PROFILES_ACTIVE to 'mock' while lines 220/341/385/420 default every datasource to ${ARTHA_DB_NAME:-artha} and lines 189/221/342/386/421 default Redis to ${ARTHA_REDIS_DB:-0} — i.e. the fallback combination is mock behaviour writing into the live database. The compose comment at lines 92-94 states the invariant: 'mock's synthetic candles/snapshots can never pollute live data'. CandleQueryService.java:65 stamps fetchSource='MOCK' when not on the live profile, and CandleRepository.java:24-31 UPSERT sets source=EXCLUDED.source plus high=GREATEST/low=LEAST, so mock rows both land in and merge into live rows. grep for 'artha_mock' across services/ returns zero hits — no service asserts profile↔DB pairing at boot; grep for source='MOCK' in market-data main shows no canary/integrity job that would ever detect mock rows in the live archive. CLAUDE.md itself documents the trigger ('raw compose leaves the vars unset → mock writes to artha').
- **Failure mode:** One `docker compose -f deploy/docker-compose.yml --env-file .env up` where .env carries SPRING_PROFILES_ACTIVE=mock but ay.ps1 didn't export ARTHA_DB_NAME (the exact documented foot-gun) boots the full mock stack against the live artha DB: synthetic MOCK candles/snapshots are written into the live candle archive and caggs. Worse, self-heal is impossible short of purgeSymbol — a later live KITE re-fetch of a polluted bucket merges via GREATEST(high)/LEAST(low), so synthetic highs/lows permanently survive in bars that backtests and the live signal engine read. Nothing ever notices: no boot guard, no canary, and provenance on collided rows was flipped to KITE by the next upsert.
- **Fix:** Two one-liners plus a guard: (1) change the compose datasource defaults to a value that fails fast (e.g. ${ARTHA_DB_NAME:?set via ay.ps1}) so raw compose without the var refuses to start rather than silently choosing live; (2) add a boot assertion in each service (or flyway-init) that profile=mock + dbName=artha (or profile=live + artha_mock) is fatal; (3) optionally a startup canary in market-data on the live profile: SELECT count(*) FROM candles WHERE source='MOCK' > 0 → alarm.

### [MEDIUM / bug] `job-pruner-fk-doc-drift` — *(no verify pass)*

**JobPruner's DELETE has no NOT EXISTS(backtest_runs) guard despite its Javadoc claiming one — the monthly prune will FK-abort wholesale once any run-bearing job ages past 180 days, so nothing is ever pruned**

- **Evidence:** JobRepository.java:242-251 — Javadoc says 'Phase 30 adds the NOT EXISTS backtest_runs guard so the research record is never pruned', but the SQL is exactly: DELETE FROM jobs WHERE status IN ('completed','failed','cancelled') AND finished_at < now() - make_interval(days => ?) — no guard. backtest/V003__runs_trades.sql:11 (backtest_runs.job_id REFERENCES jobs(id)), V004:7 (optimization_trials.sweep_job_id REFERENCES jobs(id)) and V002:11 (parent_job_id self-FK) are all ON DELETE NO ACTION. JobPruner.java:27-33 runs it monthly (cron 0 0 3 1 * *, RETENTION_DAYS=180) with no exception handling.
- **Failure mode:** First monthly run after ~Dec 2026 (jobs began June 2026 + 180d): the single DELETE hits an FK violation on the first completed job that has a backtest_runs row and the WHOLE statement aborts — including the failed/cancelled runless jobs that were legitimately prunable. Net effect: the prune never removes anything, the jobs table (each row carrying a full request JSONB) grows unbounded forever, and a scheduled-task error is logged monthly. No wrong trades, but the §6.5 hygiene mechanism is dead code and the Javadoc is actively misleading.
- **Fix:** Make the SQL match the doc: DELETE ... AND NOT EXISTS (SELECT 1 FROM backtest_runs r WHERE r.job_id = jobs.id) AND NOT EXISTS (SELECT 1 FROM optimization_trials t WHERE t.sweep_job_id = jobs.id) AND NOT EXISTS (SELECT 1 FROM jobs c WHERE c.parent_job_id = jobs.id). One statement, no migration needed.

### [MEDIUM / tech-debt] `candles-3m-cagg-lineage-drift` — *(no verify pass)*

**V019 creates the candles_3m cagg + an every-3-minute refresh policy that the codebase has since abandoned as OOM-prone — no later migration disables it, so a fresh rebuild resurrects a zombie refresh job the live DB evidently no longer runs**

- **Evidence:** marketdata/V019__candles_3m_cagg.sql:13-31 creates the candles_3m continuous aggregate AND add_continuous_aggregate_policy (schedule every 3 minutes, start_offset 1 day). No migration V020-V026 touches candles_3m (all read). The operative design abandoned it: CandleRepository.java:169-177 ('We do NOT create/refresh a candles_3m cagg because re-aggregating the stitched CONT / expired-contract 1m series (~106k contracts) OOM-crashed the live DB twice'), rangeFromAggregate's whitelist (line 146) excludes candles_3m, refreshDerivedAggregates (line 229) excludes it, and commit efb654f (#365) states 'A candles_3m cagg exists but is EMPTY + unwired'. An empty cagg despite V019's active 3-minute policy implies the policy was removed by hand on the live DB — a lineage↔live divergence the checksum-locked-migrations discipline exists to prevent.
- **Failure mode:** Any fresh rebuild (`ay reset-db`, disaster-recovery restore + re-migrate, CI parity of prod) re-creates the ACTIVE 3-minute refresh policy plus a real-time cagg whose invalidation tracking taxes every 1m insert — the exact machinery implicated in two live-DB OOM crashes — while nothing in the codebase reads candles_3m at all (3m is served by rangeRolledFromOneMinute). The rebuilt DB silently diverges from the hand-fixed live DB, and the divergence is invisible because the lineage itself contains no record of the fix.
- **Fix:** Add marketdata/V027 (suffix-versioned, per house rules): SELECT remove_continuous_aggregate_policy('candles_3m', if_exists => true); and DROP MATERIALIZED VIEW candles_3m (nothing reads it — CandleQueryService routes 3m to the 1m rollup). Needs an executeInTransaction=false .conf like V019's.

### [MEDIUM / data-integrity] `backup-follows-active-profile` — *(no verify pass)*

**The nightly backup sidecar dumps whichever database the last `up` selected, and the 3-slot global retention rotates live backups out while running the mock profile**

- **Evidence:** deploy/docker-compose.yml:153 sets db-backup's PGDATABASE to ${ARTHA_DB_NAME:-artha} at container-create time; deploy/backup/backup.sh:23 dumps only that one DB (DB="${PGDATABASE:-artha}"), and prune_global (backup.sh:68-86, GLOBAL_KEEP=3) keeps only the 3 newest stamp-dirs across manual/nightly/weekly WITHOUT distinguishing which database a dump contains. The compose comment at line 141 additionally still says 'pg_dump -Fc per schema' — stale since the #395 whole-db fix (backup.sh:10-18 documents why per-schema dumps silently lost all hypertable rows).
- **Failure mode:** Owner switches to the mock profile via `ay` (exports ARTHA_DB_NAME=artha_mock) and leaves the stack up across nightly 00:30 boundaries: night 1 replaces the newest live backup slot with an artha_mock dump; after 3 nights ALL retained backups are of the disposable mock DB and every live-artha dump has been rm -rf'd by prune_global. A subsequent live-DB failure (this DB has OOM-crashed twice already) then has zero usable backups — unrecoverable loss of the 'only irreplaceable dataset' (options_chain_snapshots forward capture).
- **Fix:** Two small changes to backup.sh: always dump the LIVE db (hardcode/env-pin DB=artha for the nightly cron regardless of active profile — mock is reproducible by design and doesn't need backups), and make prune_global scope retention per database name (include $DB in the stamp dir, prune within that prefix). Also fix the stale 'per schema' comment at docker-compose.yml:141.

### [MEDIUM / bug] `bucket-date-utc-trap-indexclose` — *(no verify pass)*

**EquityIndexContributionService.indexClose filters 1d candles with a bare `bucket::date <= ?` — UTC session cast shifts IST-midnight daily buckets one day back, letting today's in-progress close masquerade as 'on/before asOf'**

- **Evidence:** EquityIndexContributionService.java:135-141: SELECT close FROM candles WHERE interval='1d' AND tradingsymbol=? AND bucket::date <= ? ORDER BY bucket DESC LIMIT 1. Daily bars are IST-day-aligned (Kite native 1d bars carry 00:00+05:30; the 1d cagg buckets with time_bucket(...,'Asia/Kolkata'), V004:57), so IST day D stores bucket = D-1T18:30Z, and in the container's UTC session bucket::date = D-1. Every other date comparison in the codebase converts first — e.g. FuturesSnapshotReader.java:205 and PaperPositionRepository.java:262 use (ts AT TIME ZONE 'Asia/Kolkata')::date — and CLAUDE.md explicitly bans bare ::date ('off-by-one across IST midnight'). This is the lone violator among the ::date usages in services/.
- **Failure mode:** asOf = max bhavcopy trade_date (yesterday, day D). Today's (D+1) accruing 1d bar has bucket::date = D, which passes '<= D'; ORDER BY bucket DESC LIMIT 1 then returns TODAY'S live in-progress index close as the 'latest close on/before asOf'. The index-contribution points columns (advPts/decPts, indexLevel) mix yesterday's constituent % changes with today's index level — quietly wrong analytics on the equity index-contribution page any time it is viewed intraday.
- **Fix:** Match the house pattern: AND (bucket AT TIME ZONE 'Asia/Kolkata')::date <= ? (or compare bucket < the IST-midnight timestamptz of asOf+1 computed in Java, which also stays index-friendly).

### [LOW / data-integrity] `backtest-runs-no-unique-jobid` — *(no verify pass)*

**backtest_runs has no UNIQUE(job_id) and run-insert / job-completion are separate commits — a crash between them plus boot requeue writes a second run for the same job**

- **Evidence:** backtest/V003__runs_trades.sql:73 creates only the non-unique idx_runs_job; V002__jobs.sql header (lines 5-6) promises idempotent claims + stale-running requeue, and JobRepository.java:148-150 requeues EVERY 'running' row on boot. WorkerPool.java:170-171 shows runner.run(job,...) (which persists the run inside) followed by repository.markCompleted(jobId) as separate statements — no shared transaction. Read paths partially tolerate duplicates: RunRepository.findRunIdByJobId (line 167) orders by completed_at DESC LIMIT 1, but findReturnsByJobIds (lines 148-161) does map.put per unordered row, so which duplicate's total_return shows in the jobs list is arbitrary.
- **Failure mode:** Container OOM/restart lands between the run INSERT and markCompleted: boot requeues the job, the worker replays it (deterministic, so metrics are near-identical), and the job now owns two backtest_runs rows with two full backtest_trades sets. resultRef resolution stays stable (latest completed_at), but the jobs-list returns column becomes row-order-dependent, run counts/leaderboards double-count the job, and storage holds a phantom duplicate research record.
- **Fix:** Add a suffix-versioned backtest migration: CREATE UNIQUE INDEX uq_runs_job ON backtest_runs (job_id) (current duplicates, if any, must be de-duped first), and have the worker INSERT ... ON CONFLICT (job_id) DO NOTHING or wrap run-insert + markCompleted in one transaction.

### [LOW / data-integrity] `candle-upsert-merge-asymmetry` — *(no verify pass)*

**Candle UPSERT merge (high=GREATEST, low=LEAST, source=last-writer) makes bad spikes uncorrectable by re-fetch and silently flips row provenance**

- **Evidence:** CandleRepository.java:24-31: ON CONFLICT ... DO UPDATE SET high=GREATEST(candles.high, EXCLUDED.high), low=LEAST(candles.low, EXCLUDED.low), source=EXCLUDED.source. The GREATEST/LEAST math is designed for replayed-tick idempotency (header comment lines 11-14), but the SAME statement is used by upsertAll for authoritative Kite historical fetches (CandleQueryService.java:137, 226). The only correction path is the corporate-action purgeSymbol (CandleRepository.java:266-269, gated to amendment A8).
- **Failure mode:** A bad tick (exchange glitch or the mock-pollution scenario above) writes an outlier high into a 1m bar; every later authoritative Kite re-fetch of that bucket is merged with GREATEST and can never lower it — the spike permanently poisons 5m/15m/1h/1d caggs, chart rendering, and any high/low-sensitive indicator (PSAR, breakout rails) in both live signals and backtests. Meanwhile source=EXCLUDED.source means a BACKFILL re-walk over a KITE row (or vice versa) rewrites provenance last-writer-wins, so the audit trail of which feed produced a bar is unreliable on any twice-written bucket.
- **Fix:** Give the authoritative-fetch path its own upsert that REPLACES o/h/l/c/volume outright (Kite historical is the source of truth for closed bars) and keep the GREATEST/LEAST merge only for the tick-agg live path; alternatively add an admin 'purge range' (not whole-symbol) so a known-bad bucket can be corrected without the corporate-action machinery.

### Unverifiable in this dimension

- Live-DB state of the candles_3m refresh policy: whether it was manually removed (SELECT * FROM timescaledb_information.jobs) or is still firing every 3 minutes against an empty-looking cagg — needs a query against the live artha DB, which this audit was instructed not to run. Commit efb654f's 'EMPTY + unwired' claim is the only evidence of divergence.
- Whether the live artha DB's flyway_schema_history checksums all match the working-tree files (i.e. no other hand-applied divergence) — needs SELECT from flyway_schema_history in all four lineage schemas.
- The V015 claim that signal_rejections is 'bounded volume (only chart-entry-fired bars)' — actual rows/day since #404 shipped (2026-07-01) would confirm or refute; needs a live row count. If chart entries fire on most 3m bars across 12 scalpers × 2 exchanges, the full-JSONB diagnostic column could grow faster than 'bounded' suggests, and the table has no retention.
- Whether any MOCK-source rows currently sit in the live artha candles table (the compose-defaults finding's damage assessment) — SELECT count(*) FROM candles WHERE source='MOCK' on the live DB would settle it.
- The DB session timezone inside the timescaledb container (assumed UTC per CLAUDE.md's documented trap and Postgres defaults) — SHOW timezone would confirm the bucket::date finding's direction; if the container were configured to Asia/Kolkata the indexClose defect would not manifest.

---

## 5. SECURITY

### [MEDIUM / bug] `ratelimiter-lost-expire-lockout` — *(no verify pass)*

**Non-atomic INCR/EXPIRE in LoginRateLimiter can permanently lock the sole owner out of login**

- **Evidence:** services/edge-gateway/src/main/java/in/arthayantra/gateway/auth/LoginRateLimiter.java lines 41-56: `redis.opsForValue().increment(attemptsKey)` and the TTL are two separate Redis commands. The `expire(attemptsKey, WINDOW)` only runs when `attempts == 1L` (line 47). If the process dies (OOM/`restart: unless-stopped`, mem_limit 384m) or the EXPIRE round-trip is dropped between the INCR and the EXPIRE, `login:attempts:<ip>` persists with NO TTL. Redis is configured `--maxmemory-policy volatile-lru` (docker-compose.yml line 79), which never evicts a key that has no TTL, so the counter grows unbounded forever.
- **Failure mode:** A crash/restart in the narrow window right after the first INCR leaves the per-IP attempts counter without an expiry. Every subsequent login then increments past 5 and immediately re-arms the 15-minute cooldown; after each cooldown lapses the very next attempt re-trips it. Because the gateway sees the client as 127.0.0.1 for all loopback/Tailscale-serve traffic (clientIp() reads the socket remote address, no X-Forwarded-For), this is a single global bucket — the one owner is locked out of their own trading dashboard indefinitely, self-healing only via a manual `redis-cli DEL login:attempts:127.0.0.1`.
- **Fix:** Make attempt-counting atomic: use a single Lua script (or `SET key 0 EX 60 NX` followed by INCR) so the window TTL is established in the same round-trip as the increment, guaranteeing every attempts key carries a TTL. Alternatively set the TTL unconditionally on every increment (idempotent `expire`).

### [LOW / security] `owner-hash-plain-env` — *(no verify pass)*

**Owner password hash passed as a plain env var, contradicting the compose file's own 'no secret in docker inspect' invariant**

- **Evidence:** deploy/docker-compose.yml line 186 sets `ARTHA_OWNER_PASSWORD_HASH: ${ARTHA_OWNER_PASSWORD_HASH:-}` as a plain environment variable on ay-edge-gateway, sourced from .env (confirmed present). This directly contradicts the file's stated design at lines 21-29 ('Kite material + AES-GCM master key as FILES, never env vars — docker inspect must show no secret values'), which is honoured for postgres_password, kite_api_key/secret, artha_master_key, openalgo_api_key, upstox_analytics_token (all file-mounted secrets). The gateway's sole credential material is the one secret NOT file-mounted.
- **Failure mode:** `docker inspect ay-edge-gateway` (or reading /proc/1/environ inside the container, or any process image dump) reveals the Argon2id PHC string. It is a hash not the plaintext, so it is not directly usable, but it enables an offline dictionary/brute-force attack against the owner password (Argon2id m=19456,t=2 is crackable for weak passwords) and it violates the invariant the rest of the secret-handling design depends on. Requires host/docker access, which the threat model treats as game-over — hence low, not a live breach.
- **Fix:** Mount the hash as a Docker file secret (e.g. `owner_password_hash` -> /run/secrets/owner_password_hash) and read it in SecurityConfig/OwnerAuthService the same way KiteHttpProperties reads its secret files, so `docker inspect` shows no credential material. This closes the one gap in the otherwise-consistent file-mount posture.

### [LOW / architecture] `pubsub-profile-unqualified` — *(no verify pass)*

**Redis pub/sub signal channels are profile-unqualified, so mock/live logical-DB isolation is not a boundary for the WS bridge**

- **Evidence:** services/strategy-signal-service/.../signals/SignalPublisher.java line 24 hardcodes `CHANNEL = "signals"` and publishes via `redis.convertAndSend(CHANNEL, ...)` (line 76). The gateway bridge subscribes by the same bare channel name (RedisTopicHub.messages(channel) -> ChannelTopic.of(ch)). Redis PUBLISH/SUBSCRIBE is global to the server and ignores the SELECTed logical DB, so the mock(db1)/live(db0) separation that isolates keys does NOT isolate pub/sub. Same pattern for StrategyChangedPublisher / SessionStatusPublisher channels.
- **Failure mode:** If a mock stack and a live stack ever shared one Redis server simultaneously, mock-generated synthetic signals published on `signals` would be delivered to the live gateway's WS bridge and pushed to the owner's live dashboard — an integrity/trust violation. In the standard single-`arthayantra`-project deploy this is not reachable because both stacks reuse identical container_names/ports and cannot coexist, so this is latent defense-in-depth debt, not an exploitable path today.
- **Fix:** Namespace the pub/sub channels by active profile (e.g. `signals:mock` / `signals:live`, derived from SPRING_PROFILES_ACTIVE) on both publisher and subscriber, so channel isolation matches the logical-DB isolation and the 'profile isolation is a security boundary' claim holds even under an accidental shared-Redis run.

### [LOW / security] `downstream-no-auth-devtools-bypass` — *(no verify pass)*

**Downstream services enforce no auth of their own; dev-tools socat publishes bypass the gateway to reach the SQL console + order endpoints unauthenticated**

- **Evidence:** Only edge-gateway has a SecurityConfig (grep for SecurityWebFilterChain/EnableWebSecurity across services returns exactly one file). libs/common-web/servlet/.../ArthaIdentityFilter.java lines 30-35 read `X-Artha-User` into MDC for logging only and never block a request lacking it. market-data (8081) and strategy-signal (8082) therefore trust network isolation alone; docker-compose.yml `mds-publish` (lines 519-535) and `sss-publish` (lines 537-553) forward those ports onto 127.0.0.1 under the `dev-tools` profile, and the AdminQueryController SQL console (/api/v1/market/admin/query) plus the orders/strategy surfaces sit behind no service-local auth.
- **Failure mode:** With the dev-tools profile active, any local process (or a browser hitting 127.0.0.1:8081 via a DNS-rebinding/SSRF style vector) can run the read-only SQL console, invalidate the Kite session, or drive strategy endpoints without a session or CSRF token — the gateway's deny-by-default does not apply on the direct ports. This is an accepted single-owner/loopback tradeoff and dev-tools is opt-in for the T2 inner loop, so blast radius is bounded; flagged because the 'deny-by-default everywhere' invariant is only actually enforced at the gateway. (Note: the SQL console runs as superuser `artha`, so a SELECT can also call `pg_read_file()` on the DB container under the READ ONLY txn — a capability slightly beyond 'inspect the candle store', though not an escalation for the owner who already holds those secrets.)
- **Fix:** Document explicitly that the dev-tools socat publishes expose unauthenticated admin surfaces and should never be enabled on a multi-tenant host; optionally bind a lightweight shared-secret header check (validated against a file secret) in ArthaIdentityFilter so the direct ports are not fully open even locally. No change needed to the gateway path.

### [LOW / security] `auth-session-preauth-profile-disclosure` — *(no verify pass)*

**/api/v1/auth/session discloses live-vs-mock profile to unauthenticated callers**

- **Evidence:** SecurityConfig.java line 77 lists `/api/v1/auth/session` in `.permitAll()`. AuthController.session() (lines 103-118) returns `{authenticated, loginTime, profile}` where `profile` is `activeMode()` = 'live' or 'mock' (lines 115, 168-175), with no authentication check. It also materializes a WebSession for any caller via `exchange.getSession()`.
- **Failure mode:** An unauthenticated loopback client can probe the endpoint and learn whether the running stack is trading live (real broker/orders) or mock — useful reconnaissance for choosing when to attempt other loopback attacks — and can force unbounded anonymous session creation. On a single-owner loopback deploy the practical exposure is minimal, hence low.
- **Fix:** Drop the `profile` field from the unauthenticated response (return it only once `authenticated` is true), or omit it entirely and let the SPA read profile from an authenticated status endpoint. Keep `authenticated` for the SPA boot probe.

### Unverifiable in this dimension

- Whether the owner's actual password behind ARTHA_OWNER_PASSWORD_HASH is strong enough to resist offline Argon2id cracking if the hash leaks via docker inspect — cannot verify without the .env value (correctly not read).
- Runtime behaviour of the Kite OAuth `/api/v1/auth/kite/callback` under SameSite=Strict: Kite's cross-site redirect would not carry the session cookie, so the authenticated `/api/**` matcher may 401 the callback. This is a functional/auth-interaction concern rather than a confidentiality gap; verifying it needs a live Kite login round-trip against the running stack (not run per instructions).
- Whether OpenAlgo's mounted deploy/openalgo/.env broker credentials are additionally protected at rest on the host filesystem — outside the code, not inspectable here.

---

## 6. PERFORMANCE & RELIABILITY (+ failure recovery)

### [HIGH / bug] `no-timeouts-single-eval-thread` — verdict: **CONFIRMED**

**Every intra-stack REST client has NO timeout, and they all run on the single signal-eval thread — one hung connection freezes all live signal evaluation (including stop-loss EXITs) indefinitely**

- **Evidence:** MarketOiClient.java:61 and MarketDataCandlesClient.java:35 build RestClient via builder.baseUrl(baseUrl).build() with no requestFactory/timeout; strategy-signal-service has zero timeout config anywhere (grep for Timeout/requestFactory in its main tree: no hits; pom has no httpclient5/jetty/reactor, so Boot 3.5.15 detects JdkClientHttpRequestFactory whose JDK HttpClient default is NO connect/read timeout). These clients are invoked on the single 'signal-eval' executor (SignalEngine.java:121-127): refreshFromRest at every coarse-primary boundary (SignalEngine.java:520-528), the confluence-gate fan-out (scalperEntry:500-503), the flip-exit re-read (confluenceFlipExit:1130-1133), and the synchronous AutoPaperListener→PaperSignalListener chain (@EventListener without @Async, AutoPaperListener.java:38, PaperSignalListener.java:39). The bar queue feeding this thread is an unbounded ConcurrentLinkedQueue (SignalEngine.java:116-118). backtest MarketDataClient.java:32 (bare RestClient.builder()) and edge-gateway (no spring.cloud.gateway httpclient response-timeout in application.yml) share the same gap.
- **Failure mode:** market-data-service is redeployed/restarted mid-session (a documented routine op), or a TCP connection blackholes (AV TLS interception stalls are in the machine history): the eval thread parks forever inside a read() with no timeout. All bar evaluation stops silently — pending 1m bars accumulate unbounded, structural-stop and ExitEvaluator checks for OPEN scalper positions never run again until the service is manually restarted. With auto-paper-trade ON this is an unmanaged open position; with execution=live armed it is an unmanaged live option position.
- **Fix:** Set bounded timeouts on the shared Boot builder once: spring.http.client.connect-timeout=2s / read-timeout=5s (Boot 3.5 property, applies to every auto-configured RestClient.Builder) in strategy-signal, backtest and market-data; add spring.cloud.gateway.httpclient.response-timeout (~30s) in edge-gateway. Optionally add a watchdog metric (seconds since last drained bar) alerting via the existing ntfy path.

### [HIGH / bug] `kite-rest-no-timeout-snapshot-stall` — verdict: **CONFIRMED**

**Live Kite REST clients (quotes/historical/session) have no timeouts; one hung call permanently stalls the fixedDelay OI-snapshot loop — the platform's irreplaceable forward OI capture silently stops**

- **Evidence:** LiveQuoteGateway.java:46 (builder.baseUrl(baseUrl).build(), no requestFactory) — grep for Timeout in marketdata/kite/live returns zero hits (LiveHistoricalCandleGateway, LiveSessionWireClient, LiveInstrumentDumpGateway same pattern), unlike every Upstox/NSE/BSE client which sets factory timeouts. OptionsSnapshotService.scheduledSnapshot is @Scheduled(fixedDelayString=...) (OptionsSnapshotService.java:85) — fixedDelay never re-fires until the previous invocation RETURNS. The resilience4j circuit breaker (application.yml:115-123) only counts completed failures, so a hang never trips it; the rate limiter's 5s acquire timeout bounds waiting for a permit, not the HTTP call itself. FuturesOiSnapshotService.java:64-67 (60s fixedDelay) and scheduledBroadcast (30s) have the same shape. No custom HealthIndicator exists anywhere (grep: zero hits), and the compose healthcheck is a plain actuator HTTP grep (docker-compose.yml:250-254).
- **Failure mode:** One blackholed HTTPS call to api.kite.trade during market hours (TLS-intercepting AV stall, half-open socket after a network blip) → that scheduled method's virtual thread blocks forever → options_chain_snapshots capture stops for the rest of the day (and every following day until someone restarts the container). Historical intraday OI cannot be re-fetched later — this is permanent loss of the exact data the whole OI-scalping program depends on — while the container stays 'healthy' and nothing alerts.
- **Fix:** Add a SimpleClientHttpRequestFactory (or ClientHttpRequestFactorySettings) with connect ~3s / read ~10s to the kite/live RestClient constructions (same idiom the Upstox clients already use, e.g. UpstoxQuoteClient.java:38-41). Cheap belt-and-braces: a snapshot-age gauge + ntfy alert when the last successful snapshot pass is older than 5 minutes during market hours.

### [HIGH / data-integrity] `requeue-hijacks-optimizer-jobs` — verdict: **CONFIRMED**

**Backtest crash recovery ignores job kind: a backtest-service restart mid-sweep requeues the running OPTIMIZATION parent job and runs it as a plain backtest, corrupting the sweep's state**

- **Evidence:** JobRepository.requeueStaleRunning (JobRepository.java:148-151): UPDATE jobs SET status='queued' ... WHERE status='running' — no kind filter. findQueuedIds (154-158) — no kind filter. StreamBootstrap.run (StreamBootstrap.java:58-67) then dispatches EVERY queued id onto jobs.backtest (JobStreamDispatcher.java:24-26). WorkerPool.claim wins on any 'queued' row regardless of kind (JobRepository.java:95-103) and BacktestRunner.run has no kind guard (BacktestRunner.java:107-117) — the sweep echo carries strategyId/from/to (service.py _sweep_echo:309-317), so it runs as a real backtest and markCompleted()s the OPTIMIZATION parent. Meanwhile the optimizer's daemon sweep thread (service.py:117-133) keeps flipping the same row back to 'running' via an unguarded set_status (repos.py:50-59). Optimizer restart recovery is also absent: sweeps are in-memory daemon threads, so an optimizer restart strands the parent at 'running' forever until this backtest-restart path 'completes' it spuriously.
- **Failure mode:** Owner rebuilds/redeploys backtest-service (routine single-service redeploy) while a 200-trial sweep is running: the parent job status flip-flops queued→running(spurious backtest)→completed while trials are still landing; a worker slot burns a full-window replay nobody asked for; the jobs UI/leaderboard shows a completed sweep whose trials are half-missing — a corrupted research artifact the owner may tune from.
- **Fix:** Add kind filters: requeueStaleRunning → WHERE status='running' AND kind IN ('BACKTEST','TRIAL'); findQueuedIds → same predicate. Optionally guard BacktestRunner.run with an early return for kind==OPTIMIZATION. For the optimizer side, on FastAPI startup mark this service's own orphaned 'running' OPTIMIZATION rows failed (no thread can resume them).

### [MEDIUM / performance] `indicator-bank-rebuilt-per-bar` — *(no verify pass)*

**Live SignalEngine rebuilds IndicatorBank (cold ta4j cache) on every evaluated bar — the exact O(n²) pattern the golden runner fixed (D17); the D11 IndicatorValueCache lib exists but is wired into nothing; EngineSeries is never trimmed so cost and memory grow with uptime**

- **Evidence:** SignalEngine.evaluateAtBarClose calls IndicatorBank.build(...) per strategy per bar (SignalEngine.java:418-422); IndicatorBank.build creates fresh ta4j indicator instances each call (IndicatorBank.java:43-82) whose per-instance caches start empty, so valueAt(lastIndex) on recursive indicators (EMA/RSI/ADX/SUPERTREND, Ta4jIndicators.java:22-115) recomputes from bar 0 in DecimalNum precision-32 BigDecimal math. TickwiseGoldenRunner.java:116-121 documents this exact hazard: 'Rebuilding the bank per bar gave every tick a COLD ta4j cache, re-prefilling the whole history from scratch — O(n^2) over the run (D17)' — the backtest was fixed, the live path was not. The cache seam (libs/strategy-engine/.../cache/IndicatorValueCache.java, InMemoryIndicatorValueCache.java) is referenced only by its own lib test (grep: 3 files, none in services). EngineSeries has no maximumBarCount / trim (EngineSeries.java:55-82; repo-wide grep maximumBarCount: zero hits) and LiveSeriesStore warms 1m at 4 days ≈ ~1,500 bars then appends ~375 bars/session forever (LiveSeriesStore.java:88-97).
- **Failure mode:** With 12 published scalpers × ~6 indicators, each bucket-boundary evaluation recomputes ~6 indicators over 1,500+ bars of BigDecimal-32 math per strategy — tens of ms each, hundreds of ms per bar aggregate, ALL on the single eval thread that also does the HTTP fan-out. Cost grows linearly with engine uptime (4 weeks of uptime ≈ +7,500 1m bars → ~6× per-bar cost) and heap grows unbounded (ta4j bars + EngineCandles across every warmed series inside -Xmx448m, strategy-signal Dockerfile:16) — a slow drift toward missed-bar latency and eventual OOM, restart-cured and therefore easy to misattribute.
- **Fix:** Mirror the golden runner: keep one long-lived IndicatorBank per (strategy, instrument) in SignalEngine, invalidated only on reload/hot-swap, so the shared EngineSeries instances keep the ta4j caches warm (indicators are pure functions of (series, index), as the D17 comment states). Add a session-boundary trim (keep last N sessions) to EngineSeries or set barSeries maximumBarCount.

### [MEDIUM / performance] `oi-fanout-uncached` — *(no verify pass)*

**Confluence gate fires ~15-18 sequential uncached HTTP GETs per gated entry, per strategy, with intra-evaluation duplicate fetches — repeated every bar for flip-exit positions — all on the single eval thread against market-data's 5-conn pool**

- **Evidence:** Per evaluation: oi() = 5 GETs (spurt, futures/banks, active-strikes, trending, term-structure — MarketOiClient.java:271-341, 381-391), macro() = 9 GETs (iv-history, breadth, fii-dii/long-short, chain, index-contribution, active-strikes AGAIN, vix, global/dow, fii-dii/bias — MarketOiClient.java:394-493), plus the gate's own chain fetch (ScalperConfluenceGate.java:319), a possible second chain for a decoupled oi-index (:652), trend60mDir (:715) and openHighStats (:800). No caching anywhere in MarketOiClient/ScalperConfluenceGate (grep cache: comments only); market-data's Caffeine caches cover instrument lookups only (CacheConfig.java, InstrumentsController.java:118-130 — not the analytics endpoints). /options/active-strikes and /options/chain are each fetched twice within ONE evaluation. confluenceFlipExit re-runs the whole context per bar while a tagged position is open (SignalEngine.java:445-452, 1123-1137). Nothing shares results across the 12 strategies evaluating the same NIFTY-future bar.
- **Failure mode:** On a bar where several scalpers' chart gates pass simultaneously (correlated — same signal future), the eval thread issues 50-200 sequential GETs, each triggering uncached DB analytics queries on market-data's 5-connection Hikari pool (application.yml:26) that also serves the 60s snapshot writers and the UI; at 100-300ms per GET one bar's gate work spans 10-60s, delaying every queued bar (including EXIT evaluation for open positions) by that much.
- **Fix:** Add a bar-scoped memo (Caffeine, TTL = min(bar interval, 60s), key = endpoint+underlying+expiry) inside MarketOiClient.get(), shared across strategies and across the entry/flip-exit paths; deduplicate the two active-strikes and two/three chain reads within one evaluation by threading the already-fetched payloads through.

### [MEDIUM / architecture] `pel-drain-blocks-startup` — *(no verify pass)*

**StreamBootstrap drains the PEL by running full backtests inline and serially on the startup ApplicationRunner thread, before the worker pool starts — readiness (and the compose health gate) blocks for the whole recovery**

- **Evidence:** StreamBootstrap.drainPending (StreamBootstrap.java:93-125) calls workerPool.process(record) synchronously inside ApplicationRunner.run; requeueStaleRunning (line 58) has just flipped crashed 'running' jobs back to 'queued', so claim() wins and runClaimed executes the FULL replay (WorkerPool.java:146-183) one job at a time on this thread. workerPool.start() only happens afterwards (StreamBootstrap.java:71). backtest has health probes enabled (application.yml:49-52); the compose healthcheck greps '"status":"UP"' (docker-compose.yml:397-401) and optimizer-service gates on backtest being healthy (docker-compose.yml:430-431).
- **Failure mode:** backtest-service crashes mid-way through a heavy job (a months-long premium replay) or several parallel trials → on restart each orphaned PEL entry replays serially before the app reports ready; readinessState stays REFUSING_TRAFFIC so the container sits unhealthy for the full recovery (minutes to tens of minutes), a fresh `ay up --wait` times out, and optimizer never starts. New job submissions during the window queue but nothing consumes them (the pool hasn't started).
- **Fix:** Don't process inline: after requeueStaleRunning, simply XACK-and-drop the PEL entries — the same jobs are already re-queued and re-dispatched as fresh stream entries by findQueuedIds/dispatchBacktest two lines later, so inline processing is redundant — then start the pool. Bootstrap becomes O(PEL size) instead of O(sum of replay durations).

### [MEDIUM / architecture] `redis-48mb-shared-fragile` — *(no verify pass)*

**48mb volatile-lru Redis shared by live+mock: session keys ARE TTL'd (evictable — the compose comment's safety claim is wrong), job streams are never trimmed (non-evictable growth), and there is no volume/persistence — a recreate silently loses UI ticker subscriptions and sessions**

- **Evidence:** docker-compose.yml:69-89: redis 7.4, maxmemory 48mb, volatile-lru, NO volume mounted; comment claims 'TTLs only on cache keys keeps sessions/Streams safe'. But edge-gateway uses Spring Session Redis with timeout 12h (edge-gateway application.yml:21-24) — Spring Session sets EXPIRE on its keys, so under volatile-lru pressure session keys are precisely what gets evicted. Streams (jobs.backtest, jobs.backtest.trials, optimizations.results) are XADD'd (JobStreamDispatcher.java:25, streams.py:37) and XACK'd but never XTRIM'd/XDEL'd anywhere (repo-wide grep for trim/maxlen/xdel: zero stream hits) — acked entries persist forever with no TTL (non-evictable). ticks:last hash and ticks:last-at (RedisTickPublisher.java:48-50) also carry no TTL. On Redis recreate: pinned indices/futures re-pin (PinnedIndicesSubscriber/FuturesPinner) but owner-added chart subscriptions in marketdata:subscriptions are gone with nothing to re-seed them, and the owner is logged out mid-day.
- **Failure mode:** Slow-burn: months of sweep trials accumulate non-evictable stream bytes in the 48mb budget shared by BOTH profile DBs; as pressure rises volatile-lru evicts the owner's 12h session (surprise logout on the trading cockpit) and the options.chain cache; at hard maxmemory-full with nothing volatile left, writes ERROR — RedisTickPublisher.publish throws into the normalizer loop (FeedPipeline.java:124-126 catches and continues, but every tick's publish/last-tick write fails) → live candle publishing degrades. Separately, any redis container recreate silently kills live chart streaming for non-pinned symbols — a recurrence of the 2026-06-15 'chart not updating live' incident class.
- **Fix:** Three small changes: (1) XADD with approximate MAXLEN (~10k) or a nightly XTRIM for the three job streams; (2) mount a volume + appendonly yes for redis (or add a Postgres-backed re-seed of marketdata:subscriptions); (3) fix the compose comment, and if owner-session survival matters more than cache eviction, consider noeviction (TTLs already expire the caches) or a higher maxmemory.

### [MEDIUM / observability] `no-feed-liveness-watchdog` — *(no verify pass)*

**Feed liveness is checked nowhere automatically: gap backfill fires only on WS reconnect, the container healthcheck is HTTP-only, and no watchdog restarts a silently-frozen feed — mid-day tick death means no candles, hence no bar-driven exit evaluation**

- **Evidence:** scanForGaps runs only from onConnected (LiveTickerFeed.java:143-171) — a socket that freezes without a disconnect callback (half-open TCP) never triggers it, and instruments with an EMPTY lastSeen are skipped (:166 seen.isPresent() guard). The data for a watchdog exists (ticks:last-at, RedisTickPublisher.java:49-50; the B-13 status surface) but no code reads it to act: no custom HealthIndicator exists in any service (grep HealthIndicator in services main: zero hits), and the compose healthcheck for market-data is a plain actuator wget (docker-compose.yml:250-254). SessionHealthProbe (5min, SessionHealthProbe.java:36-39) probes the Kite SESSION, not tick recency.
- **Failure mode:** WS freezes at 11:00 without a clean disconnect (half-open connection through the AV proxy): container stays healthy, no reconnect event, no gap scan, no backfill. 1m candles stop → strategy-signal receives no bars → open scalper positions get no structural-stop/ExitEvaluator passes for the rest of the session. Detection is entirely manual (owner glancing at the B-13 status pill).
- **Fix:** A small @Scheduled(60s, live profile, market-hours-gated) watchdog in market-data: if ticks:last-at is older than ~3min while calendar.isOpen and the feed claims running, call FeedPipeline.restartFeed() (which already rebuilds the handle and triggers onConnected→scanForGaps→backfill) and POST the ops ntfy topic. Every building block already exists.

### [LOW / performance] `serialgc-hot-paths` — *(no verify pass)*

**-XX:+UseSerialGC on the tick-hot market-data and eval-hot strategy-signal JVMs pairs stop-the-world single-threaded collections with the per-bar cold-bank BigDecimal allocation storm**

- **Evidence:** market-data Dockerfile:33 and strategy-signal Dockerfile:16 both run 'java -Xmx448m -XX:+UseSerialGC'. The per-bar IndicatorBank rebuild (finding indicator-bank-rebuilt-per-bar) allocates O(series-length) DecimalNum-32 BigDecimals per indicator per bar, driving frequent young collections and periodic full STW pauses (typically 100ms-1s on a ~448m heap) that halt the tick-normalizer and signal-eval threads simultaneously.
- **Failure mode:** During a bar-boundary burst (12 banks rebuilt + gate fan-out), full GCs add repeated whole-JVM pauses to the already-serial eval path, stretching bar-to-signal latency; on market-data it adds jitter to tick→candle-close timing.
- **Fix:** Drop UseSerialGC on these two services (default G1 at this heap size costs a few MB of metadata for far better pause behavior); keep SerialGC only where latency is not tick-shaped (edge-gateway/backtest) if footprint matters. Re-measure container RSS against the 640m limits afterwards.

### [LOW / bug] `resubscribe-message-drop` — *(no verify pass)*

**Every reload/hot-swap rebuilds the Redis listener container stop-then-start, dropping any candle message published in the gap (pub/sub has no replay); 1m-primary series have no intra-day self-heal for a dropped bar**

- **Evidence:** resubscribe() (SignalEngine.java:317-349) does container.stop() → build fresh → fresh.start(); messages published between the two are lost. Reloads run mid-day on hot-swap (strategy.changed listener :336-344) and whenever the 20s reconciler sees drift (SignalEngine.java:1050-1057). The 1m series is appended only from the channel (onClosedBar → seriesStore.append, :388); refreshFromRest covers only non-1m caggs at boundaries (:520-528), so a dropped 1m bar is a permanent in-memory gap until restart.
- **Failure mode:** Owner publishes a strategy edit at 13:02:59; the rebuild window coincides with the 13:02 bar-close publish burst → one instrument's 1m bar is never appended; that bar's evaluation (possibly the EXIT touch of a stop level) is skipped and indicator indices shift by one bar for the rest of the session.
- **Fix:** Reuse one long-lived RedisMessageListenerContainer and diff-add/remove ChannelTopics instead of stop/rebuild; or after each resubscribe, refreshFromRest the 1m keys too (incremental from lastBarTime) to heal any gap.

### [LOW / bug] `interval-duration-silent-default` — *(no verify pass)*

**SignalEngine.intervalDuration silently maps any unrecognized primary timeframe (e.g. '1d' on a non-btst strategy) to 1 minute — full evaluation + REST refresh every 1m bar — where the golden runner throws instead**

- **Evidence:** SignalEngine.java:1191-1199: switch over 3m/5m/15m/1h with 'default -> Duration.ofMinutes(1)'. evaluateCoarsePrimary then treats every bar as a bucket boundary for such a strategy (Math.floorMod(epoch, 60)==0 always, :516-518) → seriesStore.refreshFromRest for the primary + every higher TF, plus a full IndicatorBank.build + evaluate, per 1m bar per instrument. The replay counterpart deliberately rejects unsupported primaries (TickwiseGoldenRunner behavior documented in CLAUDE.md, fixed additively for 3m in #228), and EngineSeries.intervalDuration itself throws for unknown intervals (EngineSeries.java:160-170) — the live engine is the lone lenient path.
- **Failure mode:** A published swing strategy with timeframes.primary '1d' and session.style != 'btst' turns into ~375 evaluations + 2-3 REST refreshes per minute per instrument all day (hundreds of pointless market-data hits/hour) and evaluates a daily strategy on every 1m bar — also a live-vs-replay behavioral divergence.
- **Fix:** Make the default branch throw (matching EngineSeries), or skip live evaluation for non-rollable primaries with a loud warning at load time in reload().

### [LOW / performance] `ws-passthrough-unbounded` — *(no verify pass)*

**Per-session WS passthrough queue is unbounded for non-conflated topics (candles.1m.*, options.chain, jobs.progress) — a stalled browser accumulates frames in the 256m-heap gateway**

- **Evidence:** StompWebSocketHandler.SessionState.passthrough is a ConcurrentLinkedQueue with no bound (StompWebSocketHandler.java:78); non-ticks channels enqueue every message (:171-174) and are drained only by the 20 Hz flusher into session.send (:198-224). options.chain payloads are full-chain JSON broadcast every 30s (OptionsSnapshotService.scheduledBroadcast:103-115). Gateway heap is -Xmx256m (edge-gateway Dockerfile:19), mem_limit 384m (docker-compose.yml:200).
- **Failure mode:** A backgrounded/sleeping browser tab holding options.chain + several candles.1m subscriptions stops reading; reactor backpressure stalls the outbound send while the Redis-side listeners keep appending — hours of full-chain JSON (~hundreds of KB/min) accumulate per session until the gateway GC-thrashes or OOMs, taking down the single front door for the whole UI+API.
- **Fix:** Cap passthrough per session (e.g. 1,000 frames; on overflow drop-oldest for options.chain — it is a snapshot — and close the session with a STOMP ERROR for genuinely every-message-matters topics), mirroring IngressQueue's drop-oldest philosophy.

### Unverifiable in this dimension

- Actual runtime throughput/latency numbers (ticks/s through FeedPipeline, per-GET latency of the OI analytics endpoints, per-bar eval wall time, GC pause lengths) — estimated from code structure only; verifying needs the live Prometheus metrics (ay_signal_eval_duration_seconds, ay_tick_publish_latency_seconds, ay_options_snapshot_duration_seconds), which I could not query (no docker/DB commands allowed).
- That Spring Boot 3.5.15 resolves JdkClientHttpRequestFactory (JDK HttpClient, infinite default timeouts) for the strategy-signal/backtest RestClients — inferred from the absence of httpclient5/jetty/reactor-netty in those poms (grep) plus Boot's documented detection order; a `mvnw dependency:tree` run would confirm no transitive HTTP client changes the pick. The absence of any timeout configuration in code and yml is directly verified either way.
- That the compose healthcheck actually reports non-UP during the StreamBootstrap PEL drain — the inline-serial-execution-before-workerPool.start() is verified from code; the readiness consequence rests on Boot's documented behavior that probes.enabled registers a readinessState contributor (REFUSING_TRAFFIC until ApplicationReadyEvent) in the root health endpoint. A live restart-during-a-long-job test would confirm end to end.
- Current Redis memory occupancy and eviction counters on the live box (how close the 48mb instance is to pressure; the byte size of the untrimmed job streams after weeks of sweeps) — needs redis-cli INFO memory / MEMORY USAGE on ay-redis, out of scope for this audit.
- Whether the Kite javakiteconnect WS reliably fires onDisconnected for half-open sockets through the AV TLS proxy (the premise of the no-watchdog finding's trigger) — the code-side facts (gap scan only on onConnected, no tick-age watchdog, HTTP-only healthcheck) are verified; the real-world frequency of silent socket freezes is an environmental unknown.

---

## 7. TESTING

### [HIGH / data-integrity] `e2e-global-setup-can-hit-live-db` — verdict: **PARTIAL**

**Root e2e harness boots/reuses compose against the LIVE artha/db0 — mock/live isolation is unenforced in the one place that writes test data**

- **Evidence:** e2e/global-setup.ts:16 builds COMPOSE = ['docker','compose','-f','deploy/docker-compose.yml','--env-file','.env'] and never sets ARTHA_DB_NAME/ARTHA_REDIS_DB; deploy/docker-compose.yml defaults them to the LIVE values (lines 220-221, 341-342, 385-386: `${ARTHA_DB_NAME:-artha}`, `${ARTHA_REDIS_DB:-0}`). global-setup.ts:24-30 writes a mock .env ONLY if none exists ('locally an existing .env wins'); lines 34-37 REUSE any already-healthy stack with zero check that it is the mock profile; line 40 `up -d --build` otherwise boots with the owner's existing .env. e2e/tests/helpers.ts:4-5 explicitly instructs 'Locally export E2E_OWNER_PASSWORD to match your own .env hash' — i.e. logging into the owner's real stack is the designed local flow. The specs MUTATE state: e2e/tests/strategy-editor.spec.ts:28-51 creates persisted strategy drafts; backtest-results.spec.ts submits backtest runs. Only ay.ps1:38-40 derives artha_mock/db1, and the harness bypasses it.
- **Failure mode:** Owner runs `npx playwright test` locally (the CLAUDE.md-documented flow) while the everyday LIVE stack is healthy: the suite logs into the live gateway and writes e2e strategy drafts, backtest jobs and sweep submissions into the live `artha` DB and Redis db0 — polluting the registry, jobs and signals tables the live scalper validation reads. If the stack is down, `up -d --build` boots whatever profile the owner's .env holds (possibly live, with real Kite credentials) and runs the mutation suite against it. This is the #2-ranked domain invariant (mock/live isolation) unenforced by the test harness itself.
- **Fix:** In global-setup: (1) pass `ARTHA_DB_NAME=artha_mock, ARTHA_REDIS_DB=1` in the execSync env for every compose invocation; (2) before reusing a healthy stack, hard-assert it is mock (e.g. `docker inspect ay-market-data-service` env contains SPRING_PROFILES_ACTIVE=mock) and abort with a clear message otherwise. ~15 lines, closes the whole class.
- **Verifier correction:** Core claim stands as written, with one evidence fix: backtest-results.spec.ts does not submit backtest runs (it is render-only). The actual mutating surface is publishE2eStrategy (called by signals.spec.ts, notifier.spec.ts, strategy-versions.spec.ts, sweep-explorer.spec.ts), which creates AND PUBLISHES the fire-every-bar e2e-live-momentum strategy — against a live DB the live engine loads it and (auto-paper-trade ON) pollutes live signals/paper data every bar — plus strategy-editor.spec.ts's persisted drafts and sweep-explorer.spec.ts's real POST /api/v1/optimizations/run. Additionally, the stack-down path with a mock .env boots MOCK services against live artha/db0 (compose defaults), writing mock candles/instrument tokens into live hypertables — the exact raw-compose trap CLAUDE.md warns about. Proposed fix is sound: pass ARTHA_DB_NAME=artha_mock/ARTHA_REDIS_DB=1 in the execSync env and hard-assert SPRING_PROFILES_ACTIVE=mock (docker inspect) before reusing a healthy stack.

### [HIGH / testing-gap] `no-live-vs-backtest-exit-equivalence-test` — verdict: **CONFIRMED**

**Zero tests assert live paper exits match backtest premium_pct exits — the divergence is total and the only assertion touching it is isNotNull()**

- **Evidence:** Backtest: PremiumExitEvaluator (services/backtest-service/src/main/java/.../options/PremiumExitEvaluator.java:6-15) evaluates premium_pct SL/TP/trailing/time-stop against the OPTION's own 1m premium series, pinned by OptionsPremiumGoldenTest. Live: SignalEngine.emitEntry (services/strategy-signal-service/.../signals/SignalEngine.java:699-700) sets entryPrice = the INDEX-FUTURE bar close and levelFromRules (lines 1147-1163) applies the premium_pct percentage TO THAT INDEX PRICE (a 20%-of-premium stop becomes entry−20%-of-index ≈ 5,000 NIFTY points — unreachable). Then PaperSignalListener.openSingle/openLeg (paper/PaperSignalListener.java:67-72, 89-94) pass NULL stopLoss/takeProfit, and PaperBracketEvaluator.evaluate (paper/PaperBracketEvaluator.java:38-40) skips any position with both null — so auto-papered positions (#367, toggle ON) have NO exit at all until the 15:45 mark-to-close (paper/PaperScheduler.java:59-65). The paper layer also never reads tradeable_tradingsymbol (grep 'tradeable' in paper/ = zero hits) despite SignalRepository.java:181's comment 'the option the seam picked to trade (the order/paper layer trades it)'. The ONLY test touching live stop values is SignalEngineIntegrationTest.java:181-182: `assertThat(row.stopLoss()).isNotNull()` — with a premium_pct 20/40 YAML (lines 116-117) whose computed level is semantically wrong, invisible to a null-check.
- **Failure mode:** The owner's stated methodology is 'tune on live' — ~1 month of auto-paper trades is the decision input for E9 band + keep/cut/tune. Every one of those trades exits at 15:45 mark-to-close (or expiry) regardless of the strategy's premium SL/TP, while the backtester/optimizer scores the SAME YAML with premium exits honored. Parameters tuned on either surface are calibrated against a different exit policy than the other executes; no CI test can go red on this because none compares the two paths, and the one integration assertion is value-blind.
- **Fix:** (1) Strengthen SignalEngineIntegrationTest to assert the stop VALUE (would immediately expose premium_pct-applied-to-index). (2) Add a cross-suite equivalence test: feed one scalper YAML + a synthetic premium path through PremiumExitEvaluator AND through the live chain (signal emit → auto-take → bracket evaluate with stubbed LastTickReader) and assert the same exit reason/bar. (3) Add a PaperSignalListener test asserting a taken signal's brackets are populated (currently the tests pin the null-bracket behavior as if intended).

### [MEDIUM / testing-gap] `frontend-e2e-and-axe-run-in-no-ci` — *(no verify pass)*

**frontend-react/e2e (9 specs x 2 viewports + ALL axe a11y scans) runs in no CI workflow — the stated 'a11y gated by axe' invariant is unenforced**

- **Evidence:** .github/workflows/ci-react.yml:33-47 runs only npm ci/lint/test:ci/build/docker-build — no playwright step (header comment line 3 admits 'The Docker image + the e2e/axe shard land later'). .github/workflows/ci-e2e.yml:62-66 runs playwright only with working-directory: e2e (the root suite); its frontend-react step (line 41) only builds dist. Every axe scan in the repo lives in frontend-react/e2e (grep 'axe' → cockpit-nav, cockpit, data-ops, login, oi-expiry-strategy, oi-heatmap, open-high-strategy, options-chain, scalper-checklist .spec.ts); root e2e declares @axe-core/playwright in e2e/package.json:16 but zero specs import it. frontend-react/playwright.config.ts:30-39 defines desktop + 480px mobile projects. CLAUDE.md states 'a11y gated by axe + Playwright role/name'.
- **Failure mode:** The entire oipulse OI suite UI (heatmap, expiry strategy, open-high, options chain), the scalper cockpit + manual-verify checklist, the data-ops console, the S24-mobile viewport, and all accessibility gating can regress on any merged PR with no CI signal — these specs only run when someone remembers `npm run e2e` locally against a running stack. Root e2e covers a disjoint set (strategy editor, backtests, charts, signals, ws-reconnect), so this is not redundancy.
- **Fix:** Add a second job to ci-e2e (or a nightly shard) that reuses the already-booted mock stack: `cd frontend-react && npx playwright test` with CI=true. The stack boot is the expensive part and is already paid for in that workflow.

### [MEDIUM / testing-gap] `calendar-year-rollover-no-canary` — *(no verify pass)*

**market-calendar covers only 2026 and no test fails before the 2027 rollover bricks every live trading-day query**

- **Evidence:** libs/market-calendar/src/main/resources/nse-trading-holidays.csv contains only 2026 dates (last: 2026-12-25); MarketCalendar.requireCovered (libs/market-calendar/src/main/java/.../MarketCalendar.java:236-243) throws IllegalArgumentException for any uncovered year. The only related test asserts that 2027 THROWS (MarketCalendarTest.java:156-158) — it pins the fail-loud behavior but nothing asserts the bundle covers the horizon the live system needs. Refresh is 'yearly by PR' (CSV header comment), i.e. human memory.
- **Failure mode:** On 2027-01-01 (a Friday NSE trading day) every isTradingDay/session query in the live SignalEngine, the backtest regime pre-flight, and the expired-contract jobs starts throwing 'NSE holiday calendar covers years [2026]...' — total signal outage on the first trading day of the year, with zero CI warning beforehand because every test is green right up to the moment live breaks.
- **Fix:** Add a horizon canary test: assert the bundled calendar covers LocalDate.now() plus ~45 days (e.g. `assertThat(calendar.coveredYears()).contains(Year.now().getValue())` and, when today > Nov 15, also `contains(year+1)`). It goes red in nightly ci-e2e/next ci-java run in mid-November 2026 — weeks of notice instead of a New-Year outage.

### [MEDIUM / testing-gap] `migration-only-prs-skip-ci-java` — *(no verify pass)*

**A deploy/flyway-only PR never runs the Java ITs that actually consume the schema**

- **Evidence:** ci-java.yml path filters (lines 11-27) list services/**, libs/**, tools/**, pom.xml, .mvn/**, config/checkstyle/** — deploy/** is absent. ci-migrations.yml (lines 8-17) triggers on deploy/flyway/** but validates only lineage integrity (checksum drift + fresh-volume migrate + one grant assertion) — it never compiles or tests Java. Yet the Java ITs are exactly the schema/code coupling test: MarketDataIntegrationTestBase.java:46-49 (and the backtest/strategy bases) apply the REAL deploy/flyway lineages into Testcontainers and run repository SQL against them.
- **Failure mode:** A migration-only PR that renames/retypes a column, tightens a constraint, or alters a cagg used by JdbcTemplate SQL merges green (ci-migrations passes — the lineage is internally valid), then the schema/code mismatch surfaces as a red ci-java on the NEXT unrelated PR (misattributed) or as a live failure after `ay` redeploy runs flyway-init against running services.
- **Fix:** One line: add `deploy/flyway/**` to ci-java's push+pull_request paths. The IT shards already do the right thing once triggered; cost is one extra CI run on migration PRs.

### [MEDIUM / testing-gap] `gateway-allowlist-no-contract-crosscheck` — *(no verify pass)*

**Edge-gateway route allowlist vs controller paths has no test despite a recorded live failure of exactly this class**

- **Evidence:** services/edge-gateway/src/main/resources/application.yml:43-61 is a hand-maintained Path= prefix list per service, with a catch-all Path=/** (line 82) that serves SPA index.html for anything unmatched — so a missing prefix returns HTTP 200 HTML, not an error. Gateway tests are only ContractCaptureTest, GatewayIntegrationTest, auth/status/ws tests (services/edge-gateway/src/test/java/in/arthayantra/gateway/) — none cross-check the allowlist. The committed OpenAPI specs in contracts/ enumerate every /api/v1 path per service. Memory records the live incident: /api/v1/signal-rejections (#404) silently served index.html until the allowlist was hand-patched ('live-verify catches it, unit tests don't').
- **Failure mode:** Next new controller prefix (the repo adds one every few PRs) ships with all unit tests green; through the gateway the endpoint returns 200 + HTML, which JSON-parsing clients surface as confusing parse errors — found only by manual live verification, again.
- **Fix:** A pure-file unit test in edge-gateway: parse each contracts/<svc>.openapi.json's paths and assert every one is matched by that service's Path= prefixes from application.yml. No containers, ~50 lines, kills a recurring incident class.

### [MEDIUM / testing-gap] `optimizer-cancel-and-streams-untested` — *(no verify pass)*

**Optimizer cancellation path and the real Redis Streams transport have zero test coverage**

- **Evidence:** services/optimizer-service/app/service.py:295-304 (cancel: terminal-status guard, _cancelled set, jobs.set_status) — grep 'cancel' across services/optimizer-service/tests/ returns nothing; the mid-sweep abort check at service.py:161-162 is likewise unexercised. app/streams.py TrialDispatcher (xgroup_create/BUSYGROUP handling, XREADGROUP, per-entry XACK at lines 41-55) is fully replaced by FakeDispatcher (tests/fakes.py:83-105 — a plain in-memory list with none of the consumer-group semantics). Note read_results XACKs each entry BEFORE the ask/tell loop processes it (at-most-once delivery).
- **Failure mode:** A regression in cancel (e.g. cancelling a sweep no longer stops trial dispatch, or cancels a completed job) or in the transport (BUSYGROUP regression on restart, decode changes, the ack-before-process window dropping a trial result on crash so a sweep hangs at N-1 trials) ships through ci-optimizer green — the 75% cov gate passes with these paths dark.
- **Fix:** Add test_cancel.py driving OptimizerService.cancel through the FastAPI layer (queued→cancelled, terminal→409, mid-sweep dispatch stops), and a fakeredis-based TrialDispatcher test pinning group-create idempotency, field decoding, and ack behavior. Both are pure-Python, minutes of runtime.

### [MEDIUM / tech-debt] `map-return-contract-blindspot-42pct` — *(no verify pass)*

**69 of ~166 mapped endpoints return untyped Map<String,Object> — response-shape drift is invisible to ci-contracts and the TS client for ~42% of the API**

- **Evidence:** grep 'public Map<String, Object>' across services/*/src/main/java **/*Controller.java = 69 handler methods (market-data 16 files, strategy-signal 9, backtest 6) vs 166 total @*Mapping methods. CLAUDE.md itself codifies the loophole ('Generic Map<String,Object> returns are NOT enumerated, so adding response keys does NOT drift the spec') and memory records it being exploited twice ('both Map-return=no contract drift'). ci-contracts (.github/workflows/ci-contracts.yml:1-6) fails only on BREAKING spec diffs — a renamed/removed key inside a Map response produces NO diff at all.
- **Failure mode:** A backend rename of a response key consumed by frontend-react (e.g. inside the signals list envelope or an OI analytics payload) passes ci-contracts AND tsc --strict (the generated type is a free-form object), and surfaces only as an undefined-field rendering bug in the UI — the exact drift the contract pipeline exists to catch, structurally blind for 42% of endpoints.
- **Fix:** Don't boil the ocean: ratchet. Add a ContractCaptureTest lint counting Map-returning handlers per service and freeze the current number (fail on increase), then convert the highest-churn surfaces (signals, paper, backtest results) to records as they are next touched.

### [MEDIUM / testing-gap] `backup-restore-no-automated-roundtrip` — *(no verify pass)*

**ay backup/restore has no automated round-trip test despite a proven silent-data-loss incident in that exact code**

- **Evidence:** Backup/restore lives only in ay.ps1 (PowerShell); no Pester/test files exist anywhere (find *.Tests.ps1 → none), and no CI workflow exercises pg_dump/pg_restore. Memory (#395) records the incident class: per-schema `pg_dump -n marketdata` silently omitted all 224M candle rows because Timescale hypertable data lives in _timescaledb_internal — a bug only a row-count round-trip would catch, found live.
- **Failure mode:** The next change to the backup pipeline (flag tweak, Timescale version bump changing pre/post_restore semantics) can silently produce restores missing hypertable/compressed-chunk data again; discovery happens at the worst moment — during an actual disaster restore of the live capture DB (the un-recapturable OI history).
- **Fix:** Add a scheduled CI job (or an `ay verify-backup` command run after each backup): create a tiny Timescale DB with one hypertable + compressed chunk + a cagg, run the exact dump/restore commands from ay.ps1, and assert row counts and cagg rows match. The dump commands can be extracted into a .ps1/.sh shared by ay and the test to prevent drift.

### [LOW / tech-debt] `it-naming-silent-skip-unguarded` — *(no verify pass)*

**The *IT silent-skip trap is enforced by prose only — no failsafe plugin, no naming guard**

- **Evidence:** grep 'failsafe' across every pom.xml = zero matches; no maven-enforcer plugin exists either (grep 'enforcer' = zero). Today 0 test classes match *IT.java (find = empty; 282 of 293 test files match the surefire-picked *Test/*IntegrationTest patterns), so the trap is latent. The only guard is a CLAUDE.md bullet.
- **Failure mode:** A contributor (or code assistant) following standard Maven convention names a Testcontainers class FooIT.java; surefire never picks it up, `verify` is green, and the test provides zero protection forever — precisely the silent-skip CLAUDE.md warns about, with nothing mechanical behind the warning.
- **Fix:** Cheapest: a tiny root-pom enforcer rule or a unit test in each service's testsupport that scans src/test/java for files matching .*IT\.java / .*ITCase\.java and fails with a rename instruction. Five minutes, permanent.

### [LOW / testing-gap] `rerun-retries-plus-dirty-singleton-db` — *(no verify pass)*

**rerunFailingTestsCount=2 on a shared never-cleaned singleton DB both masks real races and wastes retries on non-idempotent tests**

- **Evidence:** ci-java.yml:84 passes -Dsurefire.rerunFailingTestsCount=2; the IT contract is explicitly no per-method cleanup with state persisting 'across surefire reruns' (CLAUDE.md; SignalEngineIntegrationTest.java:166-167 comments the shared-DB scoping workaround). Commit cee2319 ('make two ITs collision-proof against shared-DB cross-test leakage', #405) proves cross-test leakage recurs. Root e2e adds retries:1 in CI (e2e/playwright.config.ts:12) and frontend playwright retries:2.
- **Failure mode:** Two directions: (a) a genuine production race (awaitility timing, event-ordering) that fails ~30% of runs is retried green and never investigated — in a trading engine an intermittent test IS the bug report; (b) a test whose first failure already inserted its unique-slug rows can never pass its rerun (RegistryService 409s on duplicates), so the retry budget converts one flavor of flake into hard failures while hiding the other.
- **Fix:** Keep the retries but make them visible: surefire writes rerun results into the XML — add a CI step that greps for flaky-test markers (testcase with flakyFailure) and posts a warning annotation, so retried-green tests accumulate a paper trail instead of vanishing.

### [LOW / testing-gap] `ci-e2e-gaps-push-trigger-and-readiness` — *(no verify pass)*

**ci-e2e has no push-to-main trigger and its readiness gate omits the backtest/optimizer containers its own specs exercise**

- **Evidence:** ci-e2e.yml:6-11 triggers on pull_request + nightly cron + dispatch only — a merge-race (two individually-green PRs whose combination breaks a journey) or any direct-to-main push (enforce_admins is disabled per memory) reaches main untested until the nightly. e2e/global-setup.ts:105 health-gates only ay-strategy-signal-service and ay-market-data-service, while backtest-results.spec.ts and sweep-explorer.spec.ts drive backtest-service and optimizer-service — their cold-start 503s are absorbed by retries:1 (playwright.config.ts:12) or surface as flakes.
- **Failure mode:** Broken e2e journey sits on main for up to ~24h with auto-paper live trading running against that build locally; separately, sweep/backtest specs flake on the 2-core runner when those services are last to become healthy, burning CI iterations (a failure mode this repo has repeatedly paid for per the CI-hardening memory).
- **Fix:** Add `push: branches: [main]` to ci-e2e, and append ay-backtest-service + ay-optimizer-service to the container-health loop in global-setup (both already have compose healthchecks).

### [LOW / testing-gap] `frontend-vitest-no-coverage-floor` — *(no verify pass)*

**Frontend vitest has no coverage threshold — 34 of 75 pages have no spec and nothing prevents erosion**

- **Evidence:** frontend-react/vite.config.ts test block (lines ~48-54) configures environment/setup/include only — no coverage key, and ci-react.yml:39-40 runs plain `vitest run`. Inventory: 79 spec files, 41 of 75 *Page.tsx components covered; the pure-math core/ and api/ fold layers are well covered but pages like OrdersPage-adjacent flows, several OI pages, and all of src/pages/dataops render-level logic depend on the never-in-CI playwright specs (see frontend-e2e finding).
- **Failure mode:** UI regressions in the 34 uncovered pages (wrong column mapping, broken filter wiring — the class of bug the oipulse QA rounds kept finding) merge green; combined with the e2e gap, those pages have literally zero automated coverage of any kind.
- **Fix:** Enable vitest v8 coverage with a modest floor (e.g. 50% lines on src/core+src/api where it's already high, report-only elsewhere) so the well-tested calculation layer can't erode; rely on the e2e-in-CI fix for page-level protection rather than chasing RTL specs for all 34 pages.

### Unverifiable in this dimension

- Whether the owner's current local deploy/.env carries SPRING_PROFILES_ACTIVE=live (runtime state, not in git) — the e2e-live-DB finding's worst path is conditional on it; the reuse-healthy-stack path is unconditional either way. Evidence needed: contents of deploy/.env on the host.
- Actual current JaCoCo/pytest coverage percentages per module (would require running the builds, which I did not do; only the configured gates were verified).
- Whether any live paper_positions rows were actually opened on the index-future symbol with NULL brackets during the ~1mo auto-paper run — verifying the blast radius of the dead-stops gap needs a live-DB query (docker/DB commands were out of scope). Evidence needed: SELECT tradingsymbol, stop_loss, take_profit, close_reason FROM strategy.paper_positions for the auto-paper window.
- Current green/red status of the nightly ci-e2e schedule and whether frontend-react/e2e passes today when run manually (needs GitHub Actions API / a stack run).
- Exact springdoc rendering of the 69 Map-returning endpoints in the committed specs (spot-checked the mechanism and CLAUDE.md's own admission, did not diff all 69 against contracts/*.openapi.json).

---

## 8. DEVOPS & MAINTAINABILITY

### [HIGH / data-integrity] `backup-rotation-mock-evicts-live` — verdict: **CONFIRMED**

**Backup sidecar dumps only the active profile's DB, and global rotation (KEEP=3) lets mock-mode nightlies evict every live dump**

- **Evidence:** deploy/docker-compose.yml:153 sets db-backup PGDATABASE: ${ARTHA_DB_NAME:-artha} — the sidecar dumps ONLY the active profile's database. deploy/backup/backup.sh:23 (DB="${PGDATABASE:-artha}") and lines 68-86: prune_global sorts ALL stamp dirs across manual/nightly/weekly by basename timestamp and deletes the oldest beyond GLOBAL_KEEP=3, with zero regard for WHICH database the dump inside contains. On-disk state confirms the stakes: backups/ currently holds exactly ONE restorable live dump (manual/20260701-011737/artha-full.dump, 3.0 GB) — the two retained nightlies (20260630, 20260701) are pre-#395 per-schema dumps (marketdata.dump 25 MB, no globals.sql) that #395's own commit message declares lost all 224M hypertable rows.
- **Failure mode:** Owner switches to mock mode for 3+ days (a normal dev pattern — mock is the documented default). Each 00:30 nightly dumps artha_mock with a newer stamp; after the 3rd, prune_global deletes the only whole-db live artha dump. Simultaneously the live DB (irreplaceable forward-captured OI history, ~1 year of Upstox backfill) is not being backed up at all. A subsequent live-DB loss (the compressed hypertable already OOM-crashed twice per project memory) is unrecoverable.
- **Fix:** Two-line-scale change in backup.sh: (a) dump BOTH databases every run (loop `for db in artha artha_mock` or at minimum always dump artha), and (b) make prune per-database — e.g. only count/delete stamp dirs containing "${DB}-full.dump", or nest dest as $MODE/$DB/$STAMP. Also delete the two useless per-schema nightly dirs so they stop occupying 2 of the 3 retention slots.

### [HIGH / observability] `nightly-backup-no-deadman` — verdict: **CONFIRMED**

**Nightly backup silently skips whenever the stack is down at 00:30 IST — already happened today, and nothing can ever alert on a MISSED run**

- **Evidence:** docker-compose.yml:160-163: the schedule is crond INSIDE the db-backup container — it fires only if the stack is up at 00:30 IST. backup.sh:28-37: notify_failure POSTs ntfy only from WITHIN a running script; a stopped container/dead crond produces no signal. Verified on disk: backups/nightly/ has 20260630 and 20260701 but NO 20260702 dir, and at 03:33 IST on 2026-07-02 a read-only `docker ps` showed every container 'Up 27 minutes' — the stack was down at 00:30, the nightly was skipped, no alert fired, and crond has no catch-up (next attempt is tomorrow 00:30). Project memory says the owner runs the stack 'market hours', making overnight-down a recurring state.
- **Failure mode:** The owner believes the 'always-on nightly backup sidecar' (compose comment line 140) is protecting live data; in reality backups occur only on nights the stack happens to be up, and each miss is invisible. Weeks of missed nightlies + finding #1's rotation means the effective recovery point can silently age to a single manual dump.
- **Fix:** Make silence detectable: send an ntfy on SUCCESS too (so no-message-by-morning = failed/missed), or add a Windows Task Scheduler host job that checks backups/ for a dump newer than 24h and alerts. Alternatively move the schedule host-side (schtasks calling `ay backup`) so it runs whether or not the stack was up at 00:30.

### [HIGH / observability] `live-notifier-points-at-wiremock` — verdict: **CONFIRMED**

**Live-mode signal notifications currently POST to the WireMock stub (catch-all 200) — fail-open compose default, key absent from .env.example and from the owner's live .env**

- **Evidence:** docker-compose.yml:345-346: ARTHA_NOTIFIER_NTFY_URL defaults to http://wiremock:8080 and topic to ay-signals-mock with NO profile gating; wiremock itself has no profiles: key (lines 320-330) and is running in the live stack right now (docker ps shows ay-wiremock Up). The owner's actual .env (checked keys only) contains SPRING_PROFILES_ACTIVE=live and ARTHA_NTFY_TOPIC but NO ARTHA_NOTIFIER_NTFY_URL/TOPIC; .env.example never mentions them either (only the ops ARTHA_NTFY_TOPIC, line 44). NotifierClient.java:40-45: configured() checks only that the topic is non-blank — 'ay-signals-mock' passes — and send() (61-66) POSTs to wiremock, which returns 200, so the delivery audit records success.
- **Failure mode:** Any live push the notifier stack emits — per-strategy signal notifications, PaperExpiryService expiry warnings (PaperExpiryService.java:139), and the Z1 scalp alerts the moment the owner flips ARTHA_NOTIFIER_SCALP_ALERTS_ENABLED — is swallowed by WireMock with a 200 and audited as delivered. The owner, scalping on push alerts, misses live entries with zero error anywhere.
- **Fix:** Change the compose default to empty (ARTHA_NOTIFIER_NTFY_URL: ${ARTHA_NOTIFIER_NTFY_URL:-}) and have NotifierClient.configured() require a non-blank URL; set the wiremock values only in the mock path (or gate wiremock behind a profile). Add both keys to .env.example under the ops-alerts section.

### [HIGH / observability] `ops-ntfy-topic-never-reaches-market-data` — verdict: **PARTIAL** (severity corrected high → medium)

**All contract-canary and corporate-action ntfy alerts are dead: compose passes ARTHA_NTFY_TOPIC only to the db-backup sidecar, never to market-data-service**

- **Evidence:** docker-compose.yml:154 passes ARTHA_NTFY_TOPIC to db-backup only; the market-data-service environment block (213-240) has no ARTHA_NTFY_TOPIC/ARTHA_NTFY_URL, and application.yml sets no artha.ntfy.topic — so NtfyClient's @Value("${artha.ntfy.topic:}") (NtfyClient.java:27) resolves to "" in the container and send() returns immediately (lines 34-36). Yet ContractCanary.java:245, UpstoxContractCanary.java:332, OpenAlgoContractCanary.java:257 and CorporateActionJob.java:225/251/258 all rely on ntfy.send for their 'urgent' drift alerts, the owner's .env explicitly sets ARTHA_UPSTOX_CANARY_ENABLED (canary intended to be armed), and CLAUDE.md states drift 'is caught OFF the critical path by the daily ContractCanary'.
- **Failure mode:** Kite/Upstox/OpenAlgo renames or removes a consumed field: the canary detects it, calls ntfy.send, and the alert no-ops. The owner learns about wire drift only when the live feed misbehaves — exactly the failure mode the canaries were built to pre-empt. Corporate-action anomaly alerts (splits/bonuses affecting candle adjustment) are equally silent.
- **Fix:** Add ARTHA_NTFY_TOPIC: ${ARTHA_NTFY_TOPIC:-} (and ARTHA_NTFY_URL passthrough) to the market-data-service environment block in docker-compose.yml. Consider a boot-time WARN in NtfyClient when a canary is enabled but the topic is blank.
- **Verifier correction:** The compose passthrough gap is real and all market-data ntfy call sites (3 contract canaries + CorporateActionJob detect/resolve/FAILED) are structurally dead — but the owner's .env currently leaves ARTHA_NTFY_TOPIC blank, so no alert (including db-backup's) is armed today. The actual defect is a latent misconfiguration trap: setting ARTHA_NTFY_TOPIC in .env — the single documented arming knob — arms only the db-backup sidecar, while every market-data drift/corporate-action alert (a Stage-B B-14 design-stated "first-party ntfy critical" invariant) silently stays dead because docker-compose.yml never passes the var to market-data-service and its application.yml maps no artha.ntfy.* property. Drift remains partially observable (Redis result key, driftCounter metric, lastContractCheck in session status, ERROR logs on CA-rebuild failure), but as push alerting it is dead. Fix as proposed: add ARTHA_NTFY_TOPIC/ARTHA_NTFY_URL passthrough to the market-data-service environment block (same bug class as the documented ARTHA_MD_SOURCE_TICKER passthrough miss at compose lines 232-234), plus a boot-time WARN when a canary is enabled with a blank topic.

### [HIGH / data-integrity] `ay-sh-stale-mirror-breaks-isolation` — verdict: **CONFIRMED**

**ay.sh is a 19-day-stale mirror still documented in README: no profile-env derivation (mock writes into live artha) and a pre-#395 destructive restore hardcoded at the live DB**

- **Evidence:** git log: ay.sh last functionally touched 2026-06-12 (a00869f/9939e1f) — BEFORE mock/live DB isolation (#11, 2026-06-14) and the restore rewrite (#395, 2026-07-01); ay.ps1 got both. ay.sh:30-40 `up` never sets ARTHA_DB_NAME/ARTHA_REDIS_DB, so compose's fail-open defaults ${ARTHA_DB_NAME:-artha} / ${ARTHA_REDIS_DB:-0} (docker-compose.yml:220, 341, 385, 420, 189) apply — a mock-profile stack writes synthetic candles/signals into LIVE artha and Redis db0, the exact pollution the compose comment (lines 92-95) claims 'can never' happen. ay.sh:54-63 `restore` runs pg_restore --clean --if-exists into hardcoded 'artha' with no dropdb/createdb, no timescaledb_pre_restore/post_restore, and no globals — the method #395's commit message describes as silently broken for hypertables. README.md:45 still advertises it ('Linux/WSL2: ./ay.sh up') and README.md:61 documents `ay restore <file>`.
- **Failure mode:** Anyone following the README quickstart from WSL2/Git Bash (or an agent using the Bash tool, which CLAUDE.md notes is bash) runs ./ay.sh up with the default mock profile → mock ticks accrue into the live artha DB and Redis db0, corrupting the forward-captured dataset the whole research program depends on. ./ay.sh restore against the live DB executes a --clean restore without the Timescale dance — partial/corrupt restore of the only production database.
- **Fix:** Either delete ay.sh and change README line 45 to WSL-invoke ay.ps1 via pwsh, or port Set-ProfileEnv + the #395 restore verbatim. If kept, the cheapest hardening is also fixing the root enabler: change the compose defaults to fail CLOSED (${ARTHA_DB_NAME:?set via ay CLI} or default artha_mock) so an env-less invocation cannot touch live.

### [MEDIUM / data-integrity] `profile-detection-exact-string-match` — *(no verify pass)*

**ay.ps1 profile detection is an exact 'mock' string compare — any compound Spring profile routes mock beans at the LIVE database**

- **Evidence:** ay.ps1:37: `if ($activeProfile -eq 'mock')` → artha_mock/db1, ELSE artha/db0 (lines 39-41). SPRING_PROFILES_ACTIVE is passed through verbatim to every service (docker-compose.yml:185, 214, 338, 381). Spring treats the value as a comma-separated LIST, so 'mock,debug' activates all mock beans (mock feed, MockQuoteGateway) while ay.ps1 classifies it as live → ARTHA_DB_NAME=artha, ARTHA_REDIS_DB=0.
- **Failure mode:** Owner (or an agent) adds a second profile for a debugging session — SPRING_PROFILES_ACTIVE=mock,verbose — and the mock tick generator writes synthetic candles into the live artha DB on Redis db0. The banner 'profile=mock,verbose -> db=artha' does print (line 42), but nothing blocks it.
- **Fix:** Split on ',' and treat the list as mock if it CONTAINS 'mock'; additionally fail hard (exit 1) if the list contains both 'mock' and 'live' or an unrecognized token.

### [MEDIUM / data-integrity] `restore-verification-is-eyeball-only` — *(no verify pass)*

**ay restore never fails programmatically: pg_restore errors are tolerated, verification is a printed row-count table, and '[ay] restore complete' prints unconditionally**

- **Evidence:** ay.ps1:63-70: Invoke-ComposeAllowFail deliberately ignores exit codes; the actual pg_restore (line 201) and the row-count sanity query (lines 205-207) both run through it. Line 210 prints '[ay] restore complete' regardless of outcome, and line 209 restarts the full stack on top of whatever state resulted. The comment (58-62) says 'The restore is verified by a post-restore row count' — but the count is only PRINTED for the owner to eyeball; no threshold, no comparison to the dump, no abort.
- **Failure mode:** A truncated dump, out-of-disk mid-restore, or a Timescale version mismatch leaves candles at 0 rows; the script still prints 'restore complete' and boots the stack, which then serves an empty/partial live dataset. A scrolling wall of tolerated pg_restore error lines is easy to miss at 2 AM during an incident — the exact moment restores happen.
- **Fix:** After the sanity SELECT, capture the candle count and fail (skip the stack restart, exit non-zero) if it is 0 — a three-line guard that converts the eyeball check into a real gate.

### [MEDIUM / observability] `obs-profile-phantom-no-metrics-pipeline` — *(no verify pass)*

**The 'obs' profile is accepted by both CLIs and advertised in README (Prometheus/Grafana/Loki, +~550 MB) but contains ZERO services; nothing scrapes the exposed metrics, no log aggregation, no stall/staleness/disk alerts exist**

- **Evidence:** README.md:69 lists the obs tier with concrete contents and RAM; ay.ps1:144 and ay.sh:35 accept 'obs'; docker-compose.yml greps for 'obs' hit only the header comment (line 3) and the down/reset commands — no service carries profiles: [obs]. All four Java services ship micrometer-registry-prometheus (each pom.xml) and expose /actuator/prometheus (strategy-signal application.yml:92-96), but nothing consumes it. Logs are per-container json-file capped at 10m×3 (docker-compose.yml:11-15) — history beyond ~30 MB/service is gone. Alerting inventory (verified exhaustively): backup-script in-run failure + 3 contract canaries + CorporateActionJob — the latter four all dead per the ops-ntfy finding.
- **Failure mode:** The owner cannot notice: a silently stale Kite feed (WS connected, no ticks), a stalled SignalEngine, the 4 GB timescaledb container approaching its mem_limit before the third OOM crash, or the disk filling with 3 GB dumps. `ay up obs` succeeds silently, reinforcing the false belief an observability tier exists. Every past incident in project memory (ticker 403 freeze, backfill OOM ×2, missed nightly) is in exactly this blind spot.
- **Fix:** Smallest honest change: delete the obs row from README, reject 'obs' in both CLIs, and delete the compose header line. If observability is wanted, a single prometheus container + 4 scrape targets + 2 alerts (feed-staleness via a last-tick gauge, container memory) under profiles:[obs] would cover the worst blind spots.

### [MEDIUM / tech-debt] `no-rollback-path-dev-tag-plus-stale-artifact-trap` — *(no verify pass)*

**Deploy is always build-latest-on-host onto a single mutable :dev tag — no image versioning or rollback, compounded by Dockerfiles that COPY host-built artifacts (stale dist/JAR ships silently)**

- **Evidence:** Every app image is tagged :dev (docker-compose.yml:182, 211, 335, 378, 414, 450, 472); each rebuild overwrites it. services/edge-gateway/Dockerfile: 'COPY target/edge-gateway.jar' — the image build needs no compile, so whatever JAR sits in target/ (possibly from a previous branch) is what ships. frontend-react/Dockerfile: 'COPY dist /usr/share/nginx/html' — same trap, already documented as a bitten gotcha in CLAUDE.md ('the Dockerfile COPYs the HOST-built dist/'). No registry, no SHA tags, no compose image pinning.
- **Failure mode:** A bad deploy during market hours (e.g. a gate regression in strategy-signal) has no fast path back: rollback = git checkout old SHA + host mvn build + compose build + up, ~10+ minutes of full reactor build while live signals are wrong. Separately, `docker compose build` after forgetting `npm run build`/`mvnw package` silently redeploys LAST build's artifact labeled as the new fix — the staleness is invisible because the tag never changes.
- **Fix:** After each service build, additionally tag the image with the git short-SHA (docker tag arthayantra/x:dev arthayantra/x:<sha>) — one line in the deploy routine gives instant `compose up` rollback. For the stale-artifact trap, add a build-stamp check: bake the git SHA into the JAR/dist at build time and have the deploy script compare it to HEAD before `compose build`.

### [MEDIUM / tech-debt] `scalper-gate-monolith-mixed-rail-policies` — *(no verify pass)*

**ScalperConfluenceGate.evaluateWithDiagnostic is a single ~620-line method chaining ~30 rails whose fail-open vs fail-closed policy exists only in comments — structurally unenforced**

- **Evidence:** ScalperConfluenceGate.java: the class is 1052 LOC and evaluateWithDiagnostic spans lines 273-895 with no intermediate methods (verified via method-boundary grep — next member is compositeReason at 895). Per-rail null-data policy is comment-only and deliberately mixed: line 430 'Fail-closed on a null higher-TF read', 447 'an unknown/unwarmed direction (0) fail-OPENs', 454 'fail-OPEN on a', 581/664/679/684 'Fail-closed', 693 'the deliberate inverse of #5's fail-open'. Siblings repeat the pattern: ScalperGates 904 LOC, MarketOiClient 874 LOC (its per-read degradation policies at lines 40, 99, 124, 214, 269, 350). Nothing — no type, no enum, no test — forces a new rail to declare which policy it uses.
- **Failure mode:** The next rail added (this file gained E5-E9 rails across #274-#404) hand-codes its null-handling inside the 620-line method; picking fail-open where the pattern's data-dependency demanded fail-closed silently converts 'no data' into 'take the live trade'. Review can't catch it structurally because the policy convention lives in prose scattered across 900 lines.
- **Fix:** Without refactoring the working chain: introduce a tiny FailPolicy enum recorded per rail in the existing RailCheck/Diag machinery, and one unit test that enumerates every rail name with its declared policy (a table test) so a new rail without an explicit policy entry fails the build. Extracting the ~30 rails into small private methods (mechanical, parity-safe — no logic change) would follow.

### [LOW / tech-debt] `claude-md-calendar-claim-false-plus-2027-timebomb` — *(no verify pass)*

**CLAUDE.md claims market-calendar 'covers only the CURRENT year (2024/2025 windows 500)' but the CSV covers 2024-2026; meanwhile nothing trips before the real coverage cliff on 2027-01-01**

- **Evidence:** libs/market-calendar/src/main/resources/nse-trading-holidays.csv contains 14×2024, 14×2025, 16×2026 entries (counted); MarketCalendar.requireCovered (MarketCalendar.java:236-244) throws only for years OUTSIDE that set. CLAUDE.md (mock-stack backtest bullet) states 'libs/market-calendar covers only the CURRENT year, so a window outside it (2024/2025) 500s' — false since Phase 1 added 2024/25. Conversely the refresh process is 'yearly refresh by PR' (CSV header comment) with no tripwire: on 2027-01-01 requireCovered starts throwing for every 2027 query (regime pre-flight, expiry math) on the live stack.
- **Failure mode:** Agents following CLAUDE.md refuse valid 2024/2025 backtest windows (wasted work, wrong advice to the owner). Separately, on New Year's Day 2027 the live stack starts 500ing calendar-dependent paths with nobody having been reminded to refresh the CSV — a guaranteed future outage with a known date.
- **Fix:** Fix the CLAUDE.md sentence to name the actual covered set. Add a one-assert unit test: fail once today() is within ~60 days of Dec-31 of max(coveredYears) with message 'refresh nse-trading-holidays.csv (CD-2)' — turns the annual process into a CI tripwire.

### [LOW / tech-debt] `dead-seams-inventory` — *(no verify pass)*

**Dead code/seams: IndicatorValueCache never wired, candles_3m cagg permanently empty (V019), obsolete weekly/ backup tier, and the compose db-backup comment describes the pre-#395 behavior**

- **Evidence:** IndicatorValueCache + InMemoryIndicatorValueCache (libs/strategy-engine/.../cache/) are referenced ONLY by RegistryAndSeriesTest — no service injects them (repo-wide grep: 3 files, all in the lib). V019__candles_3m_cagg.sql created a cagg that CandleRepository.java:172-178 explicitly bypasses ('rangeRolledFromOneMinute... because re-aggregating... OOMs') — an empty schema object that can't be edited in place (checksum-locked). backups/weekly/ is empty and backup.sh:66-67 admits the tier 'is no longer written to'. docker-compose.yml:141-144 still says 'pg_dump -Fc per schema... Rotation: 14 nightly + 8 weekly' — both statements contradicted by the mounted backup.sh (whole-db, GLOBAL_KEEP=3).
- **Failure mode:** Each seam misleads the next reader: an agent 'optimizing' indicators wires the dead cache assuming it's live infrastructure; someone trusts the compose comment's '14 nightly' retention when reasoning about recovery points (real cap: 3); a migration author assumes candles_3m is populated like its siblings.
- **Fix:** Update the compose comment block (4 lines). Delete IndicatorValueCache + impl + its test section (or wire it — but it's been unwired since Stage C). Add a code comment in V-latest migration docs or drop candles_3m via a new suffix-versioned migration. Remove the weekly/ scan lines once the dir is confirmed empty.

### [LOW / tech-debt] `fe-generated-contracts-unused` — *(no verify pass)*

**Generated OpenAPI TypeScript contracts (contracts/gen/*.d.ts, all 5 services) are imported by ZERO frontend source files — every FE DTO is hand-written and can drift without a type error**

- **Evidence:** contracts/gen/ holds backtest-service.d.ts, edge-gateway.d.ts, market-data-service.d.ts, optimizer-service.d.ts, strategy-signal-service.d.ts (regenerated per the ContractCaptureTest flow). Grep across frontend-react/src for 'contracts/gen' or any import of those modules: zero hits; no tsconfig/vite path alias exists. The ~40 hand-written API modules (frontend-react/src/api/*.ts) each redeclare response shapes. ci-contracts runs tsc --strict over the GEN dir only — it validates the generated types compile, not that the app agrees with them.
- **Failure mode:** A backend response-shape change that DOES drift the spec (new query param, renamed field on an enumerated DTO) updates contracts/gen in CI, but the hand-written FE type keeps the old shape and the UI silently renders undefined — the class of bug the contracts pipeline was built to prevent. (Mitigation exists: the *Fold.spec.ts unit tests pin some shapes.)
- **Fix:** Lowest-cost: import the generated types in the highest-churn api modules (signals, backtests, oiAnalytics) as the declared response types (components['schemas'][...]) so tsc flags drift; full adoption can stay incremental.

### [LOW / tech-debt] `redis-stateless-across-recreate` — *(no verify pass)*

**Redis has no volume and no persistence flags — sessions, ticker SubscriptionRegistry, and Streams state vanish on every `ay down`/container recreate**

- **Evidence:** docker-compose.yml:69-89: the redis service mounts no volume and passes only maxmemory/eviction/pubsub args — no appendonly, and any implicit RDB snapshot lands in the container layer, discarded on `down` (ay.ps1:149-151 removes containers). Contrast: the SubscriptionRegistry was made 'Redis-persisted... survives restart' (live-mode-findings memory, fix for the chart-not-updating-live bug) — that survival holds only for service restarts, not stack recreates, which the ay down/up cycle (stack up 'market hours') performs routinely.
- **Failure mode:** After each morning `ay up`, non-pinned live chart symbol subscriptions from yesterday are gone until each chart page re-POSTs its sub — a quiet regression of the exact bug the registry was built to fix. Backtest jobs survive (Postgres spine is authoritative), so blast radius is bounded to subscriptions/sessions/dedupe keys.
- **Fix:** Either add a small named volume + --appendonly yes (one compose stanza), or document in the registry code that persistence spans service restarts only and rely on UI re-subscription — but pick one deliberately.

### Unverifiable in this dimension

- Whether the owner's ARTHA_NTFY_TOPIC value in .env points at a real, reachable ntfy topic (value present but redacted from this audit; no network test performed).
- Whether any strategy row in the live DB currently has notifications enabled / what the notification-audit table shows for recent sends — would require a DB query, which this audit was instructed not to run. The wiremock-swallow finding is verified at the config/topology level regardless.
- Whether a Windows Task Scheduler host job exists that compensates for missed in-container nightlies (host scheduled-task inspection was out of scope); the on-disk absence of a 20260702 nightly plus the stack's 03:06 IST start time is the direct evidence used.
- Whether older arthayantra/*:dev image layers are retained locally for accidental rollback (docker image history not inspected — only a single read-only `docker ps` was run, to confirm the stack-down-at-00:30 evidence and that ay-wiremock runs in the live stack).
- Whether backtest-service/edge-gateway intentionally lack a ModularityTest (found only in market-data + strategy-signal) or whether CLAUDE.md's 'Modulith verify runs in CI' overstates coverage — CI workflow parsing was not exhaustive.

---

## 9. Phase-1-only additional risks (not re-examined in Phase 2)

From the architecture pass; evidence is Phase-1 reader citations, NOT adversarially re-verified. Treat as
credible leads, confirm the cited lines before acting.

### CI / contracts
- **[MEDIUM] optimizer-service OpenAPI contract committed but never regenerated or diff-gated.**
  `contracts/optimizer-service.openapi.json` + `contracts/gen/optimizer-service.d.ts` exist, but
  `ci-contracts.yml` loops only over the 4 Java services; `ci-optimizer.yml` has no contract step; the Stage-D
  design doc claims the FastAPI dump is "committed + diff-gated" — false. margin-service has NO committed
  contract at all. A FastAPI route/model change drifts silently from the spec + TS types the frontend compiles
  against.
- **[LOW] `openapitools/openapi-diff:latest` unpinned** (`ci-contracts.yml:47`) — the only unpinned image in a
  repo that pins everything else; an upstream release can change BREAKING classification (or crash) with no repo
  change, unreproducibly.
- **[LOW] gitleaks binary curl'd from github.com releases at step 1 of every workflow** — a GitHub-release CDN
  outage fails all 7 pipelines at step zero. Cache it or use the official action.
- **[LOW] ci-java image-build matrix covers only edge-gateway + market-data** — strategy-signal/backtest
  Dockerfiles are CI-built only via ci-e2e's `compose up --build` (PR + nightly only; no push-to-main trigger).
- **[LOW] `tools/hash-password` is in the reactor but downstream of nothing** — no `-am` shard builds/tests it
  (currently no tests; latent new-module-no-CI class, same as the hand-enumerated shard-matrix rule).

### Optimizer / margin (Python) — input-validation & robustness lows
- **[MEDIUM] Sweep failures swallowed with zero diagnostics** — `service.py:152-153` blanket
  `except Exception: set_status('failed')`, no log.exception, no error column; a config typo is
  indistinguishable from an infra outage.
- **[MEDIUM] Request-vs-YAML precedence trap** — `walkForward`/`objective`/`maxTrials` come ONLY from the
  request; a YAML carrying `walk_forward` runs as a plain full-window sweep with empty OOS folds and the default
  in-sample sharpe objective (the exact documented overfit footgun), with no warning that YAML keys were ignored.
- **[LOW] Run request is an unvalidated dict** — `maxTrials:"abc"` → naked 500 outside the ApiError envelope;
  parameter entry without `path` → KeyError 500; maxTrials uncapped for random/tpe/nsga2 (only grid caps at 500).
- **[LOW] Unknown objective metric → every trial FAILs, sweep "completes" empty** — metric name never validated
  at submission; NaN objective → FAIL; owner sees a successful-looking sweep with no results and no error.
- **[LOW] TPE sweeps not reproducible under parallelism=4** despite the seeded sampler — study.tell order depends
  on Redis arrival order; grid/random are order-independent, tpe/nsga2 are not. Determinism caveat, not a bug.
- **[MEDIUM] margin-service correctness is VERIFY-PENDING** — CI golden is a synthetic .spn; real-broker parity
  test is owner-gated/skipped; the fetch URL itself is marked (VERIFY). Do not flip `artha.margin.span-enabled`
  before the parity check. (Mitigated: triple-dormant — flag off, advisory-only, flat-pct fallback.)
- **[LOW] No .spn staleness gate on sizing** — newest-by-mtime with no age check; `SizeResponse` omits `spnDate`
  (only /health shows it); `size()` takes lotSize from `legs[0]` only — mixed-lot baskets mis-rail.
- **[LOW, unverified] `StrategyClient.resolve` expects `body["version"]` top-level** — a shape mismatch is a
  KeyError → naked 500.

### libs
- **[MEDIUM] FeeConstants pinned 2026-06-13 with no drift detection** — Zerodha/NSE/SEBI/CBDT/stamp rates are a
  manual one-file edit; a statutory revision (STT changed once already in 2024) silently biases every backtest +
  paper P&L; for thin-edge scalps the fee error can flip a strategy verdict. No canary equivalent exists.
- **[LOW] Schema-vs-engine drift surface** — schema interval enum includes 1d/1w that only btst executes
  (non-btst 1d-primary fails at submission — the #228 class); indicator-name pattern accepts ANY uppercase token
  (validity enforced only by IndicatorRegistry at save/publish, schema enum advisory).
- **[LOW] Golden side-channel fields escape byte-parity by design** — a non-deterministic new side-channel field
  passes the golden byte comparison; parity for such fields rests on trade record-equality tests happening to
  exercise them (discipline-dependent, not structural).
- **[LOW] Calendar coverage inferred from holiday presence** — a partially-entered year (Jan-Jun rows only) reads
  as fully covered; missing H2 holidays become "trading days", corrupting expectedMinuteBuckets gap denominators.
  Integrity rests on PR review of the CSV.
- **[LOW] Black76.price/greeks accept degenerate inputs unguarded** (sigma<=0 → NaN price / greeks throw at
  BigDecimal.valueOf(NaN)) — Phase-2 checked the actual callers and found them guarded (IvSolver reason-coded
  nulls, chain greeks gated on solver sigma, SyntheticPremium validates) so no live path is exposed today; the
  lib-level contract gap remains for future callers.

### market-data
- **[MEDIUM] Dual symbol grammars coexist in the candles PK space** — expired-contract 1m rows keyed in OpenAlgo
  grammar, live rows in Kite grammar; resolution rides the expired_contracts side-table + reconcilers; a reader
  querying the wrong grammar silently gets an empty/partial series (the #214 silent-miss class at the symbol layer).
- **[LOW] OI-change continuity is in-process memory** — `OptionsSnapshotService.previousOi` is an in-memory map;
  any mid-session restart nulls one pass of oi_change across every leg in the permanent archive (false-flat
  bucket for downstream sentiment factors); the map also never evicts expired legs.
- **[LOW, unassessed] BhavcopyBackfillService 365-day anti-join re-scan cost** per nightly run on the compressed
  hypertable — not profiled; verify it is index-served.

### edge-gateway / observability
- **[LOW] SystemStatusController staleness + coverage** — market-data reads UP from a possibly-stale Redis key
  (no TTL/freshness bound checked gateway-side); `services[]` omits backtest/strategy-signal/optimizer entirely —
  the single-pane health view can read green during a partial outage.
- **[MEDIUM] Contract-canary blind spots** — no live token → silent skip with no missed-canary alarm; Kite probe
  scope is 4 REST endpoints (orders/options-chain un-probed); the WS binary tick protocol has NO canary; ntfy is
  the single alert channel (and currently dead per the main report §9.3/9.4).

### deploy / ops
- **[MEDIUM] `ay reset-db` blast radius** — `down -v` on the single shared volume destroys live + mock databases
  AND the openalgo-data volume (broker sessions/API keys) in one keystroke; recovery depends entirely on the
  (rotation-fragile) backups.
- **[MEDIUM] Kite broker credentials duplicated outside deploy/secrets/** — ay.ps1 seeds BROKER_API_KEY/SECRET
  into plaintext `deploy/openalgo/.env` (bind-mounted /app/.env). Gitignore chain verified sound today, but two
  handling regimes for the same live credentials is fragile; openalgo-data volume also unsplit across profiles.
- **[LOW] Owner PHC `$$`-escaping trap** — an unescaped `$` in the Argon2id hash interpolates to garbage → all
  logins 401 (documented in .env.example; availability trap, pairs with the plain-env-var finding).
- **[LOW] TimescaleDB 5432 always host-published** (default profile) + dev-tools adds unauth adminer/redisinsight
  + wiremock 9099 always published — accepted single-owner tradeoffs; stated trust boundary: host compromise =
  full platform compromise.

---

## 10. Cleared during audit (leads that did NOT survive)

For the record — do not re-raise these without new evidence:

- **Upstox quota fragmentation** — REFUTED: limits are per-API, not per-token (official docs + staff statement);
  expired-instruments endpoints disjoint from live chain/quote; live capture rides Kite today anyway.
- **MonteCarlo seed unused** — cleared: seed persisted, reused, drives a single Random in fixed order.
- **OiAttributionService offset-key trap** — cleared: converts to IST first, joins on HH:mm string labels.
- **WalkForward folds diverging from headline path** — cleared: FoldEvaluator routes through the same
  OptionsPremiumReplay with the same config.
- **libs/strategy-engine "no JaCoCo gate"** — wrong: it has a 70% BRANCH gate, stricter than the services.
- **Black76 degenerate inputs reachable live** — largely cleared: all current callers guard (lib contract gap
  noted above for future callers).
- **R__seed_sample_strategy rerun risk** — cleared: early-RETURN on existing slug, placeholder replacement off.
- **Symbol reconcilers duplicated** — cleared: one normalizeStrike, per-edge expiry parsing intentional.
- **Frontend orphan pages** — cleared: all pages resolve to routes.
- **candles_3m "lineage vs live drift"** — reframed by live check: policy ACTIVE + 42,816 rows; lineage and live
  MATCH; the stale claim is CLAUDE.md's.
- **CLAUDE.md "market-calendar covers only the CURRENT year"** — false: CSV covers 2024-2026.

