# Minervini SEPA — autonomous build log

**Status:** ACTIVE (overnight autonomous build, 2026-07-04). Companion to the
[implementation plan](2026-07-04-minervini-sepa-implementation-plan.md) — this log is the
per-PR record; the plan's item Status/Evidence cells are the canonical tracker.

**Guardrails in force:** real Upstox analytics token via app config (never printed); no live
trades; test data loads only (~2–3 months), no unsupervised heavy backfill; deploy + off-hours
live-verify where possible; branch + PR per batch; **auto-merge only on CI-green (admin)**; never
push `main` directly.

**Key recon (2026-07-04, live stack up, profile=live, analytics enabled):**
- **Screener source = `nse_eod_bhavcopy`** (master-plan §13.2; corrected during PR-A live-verify).
  The BROAD equity universe with a full recent year lives here (**2,224 EQ/BE names ≥252 sessions;
  1,590 scanned / 210 pass all 8 gates on 2026-07-03**). Native `candles`@1d recent-year is only
  ~106 names (bhavcopy is `DO-NOTHING` on the candle PK so it never fills the year there; the deep
  candle history is source=KITE/BACKFILL for subscribed/backfilled names only). candles@1d keeps its
  **11y depth** for those ~1,671 names → the *backtest* uses candles@1d; the *screener* uses bhavcopy.
- Upstox **Fundamentals API is real** (analytics token): Share-Holdings → free-float%; Key-Ratios →
  P/E, P/B, ROE; Income-Statement → sales/margins. **No company market cap** field → derive
  `mcap = P/E × net_income`. Company Profile market cap is *sector-level* only.
- `BhavcopyBackfillService` already applies **NSE/BSE corporate-action split/bonus** adjustment.

---

## PR-A — Phase 2 core: the price-only Trend-Template screener  ✅ MERGED-PENDING-CI

**Delivers:** the load-bearing 80/20 — a daily Minervini screener over the dense native daily store,
the 8 Trend-Template gates + cross-sectional IBD RS-rank + Stage label + owner price/liquidity gates,
persisted + served, with an integration test.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-0.1 | Flyway heads confirmed | DONE | marketdata V030→**V031** allocated |
| MV-0.2 | Canonical dense source picked | DONE | **`nse_eod_bhavcopy`** (2,224 EQ/BE ≥252-sess); live screen 1,590 scanned / 210 pass |
| MV-2.1 | `TrendTemplateService` — 8 gates SQL over `nse_eod_bhavcopy` + price/liquidity/session pre-filters | DONE | IT (hand-computed, 0 gateway ports) + **live-verified: real passers STLTECH/HFCL/VENUSREM…** |
| MV-2.2 | Minervini cross-sectional RS-rank (0.4/0.2/0.2/0.2 @ 63/126/189/252, percentile 1–99) | DONE | IT asserts 100/50/0 percentiles |
| MV-2.3 | Liquidity turnover gate (avg-50d `close×volume` ≥ capital×maxNamePct×100) | DONE | in `TrendTemplateService` (replaces raw vol_ratio) |
| MV-2.4 | `V031__minervini_screen_results` + `MinerviniScreenRepository` | DONE | migration applies (35→v031); upsert/latest round-trip |
| MV-2.5 | `MinerviniScheduler` (boot one-shot + 19:30 IST cron, fail-soft) | DONE | boot log "skipped — no data yet" (fail-soft verified) |
| MV-2.6 | `MinerviniController` GET + POST /run (typed record, {items} envelope) | DONE | IT endpoint asserts |
| MV-2.7 | Gateway allowlist | DONE (N/A) | `/api/v1/market/**` prefix already covers it |
| MV-2.8 | Contract recapture + TS regen | DONE | `ContractCaptureTest -Dcontracts.capture=true`; `openapi-typescript@7`; 2 minervini paths in snapshot+gen |
| MV-2.9 | Stage 1–4 derived label | DONE | IT: winner=2, loser=4, flat=1 |

**Config (all `artha.minervini.*`, tunable):** capital 150000, max-name-pct 0.25, liquidity-multiple
100, min-price 30, rs-min 70, pct-above-52w-low 25, within-52w-high 25, sma200-rising-sessions 21,
min-sessions 252, cron `0 30 19 * * MON-FRI`.

**Deferred to PR-B (fundamentals):** low-cap gates (free-float mcap <₹5,000cr, free-float% <35%,
exclude-F&O) — columns exist in V031 (null), gated on the Upstox fundamentals feed.

**Next:** deploy market-data + live-verify the endpoint on real data → then PR-B (Upstox fundamentals
client + market-cap/free-float low-cap gate + 2–3mo fundamentals load).

---

## PR-B — Phase 1/3: Upstox fundamentals feed + low-cap gate  (verified, awaiting CI)

The fundamentals data pipeline that was the plan's "biggest gap" — now a first-class Upstox feed on
the analytics token (ADR-0004), plus the low-cap universe gate inputs (ADR-0005).

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-0.6 | Upstox fundamentals fields verified | DONE | share-holdings->free-float%; key-ratios->P/E,ROE; income-statement->net-profit/revenue (crore); company mcap derived (P/E x net-profit) |
| MV-1.2 | `FundamentalsService` derivation | DONE | `FundamentalsServiceTest` (pure math, 2/2) |
| MV-1.3 | `UpstoxFundamentalsClient` (replaces the openscreener scraper) | DONE | 3 wire DTOs; bean gated on `analytics.enabled` |
| MV-3.1 | Low-cap gate inputs + ROE, V032 `equity_fundamentals` | DONE | **live-verified: RELIANCE mcap Rs 16.68L cr / promoter 50.07% / PE 22.5 / ROE 10.94 / NP 74,088cr — all real** |
| screener | LEFT JOIN `equity_fundamentals` + optional low-cap gate (`artha.minervini.lowcap-gate.enabled`, default off) | DONE | `TrendTemplateService`; `MinerviniScreenerIntegrationTest` still green |

**Live-probe (real Upstox, 4 symbols):** RELIANCE/TCS/STLTECH/HFCL fetched + derived correctly; all 4
correctly FAIL the low-cap gate (ff% > 35% and ff-mcap > Rs 5,000 cr) — large/mid-caps, exactly what
the owner's gate excludes. Derivations crore-consistent: free-float% = 100 - promoter%; market_cap =
P/E x net-profit; free-float mcap = market_cap x free-float%/100.

**Config:** `artha.minervini.lowcap-gate.enabled` (false until loaded), `.max-free-float-mcap-cr`
(5000), `.max-free-float-pct` (35). Endpoints: `POST /api/v1/market/fundamentals/refresh?symbols=CSV`
+ `GET /api/v1/market/fundamentals/{symbol}`.

**Deferred:** Code-33 quarterly EPS/sales/margin accel (§4.8) — the hard low-cap gate (this PR) was the
owner's priority. Full-universe fundamentals load = later with owner (this PR loaded 4 test symbols).

**Next:** PR-C — React screener + analyzer page (Phase 4), consuming the live endpoint.

---

## PR-C — Phase 4: React Minervini screener page  (verified, awaiting CI)

The screener is now visible + usable in the UI (Track A end-to-end).

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-4.1 | `MinerviniScreenerPage.tsx` (`/equity/minervini`) — table: symbol, close, RS, Stage, %from-high/above-low, **8 gate chips** (hover=gate meaning), free-float mcap/%, Recompute button | DONE | lint clean + tsc-strict build OK |
| MV-4.2 | `api/minervini.ts` hook + route (App.tsx) + MegaMenu "Equity -> Minervini Screener" | DONE | deployed; endpoint serves real screen via gateway |

**Live-verified:** frontend deployed; the page's data source (`GET /api/v1/market/screener/minervini`)
returns the real 2026-07-03 screen through the gateway — 1,590 scanned, top passers STLTECH (rs 100,
stage 2, 8/8), SANGINITA, BHAGYANGR, HFCL, BLISSGVS. Owner: hard-reload (Ctrl+Shift+R) to see it.

**Track A COMPLETE** (screener + fundamentals + UI): #524 + #525 + this. The owner can find the daily
low-cap momentum candidates end-to-end. Low-cap gate + full fundamentals load are config-flag/owner-run.

**Deferred (Track B, larger):** per-candidate analyzer detail + charts (MV-4.4), VCP/Stage detectors
(Phase 5), the 6 setup pass/fail signals (Phase 6), swing paper/backtest/live (Phases 7-9). These are
a fresh multi-PR effort — best tackled with the owner (setup priority + reliability process).

---

## PR-D — Phase 5: VCP / base geometry + analyzer endpoint (Track B foundation)  (verified, awaiting CI)

The first Track-B building block: net-new base-geometry detectors + a per-candidate analyzer surface.
Pure computation, strongly unit-tested, no live/trade/backfill surface.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-5.1 | `ZigZag` percentage swing extractor (pure, config threshold; peaks off high / troughs off low) | DONE | `ZigZagTest` (alternating swings; sub-threshold wiggle ignored) |
| MV-5.2 | `VcpDetector` — 2-6 narrowing contractions (ratio 0.2-0.9), pivot = final-contraction high, volume-dry-up, shakeout, footprint `[W] [deep/tight] [count]T` | DONE | `VcpDetectorTest` reproduces canonical **`40W 31/3 4T`**; rejects V-shape + expanding base; +neg volume-dry-up case |
| MV-5.4 | `V033__minervini_setups` (sibling of screen, `(screen_date,symbol)` PK) + `MinerviniSetupsRepository` + `MinerviniGeometryService.persistForPassers` (single writer, passers-only, `vcp.enabled` gate) | DONE | geometry IT persists + round-trips an `is_vcp=true` row |
| MV-5.5 | Analyzer endpoint `GET /candidate/{symbol}` → typed `CandidateAnalysis` (persisted gates/stage/fundamentals + live VCP geometry + `scanned` flag) | DONE | geometry IT reproduces footprint + pivot end-to-end through the endpoint; spec recaptured + TS regen + `tsc --strict` |

**Config (all `artha.minervini.vcp.*`, tunable):** `zigzag-pct` 2.5 (must be < the tightest contraction
you want to resolve), `min/max-contractions` 2/6, `ratio-min/max` 0.2/0.9, `final-vol-low-fraction` 0.5,
`lookback-days` 400, `enabled` true.

**Adversarial review (12-agent workflow, 6 confirmed findings, all fixed before PR):**
1. (medium) **volume-dry-up baseline contamination** — the 50-day avg window overlapped the contraction it
   measured; corrected to the 50 sessions **ending at the pivot**, pullback measured over the days after.
2. (low) POST /run skipped geometry persist → extracted `persistForPassers`, now shared by scheduler + controller.
3. (medium) scheduler persist path never positively verified an `is_vcp=true` row → added a positive persist IT.
4. (low) IT asserted pivot `.exists()` not value → now asserts the pivot value.
5. (low) IT hermeticity (universe RS coupling) → geometry assertions pass an explicit passer, never the RS universe.
6. (low) volume-dry-up gate not independently pinned → added a normal-volume negative unit case.

**Design notes:** geometry lives in `screener/minervini/geometry/` (pure, replay-safe — no clock/IO in the
detector); reads the same broad `nse_eod_bhavcopy` universe as the screener; geometry computed for **passers
only** (per-symbol scan is expensive); the analyzer endpoint recomputes on demand for any symbol (so a
non-passer still gets a base read). `MapReturnRatchet` unaffected (typed record).

**Deferred (Phase 6+):** the 6 setup pass/fail signals, `session.style=swing`, swing paper/backtest/live,
selling discipline, analyzers. Also MV-4.4 (React analyzer page consuming `/candidate`) — a follow-up FE PR.

---

## Phase 6 — recon complete + build-spec (not yet built)

A 4-agent parallel recon mapped the parity-critical subsystems Phase 6 touches (engine indicator
registry + frozen vectors, `session.style`/square-off/goldens, strategy registry + `scalper_detail`
V009 side-channel, paper/auto-paper/exits). Captured as an exact file:line, parity-safe build recipe:
**`docs/superpowers/plans/2026-07-04-minervini-phase6-build-spec.md`**.

**Key correction the recon forced:** MV-6.1's real deliverable is the **context-value family**
(`VCP_PIVOT`/`VCP_STAGE`/`RS_RANK_PCT`/`TREND_TEMPLATE_PASS`, all `contextLevel` reads of a
screener-seeded series), NOT `WEEK52_HIGH/LOW` — the 52-week band is a Track-A screener gate injected
as context, never re-evaluated in the engine; the setups gate on the breakout (`close > VCP_PIVOT`).

**Recommended PR sequence:** PR-E `session.style=swing` engine keystone (schema + `SessionGate` swing
gate + `intervalDuration("1d")` + `ROLLABLE_PRIMARIES += 1d`, proven by a minimal `golden-minervini/`
swing fixture; existing goldens byte-identical) → PR-F `vcp` setup + context-values + `minervini_detail`
V020 → PR-G the other 4 setups + funnel + regime → Phase 7+ paper/backtest/live/selling.

**Not built here** because Phase 6's first shippable unit (the `vcp` setup) is an integrated,
parity-sensitive slice with subjective per-setup thresholds — it needs owner setup-priority input (see
build-spec §3) and is built in one focused pass, not an unattended partial merge. Phase-5 geometry
(#528) remains the deployed Track-B state.

---

## PR-E — Phase 6 keystone: `session.style=swing` engine enablement  (verified, awaiting CI)

The infrastructure keystone every Track-B swing item depends on: the engine can now run a daily-primary
strategy that holds a position across multiple sessions with no intraday square-off. Parity-safe +
non-speculative; the setups (PR-F) build on it.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-6.2 | `session.style=swing`: schema enum +`swing`; `TickwiseGoldenRunner.intervalDuration` +`case "1d"`; `SessionGate` `!swing` square-off guard | DONE | `SwingSessionTest` (3): 1d swing holds ≥2 sessions (entry, 0 exits); 1h intraday squares off; 1h swing does NOT (isolates the guard from the timeframe) |

**Parity:** all 9 `GoldenDeterminismTest` + 9 `BacktestParityTest` vectors byte-identical; full
strategy-engine 122/122; strategy-schema 63/63. The `!swing` guard + the new `1d` `intervalDuration`
case are unreachable by the existing intraday/btst goldens, so nothing frozen moved.

**Engine-only by design (scope discipline):** the LIVE `SignalEngine.ROLLABLE_PRIMARIES` + live
daily-bar rollup are a distinct, market-hours-only-verifiable concern → **Phase 9** (a published swing
strategy simply logs "not live-rollable" + is skipped until then, no crash). The swing **golden fixture
set** (replay-parity for a real swing setup) rides **Phase 8 / MV-8.3** with the setups. PR-E proves the
GOLDEN-runner swing semantics; that is MV-6.2's verify.

**Correction carried from the recon:** MV-6.1 was re-scoped to the context-value family (not WEEK52) and
now rides **PR-F** with its first consumer (the `vcp` setup) — building indicators nothing references yet
would be speculative.

**Next (PR-F, owner-approved `vcp`-first):** `vcp` setup YAML (MV-6.3) + the context-value indicators
(MV-6.1: `VCP_PIVOT`/`VCP_STAGE`/`RS_RANK_PCT`/`TREND_TEMPLATE_PASS`) + `minervini_detail` V020 (MV-6.8)
+ a seeded-context replay fixture + a firing golden — the first end-to-end setup.

---

## PR-F — Phase 6: the `vcp` breakout setup (first end-to-end Minervini entry)  (verified, awaiting CI)

The first real setup: a swing (daily-primary) long entry that fires as the close breaks out above the
Phase-5 VCP pivot on expanding volume. Builds on the PR-E swing keystone.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-6.1 | `VCP_PIVOT` context indicator (`contextLevel` alias; the Phase-5 buy trigger seeded into the engine) | PARTIAL — VCP_PIVOT DONE | `IndicatorVectorTest` frozen vector; `RegistryAndSeriesTest` freeze + schema enum synced |
| MV-6.3 | `vcp` setup `minervini-vcp.yaml` — `crossover(px, pivot)` (breakout + don't-chase in one) AND `vol>1.2` (expanding) AND `close>sma20` (health); swing 1d | DONE | `VcpSetupTest`: fires once on the breakout WITH volume; 0 entries WITHOUT (the crossover fires but the volume gate blocks it) |

**The gate-grammar insight:** the rule DSL is comparisons + crossover only (no arithmetic), so
`crossover(px, pivot)` elegantly encodes BOTH "buy the breakout" AND "don't chase an already-extended
move" — the crossover fires only on the bar the close crosses above the pivot, never when already far
above. No `pivot*1.10` arithmetic needed.

**Parity:** all 9 golden + 9 replay-parity vectors byte-identical; strategy-engine 125/125, strategy-schema
63/63. `VCP_PIVOT` is a pure additive registry entry; the vcp YAML is a NEW definition nothing else
references — no frozen output moved.

**Deferred (correct scope):** the `~7-8%` flat stop + staggered/sell-into-strength exits (need a
flat-percent exit basis + a multi-leg executor the engine lacks) → **Phase 7 / MV-7.2-7.4** (interim
`atr_multiple` stop used). `minervini_detail` V020 side-channel (MV-6.8) + the live screener→engine
context-seeding producer → **Phase 9** with the live swing operation (their only consumer). The
replay-parity golden for the vcp setup rides **Phase 8 / MV-8.3**.

**Next (PR-G):** the remaining setups (`cheat_3c`/`power_play`/`primary_base`, MV-6.4/5/6), the `sepa`
funnel 3-list (MV-6.7, + `RS_RANK_PCT`/`TREND_TEMPLATE_PASS` context-values), and the regime/group gates
(MV-6.9).

---

## PR-G — Phase 6: the SEPA funnel 3-list (MV-6.7)  (verified + LIVE)

The owner's actual daily deliverable: the day's Trend-Template passers ranked into the actionable
three-list — immediately-buyable / on-deck / watch — by converging the Phase-5 screen (pass + RS-rank)
with the base geometry (valid VCP + pivot). A market-data endpoint reusing the persisted Phase-5 rows
(simpler + live-verifiable than an engine meta-strategy; zero parity surface).

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-6.7 | `GET /api/v1/market/screener/minervini/funnel` — join screen + setups, bucket by pivot proximity + valid VCP, rank by RS (typed record) | DONE | IT buckets buyable/on-deck/no-base/extended correctly; **LIVE 2026-07-03: 62 buyable / 35 on-deck / 113 watch (=210 passers)** |

**Buckets:** a valid VCP with close in [pivot·0.98, pivot·1.05] = immediately buyable (at the breakout,
not chased); [pivot·0.90, pivot·0.98) = on-deck (tightening toward the pivot); no valid base or extended
past the pivot = watch. Config `artha.minervini.funnel.*`.

**Live sample (2026-07-03):** buyable DEEDEV (704.35 vs pivot 682.95, RS 99.56), OMAXAUTO, NINSYS;
on-deck VENUSREM (1720.90 vs pivot 1797, RS 99.62), RPTECH. Sensible convergence.

**Deployed:** market-data rebuilt + recreated (no migration — reads existing tables); spec recaptured +
TS regen + tsc-strict; typed record (MapReturnRatchet unaffected); gateway `/api/v1/market/**` covers it.

**Remaining Phase 6 (engine setups, distinct effort):** `cheat_3c` / `power_play` need a Phase-5
geometry extension (the cheat pause high / high-tight-flag level Phase-5 does not yet emit);
`primary_base` needs a `WEEK52_HIGH` new-high-breakout indicator; the market-regime gate (MV-6.9)
reuses `BreadthService`. All benefit from owner chart-verification of the geometry thresholds.
**FE:** a `/equity/minervini` funnel view + the MV-4.4 analyzer page consume `/funnel` + `/candidate`.

**Funnel FE view (#533):** `/equity/minervini` gains a Screen | Funnel toggle; the Funnel view renders
the three-list as three ranked columns (symbol · footprint · close+%-to-pivot · RS). Verify trio green
(lint / build / test:ci 257-257); deployed. The daily buyable workflow is now end-to-end usable
(screener → geometry → funnel → UI, all live).

---

## PR-I — Phase 6: `WEEK52_HIGH/LOW` + `primary_base` setup (MV-6.6)  (verified, awaiting CI)

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-6.6 | `WEEK52_HIGH/LOW(period)` trailing high/low (EXCLUDES the current bar → `close>w52h` = fresh high) + `minervini-primary-base.yaml` (new-52w-high breakout on volume, swing) | DONE | `Week52BehaviorTest` (trailing max/min on a known series); `PrimaryBaseSetupTest` fires on the new-high breakout WITH volume, 0 WITHOUT |

Parity-safe: registry-freeze + schema enum synced; all 9 golden vectors byte-identical. The production
YAML uses `WEEK52_HIGH` period 252; the test uses an inline period-20 variant (identical mechanism).

---

## PR-J — Phase 6: `cheat_3c` + `power_play` setups (MV-6.4/6.5)  (verified, awaiting CI)

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-6.4 | `CHEAT_PIVOT` context indicator + `minervini-cheat-3c.yaml` (breakout above the cheat-pause high) | DONE | `CheatPowerSetupTest` fires on the seeded cheat breakout |
| MV-6.5 | `THRUST` context flag + `minervini-power-play.yaml` (VCP-pivot breakout + `thrust>0`) | DONE | fires WITH thrust, blocked WITHOUT (thrust gate load-bearing) |

Both are seeded-context breakout setups (same pattern as `vcp`): `CHEAT_PIVOT`/`THRUST` are additive
`contextLevel` registry entries, NEUTRAL in replay. All 9 golden vectors byte-identical. The Phase-5
geometry that COMPUTES the cheat-pause high + thrust flag (and seeds them live) rides **Phase 9** with
the context-seeding producer — same staging as `VCP_PIVOT`. Thresholds config-tunable (owner chart-verify).

---

## PR-L — Phase 8: swing backtest (MV-8.2/8.3)  (verified, awaiting CI)

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-8.2/8.3 | Swing setups backtest through `ReplayEngine` (which drives the shared `TickwiseGoldenRunner` → the 1d/swing handling from PR-E carries into replay for FREE) | DONE | `SwingBacktestTest`: `vcp` swing replays to a multi-day trade (entry<exit, barsHeld≥2, held not squared off) + two-replay determinism; existing parity 9/9 green |

Test-only (no production code — the engine already handled swing). MV-8.4 (equity-class `CostConfig`:
delivery brokerage/STT vs option lots) is a later cost-accuracy refinement — the backtest runs with
default costs today.

---

## PR-M — Phase 7: flat-percent initial stop (MV-7.2 partial)  (verified, awaiting CI)

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-7.2 | flat-`percent` exit basis (= entry×value%, a clear equity alias of the `premium_pct` formula) + schema enum; all 4 setups wired to `{basis: percent, value: 8}` (the locked 7–8% initial stop) | PARTIAL | setups still fire; `SwingBacktestTest` now exits on the 8% stop (multi-day trade); 9 golden vectors byte-identical; strategy-schema 63/63 |

The locked single initial stop + `percent_equity` 5% sizing are in place. **Phase-7 remainder (a distinct
focused pass — parity + paper-money critical):** the **staggered-stop / scaled-out partial-close
executor** (MV-7.3/7.4 — the paper ledger currently does full closes; partial closes touch the ledger +
`ExitEvaluator` + the golden format), the swing paper hold-lifecycle + auto-paper (MV-7.1/7.5, mostly
free per §1d of the build-spec — intraday square-off is already style-scoped), and the 50d-MA trail
(`trailing_stop`+`SMA(50)` config, warms ≥50 daily bars).

---

## PR-N — Phase 8: the screener hit-rate harness (MV-8.1)  ✅ verified + LIVE

The reliability-evidence deliverable — "do the Minervini passers actually go up?" A point-in-time
re-screen at a weekly cadence over deep daily history, measuring each passer's forward return vs
NIFTY 50. This is the number the whole Track-B trust process (§0.5 #12) is judged against.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-8.1 | `MinerviniHitRateService` + `POST /api/v1/market/screener/minervini/backtest` (typed `HitRateReport`) — re-runs the 8-gate screen at weekly steps over `candles`@1d (deep history), forward returns at +5/+10/+21/+63 sessions vs NIFTY 50, per-horizon win-rate + beat-benchmark rate + mean excess. **Price-gates only** (§3-E; no lookahead fundamentals). | **DONE** | `MinerviniHitRateIntegrationTest` (2/2) + `MinerviniScreenerIntegrationTest` (2/2, refactor unchanged). **LIVE 2026-07-04** (see below). |

**Shared-gate refactor (drift guard):** extracted `MinerviniGates` (pure: 8 gates + weighted RS +
Stage) from `TrendTemplateService`; the live screener and the harness now compute gates from ONE
definition so a historical re-screen can never silently drift from the live screen. `TrendTemplateService`
delegates; `MinerviniScreenerIntegrationTest` stays green (behaviour byte-identical).

**Data source = `candles`@1d, not `nse_eod_bhavcopy`.** Bhavcopy holds only ~1y — far short of the
252-session warmup + 63-session forward window; `candles`@1d has ~20y depth (2006→) for the
~1,800-name backfilled/subscribed EQ universe (verified live). Universe scoped by an `instruments`
`instrument_type='EQ'` join; the same session/price/liquidity pre-filters + cross-sectional RS
percentile as the live screener.

**LIVE result (2026-07-04, default 3y / weekly / 149 asOf steps / 1,559-name universe / 104,523 samples):**

| horizon | n | win% | beat-NIFTY% | mean ret% | mean excess% | median% |
|---|---|---|---|---|---|---|
| +5  | 27,341 | 48.83 | 47.72 | 0.41 | 0.27 | -0.14 |
| +10 | 27,079 | 48.83 | 47.68 | 0.83 | 0.56 | -0.20 |
| +21 | 26,458 | 50.04 | 47.86 | 1.79 | 1.22 | 0.02 |
| +63 | 23,645 | 53.02 | 47.93 | 5.63 | **2.92** | 1.64 |

**Read:** a real, modest, *asymmetric* edge — the beat-NIFTY RATE hovers near a coin-flip (~47.7–47.9%)
but the mean excess is positive at every horizon and GROWS with the hold (+2.92% at 63 sessions), and
the median return turns positive by +21. The winners are fat-tailed; the method's payoff is in
magnitude, not frequency — exactly the momentum thesis. Survivorship is accepted + surfaced in the
report note (today's listed universe → a real forward book fares somewhat worse).

**Adversarial review (4-critic + verify workflow, 2 CONFIRMED, both fixed pre-merge):**
1. (medium) benchmark/excess stats divided a non-NaN-benchmark numerator by the FULL sample count →
   self-inconsistent `beatBenchmarkRatePct` vs `winRatePct` when `idx+h` ran off the benchmark series.
2. (low) the passer forward return used `lead(close,h)` over the STOCK's sessions while the benchmark
   used `idx+h` over the BENCHMARK's sessions → a halted/gapped stock was measured over a longer
   calendar window than the NIFTY it was differenced against.
   **One fix for both:** measure each passer's forward return to the SAME benchmark session date
   (`lead(d,h)` + a date-match); one denominator backs every stat; misaligned samples are dropped +
   counted in the note (4,208 on the live run). Byte-identical screener behaviour retained.

**Parity/contract:** no engine path, no golden surface (a market-data read-only analytics endpoint).
Typed record → `MapReturnRatchet` unaffected; springdoc recaptured + `contracts/gen` regen (additive:
the new path + `HitRateReport`). Config `artha.minervini.hitrate.*` (lookback-years 3, max-span-years 8,
step-sessions 5, benchmark "NIFTY 50").

---

## PR-O — Phase 4: the per-candidate analyzer page (MV-4.4)  ✅ verified + deployed

The React drill-down that closes Track A's UI: click a symbol on the screener or the funnel → a full
per-candidate SEPA analysis with a chart.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-4.4 | `MinerviniCandidatePage` (`/equity/minervini/:symbol`) — summary strip (close/RS/Stage/gates/VCP) over a daily chart, 3 tabs: Trend Template (8 gates + MA structure), Base geometry (VCP footprint/pivot/contractions/depth/shakeout), Fundamentals (free-float mcap/%). Consumes the live `GET /candidate/{symbol}`. | **DONE** | verify trio green (lint / build tsc-strict / test:ci **257/257**); deployed; SPA route serves 200; `/candidate` live-returns real geometry (DEEDEV 8/8, Stage 2, VCP `1W 12/7 4T`, pivot 682.95). |

**Chart (`MinerviniCandidateChart`):** daily candles + 50/150/200-day MA overlays (computed
client-side, legend-labelled) + volume underlay + a dashed price line at the VCP pivot. Reuses the
`CandleChart` lightweight-charts v5 pattern (`autoSize`, `--ay-*` theming re-applied on `data-theme`
flips, `+19800s` IST daily-bucket shift). A dedicated component so the shared `CandleChart` (used by
every other chart page) is untouched. `useDeepDailyCandles` requests ~420 daily bars so the 200-day MA
renders across the base, not just the last ~20 sessions of a 220-bar window.

**Wiring:** symbol links from the screener table + the funnel three-list → the analyzer; lazy route
(`/equity/minervini/:symbol`, sibling of the static `/equity/minervini` screener). No new menu entry —
the analyzer is a drill-down, not a top-level page.

**Track A UI is now complete end-to-end:** screener list → funnel triad → per-candidate analyzer with
the annotated chart + gate/geometry/fundamentals reasoning — the full manual-chart-check surface the
owner asked for (§0.5 #14).

---

## PR-Q(a) — Phase 7: the 50-day-MA swing trail (MV-7.4)  ✅ verified, parity-safe

The second half of the Minervini exit doctrine after the 8% initial stop (PR-M): sell on a daily close
below the rising 50-day line.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-7.4 (trail) | `minervini-vcp-trail.yaml` — vcp entry + `stop_loss percent 8` + `trailing_stop {basis: indicator, alias: sma50}` (the 50-day-MA close-below trail). Uses EXISTING engine exit types — no engine change. | **DONE** | `SwingTrailTest`: enters on the breakout, then exits on a close below the 50-day MA that is ABOVE the 8% stop — so the TRAIL (not the protective stop) is the active exit. **9 golden vectors byte-identical; BacktestParityTest green.** |

Zero parity surface — the `trailing_stop`/`indicator` basis + `SMA` are already in the engine + schema;
this is a new strategy definition exercising them, so the frozen goldens don't move. The production
YAML uses `SMA(50)`; the test fires the real 50-day trail on a crafted 58-bar series (isolated from the
8% stop). Breakeven-at-3R + pyramiding remain (small, config/executor follow-ups).

## PR-P — Phase 7: the staggered/scaled partial-close executor (MV-7.3/7.4)  ✅ BUILT + parity-verified

The Track-B engine change that touches the parity firewall — built exactly to the recon spec
(`2026-07-04-minervini-partial-close-build-spec.md`), gated on byte-identical goldens.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-7.3/7.4 | `scaled_exit` sell-into-strength tiers (`tiers:[{profit_pct, qty_pct}]`) — the multi-leg partial-close engine (a staggered/tiered stop rides the same mechanism). | **DONE** | `ScaledExitTest` (2 partial exits of 0.5 on the +10%/+20% path + determinism) + `ScaledBacktestTest` (2 fractional trades summing to the whole, ascending fills). **GoldenDeterminism 9/9 + BacktestParity 9/9 byte-identical; full strategy-engine + backtest sweep = 66 test classes, 0 failures.** |

**The parity-safe-additive implementation (7 files):**
1. `strategy-schema-v1.json` — new `scaled_exit` exit-rule shape (`tiers` array, `qty_pct` 0<x≤1). Additive.
2. `GoldenSignalsJson.SignalEvent` += `qtyFraction` **side-channel** (a 7-arg convenience ctor keeps all
   call sites; `write()` never serializes it → frozen goldens byte-identical).
3. `ExitEvaluator.ExitDecision` += `qtyFraction`+`tier` (2-arg ctor = full close, every existing site
   unchanged); `evaluate` gains a `firedTiers` overload + the `scaled_exit` branch (lowest not-yet-fired
   tier whose `profit_pct` is met returns its partial fraction).
4. `TickwiseGoldenRunner` — `OpenPosition` += `remainingFraction`+`firedTiers`; a shared `applyExit`
   emits the leg's fraction, reduces the remainder, records the tier; the 3 exit sites route through it.
   A full close of `remainingFraction==1` is byte-identical to before.
5. `StrategyCompiler.paramsMap` — recursive array/object conversion (the `tiers` array was previously
   `asText()`→`""`; no existing param is an array so scalar params compile identically, parity held).
6. `ReplayEngine` — the entry↔exit pairing became one-entry→N-partial-close legs (`Leg` += `qtyFraction`,
   `opensPosition`, `closesPosition`); the fill loop closes fractional qty per leg with pro-rated entry
   cost + the lot residual on the final leg. An un-scaled strategy = one full-close leg (unchanged).
7. New fixtures/tests: `minervini-vcp-scaled.yaml`, `ScaledExitTest`, `ScaledBacktestTest`.

**Blast radius:** the *scaled tier* logic runs only through the backtest/golden runner (the 5-arg
`ExitEvaluator.evaluate`). The LIVE `SignalEngine` also calls `ExitEvaluator.evaluate` (the 4-arg
overload) — so the 4-arg is made **scaled-blind** (`scaledEnabled=false`): a live `scaled_exit`
strategy simply does NOT fire the scaled tiers (fail-safe, never a wrong full close) until Phase 9
wires a partial-position ledger. The **live paper-ledger partial close** is that further, separately-
gated PR (touches the pinned `exit-equivalence.json` + its 3 suites). Scaling out still needs the
reliability bar met (≥30–50 single-stop paper trades, §0.5 #12) before it's used in anger.

**Adversarial review (4-critic + verify, 10 agents, 5 CONFIRMED — ALL fixed pre-merge):**
1. (medium, PARITY) `ReplayEngine` close loop dropped the removed `closing == openLeg` guard → a
   stale/colliding close leg (two positions' exits on one end-of-window fill bar) could close the
   wrong position — an un-scaled trade-list change `BacktestParityTest` doesn't catch (it never pins
   trades to a golden). **Fixed:** an `openLeg`-identity guard — a leg only closes the position whose
   `entryIndex` it shares (partials share the opener's entry, so all a scaled position's tiers fire).
2. (medium, LIVE) the 4-arg `evaluate` (live `SignalEngine`) ran the `scaled_exit` branch → a live
   scaled strategy would full-close at tier 0. **Fixed:** 4-arg is scaled-blind (above).
3. (medium) partial `closeQty` wasn't lot-aligned (latent — all callers use lot=1). **Fixed:** floor
   each partial to a lot multiple; the final leg absorbs the residual.
4. (low) `FRACTION_FULL=0.999999` could diverge from the runner's exact `remaining.signum()<=0`.
   **Fixed:** exact `cumFraction >= ONE`.
5. (low) HALF_UP rounding let a 1-unit position close whole at tier 0. **Fixed** by the floor (#3).
Re-verified after the fixes: GoldenDeterminism 9/9 + BacktestParity 9/9 byte-identical, full sweep 0 fails.

---

## PR-R — Phase 9: the `minervini_detail` side-channel (MV-6.8)  ✅ verified

The per-signal setup-detail channel a live minervini swing signal writes to — the Phase-9 producer's
persistence target (`scalper_detail` V009 is the exact template).

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-6.8 | `V020__minervini_signal_detail` (JSONB on `signals`, OUTSIDE the frozen score_breakdown — a strategy-specific key inside score_breakdown is a parity FAIL) + `SignalRepository.stampMinerviniDetail(id, json)` + `SignalRow.minerviniDetail` read-back. | **DONE** | `MinerviniDetailIntegrationTest`: stamp→read round-trip (setup=vcp / stage=2 / footprint `40W 31/3 4T`), a non-stamped signal reads null, the scalper channel untouched. Strategy Flyway head V019→**V020**. The 6 positional `SignalRow(...)` sites updated (+`null`). |

The live PRODUCER (computing the detail JSON when a swing setup fires + calling `stampMinerviniDetail`)
rides Phase 9 with the context-seeding producer — this PR lands the storage + stamp so the producer has
its target. Non-minervini signals never stamp it → null, exactly like `scalper_detail`.

---

## PR-S — Phase 9: defensive selling discipline (MV-9.2)  ✅ verified, parity-safe

The Minervini defensive exit after the breakout: sell on a daily close back below the 20-day line.

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-9.2 | `minervini-vcp-defensive.yaml` — vcp entry + `stop_loss percent 8` + `signal_exit crossunder(px, sma20)` (the "close below the 20-day MA" defensive sell). Existing `signal_exit`+`crossunder` engine, no new surface. | **DONE** | `SellingDisciplineTest`: enters on the breakout, exits on the 20-MA crossunder that stays ABOVE the 8% stop, so the DEFENSIVE rule (not the stop) is the active exit. 9 golden vectors byte-identical. |

**The full Minervini exit doctrine is now expressible + parity-verified:** 8% initial stop (PR-M) →
50-day-MA trail (PR-Q) → scaled sell-into-strength tiers (PR-P) → defensive 20-day-MA break (this).
**Offensive** climax sell-into-strength = the `scaled_exit` tiers. **Stage-3/4 top exit** rides a Stage
context gate seeded in Phase 9. **MV-9.3 daily sell-decision report** (the §2.10 buy-now/why-holding/
where-seller triad per open swing position) needs live open positions → it rides the Phase-9 live
producer with its data.

---

## PR-T / PR-U — Phase 9/10: alerts (reuse) + swing report card (MV-9.4, MV-10.1/10.2)  ✅ verified

| MV item | What | Status | Evidence |
|---|---|---|---|
| MV-9.4 | Notifier alerts (entry / stop / candidate-buyable) | **DONE-BY-REUSE (entry/stop)** | **Already built — no new code.** `NotifierService.onSignal(SignalEmitted)` fires a per-strategy opt-in alert for ANY published strategy via `targetForVersion` — a published minervini swing strategy gets entry/stop alerts the moment the owner toggles notifications ON. A minervini-specific listener would be a redundant DUPLICATE (verify-before-building). Net-new = the funnel "candidate-now-buyable" transition push (market-data producer + cross-service) → rides the Phase-9 live producer. |
| MV-10.1/10.2 | Swing report card + self-measurement | **DONE (PR-U)** | `SwingReportCard.of(List<Trade>)` — batting avg, avg-win/avg-loss %, payoff ratio, expectancy %, avg bars-held + a letter grade against the §0.5 #12 reliability bar (positive expectancy AND payoff ≥ 2 AND batting ≥ ~45%). `SwingReportCardTest` (3): A-grade winning book / D-grade losing book / N/A empty. Pure analytics, single-strategy (never blended), complements `MetricsCalculator`, computes over any run's trades. |

This closes the buildable Track-B surface. **Everything that produces observable value now is shipped**;
the only remaining Minervini work is the Phase-9 LIVE operation (context-seeding producer, live daily-bar
rollup, published swing strategies, auto-paper, the candidate-buyable push, the daily sell-decision
report) — gated on the owner publishing swing strategies + an owner hit-rate sign-off (§9), and
market-hours-verifiable only. That is a supervised Phase-9 pass, not more unattended dormant code.

---

## Phase 9 — LIVE-OPERATION pass (P9-A … P9-Z, 2026-07-04)  ✅ built + deployed

The owner reviewed the hit-rate evidence (LIVE 3y: mean excess vs NIFTY +0.27→+2.92% at +5→+63
sessions — an asymmetric momentum edge, payoff in magnitude not frequency) and said **go**: all 4
setups, full Phase-9 in one pass. Built as 6 parity-verified code PRs + a go-live deploy.

**Design (the crux):** the ~62 funnel equities do NOT tick (the live feed is index/options only), so
the tick-driven `SignalEngine` — which refuses non-rollable `1d` primaries — never evaluates them.
Live Minervini is therefore a **daily EOD batch** (`MinerviniSwingEngine`) that reuses the FROZEN
`EntryEvaluator`/`ExitEvaluator`/`IndicatorBank` verbatim over the fresh daily bar. Zero edits to the
shared eval core ⇒ goldens 9/9 byte-identical; the batch scores each bar identically to the backtest.
This matches the plan's own MV-9.1 "no intraday equity tick needed".

| PR | Item | What |
|---|---|---|
| [#548](https://github.com/prashantm912/artha-yantra-2/pull/548) | P9-B | `cheat_pivot` + `thrust` geometry (V034) — the `cheat_3c`/`power_play` seeds; on the funnel + analyzer. `VcpDetectorTest` pins cheat=91.605 on the canonical base + a thrust-true fixture. |
| [#549](https://github.com/prashantm912/artha-yantra-2/pull/549) | P9-A | Seed the 4 swing setups (drafts, idempotent) + the `minervini_funnel` universe branch + `UniverseResolver` mode. `IndicatorRegistry.Definition.seeded` flag → publish-validation skips the "context instrument must exist" gate for the seeded VCP_PIVOT/CHEAT_PIVOT/THRUST (per-symbol sentinels the batch injects — no synthetic master rows). Gate-only entries: `vol` is the sole scorer (linear 0→3, threshold 0.1) so the composite clears exactly when the gate holds. |
| [#550](https://github.com/prashantm912/artha-yantra-2/pull/550) | **P9-C** (keystone) | `MinerviniSwingEngine` — entry pass (funnel → per-symbol bank + seeded geometry → EntryEvaluator → emit ENTRY + `SignalEmitted`, auto-papered) + exit pass (active swing anchors → ExitEvaluator on the daily bar → emit EXIT + `SignalExited`, closes at the daily close). `MinerviniSwingScheduler` (20:00 IST, gated) + `POST /minervini-swing/run`. **Adversarial review (2 reviewers) found + fixed 4 issues:** (HIGH) stamp `suggested_qty` (else AutoPaperListener skips → inert); (MED) exit settles at the daily-bar close via a price-aware `closeForSignal` (else breakeven); (MED) warmup 420→520 (252-bar WEEK52 gate); (LOW) out-of-window entry → skip+log. Reviewers confirmed sound: seeded-context timestamp alignment, daily-bar freshness, money paths. Full suite 532/532. |
| — | P9-D/P9-E | Auto-paper hold-across-sessions = DONE-BY-REUSE (auto-paper is global+on; swing is excluded from the 15:45 square-off by the existing `intradayOpen` `style='intraday'` filter; exits are batch-driven). `minervini_detail` populated in `emitEntry`. |
| [#551](https://github.com/prashantm912/artha-yantra-2/pull/551) | P9-I | `GET /backtests/{id}/report-card` → typed `SwingReportCard` (MV-10.1/10.2 wiring). |
| [#552](https://github.com/prashantm912/artha-yantra-2/pull/552) | P9-H | `MinerviniBuyableProducer` — one ntfy push naming the funnel candidates that transitioned into the buyable band (MV-9.4 net-new). |
| [#553](https://github.com/prashantm912/artha-yantra-2/pull/553) | P9-F | `GET /minervini-swing/sell-decisions` — the MV-9.3 daily triad (buy-now / why-holding / where-seller) per open swing position, reusing the engine bank + frozen evaluators. |

**P9-G (Stage-3/4 exit gate) — DEFERRED BY DOCTRINE.** The owner pinned the exit doctrine to the
single 8%-stop + 50-day-MA trail (§0.5 grill). A Stage-3/4 exit both changes the reliability-measured
doctrine and needs current-stage seeding in the exit bank — so it is an A/B variant to add AFTER the
base doctrine proves out on forward paper, not a silent alteration of the pinned setups.

**P9-Z go-live (deploy):** compose flag-passthroughs (`ARTHA_MINERVINI_SEED_STRATEGIES` /
`ARTHA_MINERVINI_SWING_ENABLED` / `ARTHA_MINERVINI_BUYABLE_ALERTS_ENABLED`, default off) → live `.env`
flips seed + swing on → rebuild strategy-signal + market-data + backtest → publish the 4 swing
strategies → smoke-test (`POST /run` + `GET /sell-decisions` + `GET /funnel`). The batch fires for real
Monday post-close (20:00 IST); positions accrue as the ~30–50 forward paper trades the §0.5 #12
reliability bar needs. **That paper book — with the 8%-stop + 50d-trail exits — is the real test; the
hit-rate harness was necessary-but-not-sufficient.**

**Live-fired ahead of Monday (owner clarification):** daily-bar signals are analysed post-close on ANY
session, not only Monday — so the batch was run on the 2026-07-04 (Fri) close: **8 entries fired → 8
swing paper positions open**; the `.env` seed + swing flags persist `true`. The forward paper book is
now accruing for real.

---

## Deep-history backtest + live tuning (v1–v7 + config, #556–#563, 2026-07-04/05)

After go-live the owner asked to *calibrate the live knobs on evidence*, not defaults — an ~11-year
event-driven backtest over the dense native `candles`@1d (~1,789 EQ names, ~40k signals), then a
7-version A/B chain, then apply the winners to the live paper config. New pure-computation classes
(`MinerviniSwingBacktest` / `SwingPortfolio` / `SwingRotationPortfolio` / `MinerviniBacktestService`,
`V035__minervini_backtest_runs`), all outside the parity firewall (a market-data analytics read; the
live engine reuses the FROZEN evaluators unchanged). Full write-up + every grid:
[`docs/strategies/minervini-swing-backtest-results.md`](../../strategies/minervini-swing-backtest-results.md).

| ver | PR | question | finding |
|---|---|---|---|
| v1 | [#556](https://github.com/prashantm912/artha-yantra-2/pull/556) | technical-only baseline over ~11y | 39,531 trades, 33.5% win, payoff 4.61, **expectancy +5.68%/trade** — asymmetric momentum edge (payoff in magnitude, not frequency). |
| v2 | [#556](https://github.com/prashantm912/artha-yantra-2/pull/556) | + real cross-sectional RS-rank ≥70 + ₹37.5 L turnover floor | 22,714 trades, payoff 4.11, +5.10%/tr. Filters did NOT sharpen per-trade edge but v2 is the *trustworthy* number (turnover floor removes illiquid names where survivorship inflates v1 + slippage guts thin trades). |
| v3 | [#557](https://github.com/prashantm912/artha-yantra-2/pull/557) | 2×2 RS/turnover isolation + `SwingPortfolio` (K compounding sleeves) → CAGR/DD/Sharpe/annual/streaks | **BIG FINDING: the portfolio view FLIPS the per-trade A/B.** 8 slots hold only ~750 of 39k signals (capacity-bound) → *which* you pick is everything. 8-slot CAGR: technical 28% / **rs-only 43% (best, Sharpe 0.96)** / turnover-only 27% / rs-turnover 23%. **RS-rank is the edge** (nearly doubles CAGR). Turnover floor alone hurts return but cuts DD (53→42%) as a realism tax. Worst trade −94.93% gap-through-stop; DD 28–61%; max-loss-streak 140–169. |
| v4 | [#558](https://github.com/prashantm912/artha-yantra-2/pull/558) | RS-priority slot allocation vs FIFO | RS-priority lifts every variant's CAGR by **6–16 points** — a free lunch (pure allocation policy, no new data). The live funnel already RS-ranks its buyable list, so live already captures this. |
| v5 | [#559](https://github.com/prashantm912/artha-yantra-2/pull/559) | net of transaction costs / slippage (fixed statutory + spread + size-scaled market impact, capped) | gross figures are upper bounds; costs cut most from the illiquid small-cap edge. Net RS-priority ₹10 L book: **rs-only ~39% CAGR, rs-turnover ~25%**, both ~57–66% DD. |
| v6 | [#560](https://github.com/prashantm912/artha-yantra-2/pull/560) | turnover-floor sweep (book size × floor) | the optimal floor **scales with capital**: floor 0 at ₹1.5–10 L (38–45% net CAGR), ~₹25 L at ₹50 L, ≥₹3 Cr at ₹1 Cr+. The old **₹37.5 L default was a local minimum at *every* book size** (~20% vs 38.8% at floor 0) → lower it for a small pilot book. |
| v7 | [#562](https://github.com/prashantm912/artha-yantra-2/pull/562) | slot sweep {8,12,16,20,24} + RS-rotation (evict laggard, buy leader) | **12 slots is the sweet spot** — best net CAGR *and* lower DD *and* higher Sharpe than 8; past 12 the extra slots dilute into weaker trades. **RS-rotation is catastrophic** (+39% → −41% CAGR): it cuts winners before the multibagger tail runs → confirms the hold-to-natural-exit doctrine. |

**Adversarial review across the chain (2-reviewer passes):** v2 fixed 3 real bugs (percentile
strictly-below → midpoint; RS membership 252 vs 260 mismatch → one 260; rank-date-only → as-of
contribution) and v1 byte-reproduces after; v3 self-caught a skipped-vs-closed marker collision
(−3 distinct from −1); v7 fixed F1 (a NaN current-RS holding was eviction-*protected*, biasing the
rotation rate down → NaN now evictable). **Trap surfaced:** `BacktestParityTest` checks determinism +
signal-JSON but NEVER pins the trade list to a golden — a trade-list-only change slips through, so the
scaled-executor + these sims lean on adversarial review, not the golden gate.

**Live config tuned from the findings (owner-confirmed via AskUserQuestion on the money-adjacent picks):**

| knob | where | was | now | source |
|---|---|---|---|---|
| turnover floor | `artha.minervini.liquidity-multiple` (`TrendTemplateService` + `MinerviniHitRateService` `@Value` default) | 100 (⇒ ₹37.5 L) | **25 (⇒ ₹9.375 L)** — owner picked "multiple 25 / ~4% of daily volume" | v5/v6; [#561](https://github.com/prashantm912/artha-yantra-2/pull/561) |
| concurrent positions | `risk.max_open_paper_positions` (RiskService DB row, GLOBAL cap) | disabled (uncapped) | **12** | v7 |
| deployment cap | `risk.max_deployment_pct` (DB row) | 20% | **80%** — owner picked "80% deploy" | fills the 12-slot book |
| per-name sizing | `position_sizing.percent` (4 Minervini YAMLs → re-publish) | 5% | **6.5%** (12 × 6.5% ≈ 78%) | [#563](https://github.com/prashantm912/artha-yantra-2/pull/563) |

The 4 swing strategies re-published at **v1.0.1** with `percent: 6.5` (verified live in the published
config). Risk-settings changes went through the audited `PUT /api/v1/risk/settings` API; the sizing
through YAML → rebuild → `MinerviniStrategySeeder` re-sync → `POST /strategies/{id}/publish`. `liquidity-
multiple` derived-value note also reconciled in `docs/adr/0005-minervini-universe-low-cap-equities.md`.

**Net:** the buildable Minervini surface AND its live calibration are complete. Realistic net-of-cost
expectation for the tuned book (RS-priority, ₹10 L, 12 slots): **~25% CAGR rs-turnover (live-funnel
equivalent) → ~39% rs-only (optimistic upper bound)**, budget 40–50% drawdowns. **REMAINING = only the
supervised forward-paper watch + the owner's §0.5 #12 reliability sign-off** — the backtest establishes
the mechanics have edge, the live paper book (pinned 8%-stop + 50d-trail) is the real test.

## Post-calibration live-path hardening (2026-07-05)

After the calibration, a live-path pass caught bugs that touch the swing family (all paper-only,
goldens untouched — the parity firewall held):

- **[#575](https://github.com/prashantm912/artha-yantra-2/pull/575)** — stable symbol order in
  `MinerviniBacktestService.eqSymbols()` (added `ORDER BY c.tradingsymbol`). Run-to-run portfolio-stat
  reproducibility: without it the 8-slot FIFO sleeves filled in DB-arbitrary order → non-deterministic
  CAGR/DD. The Manas fork already had it (#569); this fixes the Minervini original.
- **[#579](https://github.com/prashantm912/artha-yantra-2/pull/579)** — SignalEngine reconcile reload
  loop stopped. The 20s safety-net compared the registry's published set (45) vs the LOADED subset (39) —
  but the tick engine deliberately SKIPS the swing strategies (`session.style=swing`, driven by the daily
  `MinerviniSwingEngine`), so `loaded < published` is the steady state, not drift → it reloaded all 39
  every ~20s forever. Fix: compare the current published set vs the set AS OF the last reload
  (`lastReloadedPublishedSet`). Lesson: a reconcile that compares "what's published" vs "what loaded"
  loops whenever the loader legitimately skips a subset.
- **[#580](https://github.com/prashantm912/artha-yantra-2/pull/580)** — 6 live-path bugs from an
  adversarial Workflow bug-hunt; the two swing-relevant ones: swing `emitEntry` never called
  `entryAllowed(book)` so the risk governor (kill/loss/max-open) was bypassed for minervini/manas →
  `MinerviniSwingEngine.entryPass`/`ManasAroraSwingEngine.entryPass` now gate on it; and the 15:45
  `expireAllActive` sweep had no style filter so it EXPIRED the ACTIVE swing anchors the daily batch
  holds → scoped to exclude swing versions.
- **[#581](https://github.com/prashantm912/artha-yantra-2/pull/581)** — regression IT locking #579:
  `reconcileConvergesWhenASkippedSwingStrategyIsPublished()` asserts `!engine.publishedSetDrifted()`
  when a swing strategy is published + skipped.

The forward-paper book is now the sole remaining gate — see the ledger's Minervini row.
