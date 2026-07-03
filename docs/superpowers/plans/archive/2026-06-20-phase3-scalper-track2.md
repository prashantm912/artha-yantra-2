# Phase 3 — Track-2 Siva Options Scalper (execution plan)

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


**Branch:** `feat/scalper-track2` · **Started:** 2026-06-20 · **Authority:** master plan
[§12](2026-06-19-openalgo-react-integration-master-plan.md) (+ §8 SPAN, §17.3 OrderGateway,
§18.4 push). This file is the *execution* layer — the staged build order, scope decisions, and the
adversarially-verified spec deltas that are **not** in the master plan. On any conflict the master
plan §17 (Errata) / §18 (Gap Addendum) win.

## Goal (M3 + M3b)

The 12 Siva options-scalper sub-strategies emit live signals off the (already-fed) OI spine + the
Phase-2 indicators, with SPAN-aware sizing and OpenAlgo order placement behind a human "Take". Exit
gate: **each sub-strategy has a green fixture test that fires exactly one signal from seeded OI +
candle data**; the daily-loss kill-switch pauses ENTRY only; the SPAN appliance returns a NIFTY
straddle margin within broker tolerance.

## Entry gate — MET

- Phases 1 + 2 merged to `main` (PR #40, `3090a34`). Indicators VWMA/PSAR + the 3m interval are in
  `IndicatorRegistry` already → master-plan build-order step 1 is **done**.
- OI-spine readers live: `OptionsSnapshotReader`, `FuturesSnapshotReader`, `OiSpurtService`,
  `MaxPainCalculator`, `ActiveStrikeService`, `PcrHistoryService`, `OiInterpretation`, plus the
  analytics controllers exposing them over `/api/v1/market/options|futures|fii-dii|breadth|iv-*`.
- Source-of-truth specs: sibling repo `C:\Trading\ArthaYantra\StockMarketStrategyTraining`
  (HEAD `5fe9d52`, 2026-06-16) — `Options_Scalper_Siva_Consolidated_Strategy.md` (authority, §6 JSON
  is the parity spec) + `_Cheat_Sheet.md` (execution) + `_Changelog.md`. See `docs/strategy-sources.md`.

## Scope of THIS phase (owner-locked staging)

The master plan §12.10 step 7 fixes the v1 surface. **Index-option core first:**

| Build now (live signals) | Defer / gate |
|---|---|
| #1 Two-Candle, #5 Trending-OI Crossover, #6 Golden Crossover, #10 Connect-the-Dots | #3 Market Movers → **DEFER** (equity-futures, Track-1-adjacent; v1 = a daily screener, not a live signal) |
| #2 O=H/O=L, #4 Gap, #8 BTST/STBT, #9 Morning, #12 Trend-Change (compositions of #1/#5) | #7 Hero-Zero (expiry lottery) + #11-short Straddle (unlimited risk) → **gate behind SPAN + mandatory manual confirm** |

#10 (Connect-the-Dots) is the master confluence scorer — **build it first**; the other 11 are its
sub-cases.

## Engine model for `options_of_underlying` — Model A (owner-locked 2026-06-20)

The Siva scalper makes its **entry decision on the index, not the option** — RSI(3m), VWAP/VWMA/
PSAR/Supertrend, OI quadrants, breadth, VIX, basis are all index/market-level; the option is only the
vehicle, with the strike chosen mechanically (ATM±3, delta 0.6–0.7, premium band → `StrikePicker`).
**Owner clarification: the analysis chart is the index FUTURES, not spot** (the future carries the
volume the §0B VWAP/VWMA/volume gates need).

**Decision — Model A (index-driven, option in the side-channel):**

- `resolveUniverse(options_of_underlying)` → the **front index FUTURE** (reuse `futuresResolver`, the
  same front/next + roll logic as `futures_of_underlying`). The engine evaluates + charts on the
  future, so the §0B chart gates read it natively — no context-alias gymnastics.
- The OI/macro half is keyed on the **underlying index name** (e.g. `"NIFTY 50"`) via `MarketOiClient`
  (#40, done) — independent of which future is the evaluated instrument.
- At signal time (the confluence seam) `StrikePicker` picks the CE/PE from the live chain
  (`MarketOiClient.chain()`, to build); the chosen option rides the **V009 side-channel as the
  `tradeable`** (#45). The signal stays **keyed on the future**, so the engine's
  *signal-instrument == evaluated-instrument* invariant is preserved.
- **Entry is evaluated on the future; EXIT is on the option premium** → the picked option's series is
  subscribed for exit pricing.

*Rejected — Model B (evaluate the picked option, futures precedent):* fits the invariant with no new
field, but forces every index indicator through context aliases, freezes the strike at daily reload
(stale as spot drifts intraday), and evaluates the option's own thin chart — none of which is §0B.

**#41 + #43 are ONE coupled slice (do not land piecemeal):** resolving to the future *without* the
tag-gated confluence seam would let a published scalper emit on chart-only gates with no OI gate (a
mis-fire). Land together: resolver→future · tag-gate (`tags` contains `scalper`) · confluence seam
(mirror `EmissionGuard` Optional injection, consulted after the chart `EntryEvaluator` passes, before
`emitEntry`) · `chain()`→`StrikePicker` pick · tradeable on the side-channel.

## Build order (master plan §12.10) + status

0. **Provenance** — `docs/strategy-sources.md` manifest + per-strategy source tags. *(this commit)*
1. **Indicators (§12.6)** — VWMA/PSAR/SUPERTREND_LINE/rsi_band. **DONE in Phase 2** (VWMA, PSAR, 3m
   registered; `SUPERTREND_LINE` level + `rsi_band` normalize still NET-NEW — fold into P3.6 if a
   strategy needs the line-vs-VWAP cross, else the existing `SUPERTREND` direction suffices for v1).
2. **Strike resolver (§12.4)** — `StrikePicker` (pure, *this commit*) → then `options_of_underlying`
   in `SignalEngine.resolveUniverse` (P3.3). This is the single biggest NET-NEW gate; nothing emits
   without it (today it logs "strategy stays unloaded" and yields an empty universe).
3. **Gate layer + context (§12.1)** — `MarketOiClient` + `ScalperGateContext` + `ScalperGates` (P3.2/P3.4).
4. **Connect-the-Dots scorer (§12.3)** + the confluence seam in `SignalEngine` (P3.5) + the YAML docs (P3.6).
5. **Risk rails (§12.7)** (P3.8) + **execution flow (§12.5)** — SPAN sizing (P3.9) + OrderGateway (P3.10).
6. **Parity (§12.9)** — V009 side-channel fields + golden/parity fixtures (P3.7).
7. **End-to-end** — live paper-trade #1/#5/#6/#10 on the mock/live stack. *Historical scalp backtests
   are OUT of scope* (§17.5 / Phase 6) — they need §S5 ExpiryTrack historical OI, which is deferred.

## Hard constraints carried in

- **Frozen `ScoreBreakdown` (Stage-D byte parity).** OI/confluence score, dot breakdown, chosen-strike
  delta, IV-band, SPAN qty ride a **non-serialized side-channel** (like `suggested_qty` /
  `entryLevels`) or new `signals`/`scalper_signal_detail` columns — **never** inside `score_breakdown`
  jsonb. New strategy migration is a **new suffix-versioned** `strategy/V009` (V008 is the current head;
  applied migrations are checksum-locked).
- **Determinism.** Every confluence input is computed from the deterministic replay series (the OI
  snapshot at that bucket, in-JVM Black-76 greeks) — never `now()` or per-run randomness. Compute at
  entry, persist, replay recomputes byte-identically. `GoldenDeterminismTest` + `BacktestParityTest`
  enforce this; full live↔backtest parity additionally blocks on §S5 OI history.
- **Engine grammar is chart-only.** The YAML gate tree reads aliases + `close`/`volume`/`vwap` (no OI
  operands). OI/macro confluence is a **typed Java scorer that runs alongside** the engine (consulted
  after the chart `EntryEvaluator` passes, before `emitEntry`), not a new gate operator.
- **Risk limits on DB rows, never YAML** (extends `paper/RiskService` + `risk_settings`; Stage-F
  kill-switch / loss-cap / `EmissionGuard` SPI pattern).
- **OpenAlgo is consumed over its API only** (AGPL appliance, never forked). Live order placement is
  flag-gated (`artha.scalper.execution=live`), behind the human "Take", semi-auto first.

## Adversarially-verified spec deltas (DO NOT mis-port)

From the source's own `uncertain[]` flags — these are real column-misattribution traps:

- **"aim 1–2%"** is **Market Movers (#3)**'s column — NOT O=H (#2).
- **"30–50 pts / exit ~5 pts below OH"** is **O=H (#2)**'s — NOT Trending-OI (#5) or Connect-the-Dots (#10).
- **"SL 50% / close 3:20pm"** is **BTST/Hero-Zero (#7/#8)**'s — NOT O=H (#2) or Morning (#9).
- **Strategies with NO native numeric SL/target** (size off structure + VWAP, never invent a figure):
  **#5 Trending-OI**, **#3 Market Movers**, **#11 Straddle** (only "above/below VWAP"), **#12 Trend-Change**
  (only the >60 up-RSI is stated; the <40 down-RSI is an inferred mirror).
- **O=H (#2) premium bands** are the one true session-conflict: use the **S22 bands** (BN 250–550 /
  N 150–350) as primary; retain S20 (BN 250–400 / N 100–250) as the general default.
- **Active-Strike Sentiment %** basis differs from oipulse-EXACT (§18.6) — reconcile
  `100·(ΣpeΔOI−ΣceΔOI)/Σ(ceOi+peOi)` vs `(ΣPut OI−ΣCall OI)/ΣPut OI×100` **before** a gate uses it.

## Universal §0 rails (apply to every strategy)

RR 1:2, hard SL in-system. Daily loss: stop-all at 0.5%, never exceed 2–3%/day, single-day hard cap
10–12% [S24]. Intraday gates: after **09:45** (block 11:00–13:00 sideways, no fresh entry after 15:30);
volume ≥ **50k (BN/SENSEX) / 125k (N)**; **RSI(3m,14): 40–60 = NO TRADE**, CE>50 (zone 50–75), PE<50
(zone 40–25); strike **ATM±3, delta 0.6–0.7, premium N 100–250 / BN 250–400**; VIX down→CE / up→PE;
**Adv>32=CE / Dec>32=PE**. Index-scaled point SLs [S22/S24]: BN ~75 / N ~30–60 / Sensex ~200–250.

## Files (created as the phase lands)

- `strategy-signal-service/.../scalper/` — `StrikePicker`, `MarketOiClient`, `ScalperGateContext`,
  `ScalperGates`, `ConnectTheDotsScorer`, `ScalperConfluenceGate`, `ScalperAccountModel`.
- `SignalEngine` — `options_of_underlying` resolver + the confluence seam.
- `deploy/flyway/strategy/V009__scalper_signal_detail.sql`, `R__seed_scalper_strategies.sql`.
- SPAN appliance `services/marginism-service/` (Python FastAPI) + compose + gateway route.
- `docs/strategy-sources.md`, `docs/manual-tests/phase3-scalper.md`.
