# Minervini partial-close executor — build-spec (MV-7.3 / MV-7.4)

**Status:** ✅ IMPLEMENTED (PR-P, 2026-07-04) — built to this spec, `GoldenDeterminism`+`BacktestParity`
byte-identical; the paper-ledger partial close (§7) remains the one deferred, separately-gated follow-up.
· **Owner:** solo · **Companion:** the
[implementation plan](2026-07-04-minervini-sepa-implementation-plan.md) + [build log](2026-07-04-minervini-build-log.md).

The staggered-stop / scaled-out partial-close executor is the one remaining Track-B engine change that
touches the platform's **parity firewall** (the frozen golden writer + the golden-runner position loop
+ the `ReplayEngine` Trade pairing). This spec captures the exact parity-safe design from a full recon
so it can be built in a focused, supervised pass. It is deliberately NOT built unattended: the change
spans 6–7 files, and while it is parity-safe-*additive* by design, the automated parity gate
(`GoldenDeterminismTest` + `BacktestParityTest` byte-identical) must be the hard merge gate — and a
partial-close bug that slipped a golden red would corrupt the single most important invariant in the
platform. It is also **not yet needed**: the reliability bar (§0.5 #12) requires ≥30–50 forward paper
trades on a single-stop setup before scaling out is trusted, and none exist yet.

## What "partial close" means here

Two owner-requested behaviours (§0.5 #10, over-ridden to "build now"):
- **Scaled-out exit (MV-7.4, sell-into-strength):** `scaled_exit` with tiers `[{profit_pct, qty_pct}]`
  — sell `qty_pct` of the position when it is `profit_pct` in profit; the remainder keeps running
  under the stop / trail. E.g. sell ½ at +2R, trail the rest on the 50-day MA.
- **Staggered stop (MV-7.3):** partial closes at tiered stop distances — a `qty_pct` on a `stop_loss`
  rule so a first slice exits at a tighter stop and the rest at the wider one.

Both are the SAME mechanism: **an exit decision can close a FRACTION of the position, and the position
persists with a reduced remaining fraction until fully closed.**

## Why it is parity-safe-additive (the load-bearing fact)

- `GoldenSignalsJson.SignalEvent` carries `stopLoss`/`takeProfit` as a **pure side-channel** —
  `GoldenSignalsJson.write()` never serializes them. A new `qtyFraction` (and optional `tier`) field
  rides the SAME way: `write()` ignores it, so the frozen golden JSON is byte-identical.
- Existing strategies declare **no** tiers → every exit closes the full position (`fraction = 1.0`) →
  the emitted event sequence is unchanged → `GoldenDeterminismTest` (9 vectors) stays byte-identical
  and `BacktestParityTest` (Trade record-equality) is unchanged.
- New scaled/staggered strategies emit MULTIPLE partial exit events — these live in a **new**
  `golden-minervini-scaled/` fixture set, never mixed with the frozen scalper goldens.
- The *scaled tier* logic runs only through the backtest/golden runner (the 5-arg
  `ExitEvaluator.evaluate`). **Correction (adversarial review):** the LIVE `SignalEngine` ALSO calls
  `ExitEvaluator.evaluate` — the 4-arg overload — so it is made **scaled-blind** (`scaledEnabled=false`):
  a live `scaled_exit` strategy does NOT fire the scaled tiers (fail-safe, never a wrong full close)
  until Phase 9 wires a partial-position ledger. Live scalper exits still use the separate
  `PremiumBracketRules`/`PaperBracketEvaluator` chain. So the *scaled* blast radius is backtest + golden.

## Blast radius — the exact files + changes

1. **`libs/strategy-engine/.../golden/GoldenSignalsJson.java`** — `SignalEvent` += `BigDecimal qtyFraction`
   (default `ONE`) as a side-channel; `write()` UNCHANGED (never serialize it). *Parity: byte-identical.*
2. **`libs/strategy-engine/.../eval/ExitEvaluator.java`** —
   - `ExitDecision` += `qtyFraction` (default `1.0`) + `int tier` (default `-1`).
   - New `scaled_exit` branch in the precedence loop (place it AFTER `take_profit`, BEFORE `time_stop`).
     It reads `tiers`, and to avoid re-firing a tier it needs the position's already-fired tiers →
     pass a `Set<Integer> firedTiers` (or a fired-bitmask) into `evaluate`. On the first not-yet-fired
     tier whose `profit_pct` is met, return a partial `ExitDecision(qty_pct-as-fraction, tierId)`.
   - `qty_pct` on a `stop_loss` rule → a partial full-priority close at that stop (staggered).
   - Determinism: tier thresholds are static config; profit measured off `favorableExtreme` /
     `close` exactly like the existing level math — no per-run state.
3. **`libs/strategy-engine/.../golden/TickwiseGoldenRunner.java`** —
   - `OpenPosition` += `BigDecimal remainingFraction` + `Set<Integer> firedTiers`.
   - Extract the 3 duplicated exit sites (coarse-primary ~L165, intrabar ~L197, 1m ~L237) to ONE
     `applyExit(open, decision, at, events)` helper: emit an `exitEvent` carrying `decision.qtyFraction`,
     subtract it from `remainingFraction`, add `decision.tier` to `firedTiers`; return `null` when the
     remainder reaches ~0 (full close, identical to today) else the reduced `OpenPosition`. A full
     `fraction = 1.0` decision emits the same single event + returns null → **byte-identical** for
     existing strategies.
   - `exitEvent(...)` passes the fraction through (default `ONE`).
4. **`libs/strategy-schema/.../strategy-schema-v1.json`** — add the `scaled_exit` exit-rule shape
   (`tiers: [{profit_pct, qty_pct}]`, both `number`, `qty_pct` in (0,1]) + allow optional `qty_pct` on
   `stop_loss`. Additive enum/shape → no existing document invalidated. Recapture NOT needed (engine
   schema, not springdoc).
5. **`services/backtest-service/.../replay/ReplayEngine.java`** — the entry↔exit **leg pairing**
   (~L99–183) currently assumes one entry ↔ one exit. Make it fraction-aware: one entry pairs with N
   partial exit events → N `Trade` rows, each `qty = round(totalQty · fraction)` (allocate the last leg
   the residual so the fractions sum exactly), the position stays open (`posSign != 0`) until the final
   leg. A single `fraction = 1.0` exit → one full-qty `Trade`, **unchanged** → `BacktestParityTest` holds.
6. **New fixtures + tests** — `golden-minervini-scaled/` byte-freeze for a scaled setup +
   `ScaledExitTest` (engine: N partial exit events on the tier path; determinism) + a
   `ScaledBacktestTest` (backtest: N fractional Trades summing to the whole).
7. **(Deferred, separate PR) paper ledger** — `PaperPositions` does full closes today; a live scaled
   exit needs partial-close support in `PaperBracketEvaluator` + the paper ledger, and the
   `contracts/fixtures/exit-equivalence.json` fixture + its THREE suites (`PremiumExitEquivalenceTest`,
   `PaperBracketEquivalenceTest`, `PremiumBracketEquivalenceTest`) must be updated in the SAME PR (the
   pinned live/backtest exit-equivalence rule). Keep this OUT of the engine/backtest PR — it is the
   live-money surface and gates on Phase 9 + owner sign-off.

## Hard merge gate

`GoldenDeterminismTest` (9) **and** `BacktestParityTest` byte-identical, `strategy-engine` +
`strategy-schema` full green, plus the new scaled fixtures/tests. If any frozen vector moves, do NOT
merge — the design guarantees they must not.

## Sequencing note

Build order: schema shape → `SignalEvent`/`ExitDecision` side-channel fields → `ExitEvaluator`
`scaled_exit` + firedTiers → runner `applyExit` + `OpenPosition` state → new engine fixture/test
(prove partials + parity) → `ReplayEngine` fractional legs + backtest test. The paper-ledger partial
close is a later, separately-gated PR.
