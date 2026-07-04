# Minervini Phase 6 — engine build-spec (from the 2026-07-04 recon)

**Status:** SPEC (recon complete, not yet built) · **Companion to:** the
[implementation plan](2026-07-04-minervini-sepa-implementation-plan.md) (§Phase 6) + the
[build-log](2026-07-04-minervini-build-log.md). Phase 5 (VCP/base geometry + `/candidate`) is SHIPPED+LIVE
(PR-D / #528). This doc turns a 4-subsystem parity-critical recon into an exact, file:line, parity-safe
build recipe so Phase 6 lands in one focused pass without breaking the frozen scalper goldens.

> **Why a spec instead of code:** Phase 6's first shippable unit (the `vcp` entry setup) is an
> **integrated** slice — new engine context-values + `session.style=swing` + a new golden fixture set +
> the `minervini_detail` side-channel all move together; no third of it ships cleanly alone. It is also
> **parity-sensitive** (touches `libs/strategy-engine` goldens) and its per-setup entry thresholds are
> **subjective** (owner setup-priority input). So it is built as a focused pass, not an unattended
> partial merge.

---

## 0. The one correction the recon forced (READ FIRST)

The plan's **MV-6.1 lists `WEEK52_HIGH`/`WEEK52_LOW(252)`** as new engine indicators. The recon shows
those are **not needed** for the setups: the "within 25% of 52-week high / 25% above low" checks are
**Track-A screener gates** (§4.2 gates 6–7), already passed before a candidate reaches Track B, and are
injected as a *trend-template-passed* context — the engine never re-evaluates them. The setups gate on
the **breakout** (`close > VCP_PIVOT` on expanding volume), not on the 52-week band.

**So MV-6.1's real deliverable is the context-value family** (all `contextLevel` reads of a
screener-seeded series, the exact `VIX_LEVEL`/`ADVANCE_DECLINE_RATIO` pattern):

| Registry id | Meaning | Seeded from | Replay behaviour |
|---|---|---|---|
| `VCP_PIVOT` | the buy trigger = Phase-5 pivot | `minervini_setups.pivot` | fixture-seeded (NEUTRAL/absent → gate fails safe) |
| `VCP_STAGE` | Stage 1–4 (as a level) | `minervini_screen_results.stage` | fixture-seeded |
| `RS_RANK_PCT` | cross-sectional RS-rank 0–100 | `minervini_screen_results.rs_rank` | fixture-seeded (NEUTRAL in replay, like the scalper confluence gate) |
| `TREND_TEMPLATE_PASS` | 1.0 if all 8 gates passed, else 0.0 | `minervini_screen_results.passes_all` | fixture-seeded |

Add `WEEK52_HIGH`/`WEEK52_LOW` **only if** a setup is later designed to re-check the band in the engine
(none currently does). If added, vector-test them with a SMALL period (the `VectorFixtures` series is
only 80 bars — a 252-period indicator is all-null/warm-up there and proves nothing).

---

## 1. Subsystem map (file:line, verified 2026-07-04)

### 1a. Indicator registry + frozen vectors (`libs/strategy-engine`)
- Registry: `indicators/IndicatorRegistry.java` — `static{}` block registers `Definition(id, desc, params, requiresContext)` + a `Factory`. `create()` validates params via `Params.allowOnly` and throws if a `requiresContext` indicator is built without a context series.
- Impls: `indicators/Ta4jIndicators.java` (ta4j wrappers, 8-dp `EngineMath.round`) and `indicators/SessionIndicators.java` (custom + context; helper `indicator(unstable, series, index -> BigDecimal)`; `contextLevel(s,c)` = context close at-or-before via `context.indexAtOrBefore(ts)` — the template for all four context-values above).
- Context wiring: `eval/IndicatorBank.java:61–79` — when a spec carries `instrument:{exchange,tradingsymbol}` AND the registry def `requiresContext`, the override series becomes the *context* and the signal series stays the eval base. Replay seeds context candles as `Map<SeriesKey,List<EngineCandle>>` (`ReplayEngine` → `TickwiseGoldenRunner.run`).
- YAML spec shape: `config/StrategyDefinition.java` `IndicatorSpec(name, alias, timeframe, params, weight, optional, normalize, instrument)`. Gates reference the **alias**.
- Frozen-vector test: `IndicatorVectorTest.java` `VECTORS[]` + committed CSVs in `src/test/resources/vectors/<CSV>.csv` (8-dp decimal-string per bar; blank = null/warm-up). `VectorFixtures` = synthetic 80-bar (2×40) primary + aligned context series.
- **Parity rule:** a NEW indicator is pure `(series,params)→vector`; adding one cannot change an existing vector. Add the registry entry + a `VECTORS[]` row + generate the CSV; existing goldens are untouched unless a golden *strategy* references the new indicator.

### 1b. `session.style=swing` + goldens (`libs/strategy-engine`, `strategy-schema`, `strategy-signal-service`)
- Schema enum: `strategy-schema/.../strategy-schema-v1.json:578` — `style` enum is `["intraday","btst","expiry_day","positional"]`; **add `"swing"`**. `1d` is already in the `interval` enum (line 54).
- Engine square-off: `golden/TickwiseGoldenRunner.java` `SessionGate.pastSquareOff(t)` (~line 443) `return !relax && squareOff!=null && !t.isBefore(squareOff)`. Force-exits at ~line 161 (coarse-primary bucket close) and ~231 (1m primary). **Gate for swing:** add a `style` field to `SessionGate`, make `pastSquareOff` also return false when `"swing".equals(style)`. No-op for existing intraday/btst goldens.
- Daily primary bucketing: `TickwiseGoldenRunner.intervalDuration(String)` (~line 371) has `3m/5m/15m/1h`; **add `case "1d" -> Duration.ofDays(1)`** (epoch-floor bucketing aligns to the IST day). `coarsePrimary` path (lines 154–207) then rolls 1m→1d; skip the BTST pre-close branch for swing.
- Live rollable set: `strategysignal/signals/SignalEngine.java:1287` `ROLLABLE_PRIMARIES = {1m,3m,5m,15m,1h}` — **add `1d`** (or gate the load-check to exclude `swing` like `btst`, lines 237–242).
- Golden harness: `GoldenDeterminismTest.java` (byte-identical signals JSON, run-twice determinism + frozen `expected/<feature>.signals.json`) + `services/backtest-service/.../replay/BacktestParityTest.java` (ReplayEngine == golden, signals byte + trades record-equality). Fixtures in `libs/strategy-engine/src/test/resources/golden/{strategies,candles,expected}`. **New swing set → `golden-minervini/`** (separate dir), a new `@TestFactory`/test class in BOTH suites; regen expected via `-Dgolden.generate=true`, commit, re-run parity. `GoldenSignalsJson` is FROZEN → new `SignalEvent`/`Trade` fields ride a non-serialized side-channel (compute deterministically at entry).
- Exit-equivalence pin: `contracts/fixtures/exit-equivalence.json` (backtest `PremiumExitEvaluator` + live `PremiumBracketRules`/`PaperBracketEvaluator`) — only touch if swing changes exit semantics; update the fixture + both suites in one PR.

### 1c. Strategy registry + `minervini_detail` side-channel (`strategy-signal-service`, strategy Flyway)
- Registry: `strategies` + `strategy_versions(config_yaml, config JSONB, status, checksum)` + `strategy_audit_log` (`deploy/flyway/strategy/V002`). Published = `status='published'` (lowercase) + `strategies.published_version_id`. Live load: `SignalEngine.reload()` (196–292) iterates `enabled && publishedVersionId!=null`, compiles `config` via `StrategyCompiler.compile`.
- Gate/score shape: `entry_rules:{direction, gate:{all:[...]}, scoring:{threshold, optional_min_score, optional_gate_margin}}`. Result = `eval/ScoreBreakdown` (`composite=Σ(w·s)/Σw`, gate tree, `indicators[]`), serialized by `eval/ScoreBreakdownJson.write` (frozen, byte-parity).
- **`minervini_detail` V020 template = `scalper_detail` V009** (`deploy/flyway/strategy/V009__scalper_signal_detail.sql`: `ALTER TABLE signals ADD tradeable_exchange/tradeable_tradingsymbol/scalper_detail JSONB`). Recipe: `V020__minervini_signal_detail.sql` adds `minervini_detail JSONB`; `SignalRepository` gets `stampMinerviniDetail(id, json)` (mirror `stampScalperDetail`, lines 218–224); `SignalEngine.emitEntry` (829–844, inside the atomic `tx.execute`) stamps it when the fired strategy is a Minervini one; a `minerviniDetailJson()` builder mirrors `scalperDetailJson()` (setup type, stage, footprint, pivot, gate booleans). **Strategy Flyway head = V019** (`V019__bot_commands_audit.sql`), so the next file is **V020**.
- Modulith: `signals` must NOT import `notifier`/`paper` — publish an in-process event (`SignalEmitted`) + `@EventListener` in the consumer (`DotInputAlert`/`DotAlertListener` template). `ModularityTest` enforces it per service (full `-am verify` locally).

### 1d. Paper / auto-paper / exits (`strategy-signal-service`) — for Phase 7
- Auto-paper chain (event-driven): `SignalEngine` publishes `SignalEmitted` → `AutoPaperListener` (gates on `autoPaperTradeEnabled`, stamps qty, transitions `TAKEN`, publishes `SignalTaken`) → `PaperSignalListener.openOrder` → `PaperService`. Swing signals ride the same chain (non-scalper path; skip the `ScalperAccountModel` sub-account routing).
- **Intraday square-off is already style-scoped:** `PaperScheduler.intradayMarkToClose()` (cron `0 45 15 …`) closes only positions whose `session.style='intraday'` (`PaperPositionRepository.intradayOpen()` filters on it). **Swing positions are naturally excluded** — MV-7.1 (survive EOD) is mostly free; add a `swingOpen()` reader + overnight-MTM refresh if needed.
- Exits: `eval/ExitEvaluator.java` precedence stop_loss > trailing_stop > take_profit > time_stop > signal_exit; each `ExitRuleSpec(type, params{basis,value})`. **Only the FIRST rule of each type is used today** — staggered/tiered exits (MV-7.3/7.4) need a multi-leg executor (net-new). `PaperBracketEvaluator` polls every 15s and already holds across days.
- Sizing: `eval/PositionSizer.java` already has `percent_equity` and `atr_risk` (`= equity×risk%/stopDistance`) — MV-7.2's capital%/stop-distance sizing is `atr_risk` (alias for clarity); all modes lot-round.

---

## 2. Recommended PR sequence

- **PR-E (keystone): `session.style=swing` engine enablement** — schema enum + `SessionGate` swing gate + `intervalDuration("1d")` + `ROLLABLE_PRIMARIES += 1d`, proven by a **minimal swing golden fixture** in `golden-minervini/` that holds a position across ≥2 daily sessions with no square-off; all existing goldens byte-identical (GoldenDeterminismTest + BacktestParityTest green). Infrastructure, parity-safe, non-speculative — unblocks every downstream swing item. (MV-6.2.)
- **PR-F: `vcp` setup + context-values + side-channel** — MV-6.1 context indicators (`VCP_PIVOT`/`VCP_STAGE`/`RS_RANK_PCT`/`TREND_TEMPLATE_PASS`) + the `vcp` strategy YAML (MV-6.3) + `minervini_detail` V020 (MV-6.8) + the seeded-context replay fixture + a firing golden. This is the first end-to-end setup.
- **PR-G: the other four setups + funnel + regime** — `cheat_3c`/`power_play`/`primary_base` (MV-6.4/5/6), the `sepa` funnel 3-list (MV-6.7), the regime/industry-group gates (MV-6.9).
- **Phase 7+ (paper/backtest/live/selling):** swing paper lifecycle (mostly free per §1d) + staggered/scaled exits (net-new executor) + swing backtest goldens + live + selling discipline + analyzers.

## 3. Open owner questions (before PR-F)
1. **Setup priority** — which of the six setups to build/enable first? (recommend `vcp` — the canonical Minervini entry, already has Phase-5 geometry).
2. **Entry thresholds** — breakout-volume multiple, "don't chase" extended-% cap (~10%?), post-breakout 20-day-MA health tolerance. All will ride `artha.minervini.*` config (default + tunable), so this is a default-picking question, not a blocker.
3. **Context seeding in live** — confirm the daily screener output (`minervini_setups`/`minervini_screen_results`) is the seed source for the `VCP_PIVOT`/`RS_RANK_PCT` context series (a small producer that publishes the per-symbol daily levels into the engine's `SeriesProvider`).
