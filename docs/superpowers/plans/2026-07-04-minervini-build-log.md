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
