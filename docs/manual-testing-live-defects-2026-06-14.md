# Live-mode UI manual test — defect log (2026-06-14)

Tester: senior testing-architect pass. Stack: **live** profile (`SPRING_PROFILES_ACTIVE=live`,
DB `artha`, redis db0), all 10 containers healthy. Gateway same-origin `http://127.0.0.1:8080`.
Owner password `MyPassword123`. Markets **closed** → no tick data (`/market/ticks/latest` = `{}`);
historical candles fetched cache-first from Kite (session token valid until 2026-06-15 06:00).

Data state at start: live DB had only `NSE:RELIANCE 1m` (2026-05-15..06-12). NIFTY 50 / INDIA VIX /
NIFTY BANK 1d warmed from Kite during testing. `BSE:SENSEX` returns empty from Kite.

Severity key: **S1** blocker · **S2** major (feature broken) · **S3** minor (works, wrong/ugly) ·
**S4** cosmetic/a11y · **DATA** environment/data-availability, not a code bug.

---

## Dashboard (`/dashboard`)

### D1 — Market Overview cards stay "—" for un-cached indices until a full page reload (S2)
- **Repro:** cold cache (index daily not yet in store). Load `/dashboard`. Cards fetch
  `/api/v1/market/candles?...interval=1d&limit=60` + `/api/v1/market/ticks/latest`.
  NIFTY 50 / INDIA VIX (already cached) render value+sparkline; **NIFTY BANK shows "—"**.
- Waited 4 min — card stayed "—". After `Connect`/reload of `/dashboard`, NIFTY BANK rendered
  **56814.80** with sparkline (data was available all along — `GET candles NSE:NIFTY BANK` returns
  items, close 52609 etc.).
- **Expected:** card updates reactively when the async cache-first warm resolves (or the panel
  re-polls). **Actual:** first paint of an un-cached index sticks on "—"; only a manual reload fixes it.
- **Impact:** every first view of an index whose daily isn't cached shows blank — looks broken in live.

### D2 — SENSEX card permanently "—" — BSE cash not in instruments (DATA, root-caused)
- `GET candles exchange=BSE&tradingsymbol=SENSEX&interval=1d` → `{"items":[],"total":0,"stale":true}`.
- Root cause: `marketdata.instruments` holds only exchanges **NFO (43307), NSE (9851), BFO (4908)** —
  **no `BSE` cash rows at all**, so `BSE:SENSEX` is "unknown instrument" and can never warm.
  market-data log: `gap fetch failed for BSE:SENSEX 1d … unknown instrument BSE:SENSEX` (recurring).
- NSE:NIFTY 50 (256265), NSE:NIFTY BANK (260105), NSE:INDIA VIX (264969), NSE:RELIANCE (738561)
  ARE present (segment INDICES for the indices) and warm fine.
- **Action:** either include BSE cash in the instrument sync, or drop the SENSEX card / show an
  explicit "unavailable" state. Product decision.

### D3 — Watchlist dashboard widget flashes a large near-white box during load (S4)
- On every fresh `/dashboard` load the Watchlist panel renders as a big light-grey/white rectangle
  for ~1–4 s before resolving to "No watchlist instruments…". Jarring on the dark theme.
- DOM probe at that location shows correctly-themed `section.widget` (bg rgb(19,25,38)) and no
  white DOM element → the white is almost certainly a **lightweight-charts canvas painting its
  default white background** before teardown/empty-state. (Matches the known LWC zoneless caveat.)
- **Action:** set chart `layout.background` to the theme color (or render the empty-state before
  instantiating the chart). Re-check whether a populated watchlist renders the chart at all
  (canvas measured 0×0 in probes — possible 0-size LWC bug).

### D4 — Console error `Missing requestHandler or method: doValidation` (S3, localising)
- 4× `ERROR Error: Missing requestHandler or method: doValidation … provideMarkerData`
  (monaco-yaml LSP worker) in console. monaco worker registration failing — repo is supposed to
  use a textarea fallback. Need to find which page still instantiates monaco. (Seen with `/options`
  in the tab history; will localise.)

### D5 — Unnamed `<button>` on dashboard (S4, a11y, to confirm)
- `read_page` interactive listed `button [ref_20]` with no accessible name in the Kite Status area.
  Buttons need an aria-label / text. To confirm + locate.

---

## ⭐ D6 — APP-WIDE: every PrimeNG component renders in LIGHT theme on the dark shell (S2)
The single biggest issue. The app shell (sidebar, page bg, custom `var(--ay-*)` components) is
dark, but **every PrimeNG component — `p-table`, `p-select`, `p-inputtext`, `p-datepicker`,
buttons, dialogs — renders with PrimeNG's default LIGHT colour scheme** (white surfaces, slate
text). First seen on `/signals` (white filter dropdown + white symbol input + white table
header/body) but it is global.

**Evidence (runtime, `/signals`):**
- `.p-datatable-thead th`, `.p-select`, `.p-inputtext`, `input` all computed `background-color:
  rgb(255,255,255)`, text `rgb(51,65,85)` / placeholder `rgb(100,116,139)` (Aura LIGHT tokens).
- `<html class="">` (no `.ay-light`) → `SessionStore` theme = **dark**; `root.matches(':root:not(.ay-light)')` = **true**, so dark SHOULD apply.
- Active token `--p-content-background` = `#ffffff`, `--p-surface-0` = `#ffffff`, `--p-text-color` = `#334155` (all LIGHT) despite dark being selected. Adding/removing `.ay-light` changes nothing.

**Root cause:** the @primeuix-generated dark colour-scheme CSS is emitted under an **unresolved
nesting selector**. Enumerating every `--p-content-background` declaration:
| selector | value |
|---|---|
| `:root, :host` | `var(--p-surface-0)` (= #fff, light) |
| `:root:not(.ay-light)` | *(empty)* |
| **`& :root, & :host`** | `var(--p-surface-900)` (= #0f172a, **dark**) |

The dark value lives under `& :root, & :host` — a **literal, unresolved `&`** (top-level `& :root`
is invalid CSS and never matches). So the dark scheme is dead and PrimeNG falls back to the light
`:root` tokens. This happens because `darkModeSelector: ':root:not(.ay-light)'` (app.config.ts:44)
is itself `:root`-anchored and collides with @primeuix's own `:root/:host` colour-scheme wrapper,
producing the malformed `&`-prefixed rule.

**Note** automated gates missed it: dark-text-on-white passes WCAG contrast, and e2e asserts
behaviour not pixel colour — so axe/Playwright stayed green while the UI is visibly wrong.

**Fix direction (to implement + verify in fix phase):** make `darkModeSelector` a selector
@primeuix compiles cleanly — a plain class/attribute, NOT `:root`-anchored. e.g. invert to a
`.ay-dark` class added by `SessionStore` (dark = class present), `darkModeSelector: '.ay-dark'`,
and flip styles.scss `:root` ↔ `:root.ay-dark`. Then re-verify `--p-content-background` resolves
to `var(--p-surface-900)` and `th`/`input`/`select` backgrounds are dark.

**Blast radius:** all 12 routes (any p-table/p-select/p-inputtext/p-datepicker/dialog). The
dashboard "watchlist white box" (D3) is the same cause (p-table mounting white).

---

## Charts (`/charts`) — FUNCTIONAL PASS (chart canvas is correctly dark-themed)
Works well: instrument autocomplete (live search), interval toggles (1m/5m/15m/1h/1d/1w), candle +
volume render (NSE:RELIANCE 1m verified), EMA/SMA/RSI/VWAP/ADX overlays (EMA(20) verified with a
configurable period), "View as table" toggle (OHLCV+indicator table — a *custom* `<table>`, dark).
LWC canvas measured 1458×726 — autoSize works here (no 0-size bug). No console errors.

### D7 — Charts auto-fetches a bogus default contract on mount → repeated Kite 400 (S3, + circuit pressure)
- On every `/charts` mount (before the user picks anything; the symbol input is empty, placeholder
  `NFO:BSE26AUG3300CE`, interval defaults 15m), the page fires a historical gap-fetch for
  **`NFO:BSE26AUG3300CE` @ 15m**. Kite rejects it:
  `400 Bad Request "invalid interval for continuous data" (InputException)` — logged 5× per visit.
- Two problems: (a) the default chart symbol is a random BSE option contract — should be a sane
  default (e.g. NIFTY 50) or fetch nothing until a symbol is chosen; (b) market-data appears to send
  `continuous` for an NFO option at 15m, which Kite refuses — the request builder shouldn't.
- Side effect: these repeated 400s + closed-market quote failures **trip the Kite REST circuit
  breaker** (`KITE_CIRCUIT_OPEN` toast on `/options`), after which everything serves cached/stale.

### D-charts-2 — table timestamps look UTC while chart axis is IST (S3, verify)
- "View as table" rows start `2026-06-12 05:50:00`; the chart x-axis for the same data runs ~09:15–
  15:30 (IST market hours). Possible the table renders bucket time in UTC (or without the IST
  offset) while the chart renders IST. Needs a careful side-by-side check; if real, it's a
  display-tz inconsistency. (Also: table indicator column shows unrounded floats e.g. `1269.211…`.)

---

## Options (`/options`)
Chain is empty: header reads `spot — · PCR — · ATM — · 0 strikes`, body
"No chain — pick an underlying with a live (mock) options chain", IV-smile/OI/PCR/IV-history charts
empty. `options_chain_snapshots` = 0 rows, `iv_daily_summary` = 0 rows. With markets closed and no
snapshot job, an empty live chain is **expected** (chain is live-quote-driven, not historical).
Controls present + themed-white (D6): underlying `p-select` (NIFTY 50), expiry select (2026-06-16),
±strikes select; chips "IV rank: insufficient (0/60d)", "VIX 14.72 · 26%ile", "History".

### D8 — "(mock)" wording shown in LIVE mode (S4)
- Empty-state copy: "pick an underlying with a live **(mock)** options chain." The literal "(mock)"
  string ships in the live build. Either make it profile-aware or drop it.

### D9 — KITE_CIRCUIT_OPEN error toast on a normal page visit (S3)
- Visiting `/options` (markets closed) surfaces a red `KITE_CIRCUIT_OPEN — kite-rest circuit open;
  serving cached data` error toast. Graceful degradation is correct, but presenting it as an *error*
  toast on a routine visit is alarming; an info/"market closed" affordance would be better. The
  underlying trips are largely D7 + closed-market quote failures.

---

## Strategies (`/strategies`, `/strategies/:id/edit`) — FUNCTIONAL PASS
List (seed "Minimal EMA Crossover" draft), search, Status filter, Edit/History row actions all work.
Editor: metadata + indicator-weight inputs + **textarea YAML editor (dark, NO monaco — fallback in
use, no console errors here)**, version-bump dropdown, Save draft, Quick backtest. Live validation
verified: breaking the YAML flipped the badge valid→**invalid (red)**, left panel "YAML can't be
parsed", footer `✗ unparseable YAML: mapping values are not allowed here`; undo restored valid. 👍

### ⭐ D10 — "Quick backtest" panel never leaves "queued" though the job completes (S2)
- From the strategy editor → "Quick backtest" → set interval 1m, From 2026-05-15 / To 2026-06-14,
  capital 100,000 → "Run quick backtest".
- `POST /api/v1/backtests/run` → **202** (job accepted). Panel shows spinner + empty progress bar +
  text **"queued"**.
- Backend ran it fine: `backtest.jobs` went `running` → `progress 40` → **`completed, progress 100,
  error none, finished_at` set**, and `backtest.backtest_runs` got **1** row. ~30 s later the panel
  is **still "queued"** with the spinner going.
- Network from the panel: only the POST (202) + the global `GET /backtests/jobs?limit=50` list poll.
  **No `GET /backtests/jobs/{id}` and no per-job progress subscription** — the panel renders the
  202's initial state and never advances. So progress/% never moves, no success state, no link to
  results. A user would believe the run is stuck.
- **Fix direction:** after the 202, the panel must track the returned job id — subscribe to the
  `jobs.progress` WS topic (or poll `/backtests/jobs/{id}`) and render running %/completed/failed,
  then surface a "view results" link. Verify the progress bar fills and the terminal state shows.
- (Engine + main jobs path appear fine — see Jobs/Results section.)

---

### N1 — cold-boot instrument race (S4, self-healing, note only)
- Just after stack boot, candle requests log `gap fetch failed … unknown instrument NSE:NIFTY 50 /
  NSE:RELIANCE` etc. — fired before the 58k-row instrument sync finished. Resolves once sync
  completes; only a brief cold-start window. Low priority.

---

## Backtest engine + Jobs + Results — CORE PATH PASS ✅
Ran the seed strategy (NSE:RELIANCE 1m, 2026-05-15→06-14, ₹100k) via Quick backtest. Despite the
drawer bug (D10), the **engine ran end to end**: `backtest.jobs` → completed/100%, `backtest_runs`
got a full row (return -0.19%, Sharpe -74.05, maxDD 0.20%, 35 trades, win 8.5%, equity/drawdown/MC).
- **Jobs page** (`/backtests/jobs`): shows the job, BACKTEST, **completed, 100%** progress bar (dark
  custom table). Polls REST — reflects state correctly.
- **Results page** (`/backtests/:id`): renders fully — Overview metric cards (all 11 metrics,
  numbers consistent with DB), equity-curve **LWC chart correctly dark-themed** (ends 99808.21),
  `dataHash …· seed 0`; **Trades** tab (35 rows: #/Side/Entry/Exit/P&L/%/Reason); **Monte Carlo**
  tab (p5/p50/p95 equity paths + drawdown distribution bars + "risk of ruin 0.000000").
- `GET /backtests/jobs/{id}` correctly returns `resultRef` = run id; `GET /backtests/{run}/results`
  drives the page. Good.

### ⭐ D11 — Monte Carlo (echarts) charts render with a WHITE canvas background (S3)
- The two MC charts are **echarts** instances (`_echarts_instance_` ×2). Their DOM containers are
  transparent but the echarts canvas paints echarts' **default light theme** (white bg, light grid,
  dark labels) — jarring on the dark page. LWC charts on the same app are themed dark.
- **Narrowed:** the **Futures basis-history** chart renders correctly in dark, so it's NOT all
  echarts — the MC results charts specifically aren't passed the dark theme/`backgroundColor`. Fix
  the MC chart component(s): `backgroundColor:'transparent'` + dark `textStyle`/axis/`splitLine`
  (or apply the same theme the futures chart uses).

### D12 — Jobs: no "view results" action on a completed row (S3) — [Cancel part corrected]
- Correction after reading `jobs-page.ts`: the Cancel button **is** `[disabled]="!cancellable(status)"`
  and `cancellable` is only `queued|running` — so on a `completed` row it renders **disabled**, not
  active (my earlier "offered on completed" read was imprecise; it's a disabled button, not a
  functional bug). Optional UX: hide it entirely for terminal states.
- Real gap (enhancement): there is **no "view results"/open action** on a completed BACKTEST row, and
  the row isn't clickable — results are only reachable via the deep route `/backtests/{runId}`. Add a
  results link (needs the job's `resultRef`). Deferred (enhancement, not a regression).

### D13 — Trades tab "BUY" side badge is coloured red/pink (S4, nit)
- Long entries show a **red-ish** "BUY" chip. BUY/long is conventionally green/bullish; red reads as
  sell/loss. Consider bull-green for BUY, bear-red for SELL.

### ⭐ D14 — Sweep (optimizer) launch always fails: "missing required field: strategyVersion" (S2)
- `/backtests/run` (Sweep tab) → "Launch sweep" → red toast **"VALIDATION_FAILED — missing required
  field: strategyVersion"**. The optimizer cannot be launched from the UI at all.
- Root cause: `runner-page.ts launchSweep()` posts `{strategyId, from, to, …}` to
  `POST /api/v1/optimizations/run` **without `strategyVersion`**. The optimizer endpoint requires it,
  whereas `/backtests/run` (used by Run backtest + Quick backtest) treats `strategyVersion` as
  optional and resolves the active version server-side — hence backtests work but sweeps 422.
- **Fix options:** (a) frontend — resolve the selected strategy's active version id and include it
  in the `SweepRunRequest` (the `p-select` only binds `strategyId`; needs the version uuid); or
  (b) backend — make `/optimizations/run` resolve the active version from `strategyId` when
  `strategyVersion` is omitted, for parity with `/backtests/run` (cleaner, but a service rebuild).

### D10 addendum — WS job-progress topic likely the culprit
- `BacktestsStore` (onInit) subscribes to STOMP topic **`/topic/jobs/stream`** and updates progress
  in `applyProgress()`. The quick-backtest drawer relies on this. Since the drawer never left
  "queued" while the Jobs **list** (REST poll of `/backtests/jobs`) showed `completed/100%`, the
  `/topic/jobs/stream` frames are **not reaching the client** (or jobId mismatch). Header shows "WS
  connected", so the socket is up but this topic isn't delivering job frames. Confirm by watching WS
  frames during a run; fix the publisher/topic (or, as a fallback, have the drawer poll
  `/backtests/jobs/{id}` until terminal).

---

## Remaining pages — FUNCTIONAL PASS (theme D6 aside)

- **Watchlists** (`/watchlists`): create watchlist ✓ (created "QA Watchlist"), add instrument via
  autocomplete ✓ (NSE:RELIANCE; Last/Vol "—" — no ticks). **Screener** tab ✓: momentum preset →
  NSE:RELIANCE (Close 1296.40, Value 0.003794). Minors: name input not cleared after Create; no
  obvious remove-instrument/delete-watchlist affordance on the row; autocomplete panel stays open
  after pick.
- **Journal** (`/journal`): New entry dialog ✓ → created note + tags (qa/test), shows Linked "free",
  Created 2026-06-14. Filter-by-tag + "Linked to" present.
- **Paper** (`/paper`): Equity ₹1,000,000; risk controls (Kill switch, Max open, Daily loss,
  Starting capital/Set) — "Max open = 5" applied with no error; Open positions / Closed trades empty
  (no signals, markets closed); Realized-equity panel empty. All custom-dark (few PrimeNG bits).
- **Futures** (`/futures`): rich + correct — Front/Next/Far contracts (LTP, basis, annualized,
  days-to-expiry, OI), basis-history chart (renders dark ✓), calendar spread/rollover (CONTANGO),
  OI buildup tiles. "STALE" badge correct (closed market).
- **Settings** (`/settings`): Kite Connect (Connected YES, State CONNECTED, User **KM8033**, token
  valid until 2026-06-15 06:00, Ticker DISCONNECTED), Appearance (Theme dark, toggle works), Data
  sync (OK, last run 10:38), Global Risk → Paper page. All dark.
- **Auth** (`/login`): logout ✓; wrong pw → "Bad owner password" ✓; show-password eye ✓; correct pw
  → dashboard ✓. Minors: prior error message persists until next submit; empty-password submit not
  deeply tested.

### D15 — small unstyled white square on the dashboard Watchlist widget header (S4)
- A tiny white square sits at the right of the dashboard "WATCHLIST" panel header (the unnamed
  `button [ref_20]` from D5). Looks like an unstyled/unlabelled icon button (collapse/popout) — white
  on dark. Give it a theme background + an `aria-label`. (Same a11y bucket as D5.)

### D16 — dashboard Watchlist widget shows "No watchlist instruments" despite a populated watchlist (S3, verify)
- Created "QA Watchlist" with NSE:RELIANCE, but the dashboard Watchlist widget still reads "No
  watchlist instruments — add some on the Watchlists page." It may intentionally show only a
  designated default watchlist; if so, document/allow choosing one. If not, it's not reflecting
  created watchlists. Verify intent.

---

## Summary

| # | Sev | Area | Defect |
|---|-----|------|--------|
| **D6** | S2 | App-wide | PrimeNG components render LIGHT on the dark theme (dark color-scheme CSS dead — unresolved `& :root` selector from `:root`-anchored `darkModeSelector`) |
| **D10** | S2 | Backtest | Quick-backtest drawer stuck on "queued" (WS `/topic/jobs/stream` progress not delivered; never polls job) |
| **D14** | S2 | Backtest | Sweep/optimizer launch 422 "missing required field: strategyVersion" (runner omits it; `/optimizations/run` requires it) |
| **D1** | S2 | Dashboard | Market cards stuck "—" for un-cached indices until full reload (no reactive update after async warm) |
| D7 | S3 | Charts | Auto-fetches bogus default `NFO:BSE26AUG3300CE`@15m on mount → Kite 400s, trips circuit |
| D11 | S3 | Backtest | Monte Carlo echarts charts have white background on dark theme |
| D12 | S3 | Backtest | Jobs: "Cancel" shown on completed jobs; no "view results" action |
| D9 | S3 | Options | KITE_CIRCUIT_OPEN shown as an error toast on a routine visit |
| D2 | DATA | Dashboard | SENSEX card always "—" (no BSE cash in instruments) |
| D16 | S3 | Dashboard | Watchlist widget shows empty despite a populated watchlist (verify intent) |
| D-charts-2 | S3 | Charts | "View as table" times look UTC vs chart IST; unrounded indicator floats |
| D3 | S4 | Dashboard | Watchlist widget white flash on load (PrimeNG, =D6) |
| D4 | S3 | Strategies | monaco still bundled+instantiated → worker fails, main-thread fallback (UI-freeze risk); localise to compare/versions pages |
| D5/D15 | S4 | a11y | Unnamed buttons (Kite-status / watchlist-header icon buttons) |
| D8 | S4 | Options | "(mock)" wording in the LIVE build |
| D13 | S4 | Backtest | Trades "BUY" badge coloured red |
| N1 | S4 | Market data | Cold-boot "unknown instrument" race (self-heals) |

**Fix pass target (frontend, one rebuild):** D6 (theme) — the headline fix — plus quick wins D8,
D11, D12, D13, and D14 (frontend version resolution) where feasible. D10/D1 (WS + reactive polling)
and D2/D7 (market-data/Kite) are follow-ups.

---

## Fixes applied & re-tested (2026-06-14)

Frontend changes (one `npm run build` + `docker compose build/up frontend-ui`, live stack, only
`ay-frontend-ui` recreated):

| # | Fix | Files | Re-test result |
|---|-----|-------|----------------|
| **D6** | `darkModeSelector: ':root:not(.ay-light)'` → **`.ay-dark`**; `applyTheme` now toggles `.ay-dark` (dark) **and** `.ay-light` (light), mutually exclusive | `app/app.config.ts`, `app/core/session.store.ts` | ✅ **Fixed.** `--p-content-background` now `#18181b` (was `#fff`); th/select/input dark (`rgb(24,24,27)`/`rgb(9,9,11)`). Verified dark on signals, dashboard, options chain, trades, results. Light mode still consistent (`html.ay-light`, white surfaces). Theme toggle round-trips dark↔light cleanly. |
| **D3** | (same root cause as D6) | — | ✅ Dashboard Watchlist widget no longer flashes white — renders dark. |
| **D13** | trades "BUY" severity keyed to `'LONG'` only, but API returns `'BUY'` → always red. Now `=== 'LONG' \|\| === 'BUY'` → green | `app/pages/backtests/results-page.ts` | ✅ BUY badges now green. |
| **D8** | dropped misleading "(mock)" from the live options empty-state copy | `app/pages/options/options-page.ts` | ✅ Source updated (empty state not shown now — chain loads). |
| **D14** | optimizer `/optimizations/run` now resolves the **current** version from `strategyId` when `strategyVersion` is omitted (parity with `/backtests/run`); resolved version pinned into the sweep + trials. Added `StrategyClient.resolve`, dropped the hard `strategyVersion` requirement, fixed/added tests. | `optimizer-service app/strategy_client.py`, `app/service.py`, `tests/fakes.py`, `tests/test_api.py` | ✅ **Fixed.** Sweep now launches (no more 422) → OPTIMIZATION job `running` + TRIAL jobs dispatched to the backtest worker (verified the seed strategy after adding an `optimize` block). 50 pytest + ruff green; optimizer-service rebuilt + redeployed (live). |

Re-test notes:
- **D11** (MC echarts white) — **did NOT reproduce** on the rebuilt app; Monte Carlo equity + drawdown
  charts render correctly on dark. No echarts source bug existed (`ay-echart` already forces
  `backgroundColor:'transparent'`); the earlier white was a stale-cached-chunk artifact. No code change.
- **D9** (KITE_CIRCUIT_OPEN) / empty options chain — **transient**: the circuit recovered and the
  NIFTY chain now loads (spot 23622.90, PCR 1.7472, ATM 23600, 21 strikes, CE/PE LTP+OI populated).
- Build gates: `npm run lint` ✅, `npm run build` ✅, `npm run test:ci` ✅ (session.store spec asserting
  `.ay-light` toggling still passes — change is additive).

### Still open (not fixed this pass — follow-ups with clear directions above)
- **D10** (quick-backtest drawer stuck on "queued" — WS `/topic/jobs/stream` not delivering) — needs
  WS publisher/topic investigation or a poll fallback.
- **D17 (NEW, S2)** — optimizer **trials stall at 40%**. After D14, a 2-trial sweep on the seed
  strategy (RELIANCE 1m, +`optimize` block) launches and dispatches both trials to the backtest
  worker, but each trial sits at `progress=40` indefinitely (245 s+, vs the solo `/backtests/run`
  job that finishes ~87 s). Diagnosis: **not** data/Kite-bound — market-data shows zero fetch
  activity during the stall and the benchmark has 407 NIFTY-50 daily (≥272 for the regime
  preflight). The worker publishes 40% then never returns from `runner.run(...)`
  (`WorkerPool:170-172`) and emits no further logs; cancelling the sweep marks the OPTIMIZATION
  `cancelled` but the trial threads stay `running` (never reach a cancel checkpoint → a true stall).
  Likely a TRIAL-path issue (walk-forward fold mode and/or the §D.7 ask-tell emission) that the live
  UI never exercised before (D14 blocked all sweeps). **Next:** read `BacktestRunner` past the 40%
  publish (fold loop / `optimizations.results` emit), add progress granularity, repro in an IT. The
  2 stuck trial threads are inert (2 of 14 workers) and clear on the next `backtest-service` restart.
  - **D17 CORRECTED (downgraded S2 hang -> S3 slow + poor progress) after a thread dump + status
    recheck.** It is NOT a hang/deadlock: both trials eventually **completed** (`status=completed,
    progress=100`) after ~1336 s (~22 min) wall each; a `kill -3` thread dump showed all 14
    `bt-worker` threads idle (parking at `WorkerPool.loop:121`) with two of them having burned
    `cpu=322 s` and `cpu=378 s` — i.e. the trials were CPU/work-heavy, then finished. Both trial
    runs are **full-window** (`foldContext=false`, `fold_metrics` NULL) so walk-forward is NOT the
    cause either; the optimizer recorded trial 0's objective (`sharpe 0.89`) and trial 1's backtest
    finished (`sharpe 5.85`, 73 trades) — its ledger row only stayed `RUNNING` because the sweep was
    cancelled mid-flight. So the optimizer is **functionally correct**; the real issues are:
    (a) **per-trial latency** — ~22 min wall / ~350 s CPU on a 60-day 1m window vs the 30-day solo's
    ~87 s; a multi-trial sweep on minute data would take hours. The ~970 s of non-CPU wait per trial
    isn't pinned (candidates: the runner's default 60-day window vs the solo's 30, 2x parallel
    contention, or `warmSeries` HTTP waits). Needs single-variable controlled runs (~20 min each) to
    isolate. (b) **progress UX** — `progress` jumps 40 -> 80 -> 100 in coarse steps, so it sits at 40%
    for the whole replay and looks hung (same family as D10). Next: add intra-replay progress; isolate
    the latency with a controlled solo run on the same 60-day window + a known period.
  - **D17 ROOT-CAUSED + FIXED (2026-06-14) — replay was O(n²) in bars.** Controlled solo, pre-warmed
    runs of the seed strategy (RELIANCE 1m) isolated it: **30-day = 87 s; 60-day = 387 s** — i.e.
    **2× the bars → 4.5× wall / ~3.25× CPU**, super-linear (a year of 1m would be ~hours; sweeps
    impractical). Two mid-run `kill -3` thread dumps caught the worker **RUNNABLE on-CPU** (not
    blocked/IO/lock; zero market-data fetch) in
    `BigDecimal.divide → DecimalNum.plus → AbstractEMAIndicator.calculate →
    RecursiveCachedIndicator.prefillMissingValues → CachedBuffer.prefillUntil → IndicatorBank.valueAt
    → TickwiseGoldenRunner.run → ReplayEngine.replay`. **Root cause:** `TickwiseGoldenRunner.run`
    rebuilt the whole `IndicatorBank` (fresh ta4j indicators, COLD cache) **inside the per-bar loop**
    (1m path, also per-bucket/per-day paths), so bar *i* re-prefilled the entire 0..i history from
    scratch → Σi = n(n+1)/2. ta4j's DecimalNum-32 (deliberate for golden/live parity) is only the
    constant factor; the O(n²) was ours. **Fix:** hoist the bank build ONCE before the loop — the
    `provider` series are stable instances that grow via `append`, so the bound indicators stay warm
    (O(n)). Numerically identical (indicators are pure functions of series+index): `GoldenDeterminismTest`
    5/5 still byte-match the frozen vectors, full strategy-engine suite 94/94 green. The 2× parallel
    contention seen in the sweep was a *symptom* (two O(n²) replays sharing cores), not the cause; it
    disappears once each replay is O(n). **Measured after redeploy:** the same 60-day solo run dropped
    to **5.7 s replay wall (was 387 s — ~68× faster)** with **byte-identical results on real data**
    (69 trades; sharpe −152.160913, return −0.037042, drawdown 0.037042 all equal to the pre-fix run) —
    real-data parity on top of the frozen golden vectors. (b) coarse progress 40→80→100 remains a
    separate UX follow-up.
- **D1** (dashboard cards don't reactively update after async warm) — reactive/poll fix.
- **D2** (SENSEX / BSE cash not in instruments), **D7** (charts default placeholder fetch + Kite
  `continuous` interval) — market-data/Kite-side.
- **D4** (monaco still bundled → worker fallback), **D5/D15** (unnamed buttons a11y),
  **D12** (no results link on Jobs row), **D16** (dashboard watchlist widget shows empty),
  **D-charts-2** (table tz/rounding) — frontend follow-ups.

---

## Follow-up fixes — part 2 (2026-06-14, branch `fix/live-defects-followups`)

The remaining open defects above, fixed + re-tested live (3 services rebuilt + redeployed:
frontend-ui, backtest-service, optimizer-service; live profile, only the changed services recreated).

| # | Sev | Fix | Files | Re-test result |
|---|-----|-----|-------|----------------|
| **D10** | S2 | Job progress was published per-job on `jobs.<jobId>`, but the gateway folds every `/topic/jobs/*` subscription onto the SINGLE `jobs.progress` Redis channel (the design + all three frontend stores agree). Nothing published there → no frame ever reached the browser. Both publishers now emit on `jobs.progress` (each frame carries its `jobId`; the client fans out). No gateway/frontend change. | bt `ProgressPublisher`/`Streams.java`; opt `streams.py` | ✅ Quick-backtest drawer advances queued→completed(100%) + renders metrics (Sharpe −74.05, 35 trades). |
| **D17b** | S3 | Coarse 40→80→100 progress. Throttled intra-replay progress over the 40–80 band via no-op-default overloads on `TickwiseGoldenRunner.run` + `ReplayEngine.replay` (signal-gen→0–60%, fill loop→60–100%). | strategy-engine `TickwiseGoldenRunner`; bt `ReplayEngine`/`BacktestRunner` | ✅ `GoldenDeterminismTest` 5/5 byte-match (parity preserved). Post-D17 the replay is so fast (~3 s) the bar no longer visibly sticks. |
| **D1** | S2 | Market cards never re-fetched an empty (uncached) index. Now a BOUNDED re-poll (5×3 s) fills it in when the warm resolves; on exhaustion it settles. | `market-overview-widget.ts` | ✅ NIFTY/BANK/VIX render; an uncached index self-heals without a manual reload. |
| **D2** | DATA | `BSE:SENSEX` is not in the instrument universe (only NSE/NFO/BFO) and can't warm. The card now shows an explicit **"n/a"** once the warm retries exhaust, instead of a perpetual "—" (reversible — auto-corrects if BSE is ever synced). | `market-overview-widget.ts` | ✅ SENSEX shows "n/a". |
| **D4** | S3 | The `doValidation` console error was a STALE-CACHE artifact — source has no monaco import (textarea/LCS fallbacks since 92a4be4). Removed the dead `monaco-editor`/`monaco-yaml` deps. | `package.json` (+lock) | ✅ No `doValidation`/monaco error on the fresh build. |
| **D5** | S4 | Not a real defect — every dashboard button already has an accessible name (the `read_page` "unnamed ref_20" was the labelled "Connect Kite" button; a DOM scan found 0 unnamed buttons). | — | ✅ All 10 buttons named. |
| **D15** | S4 | **FIXED (done right).** PrimeIcons CSS was never bundled (no `primeicons` import) → all `pi-*` icons missing app-wide; icon-only buttons render blank ("white square"). Bundling `primeicons.css` makes them render BUT PrimeNG 21 does not `aria-hidden` button icons, so the PUA glyph (e.g. ``) leaks into every icon+label button's accessible name (`"Publish"`), breaking `getByRole({exact:true})` (e2e) and announcing junk to screen readers. Fix: bundle `primeicons.css` AND stamp `aria-hidden` on every button icon via the global PrimeNG PassThrough `pt.button.icon` hook (`ptm('icon')`) — icons are decorative; the label/`ariaLabel` carries the name. All `pi-*` icons are on `p-button`; other components use built-in SVG icons (no `::before` text, no leak). | `angular.json` (+primeicons style); `app.config.ts` (`pt.button.icon`) | ✅ Verified live: glyphs render; 7/7 button-icon spans carry `aria-hidden="true"`; "Create strategy" accessible name clean (no PUA); `getByRole({name,exact:true})` resolves uniquely; axe 0 violations. |
| **D12** | S3 | Completed BACKTEST rows had no results action. Added a "Results" button (**visible text label** + `pi-eye`) that lazily resolves the run id from the job detail (the LIST omits `resultRef`) and routes to `/backtests/{runId}`. The text label keeps it usable independent of D15. | `jobs-page.ts` | ✅ Button on every completed BACKTEST row → opens the run results page (verified routing to `/backtests/{runId}`). |
| **a11y** | — | Light-theme `--ay-bear` (`#b91c1c`) on the market-card surface dropped to 4.45:1 on a down-price (under WCAG 4.5:1); darkened to `#991b1b` (≥5.7:1). | `styles.scss` | ✅ Dashboard axe clean. |
| **D16** | S3 | Watchlist widget showed the alphabetically-first watchlist even when empty. Now picks the first watchlist that HAS instruments. | `watchlist-widget.ts` | ✅ Dashboard widget shows NSE:RELIANCE. |
| **D-charts-2** | S3 | "View as table" rendered the bucket time as UTC (chart axis is IST) + unrounded overlay floats. Table time now IST-shifted (matches the axis); overlay values rounded to the price precision. | `lwc-chart-binding.ts` | ✅ Verified vs real data: bucket `09:15:00+05:30` → table `09:15:00` (was `03:45:00`). |
| **D7** | S3 | Frontend default is already sane (`NSE:NIFTY 50`/`1d`); the bogus `NFO:BSE26AUG3300CE@15m` fetch was STALE localStorage, not a code default — fresh `/charts` fetches only NIFTY 50 (200, no 400). The backend `continuous=1`-for-options edge case is left unchanged (deliberate per B-18/B-19; not reproduced on clean state, Kite rule unconfirmed). | — (no code change) | ✅ Fresh `/charts` clean. |

Build gates: frontend `lint`/`test:ci` (121)/`build`; optimizer `pytest` (50) + `ruff`; maven compile +
`GoldenDeterminismTest` 5/5. Bonus: discovered + fixed PrimeIcons never loading (the real root cause
of D15, which would also have hidden D12's icon).
