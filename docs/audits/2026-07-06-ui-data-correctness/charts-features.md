# UI Data-Correctness Audit — Charts / Cockpit / Features cluster

Date: 2026-07-06, market LIVE (~10:45–10:48 IST). Method: page .tsx → api/*.ts → live
in-container `market-data` (`:8081`) call → TimescaleDB truth (`ay-timescaledb`, db `artha`)
→ formula/rollup verification. Read-only.

Verdict: **No data-correctness defects found.** Every numeric surface checked matches DB
truth and formula. Two non-defect observations (config-gated Dow dot; an invalid-underlying
empty edge that the real UI never hits) are logged below.

---

## Charts pages (ChartsPage / AdvanceChartPage / MultiframeChartPage)

Endpoint: cache-first `GET /api/v1/market/candles` (`api/charts.ts::useCandles`). Window is
anchored to `min(lastTradingDay 15:30 IST, now)` with `span×220` lookback; intraday auto-refreshes
every ~10 s during IST cash hours (`isMarketHoursIst`).

**1m OHLCV parity — EXACT.** NSE:NIFTY 50 1m, buckets 09:15–09:23 IST: endpoint O/H/L/C/V/oi
byte-for-byte identical to `marketdata.candles` rows (e.g. 09:15 O 24306.85 / H 24339.30 /
L 24290.05 / C 24337.50 / V 0). `source=TICK_AGG`, `asOf` fresh, `stale:false`.

**3m rollup — CORRECT aggregation and CORRECT bucket alignment.** 3m is not a cagg; the FE rolls
1m→3m client-side (`charts.ts::rollupCandles`) with key `floor(floor(epoch_ms/60000)/3)`.
Reproduced the same epoch-floor grouping in SQL over the live 1m bars:
- 09:15–09:18: O 24306.85 (first 1m open), H 24356.65 (max), L 24290.05 (min), C 24351.70 (last), V 0 (sum)
- 09:18, 09:21 buckets likewise correct.
Epoch-floored 3m buckets align exactly to 09:15/09:18/09:21 IST (09:15 IST is a 3m boundary), so
open=first, high=max, low=min, close=last, vol=sum are all faithful. Matches CLAUDE.md's read-time
1m→3m rollup contract.

**Candle→chart mapping — no field swap.** `CandleChart.tsx` maps open→open … close→close via
`Number()`; volume pane hidden when every bar is volume=0 (index symbols) — correct.

**Freshness.** NIFTY 50 / INDIA VIX / NIFTY BANK 1m all current to 10:44–10:46 IST (≤1 min behind
now). MultiframeChart index-default 4th pane swaps 1m→30m for indices (indices have no 1m gap issue
here, but the swap is harmless and intentional per the in-file audit note).

---

## Vix & Index page (VixIndexPage)

Zero-backend: three `/market/candles` 1m reads (INDIA VIX / NIFTY 50 / NIFTY BANK) folded on a union
minute axis (`core/vixIndexSeries.foldVixIndex`, plots `close` per minute, missing minute → null).

- INDIA VIX 1m fresh to 10:44 IST (close 11.88). VIX quote endpoint `/market/vix`: ltp 11.89,
  change +0.09 / +0.76% — consistent, current.
- Fold plots each series' `close`; `Number()` used only for chart coordinates (sanctioned string→num
  boundary), displayed money stays string. Correct.

---

## World Indices page (WorldIndicesPage)

Endpoint `GET /market/world-indices` (Upstox global feed). Live at audit time, asOf 10:44:48 IST.

**%change derivation — EXACT (spot-checked 3 rows):** HANG SENG 204.96/23350.04=0.88 ✓,
DOW JONES 594.83/52326.24=1.14 ✓, FTSE 21.56/10652.87=0.20 ✓. Sign-aware ChangeCell (arrow + sign +
tone, never colour alone). Per-venue `latency` (20/120/900 s) surfaced; `isStaleQuote` flags >1 h old
asOf as "closed". LTP/netChange/changePct arrive as decimal strings, formatted exact (no parseFloat).
Values plausible and fresh.

---

## Market Holidays page (MarketHolidaysPage)

Endpoint `GET /market/holidays`. Returns the bundled NSE calendar 2024–2026 (Republic Day … Christmas),
date-ascending with weekday + description — matches `libs/market-calendar` bundled set (CLAUDE.md:
"FIXED bundled set currently 2024–2026"). Passed/Coming validity derived client-side vs `todayIst()`
(Asia/Kolkata-pinned) — correct.

---

## Risk Calculator page (RiskCalculatorPage / core/riskCalculator.ts)

Pure client-side. Traced default inputs (cap 200000, risk 1%, entry 100, stop 90, lot 1, RR 2):

| field | formula | value | ✓ |
|---|---|---|---|
| riskAmount | capital×risk% | ₹2000 | ✓ |
| perUnitRisk | \|entry−stop\| | 10 | ✓ |
| units | floor(riskAmount/perUnitRisk/lot)×lot | 200 | ✓ |
| sizedRisk | units×perUnitRisk | ₹2000 (= budget) | ✓ |
| target | entry+dir×perUnitRisk×RR | 120 (2R) | ✓ |
| rewardAmount | units×perUnitRisk×RR | ₹4000 | ✓ |
| onePctTarget | entry+dir×(capital×0.01)/units | 110 → gain ₹2000 = 1% cap | ✓ |
| deploymentPct | units×entry/capital | 10% | ✓ |

Side inferred from stop-vs-entry (stop<entry → LONG); dir flips onePctTarget/target correctly for
SHORT. `onePctTarget` uses `capital×0.01` independent of riskPct — intentional (Siva fixed 1%-of-capital
profit lock, distinct from the risk budget). Lot-floor guarantees realised risk ≤ budget. All correct.

---

## Multiple Window page (MultipleWindowPage)

Composition-only: lazy-loads existing pages (vix/world/oi-stats/oi-spurt/big-oi/connecting-dots/
holidays/risk-calc) into 1/2/2×2 panes, per-pane error boundary, layout persisted to localStorage.
No independent data path — inherits the correctness of each embedded page. Nothing to flag.

---

## Cockpit (scalper/CockpitPage.tsx)

Composite live screen. Shared FilterBar (default `name='NIFTY 50'`) fans one Go out to chain /
connecting-dots / straddle / heatmap; auto-refresh every 45 s during IST hours, paused on hidden tab;
header LiveDot shows chain `asOf` + stale flag.

- **Header strip:** spot = `formatDecimal(chain.spot,2)`; Total PCR = chain.pcr (total put OI ÷ total
  call OI — tooltip correct); ATM = `nearestStrike(strikes, spot)`; Sentiment = newest connecting-dots
  row's 5-state Trend via `matrixSentiment`/`trendMeta` (Bullish/Bearish/neutral mapping correct).
- **OI-confluence matrix panel** binds `useConnectingDots` — verified live below.
- as-of + auto-refresh + book aggregate framing all wired correctly.

---

## Connecting Dots (used by Cockpit matrix + Multiple-Window + /features/connecting-dots)

Endpoint `GET /market/connecting-dots` (`ConnectingDotsService.matrix`). Factors keyed off the
**front-month futures 1m spine** (`instruments.futures(underlying)`) + VIX/OI/IV series bucketed by
`.toInstant()`.

**Live rows CORRECT for every pickable index.** With the real FE params (`name=NIFTY 50` / `NIFTY BANK`
/ `NIFTYNXT50` / `SENSEX` / `BANKEX`, `mode=live`, `interval=3m`) the endpoint returns fully-populated
rows — vix/volume/activeStrikeIv/activeStrikeOi/futOi/vwap/supertrend/rsi/futPrice/dailyTrend all
computed live (NOT stuck NEUTRAL). `derived:false` (real captured OI, live session). Bucket labels
10:45-10:48 etc. align to the 3m grid.

**Cross-check of two factors against ground truth:**
- `dailyTrend=1` (Bullish) — CORRECT: today's NIFTY 50 1d bar open 24306.85 → close 24410.05 = +103.20.
- `dow=0` (Neutral) on every row — **by design, config-gated** (see Observation 1).

---

## Observations (non-defects)

**Obs 1 — Dow dot permanently NEUTRAL live.** `ConnectingDotsService.dowFactor` reads
`GlobalQuoteSource` (OpenAlgo `DOWJONES@GLOBAL_INDEX`), which is bean-gated on
`artha.openalgo.global-quotes-enabled`. On this stack `ARTHA_OPENALGO_GLOBAL_QUOTES_ENABLED=false`,
so the bean is absent → Dow factor returns Neutral for every row, even though World Indices shows Dow
live (+1.14%) via a *separate* Upstox feed. This is documented/expected behavior, not a data bug, but
it means the composite Trend never receives the Dow vote live. Flag for owner: enabling the OpenAlgo
global-quotes feed would activate the Dow dot; today it silently contributes 0.

**Obs 2 — `?name=NIFTY` (bare) returns empty rows; the real UI never sends it.** During probing,
`connecting-dots?name=NIFTY` returned `rows:[]` because `instruments.futures('NIFTY')` finds nothing:
NIFTY futures carry `underlying_tradingsymbol='NIFTY 50'`, not `'NIFTY'`. The FE store defaults to
`'NIFTY 50'` and the underlyings picker only offers `NIFTY 50 / NIFTY BANK / NIFTYNXT50 / SENSEX /
BANKEX` (all of which match), so no real user flow hits the empty branch. Logged only as a latent
brittleness: any caller passing the bare index root ("NIFTY"/"BANKNIFTY") instead of the exact
tradingsymbol would silently get a blank matrix with no error.

---

## Files touched (read-only)
Frontend: `pages/charts/{ChartsPage,AdvanceChartPage,MultiframeChartPage}.tsx`,
`pages/scalper/CockpitPage.tsx`, `pages/features/{VixIndexPage,WorldIndicesPage,MarketHolidaysPage,
RiskCalculatorPage,MultipleWindowPage}.tsx`, `pages/options/ConnectingDotsPage.tsx`,
`api/{charts,vixIndex,worldIndices,holidays,oiAnalytics}.ts`,
`core/{riskCalculator,vixIndexSeries,connectingDots}.ts`, `components/charts/CandleChart.tsx`.
Backend: `market-data-service/.../ConnectingDotsService.java`, `InstrumentRepository.java`.
