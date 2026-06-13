# Stage E — Manual Testing Guide (Frontend UX, Phases 35–41 + 40A/40B/40C)

Everything Stage E built, testable by hand on the **mock stack with zero Kite
credentials**. Stage E is the browser front-end on top of the existing backends, plus
one new backend deliverable (the Phase-40B indicator-series endpoint in
backtest-service) and the Phase-41 notifier module in strategy-signal-service. The
whole pass takes ~40–60 min in a browser at **`http://127.0.0.1:8080`** (the SPA is
served same-origin through the gateway — zero CORS).

> **Shell labels.** Fenced blocks tagged `powershell` run at the repo root
> `C:\Trading\ArthaYantra\artha-yantra-2`; `bash` blocks are Git-Bash/WSL (POSIX
> `curl`/`jq`/here-doc).

> **Machine notes (carried from Stage A–D):**
> - Maven builds use the cached wrapper + `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`
>   (the TLS-intercepting AV blocks the wrapper download).
> - Build services with the **full reactor + `-am`** — never a bare `-pl` on a leaf
>   (a stale `.m2` lib otherwise embeds in the fat JAR).
> - Run compose only via `.\ay.ps1` (it passes `--env-file .env`; a bare
>   `docker compose` blanks the owner hash → login 401).
> - Owner password in the mock setup is `MyPassword123` — substitute yours. `.env`
>   must have `SPRING_PROFILES_ACTIVE=mock`.
> - **Mock data is real-time + rolling** — candles accrue from boot. Never hardcode
>   dates; §0 derives a covered window and back-fills the daily `NIFTY 50` benchmark
>   the backtest regime pre-flight needs.

---

## 0. Build + bring-up + login (10 min)

Rebuild the two services Stage E touched (the indicator endpoint + the notifier) and
the SPA bundle; bring the mock stack up (it now also starts `ay-wiremock`, the ntfy
stub for the notifier):

```bash
MVN=$(ls ~/.m2/wrapper/dists/apache-maven-*/*/bin/mvn | head -1)
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl services/backtest-service,services/strategy-signal-service -am package -DskipTests
cd frontend-ui && npm ci && npm run build && cd ..
```

```powershell
# .env must be SPRING_PROFILES_ACTIVE=mock for this walk
.\ay.ps1 up
.\ay.ps1 status
```

**PASS when:** every container `(healthy)` — including `ay-wiremock` and
`ay-frontend-ui` — and `ay-flyway-init` `Exited (0)`. Confirm the new strategy
migration landed:

```bash
docker exec ay-timescaledb psql -U artha -d artha -c "\dt strategy.notification_events"
```

Open `http://127.0.0.1:8080`, log in with `MyPassword123`. **PASS:** the app shell
loads with the side-nav (Dashboard · Signals · Charts · Strategies · Backtests · Jobs ·
Watchlists · Settings), the TopBar shows a green **WS connected** pill and the IST
clock ticking, and a **MOCK MODE** tag.

### 0a. Back-fill the benchmark history (needed by §5–§6)

The backtest regime pre-flight needs ~272 daily `NSE:"NIFTY 50"` sessions. Derive a
recent covered window and warm the daily benchmark (cache-first GET; repeat with a
wide `from` so the daily cagg fills):

```bash
# login (cookie jar) + CSRF
curl -s -c /tmp/ay.jar -d "password=MyPassword123" http://127.0.0.1:8080/api/v1/auth/login -o /dev/null
XSRF=$(curl -s -b /tmp/ay.jar http://127.0.0.1:8080/api/v1/auth/session -c /tmp/ay.jar >/dev/null; \
  awk '/XSRF-TOKEN/{print $7}' /tmp/ay.jar)
# warm ~2y of NIFTY 50 1d (rolling mock — re-run until total >= 272)
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=NIFTY%2050&interval=1d&from=2024-01-01T00:00:00%2B05:30&to=2026-12-31T00:00:00%2B05:30&limit=5000" | jq '.total'
```

**PASS:** `total` ≥ 272 (re-run if low — the cagg materializes lazily).

---

## 1. Dashboard / jobs / watchlists / settings — Phase 35

Go to **Dashboard**.

1. **Market Overview** shows NIFTY 50 / BANK NIFTY / SENSEX / **INDIA VIX** cards.
   **PASS:** at least the NIFTY 50 card shows a live 2-dp price that **updates** every
   second with a ▲/▼ glyph (never colour-only) and a sparkline. (Cards for indices the
   mock feed doesn't tick show "—" — honest.)
2. **Jobs** widget: empty ("No jobs running") until §5.
3. **Kite Status** widget: the MOCK-MODE banner + a *Connect Kite* button.
4. **Watchlist** widget: "add some on the Watchlists page" until §1b.
5. Collapse a widget (chevron), **reload** the page → **PASS:** the collapse state
   persists (localStorage).
6. DevTools → Network: **PASS:** no repeating poll except one `GET /system/status`
   every 10 s; tick updates arrive over the `/ws` socket, not HTTP.

### 1b. Watchlists + screener

**Watchlists** page → *Create* a list → add `RELIANCE` via the autocomplete. **PASS:**
the row appears and its **Last** price pulses on each tick. Back on the Dashboard, the
Watchlist widget now shows it. **Screener** tab → preset `momentum` → *Run*. **PASS:** a
ranked result table renders.

### 1c. Settings

**Settings** page. **PASS:** the Kite section shows status (mock), the theme toggle
flips dark/light (persists on reload), *Sync instruments* runs, and the **Global Risk**
section shows the reserved-slot note (controls land in Stage F).

---

## 2. Charts — Phases 40 / 40C / 40A

Go to **Charts**.

1. **PASS:** a lightweight-charts candlestick chart renders for NIFTY 50 with a volume
   histogram and the TradingView attribution logo (Apache-2.0 posture).
2. **Toolbar:** change the interval to `5m` → chart reloads; **reload the page** →
   **PASS:** `5m` is still selected (chart-state persistence). Search an instrument →
   the chart switches symbol.
3. **Overlays:** *Add overlay* → `EMA` → **PASS:** an EMA line overlays the price; a
   sub-pane oscillator (e.g. `RSI`) renders in its own pane. Edit the period → the
   overlay re-fetches. (All overlay values come from `GET /api/v1/indicators/...` — no
   client-side indicator math.)
4. **Crosshair:** hover → **PASS:** the OHLCV + overlay-value legend updates.
5. **View as table:** toggle → **PASS:** an accessible OHLCV + overlay table renders
   (the sole accessible representation of the chart).
6. **Marks / deep-link (after §5):** from a backtest's Results → Trades, click a trade's
   **View on chart** → **PASS:** the chart centers on the trade and shows entry/exit
   arrow markers; clicking a marker navigates back to the run. From a **Signal**'s detail
   → **View on chart** → the chart opens on that symbol with the signal marked.

---

## 3. Strategy editor — Phase 36

**Strategies** page → *Create strategy*.

1. **PASS:** the Monaco YAML editor opens with a template; the CD-11 form pane shows the
   indicator weight/optional toggles, two-way synced with the YAML.
2. Break the YAML (delete `version`) → **PASS:** the validation panel shows the
   server-side error (debounced `POST /validate`); **Save is disabled**. Fix it → the
   panel shows "validation passed".
3. *Create draft* → **PASS:** it persists and the URL becomes `/strategies/{id}/edit`
   with a version tag.
4. *Quick backtest* → set a recent window → *Run* → **PASS:** the progress bar moves
   (over `/topic/jobs/...`) and headline metrics + an inline equity/drawdown curve render
   without leaving the editor. (Needs §0a benchmark history.)

---

## 4. Versions / diff / publish + stress advisory — Phase 37

From a strategy → *History* (`/strategies/:id/versions`).

1. **PASS:** the version timeline lists semver/status/checksum; the compare selects show
   a structured diff (`path: before → after`) above a Monaco side-by-side YAML diff.
2. *Publish…* → **PASS:** the dialog shows the hot-swap notice + the **stress advisory**
   ("Advisory only — publishing is never blocked"); *Publish* is enabled even with zero
   stress runs. Publishing succeeds.
3. *Run stress test* in the advisory → **PASS:** it runs on the suggested clean window
   and the guard-7 degradation badge renders from the result.
4. *Rollback* a version → confirm → **PASS:** a copy-forward draft is created.

---

## 5. Backtest runner / results / compare — Phase 38

**Backtests** (runner). Pick a published strategy, a recent window, *Run backtest*.

1. **PASS:** lands on **Jobs** with a live progress bar; on completion the job shows
   `completed`. Open the run (`/backtests/:id`).
2. **Overview:** **PASS:** the metric panel (return/CAGR/Sharpe/Sortino/maxDD/win-rate/…)
   renders, the equity + drawdown curves draw with the **benchmark buy-and-hold overlay**,
   and alpha/beta/IR/excess-CAGR columns appear (em-dash for pre-Phase-32A runs).
3. **Trades:** **PASS:** the virtualized trade table renders; selecting a trade shows its
   per-indicator contributions.
4. **Folds** (walk-forward run): **PASS:** the grouped train-vs-OOS bar chart + fold table
   (date ranges, degradation badge, regime chips) render.
5. **Monte Carlo:** **PASS:** the fan chart + drawdown histogram render (tab hidden for
   runs with no `montecarlo_summary`).
6. **Compare:** run a second backtest, open `/backtests/compare?ids=run1,run2` → **PASS:**
   the metric matrix (best-per-row highlighted), overlaid normalized equity curves, and a
   `dataHash` mismatch banner when the runs aren't like-for-like.

---

## 6. Sweep explorer / leaderboard — Phase 39

From the runner's **Sweep** tab, launch a sweep (method `tpe`, ~10–30 trials) → **Jobs**.
Click the OPTIMIZATION row's chart icon → `/optimizations/:sweepId`.

1. **PASS:** the explorer's trial scatter (best-so-far step line, pruned greyed),
   parameter parallel-coordinates (brush filters the trial table), and 2-param heatmap
   update **live** as trials complete; an `nsga2` sweep renders a **Pareto front**, never
   a single winner.
2. **Leaderboard:** **PASS:** plateau-adjusted sort by default (Raw one click away),
   params, objective; a trial's *Folds* drill-down shows the fold panel (regime chips +
   degradation badge); pruned/failed trials are **flagged, never hidden**.
3. *Promote* a trial → **PASS:** a param preview → confirmation → a new draft version
   appears in the strategy list.

---

## 7. Signal notifier — Phase 41

**Strategies** list → on a published strategy toggle **Notify** on, channel `NTFY`.

1. **PASS:** the toggle persists (PATCH; **no new version** is minted — verify the
   version checksum is unchanged in *History*).
2. Click the **bell** (test-send) → **PASS:** no error; WireMock recorded the POST:
   ```bash
   curl -s http://127.0.0.1:9099/__admin/requests | jq '[.requests[]|select(.request.method=="POST")]|length'
   ```
   returns ≥ 1.
3. Let the mock feed emit a live signal for an opted-in strategy → **PASS:** WireMock's
   POST count increases within a few seconds of emission (the in-process push). A chatty
   strategy is rate-limited (cooldown + hourly cap); ops alerts use a **distinct topic**.

---

## Cross-cutting checks (hold across the walk)

- **No `markForCheck`, zero polling** where a topic exists (the 10 s system-status
  fallback is the only poll). Virtualized tables keep ~30 DOM rows.
- **Prices are decimal strings** end-to-end (no `parseFloat` arithmetic); equity/drawdown
  curves use the **persisted** downsampled curve, never client-recomputed.
- **Bundle:** initial ≤ 500 KB gz (the build prints ~113 KB gz; no chart/editor lib in
  the initial chunk — Monaco only on the editor route, ECharts only on analytics chunks,
  lightweight-charts confined to the chart wrappers by the CI-enforced lint boundary).
- **Accessibility:** every route passes `@axe-core/playwright` in the E2E suite; bull/bear
  is never colour-only.

The Playwright suite under `e2e/tests/` is the automated companion to this guide — run
`cd e2e && npx playwright test` against the running mock stack.

---

## Restore note

If you backed up a live-mode `.env` to run this mock walk, restore it afterwards
(`cp .env.<bak> .env`) before returning to live mode.
