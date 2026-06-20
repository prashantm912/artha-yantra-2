# Open=High Per-Strike Probability (faithful Table-1/Table-2 grading) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Replace the #2 Open=High/Open=Low v1 proxy (front-Future OH/OL x OI-quadrant) with the **source-faithful** probability grading from the Siva "Open & High" deck (Day 14): the per-strike OH/OL **FNO-structure table (Table 1)** + the **price/volume modifier (Table 2)**, graded HIGH / MILD / LOW / STAND_ASIDE — by exposing per-strike option-premium session OHLC + volume from the existing snapshot store.

**Architecture:** The data already exists — `options_chain_snapshots` (TimescaleDB, V006) stores per-strike `ltp` + `volume` + `oi` every 5 min for the full chain. We DERIVE per-strike session open/high/low/last + day-volume + decline-volume + prior-session close (no new capture, no schema change), expose them via ONE new market-data analytics endpoint, and consume them in `OpenHighLow` to compute the faithful tier. Strategy logic stays in strategy-signal (pure, testable); data derivation stays in market-data (the established split). Parity-safe (scalper rides the V009 side-channel; live-only).

**Tech stack:** Java 21 (market-data-service + strategy-signal-service), Spring Boot, TimescaleDB time-bucket SQL, springdoc + openapi-typescript@7, JUnit + Testcontainers.

## Source of truth (the model we are matching)

`strategy-documents/options-scalper-siva/original-source-backup/.../Day 14/Open & High Strategy - Index Options & Futures (2).pdf` (deck p.6-10; narrated Day-14 lines 670-940). Two tables + rules:

**Table 1 - FNO structure (per-strike OH/OL footprint):**
| Configuration | Probability |
|---|---|
| Futures OH + Call OH on **>=3 strikes ATM+-3** + Put OL | **HIGH** (bullish) |
| Few Calls OH + few Puts OL | **MILD** |
| Puts OH (>=3) + Calls OL | **HIGH** (bearish) |
| Few Puts OH + few Calls OL | **MILD** |
| Calls OH **AND** Puts OH together | **MILD both sides** -> STAND_ASIDE |

**Table 2 - price/volume modifier (per-strike option premium candles):**
| OH | premium move | volume | probability |
|---|---|---|---|
| Call OH | falls | < 50k | **INCREASES** |
| Call OH | falls | > 50k | **DECREASES -> LOW** |
| Call OH | flat | flat | **INCREASES** |
| Put OH | rises | < 50k | INCREASES |
| Put OH | rises | > 50k | DECREASES -> LOW |
| Put OH | flat | flat | INCREASES |

**Rules:** if the identified-strike premium **fell >50% from prev-day close** -> LOW / avoid; **avoid** if premium down >50% AND/OR strike OI up >50%; 1st-half > 2nd-half; **enter on momentum (price+volume+RSI>50)**, target never above OH; the OI-Pulse >=90% badge is the **external** auto-grading (stays unavailable / degrade-around).

## What changes vs the shipped v1

- v1 tier = `front-Future OH/OL x underlying OI-quadrant` (a PROXY; the quadrant is NOT in the source's probability tables). **Replaced** by Table-1 (per-strike OH/OL count) + Table-2 (price/volume).
- v1 had HIGH / MILD / STAND_ASIDE. **Add the graded LOW** (Table-2 fall-on->50k-volume + the >50% prev-close fall).
- v1 entry rode the shared RSI band 60-80. **Relax to RSI>50 for #2** (source).
- The existing <=50% spurt reject rules stay (they ARE the source's avoid rules) but key off the new per-strike prev-close fall% where available.

## Resolved decisions (owner, 2026-06-21)

1. **Support BOTH 3-min and 5-min** (needed in different cases). The per-strike-stats endpoint takes an
   `interval` param (3 or 5) and `time_bucket`s the snapshot series accordingly. KEY CONSTRAINT: you can
   downsample to coarser, NEVER to finer than the capture cadence — so a NATIVE 3-min volume-candle
   requires 3-min capture. Make `artha.options.snapshot-interval-ms` the lever (default stays 300000 =
   5-min; set 180000 for 3-min). The existing OI endpoints keep requesting 5-min buckets (behavior
   preserved by downsampling). Storage-light alternative (follow-up, not this slice): a TARGETED 3-min
   capture for only the front-expiry ATM+-3 strikes instead of a global 3-min capture (the full chain at
   3-min is ~67% more rows). For THIS slice: build the endpoint interval-parametric + verify it serves
   whatever is captured; document that the 3-min volume-candle test needs 3-min capture.
2. **Drop the OI quadrant** from #2's tier (source does not use it for OH probability). The >50% OI-change
   AVOID rule stays (via the existing spurt magnitudes).
3. **prevClose = snapshot-derived** (the prior trading session's last snapshot ltp) — the easiest +
   self-contained option (no Kite wire / chain-mapping change).
4. **`snapshots.volume` is cumulative day-volume** (Kite convention) → the candle/interval volume = the
   consecutive-snapshot DIFF; at 3-min capture each diff ~= a 3-min candle (source-faithful). A1 Step 2
   VERIFIES this against `OptionsSnapshotService` before relying on the diff; if it is already interval,
   drop the diff. The 50k volume floor is config, calibrated to the chosen interval.

---

# PART A — market-data: per-strike session-stats endpoint

## Task A1: OptionsSnapshotReader — fetch volume + a session-stats method

**Files:**
- Modify: `services/market-data-service/.../options/analytics/OptionsSnapshotReader.java` — add `volume` to the `StrikePoint` SELECT/record (currently omitted, ~line 25-48); add a `sessionStats(...)` method.
- Test: `OptionsSnapshotReaderTest` (or the analytics IT) — derive open/high/low/last/dayVolume/declineVolume per strike from a seeded multi-bucket session.

- [ ] **Step 1 (test first):** Seed (Testcontainers) a session of `options_chain_snapshots` rows for one underlying+expiry: ~5 buckets, a few strikes, CE+PE, with rising/falling `ltp` and cumulative `volume`. Assert `sessionStats(underlying, expiry, session)` returns, per (strike, optionType): `open` = first-bucket ltp, `high` = max ltp, `low` = min ltp, `last` = newest ltp, `dayVolume` = last cumulative volume - first (or session total), `declineVolume` = summed INTERVAL volume of buckets whose ltp dropped vs the running max (the Table-2 "fall on volume"), and `prevClose` = the last ltp of the PRIOR trading session (null if none). Use `MarketCalendar` for the prior-session date.
- [ ] **Step 2:** Implement: a windowed SQL (`time_bucket` + `first`/`last`/`max`/`min`/`sum`) over the session's snapshots per (strike, optionType), plus a small prior-session `last(ltp)` lookup. Add a `PerStrikeSessionStat` record. Interval volume = `volume - lag(volume)` per strike (cumulative day volume -> interval); guard the first bucket. (If `volume` is already interval not cumulative, adjust + note it — verify against `OptionsSnapshotService` capture semantics.)
- [ ] **Step 3:** Tests green.
- [ ] **Step 4:** Commit: `feat(market-data): per-strike session OHLC+volume reader for Open=High grading`.

## Task A2: the endpoint

**Files:**
- Modify: `services/market-data-service/.../options/analytics/OptionsAnalyticsController.java` — add `GET /api/v1/market/options/strike-session-stats`.
- New service `OpenHighStatsService` (or fold into an existing analytics service) computing ATM + the ATM+-window slice + the OH/OL marks.
- Test: `OptionsAnalyticsControllerIntegrationTest` (extend).

- [ ] **Step 1 (test first):** `GET /strike-session-stats?underlying=NIFTY 50&expiry=<e>&window=3` returns `{ asOf, spot, atmStrike, items: [ { strike, optionType, open, high, low, last, dayVolume, declineVolume, prevClose, ohMark, olMark, fallPctFromOpen, fallPctFromPrevClose } ] }` for the 2*window+1 strikes nearest the ATM (both CE+PE). `ohMark = (high - open) <= tolerance`; `olMark = (open - low) <= tolerance` (tolerance a server const, document it). `fallPctFromPrevClose = (last - prevClose)/prevClose*100` (null if no prevClose). ATM = strike nearest `spot`. Decimal-string on the wire.
- [ ] **Step 2:** Implement (reuse A1's reader; pick ATM via the latest spot; slice ATM+-window).
- [ ] **Step 3:** Tests green. Note: typed-DTO endpoint -> springdoc drift (recaptured in C1).
- [ ] **Step 4:** Commit: `feat(market-data): GET /options/strike-session-stats (ATM+-window per-strike OH/OL)`.

---

# PART B — strategy-signal: faithful Table-1/Table-2 grading

## Task B1: MarketOiClient — read the per-strike session stats

**Files:**
- Modify: `services/strategy-signal-service/.../scalper/MarketOiClient.java` — a `strikeSessionStats(underlying, expiry)` read returning a list of per-strike stats; and a carrier on the context (a new `ScalperGateContext` field, e.g. `Oi.openHighStats` or a dedicated record `OpenHighStats`).
- Modify: `ScalperGateContext.java` — add the carrier (nullable; null/empty degrades).
- Test: `MarketOiClient` test (pure JSON-in) for the mapping + degrade.

- [ ] **Step 1 (test first):** Feed a `/strike-session-stats` JSON; assert MarketOiClient maps it to a `List<StrikeStat>` (strike, optionType, ohMark, olMark, fallPctFromOpen, fallPctFromPrevClose, declineVolume, dayVolume) + the ATM; null/empty when the endpoint is unavailable (degrade — the gate then falls back / blocks per B2).
- [ ] **Step 2:** Implement (one isolated `get(...)` read, conservative-null fallback like the others). Thread it into `context(...)` (the per-strike stats are only needed by #2 — fetch lazily/only when a `open-high-low` strategy is evaluating, to avoid an extra HTTP call on every scalper bar; decide: fetch in `context` always vs a dedicated `openHighStats(underlying,expiry)` the gate calls. Prefer the gate calling it ONLY for #2, to keep the other strategies' fan-out unchanged).
- [ ] **Step 3:** Tests green.
- [ ] **Step 4:** Commit: `feat(strategy-signal): MarketOiClient reads per-strike OH/OL session stats`.

## Task B2: OpenHighLow — Table-1 + Table-2 + LOW state (replace the quadrant proxy)

**Files:**
- Modify: `services/strategy-signal-service/.../scalper/OpenHighLow.java` — rewrite `tier(...)` to consume the per-strike stats (keep the front-Future `marks(...)` as the Futures-OH input).
- Modify: `OpenHighLowGate.java` — use the faithful tier; relax RSI to >50 for #2; keep the <=50% reject rules (now also using `fallPctFromPrevClose`).
- Modify: `ScalperOiProps.java` — add `openHigh.minStrikes` (default 3), `openHigh.fallVolumeFloor` (default 50000), `openHigh.maxPrevCloseFallPct` (default 50), `openHigh.tickTolerance`.
- Test: `OpenHighLowTest`, `OpenHighLowGateTest` (rewrite/extend).

- [ ] **Step 1 (test first), Table 1:** `tier(...)` returns:
  - **HIGH** when Futures OH (front future) AND **>= minStrikes (3)** CE strikes in ATM+-3 show `ohMark` AND the PE side shows `olMark` (bullish); mirror for PE/bearish (>=3 PE OH + CE OL).
  - **MILD** when a mark is present but fewer than `minStrikes` strikes match (the "few strikes" case), or only the future OH without the per-strike footprint.
  - **STAND_ASIDE** when both CE-OH and PE-OH footprints appear together (two-sided), or no mark on the side.
- [ ] **Step 2 (test), Table 2 + LOW:** apply the price/volume modifier on the identified OH strikes: if the premium **fell** vs its session open/high AND the `declineVolume >= fallVolumeFloor (50k)` -> downgrade to **LOW**; fell on `< 50k` or flat -> keep/upgrade. Also **LOW** when `|fallPctFromPrevClose| > maxPrevCloseFallPct (50)` on the identified CE (the ">50% fall on CE = low" rule). LOW (and MILD, STAND_ASIDE) -> the gate blocks; only HIGH fires.
- [ ] **Step 3 (test), gate wiring:** `OpenHighLowGate` proceeds only on tier==HIGH; RSI gate for #2 uses `> 50` (not 60-80) — thread a #2 RSI mode (a `ScalperGates.rsiAbove(rsi, 50)` variant or a config flag) so the other strategies are unaffected; keep the existing spurt `<=50%` reject (premium/OI) as the avoid rule. Degrade: null/empty stats -> NOT HIGH -> block (never a false HIGH). Record the tier + the chosen reason in the side-channel for explainability.
- [ ] **Step 4:** Implement; remove the OI-quadrant-as-tier-confirmation (it was the proxy — the per-strike footprint replaces it). Run `OpenHighLowTest`/`OpenHighLowGateTest` green, THEN `GoldenDeterminismTest` (must stay 5/5 — live-only/side-channel).
- [ ] **Step 5:** Commit: `feat(strategy-signal): #2 faithful Table-1/Table-2 OH probability grading (HIGH/MILD/LOW)`.

## Task B3: YAML + provenance

**Files:**
- Modify: `resources/scalper-strategies/scalp-open-high-low-nifty.yaml` — description: now per-strike-faithful (Table-1/Table-2), note the 5-min snapshot-resolution caveat + the still-external OiPulse badge.
- Modify: `docs/strategy-sources.md` — update #2's row (proxy -> per-strike faithful; last-ported commit).

- [ ] **Step 1:** Edit + a strategy-load assertion still green.
- [ ] **Step 2:** Commit: `docs(scalper): #2 now per-strike-faithful Open=High grading`.

---

# PART C — verify, contracts, docs

## Task C1: contract recapture + TS regen
- [ ] Recapture `market-data-service.openapi.json` (the new endpoint drifts) + regen `contracts/gen/market-data-service.d.ts`; confirm strategy-signal undrifted + canary manifests untouched. Commit `chore(contracts): recapture for /options/strike-session-stats`.

## Task C2: full verify gate
- [ ] `MAVEN_OPTS=... mvn -pl services/strategy-signal-service,services/market-data-service -am verify` green (unit + IT + Modulith + JaCoCo); `GoldenDeterminismTest` + `BacktestParityTest` 5/5 byte-identical.

## Task C3: docs + manual guide + memory
- [ ] Update `docs/manual-tests/phase-3.5-oi-fidelity-and-strategies.md` (the #2 deferral -> done; the new endpoint walk) + the 3.5 backlog (move "#2 per-strike OH/OL confluence" from deferred to done; the OiPulse badge stays deferred). Memory `phase3-scalper-state`.

## Task C4: PR
- [ ] Final review (correctness + parity + degrade + the 5-min caveat). Open the PR (base main); do NOT merge (branch protection). Body ends with the Claude Code line.

---

## Open design questions for owner review (resolve before/early in execution)

1. **5-min vs 3-min snapshots.** Ship at the 5-min resolution (zero infra change), or also lower `snapshot-interval-ms` to 180000 for 3-min fidelity (more storage, closer to the source charts)? Recommendation: ship 5-min, document the caveat, make 3-min a config follow-up.
2. **Volume semantics.** Confirm `options_chain_snapshots.volume` is **cumulative day volume** (Kite quote convention) so the interval/decline volume is a diff — A1 Step 2 verifies against `OptionsSnapshotService`; if it is already interval, the diff is dropped.
3. **prevClose for the >50% rule.** Derived from the prior trading session's last snapshot (this plan), vs a Kite `previousClose` per-strike field if the live chain carries one. Snapshot-derived is self-contained (no extra Kite dependency) and chosen here.
4. **OI quadrant.** Dropped from #2's tier (the source doesn't use it for OH probability); the OI **avoid** rule (>50% OI change) is kept via the existing spurt magnitudes. Confirm OK to drop the quadrant from #2.

## Self-review (writing-plans)
- **Coverage:** Table 1 -> B2 Step 1; Table 2 + LOW -> B2 Step 2; avoid rules -> B2 Step 3; per-strike data -> A1/A2/B1; RSI>50 -> B2 Step 3; OiPulse badge -> stays external (unchanged). The 5-min caveat is documented (A2/B3/C3).
- **Type consistency:** `PerStrikeSessionStat`(market-data) -> the `/strike-session-stats` items -> `StrikeStat`(strategy-signal) -> `OpenHighLow.tier` inputs; `ohMark`/`olMark`/`fallPctFromPrevClose`/`declineVolume`/`dayVolume` names consistent across A1/A2/B1/B2.
- **No new capture / no schema migration** — derive from V006 `options_chain_snapshots`. The only DDL-free risk is the volume-cumulative-vs-interval semantics (Q2), gated in A1 Step 2.
