# Tier-1 OI-Analytics Fidelity + Tier-2 Defined-Risk Scalpers — Implementation Plan

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Each task is a fresh subagent + spec-review + quality-review.

**Goal:** Close the Phase-3.5 Tier-2 OI-analytics fidelity gaps (T2.1–T2.8) so the scalper confluence matches Siva's full spec, and add the four feasible index-option defined-risk strategies (#4 Gap, #12 Trend-Change, #2 O=H/O=L, #9 Morning-Trade) — all the pre-Phase-4 backend that the React cockpit will consume.

**Architecture:** Temporal OI derivations live in `MarketOiClient` (it already iterates the REST envelope) and surface as new fields on the `Oi`/`Macro` context records; `ConnectTheDotsScorer` stays **pure/point-in-time** and reads those fields. New confluence dots + the chosen option ride the **V009 `scalper_detail` side-channel** (outside the frozen `score_breakdown`) → parity-safe. New strategies are YAML + a tag→enum in `ScalperConfig` + (where a new multi-bar pattern is needed) a small gate class dispatched from `ScalperConfluenceGate` — the uniform pipeline, **no per-strategy Java branching**. Two market-data endpoints gain fields/params (T2.5 sentiment series, T2.7 spurt price%) → springdoc drift → a contract-recapture pass.

**Tech stack:** Java 21 (strategy-signal-service + market-data-service), Spring Boot, Jackson, ta4j; Flyway (strategy + marketdata lineages); JUnit + Testcontainers; springdoc + openapi-typescript@7.

## Branch / PR

- Branch: `feat/phase3.5-oi-and-scalpers` (already created off `main`).
- ONE branch, **phase-per-commit** (Part-A tasks then Part-B tasks), **single PR** at the end.
- Tier 2 depends on Tier 1 (#12 consumes the T2.1/T2.2 per-side ΔOI) — Part A lands first within the branch.

## Standing constraints (every task respects these)

- **NO Angular / `frontend-ui` changes** — UI is locked to React (Phase 4); this plan is backend + contracts only.
- **`score_breakdown` is FROZEN, byte-parity** — scalper dots/fields ride the V009 side-channel ONLY. `GoldenSignalsJson.write()` is frozen; new dots must NOT change the golden byte-string. Verify with `GoldenDeterminismTest` after every dot change.
- **`MarketOiClient` is LIVE-only** — never part of deterministic replay; parity is the persisted confluence at entry. New derivations compute at entry, never per-run random.
- **Degrade-to-safe discipline** — when a new field's data is unavailable, the dot/gate must NOT confirm a side and must NOT falsely block. Soft numeric → `null` (gate `pass(null, …)`); quadrant → `NEUTRAL`; counts → `0`. A hard pre-gate degrades to PASS when its data is missing.
- **Thresholds/floors ride config, not magic numbers** — new floors (T2.1 50%, T2.6 drastic, T2.7 50%, T2.8 10pt) go in `application.yml`/DB params, mirroring `ScalperGates`/`ScalperConfig` (constants are §0B-verified; tuning rides rows).
- **ASCII-only Java strings** (no smart quotes / non-ASCII in code).
- **Build:** `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`, full reactor with `-am` (`-pl services/<svc> -am`), never a bare leaf `-pl`. Tests named `*Test`/`*IntegrationTest`.
- **Bash cwd stays at repo root** (guard-paths.py hook). Commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **Kite-wire DTO discipline** does NOT apply here — the new market-data fields are computed analytics over OUR stored OI, not Kite-mirror fields; the daily `ContractCanary` manifests are unaffected (verify in A10).

## Deferred (explicitly NOT in this plan — recorded so scope is unambiguous)

- **#3 Market Movers** — trades F&O **stocks**; the scalper engine is index-option-only (`SignalEngine.resolveUniverse` resolves `options_of_underlying` to the index front future). Needs the Track-1 equity-futures universe + N-day-high + daily-RSI. → **Track-1 / Phase 5-adjacent.**
- **#8 BTST/STBT** — needs **overnight carry**; the paper layer force-squares-off at 15:45 IST unconditionally (`PaperScheduler.intradayMarkToClose`). Plus a short-PE/CE leg needs SPAN (#47). → **after an overnight position lifecycle + SPAN.**
- **#7 Hero-Zero / #11-short** — short-premium, need SPAN (#47). Unchanged.
- **Tier-3 data backfill** (intraday-OI history, 200-day daily) and **Phase-4 React UI** — unchanged deferral.

---

# PART A — Tier-1 OI-analytics fidelity (T2.1–T2.8) + contract recapture

## Task A0: Parity guard — pin the golden baseline before any dot change

**Files:**
- Read: the golden fixtures + `GoldenDeterminismTest` / `BacktestParityTest` (locate under strategy-signal-service + strategy-engine).
- Read: `SignalEngine.scalperDetailJson` (lines ~642–664) + `SignalRepository.stampScalperDetail`.

- [ ] **Step 1:** Run the existing golden/parity suite GREEN as the baseline: `MAVEN_OPTS=… mvn -pl services/strategy-signal-service -am test -Dtest=GoldenDeterminismTest,BacktestParityTest`. Record that it passes.
- [ ] **Step 2:** Confirm in code that `scalper_detail` (and its `dots` array + `confluence_aggregate`) is NOT part of the golden byte-string (`GoldenSignalsJson.write()` is frozen; scalper fields are a non-serialized side-channel). Write a one-paragraph note into the task's spec-review confirming this, so every later dot task re-runs this suite and expects byte-identity.
- [ ] **Step 3:** No commit (guard task). Output: the exact test command + the fixture path the later tasks must keep byte-identical.

**Status handling:** if step 2 finds scalper_detail IS in a golden byte-string, STOP and escalate — the side-channel assumption is wrong and the dot tasks need a different approach.

## Task A1: market-data — expose spurt price%-change (T2.7 data)

**Files:**
- Modify: `services/market-data-service/.../options/analytics/OptionsAnalyticsController.java` (the `/options/spurt` handler ~line 125) + its `StrikeSpurt` / `SpurtSummary` DTOs.
- Modify: the spurt service (`OiSpurtService` or equivalent — it already diffs `reader.latestPair()` and computes `ltpDelta`).
- Test: the spurt-service unit test.

- [ ] **Step 1 (test first):** Assert the `/options/spurt` response now carries, per `StrikeSpurt`, `ltpChangePct` (BigDecimal, signed % change of the strike LTP between the latest pair); and on `SpurtSummary`, `oiChangePct` + `priceChangePct` (the representative ATM-side magnitudes that drive the §3.10 spurt interpretation). Use the existing latest-pair fixture; assert values match the hand-computed `(now-prev)/prev*100`.
- [ ] **Step 2:** Implement — surface the `ltpDelta%` the service already computes; add the two summary percents (price% from the spurt strike's LTP pair, OI% from `spurtPct`). Decimal-string on the wire (repo convention). Conservative null when a prior bucket is absent.
- [ ] **Step 3:** Run the spurt test green. Note: this is a **typed-DTO** field add → springdoc drift (recaptured in A10).
- [ ] **Step 4:** Commit: `feat(market-data): expose spurt price%/OI% magnitudes for scalper T2.7`.

## Task A2: market-data — sentiment series for slope (T2.5 data)

**Files:**
- Modify: `OptionsAnalyticsController.java` `/active-strikes` handler (~line 88) — add optional `buckets` query param (default absent → unchanged scalar-only response).
- Modify: the active-strikes service to compute per-bucket `sentimentPct` over the window (reuse the existing top-5 sentiment formula per bucket via `reader.series()`).
- Test: active-strikes service test.

- [ ] **Step 1 (test first):** With `?buckets=20`, assert the response adds `sentimentSeries: [{bucket, sentimentPct}]` of length ≤ buckets, newest-last, each bucket's `sentimentPct` computed by the same `100·(ΣpeΔOI−ΣceΔOI)/Σ(ceOi+peOi)` formula on that bucket's top-5 active strikes. Without `buckets`, the response is unchanged (no series key).
- [ ] **Step 2:** Implement using `reader.series()` (same window machinery as `/trending`/`/premium-series`).
- [ ] **Step 3:** Test green. Note: a **new query param** → springdoc drift (A10).
- [ ] **Step 4:** Commit: `feat(market-data): optional sentiment series on active-strikes for scalper T2.5`.

## Task A3: MarketOiClient — per-side ΔOI, cross-event, widening, drastic (T2.1/T2.2/T2.3/T2.6 derivations)

**Files:**
- Modify: `services/strategy-signal-service/.../scalper/MarketOiClient.java` — add full-series derivations alongside `latestPeMinusCePct` (line 265).
- Modify: `ScalperGateContext.java` — extend the `Oi` record with new fields.
- Test: a new `MarketOiClientTrendingDerivationTest` (pure JSON-in → fields-out; no network).

- [ ] **Step 1 (test first):** Feed a trending-series JSON (≥3 buckets) and assert derived `Oi` fields:
  - `peOiDelta` / `ceOiDelta` = last-bucket minus first-bucket-in-window per side (signed long→BigDecimal).
  - `crossedThisWindow` (Boolean) = the `peOi−ceOi` sign transitioned below→above (bullish cross) or above→below (bearish) within the window; false if permanently one-sided.
  - `gapWidening` (Boolean) = `|latest(peOi−ceOi)| > |prior(peOi−ceOi)|`.
  - `callPutDeltaImbalancePct` (BigDecimal) = `|peOiDelta − ceOiDelta| / max(|peOiDelta|,|ceOiDelta|) · 100` (T2.1's quantity).
  - `drasticBothSides` (Boolean) = `|ceOiDelta| ≥ floor AND |peOiDelta| ≥ floor` (floor from config, A9).
  - **Flat-OI caveat:** a static 50% absolute PE/CE gap with unchanged OI over the window → all deltas ≈ 0 → `callPutDeltaImbalancePct` is undefined → return null, `crossedThisWindow=false`, `drasticBothSides=false` (must NOT fire).
  - **Degrade:** series length < 2 → all new fields null/false.
- [ ] **Step 2:** Implement a single `private TrendingDerived deriveTrending(JsonNode trending, long drasticFloor)` that iterates `items` once; keep `latestPeMinusCePct` for the existing `trendingPeMinusCePct` level field (both coexist — the level dot stays). Window = all returned buckets (the caller controls `trendBuckets`).
- [ ] **Step 3:** Wire into `oi(...)` — one extra read of `/options/trending` is already made; reuse that JSON (don't double-fetch: have the trending GET map to both the level % and the derived struct).
- [ ] **Step 4:** Tests green.
- [ ] **Step 5:** Commit: `feat(strategy-signal): per-side dOI cross/widening/drastic derivations (T2.1/2.2/2.3/2.6)`.

## Task A4: MarketOiClient — 6-strike CE/PE IV pair (T2.8 derivation)

**Files:**
- Modify: `MarketOiClient.java` — compute from the EXISTING `/options/chain` rows (already read in `chain(...)`/`toChainSnapshot`).
- Modify: `ScalperGateContext.java` — extend `Macro` with `ceIvAvg6` / `peIvAvg6` (BigDecimal, nullable).
- Test: `MarketOiClientIvPairTest`.

- [ ] **Step 1 (test first):** Feed a chain JSON (rows with `ce.iv`/`pe.iv` + a spot/ATM). Assert `ceIvAvg6` = mean CE IV of the 3 strikes above + 3 below the ATM (6 strikes), `peIvAvg6` likewise for PE; null when fewer than 6 usable strikes or IVs missing. Add a `40/40 both-high` fixture (both averages high & within a small band) used by the A7 dot.
- [ ] **Step 2:** Implement — the macro read needs the chain; have `macro(...)` accept (or fetch) the chain ATM + the 3+3 IV averages. Reuse `chain(underlying)`; pick ATM by nearest-to-spot strike. Conservative null on gaps.
- [ ] **Step 3:** Tests green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): 6-strike CE/PE IV-pair averages (T2.8)`.

## Task A5: MarketOiClient — sentiment slope (T2.5 consume)

**Files:**
- Modify: `MarketOiClient.java` — call `/active-strikes?buckets=N`, derive slope from `sentimentSeries`.
- Modify: `ScalperGateContext.java` — `Oi.sentimentSlope` (BigDecimal, nullable).
- Test: `MarketOiClientSentimentSlopeTest`.

- [ ] **Step 1 (test first):** Feed a `sentimentSeries`; assert `sentimentSlope` = sign+magnitude of a simple first→last (or least-squares) slope over the window; null when series < 2 or absent (degrade). Keep the existing `sentimentPct` level field unchanged.
- [ ] **Step 2:** Implement; pass `buckets` matching the trending window.
- [ ] **Step 3:** Tests green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): sentiment-graph slope derivation (T2.5)`.

## Task A6: MarketOiClient — spurt OI%/price% (T2.7 consume)

**Files:**
- Modify: `MarketOiClient.java` `oi(...)` — read the new `summary.oiChangePct` / `summary.priceChangePct` from `/options/spurt` (currently only `summary.interpretation` is read, line 147).
- Modify: `ScalperGateContext.java` — `Oi.spurtOiPct` / `Oi.spurtPricePct` (BigDecimal, nullable).
- Test: extend the spurt mapping test.

- [ ] **Step 1 (test first):** Assert the two new `Oi` fields populate from the A1 summary fields; null when absent (degrade). The existing `underlying` quadrant mapping is unchanged.
- [ ] **Step 2:** Implement (map the same `/options/spurt` JSON to quadrant + the two percents in one mapper).
- [ ] **Step 3:** Tests green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): consume spurt OI%/price% magnitudes (T2.7)`.

## Task A7: ConnectTheDotsScorer — new + qualified dots (T2.2/2.3/2.5/2.6/2.7/2.8)

**Files:**
- Modify: `services/strategy-signal-service/.../scalper/ConnectTheDotsScorer.java` (`score()` ~lines 54–93; `add(dots, name, weight, supports, reason)` helper).
- Test: extend `ConnectTheDotsScorerTest` — one case per dot + a degrade-to-safe case per dot.

- [ ] **Step 1 (test first), per dot:**
  - **`trending_cross` qualified (T2.2/T2.3):** now requires the CHANGE, not the absolute tilt — CE side supported only if `crossedThisWindow` (or `gapWidening` with the right sign) AND `peOiDelta>0 AND ceOiDelta<0` (mirror for PE). A permanently PE-tilted chain with no cross/widening → no longer confirms. Degrade (null deltas) → unconfirmed (not blocking).
  - **`drastic_oi` (T2.6):** new dot — supports the side when `drasticBothSides` AND the net direction favors the side. Weight from config (default 1.0). Degrade false → unconfirmed.
  - **`sentiment_slope` (T2.5):** new dot ALONGSIDE the existing `sentiment` level dot — CE supported if `sentimentSlope>0`, PE if `<0`; null → unconfirmed. Weight default 1.0.
  - **`oi_spurt` (T2.7):** new dot — supports the side only when the spurt quadrant matches the side AND `spurtOiPct ≥ 50 AND spurtPricePct ≥ 50` (both magnitudes). Degrade (nulls) → unconfirmed.
  - **`iv_pair` (T2.8):** new dot — the ≥10-pt-higher-IV side supports that side (CE if `ceIvAvg6 − peIvAvg6 ≥ 10`, mirror PE); **40/40 both-high → suppression** (return a non-supporting + a `stand-aside` reason that lowers the aggregate, never a confirm). Null averages → unconfirmed. Weight default 0.8 (soft, like `iv_rank`).
- [ ] **Step 2:** Implement via `add(...)`; pull weights from a config-bound `ScalperConfig`/properties (A9). Keep all existing 14 dots; these are additive/qualifying. The weighted aggregate auto-recomputes.
- [ ] **Step 3:** Run `ConnectTheDotsScorerTest` green, THEN re-run `GoldenDeterminismTest` (A0) — MUST stay byte-identical (scalper_detail is side-channel). If golden breaks, STOP and escalate.
- [ ] **Step 4:** Commit: `feat(strategy-signal): add drastic/sentiment-slope/oi-spurt/iv-pair dots + qualify trending-cross (T2.2-2.8)`.

## Task A8: ScalperGates + ScalperConfluenceGate — #5's ΔOI ≥50% hard pre-gate (T2.1)

**Files:**
- Modify: `ScalperGates.java` — add `callPutDeltaFilter(Oi oi, BigDecimal floorPct)` returning `GateOutcome`.
- Modify: `ScalperConfluenceGate.java` — run it as a HARD pre-gate **only for the trending-oi-cross strategy** (tag-selected, see A9 tag wiring); for other strategies it is skipped.
- Modify: `ScalperConfig.java` — a `requireCallPutDeltaFilter` boolean from a tag (e.g. `oi-cross-filter`), defaulting false.
- Test: `ScalperGatesTest` (filter math + flat-OI caveat + degrade) + a `ScalperConfluenceGate` test proving #5 blocks below 50% and passes at/above, and that the gate is absent for non-#5 strategies.

- [ ] **Step 1 (test first):** `callPutDeltaFilter` PASSES when `callPutDeltaImbalancePct ≥ floor (default 50)`, FAILS when below, and **PASSES (degrade, never blocks)** when `callPutDeltaImbalancePct` is null (data unavailable) or the flat-OI caveat applies. Confluence-gate test: tag `oi-cross-filter` present → the filter gates entry; absent → not consulted.
- [ ] **Step 2:** Implement; thread the floor from config (A9).
- [ ] **Step 3:** Tests green; golden still byte-identical (this is a live gate, not in golden).
- [ ] **Step 4:** Commit: `feat(strategy-signal): #5 trending-OI >=50% call-put dOI hard pre-gate (T2.1)`.

## Task A9: Config — floors/weights + the #5 tag wiring

**Files:**
- Modify: `services/strategy-signal-service/src/main/resources/application.yml` — a `artha.scalper.oi.*` block: `drastic-floor`, `cross-filter-pct` (50), `iv-pair-min-gap` (10), spurt `oi-pct`/`price-pct` (50), dot weights.
- Modify: `ScalperConfig.java` — bind the tag `oi-cross-filter` → `requireCallPutDeltaFilter`; pass floors through where the scorer/gates read them. (Follow the existing "constants here, tuning rides rows" pattern — these are config-overridable defaults, not YAML-per-strategy.)
- Modify: `scalper-strategies/scalp-trending-oi-nifty.yaml` — add the `oi-cross-filter` tag.
- Test: a config-binding test + the seeded trending-oi strategy carries the tag.

- [ ] **Step 1 (test first):** Assert defaults bind; assert `scalp-trending-oi-nifty` tags now include `oi-cross-filter`.
- [ ] **Step 2:** Implement the properties record + wiring.
- [ ] **Step 3:** Tests green; the existing scalper IT (`ScalperRiskIntegrationTest`, confluence IT) still green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): scalper OI-analytics floors/weights config + #5 tag`.

## Task A10: Contract recapture + TS regen + manifest check

**Files:**
- Regenerate: `contracts/market-data-service.openapi.json`, `contracts/strategy-signal-service.openapi.json` (A1/A2 drift); `contracts/gen/*.d.ts`.
- Verify: `kite-contract-manifest.json` / `openalgo-contract-manifest.json` unchanged (the new fields are internal analytics, not Kite/OpenAlgo wire fields).

- [ ] **Step 1:** Re-run `ContractCaptureTest` with `-Dcontracts.capture=true` for market-data + strategy-signal (full reactor `-am`). Confirm the only diffs are the A1 typed-DTO fields + the A2 query param/series (expected drift), no unexpected path/param changes.
- [ ] **Step 2:** Regen TS: `cd frontend-ui && npm run gen:api`; confirm `tsc --strict` clean. (Generated types only — NOT Angular app code; this is the contract surface React will adopt.)
- [ ] **Step 3:** Confirm both Kite/OpenAlgo canary manifests are untouched (these new fields are not Kite-mirror) — if a canary test references them, it should not.
- [ ] **Step 4:** Commit: `chore(contracts): recapture market-data + strategy-signal specs + regen TS (T2.5/T2.7)`.

## Task A11: Part-A build gate

- [ ] **Step 1:** `MAVEN_OPTS=… mvn -pl services/strategy-signal-service,services/market-data-service -am verify` — all unit + IT + Modulith + JaCoCo green.
- [ ] **Step 2:** Re-run `GoldenDeterminismTest` + `BacktestParityTest` — byte-identical.
- [ ] No separate commit (gate). If red, fix in the owning task's scope.

---

# PART B — Tier-2 defined-risk strategies (#4, #12, #2, #9)

Shared pattern per strategy (from the wiring probe): (1) a YAML under `resources/scalper-strategies/`; (2) register the id in `ScalperStrategySeeder` (lines ~32–37); (3) any new pattern gate as a tag→enum in `ScalperConfig.from` dispatched in `ScalperConfluenceGate`; (4) provenance line in `docs/strategy-sources.md`; (5) strategy-load test + gate unit test. Core `ScalperGates`/`ConnectTheDotsScorer`/`StrikePicker` reused.

## Task B1: #4 Gap Theory — 3-min gap-fill primitive + strategy

**Files:**
- Create: a gap primitive — `scalper/GapState.java` (gap size vs prev-close/prev-candle, `filled` flag) + detection in the session-indicator path (`SessionIndicators` has `GAP_PCT`; add fill-tracking).
- Create: `scalper/GapTheoryGate.java` (the wait-for-fill + trade-with-trend pre-gate).
- Modify: `ScalperConfig.java` (tag `gap-theory` → a `StructuralStop`/flag), `ScalperConfluenceGate.java` (dispatch).
- Create: `resources/scalper-strategies/scalp-gap-theory-banknifty.yaml` (§3.4 is Bank Nifty 3-min).
- Test: `GapTheoryGateTest` + strategy-load.

- [ ] **Step 1 (test first):** Gate logic (§3.4 + Session-24 refinement): gap significant (`>3 pts / 60 ticks`); **block entry until the gap is filled** (body or wick reaches the gap origin); after fill, trade WITH the prevailing trend on a pullback to Supertrend/VWAP; if unfilled by ~40 min, trade with the prevailing trend instead. SL = Supertrend (in-trend) / day-extreme (counter-trend gap-fill). Degrade: no gap → gate inert (no signal, never a false fire).
- [ ] **Step 2:** Implement the primitive + gate; reuse RSI/volume/time gates + the confluence scorer.
- [ ] **Step 3:** Tests green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): #4 Gap Theory (3-min gap-fill) scalper strategy`.

## Task B2: #12 Trend Change — structure-break primitive + strategy (consumes T2.1)

**Files:**
- Create: `scalper/MarketStructure.java` (swing-high/low pivots + trendline/structure-break detection over the 3-min series).
- Create: `scalper/TrendChangeGate.java` (structure break + Trending-OI momentum shift + 2-candle confirm).
- Modify: `ScalperConfig.java` (tag `trend-change`), `ScalperConfluenceGate.java` (dispatch).
- Create: `resources/scalper-strategies/scalp-trend-change-banknifty.yaml`.
- Test: `MarketStructureTest`, `TrendChangeGateTest`, strategy-load.

- [ ] **Step 1 (test first):** Structure: detect swing pivots + a swing/trendline break in the reversal direction. Gate (§3.12): break + **Trending-OI momentum shift** — reuse A3's `peOiDelta`/`ceOiDelta` requiring `≥50%` quantified shift (bullish: Put-OI rising AND Call-OI falling; mirror) via the A8 `callPutDeltaImbalancePct`; RSI >60 bull / <40 bear (40–60 no-trade); volume floor; VWAP held the correct side post-entry; 2-candle 3rd-candle confirm; window 09:45–14:30 (avoid >14:30 down-reversals); benefit-of-doubt ~10–20pt SL allowance when OI confirms. Degrade: no break or null deltas → inert.
- [ ] **Step 2:** Implement; reuse `TwoCandleGate` for the 3rd-candle confirm; consume the A3/A8 ΔOI fields.
- [ ] **Step 3:** Tests green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): #12 Trend Change (structure-break + OI-shift) scalper strategy`.

## Task B3: #2 O=H / O=L — OH/OL marks + probability tier + strategy

**Files:**
- Create: `scalper/OpenHighLow.java` (OH/OL marks: session open == running session high/low within a tick tolerance, on the front future; + the §3.2-L489 probability TIER: HIGH / MILD / STAND_ASIDE).
- Create: `scalper/OpenHighLowGate.java`.
- Modify: `ScalperConfig.java` (tag `open-high-low`), `ScalperConfluenceGate.java` (dispatch).
- Create: `resources/scalper-strategies/scalp-open-high-low-nifty.yaml`.
- Test: `OpenHighLowTest`, `OpenHighLowGateTest`, strategy-load.

- [ ] **Step 0 (feasibility checkpoint — first):** Determine whether per-strike option-premium intraday OHLC (session-open vs running-high per strike) is available for ATM±3 (it drives the strike-confluence count). If only per-strike LTP/OI snapshots exist (likely), ship **v1 = front-Future OH/OL + the OI-quadrant tier** and record the per-strike ATM±3 confluence as a documented refinement (needs per-strike OHLC capture). Note the finding in the task output + `docs/strategy-sources.md`.
- [ ] **Step 1 (test first):** OH = `sessionOpen == sessionHigh` within tolerance (mirror OL); compute the **probability tier** from what is available (§3.2 L489): front-Future OH + OI quadrant LB/SC (bullish) → at least the bullish tier; both-sides ambiguity → STAND_ASIDE. Gate: tier ≥ HIGH (our computed equivalent of the "90% badge") + OI quadrant + volume + RSI + the **reject rules** (option premium NOT increased >50% from prev close; identified-strike OI-change NOT >50%); exit ~5 pts inside the OH/OL, never beyond it. **OiPulse 90% AI badge = external + unavailable → optional, degrade-around (do NOT require).** Degrade: no OH/OL or tier<HIGH → no signal.
- [ ] **Step 2:** Implement v1 per the feasibility finding; reuse the reject-rule numerics (premium/OI 50% checks) from the A3 magnitudes where applicable.
- [ ] **Step 3:** Tests green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): #2 Open=High/Open=Low (FNO probability tier) scalper strategy`.

## Task B4: #9 Morning Trade — window-aware time gate + opening-tick strategy

**Files:**
- Modify: `ScalperGates.java` — `timeWindow` becomes window-aware: overload `timeWindow(LocalTime ist, LocalTime from, LocalTime to)` honoring a strategy-declared window; keep the no-arg default (≥09:45 + midday-block + no-fresh-after-15:30) for strategies that declare none.
- Modify: `ScalperConfig.java` — parse the YAML `risk.session.window` (from/to) into the config; tag `opening-tick` selects the opening window + a `StructuralStop.FIRST_CANDLE` enum.
- Modify: `ScalperConfluenceGate.java` — pass the configured window to `timeWindow`; for `opening-tick`, **degrade the VWAP hard-gate/dot to non-blocking before 10:30** (use prior-close + OI/global-cue confluence instead).
- Create: `resources/scalper-strategies/scalp-morning-trade-nifty.yaml` (session.window from 09:16; tag `opening-tick`).
- Test: `ScalperGatesTest` (window-aware cases) + `ScalperConfluenceGate` opening-tick test + strategy-load.

- [ ] **Step 1 (test first):** `timeWindow(09:16, from=09:16, to=09:30)` PASSES (opening-tick), while the default `timeWindow(09:16)` still FAILS ("before 09:45"). Opening-tick: VWAP non-blocking before 10:30; SL anchored to the first candle's low/high (`FIRST_CANDLE`); EOD-formed view reuses the existing `MarketOiClient` FII/trending/sentiment reads. Default strategies are unaffected (regression: the 4 core strategies still use ≥09:45).
- [ ] **Step 2:** Implement the window-aware gate + the FIRST_CANDLE stop + the VWAP degrade; keep the midday-block + 15:30 cutoff applicable.
- [ ] **Step 3:** Tests green; the 4 core scalper strategies' existing tests/ITs unchanged (prove no regression to the ≥09:45 default).
- [ ] **Step 4:** Commit: `feat(strategy-signal): #9 Morning Trade (opening-tick window + first-candle stop)`.

## Task B5: Seed registration + provenance + load test

**Files:**
- Modify: `ScalperStrategySeeder.java` — add the 4 new ids.
- Modify: `docs/strategy-sources.md` — provenance rows (#4/#12/#2/#9 → consolidated-doc section + last-ported commit), and the #2 per-strike-confluence refinement note + #9 opening-tick note.
- Test: extend the strategy-load test to assert all 8 scalper strategies seed + validate.

- [ ] **Step 1 (test first):** Load test asserts the 4 new YAMLs seed without schema errors and carry the right tags.
- [ ] **Step 2:** Implement the seeder list + docs.
- [ ] **Step 3:** Test green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): seed #4/#12/#2/#9 scalper strategies + provenance`.

---

# PART C — verify, docs, PR

## Task C1: Full verify + golden/contract gates

- [ ] **Step 1:** `MAVEN_OPTS=… mvn -pl services/strategy-signal-service,services/market-data-service -am verify` green (unit + IT + Modulith + JaCoCo ≥60%).
- [ ] **Step 2:** `GoldenDeterminismTest` + `BacktestParityTest` byte-identical; `ContractCaptureTest` diffs are only the intended T2.5/T2.7 drift; `tsc --strict` clean.
- [ ] **Step 3:** No commit (gate).

## Task C2: Manual-test guide + PHASE_GATES + memory

**Files:**
- Create: `docs/manual-tests/phase-3.5-oi-fidelity-and-strategies.md` — a mock-stack walk: trigger the new dots/gates, observe `scalper_detail` carrying the new dots, the 4 new strategies emitting on the mock feed.
- Modify: `PHASE_GATES.md` — flip the Phase-3 / Phase-3.5 rows (Tier-2 OI fidelity DONE; #4/#12/#2/#9 DONE; #3/#8/#7/#11 + SPAN + per-strike OH/OL refinement still deferred).
- Modify: memory `phase3-scalper-state.md` + `phase3.5-...backlog` doc (mark T2.1–T2.8 done; note #2 v1 scope).

- [ ] **Step 1:** Write the guide + update gates/memory.
- [ ] **Step 2:** Commit: `docs(scalper): phase-3.5 OI fidelity + 4 strategies manual guide + gate/state`.

## Task C3: Final review + PR

- [ ] **Step 1:** Dispatch the final code-reviewer over the whole branch diff (correctness + parity + degrade-discipline + ASCII + no-Angular).
- [ ] **Step 2:** Open the PR (base `main`), Conventional-Commit title, body summarizing Tier-1 fidelity + the 4 strategies + the explicit deferrals (#3/#8/SPAN/per-strike OH/OL) + the contract drift. Body ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. Do NOT merge (branch protection; owner merges).

---

## Self-review notes (writing-plans)

- **Spec coverage:** T2.1→A3+A8; T2.2/T2.3→A3+A7; T2.5→A2+A5+A7; T2.6→A3+A7+A9; T2.7→A1+A6+A7; T2.8→A4+A7. Strategies #4→B1, #12→B2, #2→B3, #9→B4. Contract drift→A10. Parity→A0/A11/C1.
- **Type consistency:** new `Oi` fields (`peOiDelta`, `ceOiDelta`, `crossedThisWindow`, `gapWidening`, `callPutDeltaImbalancePct`, `drasticBothSides`, `sentimentSlope`, `spurtOiPct`, `spurtPricePct`); new `Macro` fields (`ceIvAvg6`, `peIvAvg6`). Same names used in A3/A4/A5/A6 (producers) and A7/A8/B2 (consumers).
- **Known risk to confirm during execution:** B3 per-strike option OHLC availability (B3 Step 0 gates the #2 scope); A7 must keep `GoldenDeterminismTest` byte-identical (re-run after every dot change).
