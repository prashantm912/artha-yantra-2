# Scalper tunable-infra build (2b) — forward plan

**Owner goal:** make the 12 Siva scalper strategies tunable + paper-ready on the now-complete
expired-premium archive, then tune on LIVE forward paper. Status as of 2026-06-26: the keystone
blocker is fixed; the remaining build is specced below.

## Context / what's already done
- **2a proof-run DONE:** the backtest+optimizer pipeline works on the 121M-bar archive. Backtest
  sweeps overfit (Sharpe 33 on 18 trades) → backtest = functional screening; final tuning on live.
- **Fold-engine bug FIXED (PR #220, `f36213c`, merged+deployed+live-verified):** `FoldEvaluator` now
  routes options folds through `OptionsPremiumReplay` (was always the bare candle engine → 0 fold
  trades → `oos_fold_mean` always NULL). Live-verified: v1.3.0 fold sweep → 4/4 COMPLETE,
  `oos_fold_mean=942.52` (₹ mean OOS expectancy). **Trustworthy OOS backtest tuning now works.**
- **OOS tuning recipe (proven):** submit `objective={metric: oos_fold_mean}`; per-fold metric =
  `expectancy` (YAML `backtest.optimize.objective.metric`); `walk_forward` test folds long enough to
  clear `min_trades` (~30-day test, `min_trades` 10); window ≥ ~6 months (2026 H1 — calendar-bounded).
  See memory `scalper-tuning-findings`.

## Owner design decisions (LOCKED 2026-06-26)
1. **Strategies are instrument-AGNOSTIC.** Doc instruments are examples. Every scalper applies to BOTH:
   (NIFTY-fut signal → NIFTY options) and (NIFTY-fut signal → SENSEX options).
2. **The SIGNAL is computed on the NIFTY index FUTURE in BOTH pairings** — SENSEX options trade off the
   *NIFTY-future* signal (correlation play), NOT a SENSEX-index signal.
3. **Signal source = NIFTY continuous front-future 1m** (not the index proxy). Stitch from the monthly
   FUT contract candles (front/nearest-unexpired per session, roll at expiry).
4. **Build the signal/option-root decoupling FIRST, then register all 12** on NIFTY + SENSEX.
5. BankNifty dropped (now monthly-expiry); the 2 "banknifty" YAMLs are generic → re-home to NIFTY/SENSEX.

## Engine findings (the gaps to close)
- **Continuous future:** instrument `NFO | NIFTY-FUT-CONT | SYN-CONT | FUT` EXISTS in the master but has
  NO 1m candles materialized. Monthly contracts (`NIFTY30MAR26FUT` …) have dense 1m. → stitch read-time.
- **Coupling:** `OptionsPremiumReplay` resolves option legs via `registryUnderlying(underlyingTradingsymbol)`
  — the SAME symbol that drives the signal (`BacktestRunner.signalInstrument` → `universe.underlying`).
  So option-root == signal-underlying today; "signal NIFTY-fut → SENSEX options" is NOT expressible.
  Schema has no signal-underlying override.

## Remaining build (4 phases, each its own PR)

### 2b-E1 — NIFTY continuous front-future 1m signal series — DONE (#222 + fix #223, merged/deployed/live-verified: 106,560 CONT 1m bars 2025-04→2026-06, 12 rolls; fix = backfill skips wide-historical cagg refresh)
**Approach (revised from "read-time virtual"):** the backtest reads its primary 1m via a DIRECT JDBC
read (`backtest CandleReader` → `marketdata.candles WHERE tradingsymbol=? interval='1m'`), so a read-time
reader living in market-data would never be seen. AND the continuous-future infra already MATERIALIZES
(`ContinuousFuturesRoller.stitchInto` writes raw front-segment bars into `NIFTY-FUT-CONT`; `roll_events`
+ `ContBackAdjuster` give `adjust=back`). So 2b-E1 = a HISTORICAL backfill that reuses that exact loop
but sources the ladder from the FULL roster — `expired_contracts` FUT ∪ live `instruments` futures —
instead of live-only. Row cost is trivial (one series, ≤~1M bars; 3 orders below the 1.12B OOM case).
- `ContinuousFuturesRoller.stitch(ladder, underlying, today)` extracted from `rollOne` (shared by the
  live 16:15 roll and the backfill); gap calc gains a 1m fallback (`closeForRoll`) because expired
  contracts carry 1m-only bars (no native 1d) → `CandleRepository.lastIntradayClose`.
- `ContinuousFuturesBackfill` builds the union ladder (live wins on overlap) + runs `stitch`.
- `ContinuousFuturesBackfillRepository` reads the expired FUT roster (raw JDBC, no upstox-module edge).
- Admin trigger `POST /api/v1/market/admin/futures/continuous-backfill?root=NIFTY&underlying=NIFTY 50`.
- `ContinuousFuturesBackfillIntegrationTest`: front-month continuous across a roll + 1m-fallback gap +
  idempotent re-run. Existing `ContinuousFuturesIntegrationTest` (live path) still green (1d → no fallback).
Backtests read the UNADJUSTED CONT 1m directly; the one-bar roll-day basis gap (B-19) is documented +
accepted (intraday scalpers reset daily, so a monthly roll-day discontinuity is a minor known artifact).
**Run after merge:** `POST .../continuous-backfill` on the live stack to populate `NIFTY-FUT-CONT` 1m.

### 2b-E2 — decouple signal-underlying from option-execution root — BUILT (PR pending)
Done: schema `universe.signal_underlying` (optional instrumentRef) + `oi_confluence_gate.index` override;
`BacktestRunner.signalInstrument` prefers `signal_underlying`; `OptionsPremiumReplay` resolves the option
root + OI-gate index from `universe.underlying` (helpers `optionRoot`/`optionRootDisplay`/`oiGateIndex`),
NOT the signal symbol. Parity-verified byte-identical (BacktestParityTest + OptionsPremiumGoldenTest green —
absent the override, signal symbol == underlying → no behaviour change). Fold path needs no change (it
already threads config + the signal instrument). Tests: signalInstrument override + decouple helpers +
accept-corpus fixture. **OI-gate index defaults to the option-execution root**; a SENSEX-option/NIFTY-signal
strategy gates on NIFTY OI via the explicit `index` override.

Original design notes:
- Schema (`strategy-schema-v1.json`, additive optional): a signal-underlying override on the options
  universe, e.g. `universe.signal_underlying: {exchange, tradingsymbol}` (default = `universe.underlying`).
- `BacktestRunner.signalInstrument`: prefer the override for the signal series.
- `OptionsPremiumReplay.replay`: run signals on the signal-underlying series, but resolve option legs
  from `universe.underlying` (the option-execution root) — split the single `(exchange, tradingsymbol)`
  it currently uses for both. Keep the candle-close + premium goldens byte-identical (the override is
  absent in all existing configs → no behaviour change → parity holds).
- Verify: a config with signal=NIFTY-FUT-CONT, options=SENSEX backtests + trades SENSEX legs off NIFTY signal.

### 2b-1 — rewrite the 12 scalper YAMLs (instrument-agnostic, tunable)
Per strategy, 2 variants (NIFTY-options, SENSEX-options), both with `signal_underlying: NIFTY-FUT-CONT`:
- universe.underlying = NIFTY 50 (NIFTY variant) / SENSEX (SENSEX variant); `signal_underlying` = NIFTY-FUT-CONT.
- tailored `backtest.optimize` per the proven recipe (objective.metric `expectancy`, `min_trades` ~10,
  `walk_forward` train30/test30/step20, parameters = each strategy's real indicator/exit knobs).
- `oi_confluence_gate` on the OI-led ones (connect-the-dots, trending-oi, two-candle, open-high-low).
- re-home gap-theory + trend-change off BankNifty. All `validate` (POST /strategies/validate) green.

### 2b-2 — register + functional-verify
Register each variant (~24) via PUT/POST; run a FULL-WINDOW functional backtest on each (confirm it
executes + trades sanely, no engine errors); flag any strategy whose features the engine can't replay
(e.g. per-strike grading, two-leg straddle) as needing more engine work. Then → 2c paper.

### Optional — optimizer guardrail
`optimizer service.py`: when a fold-context sweep (`walkForward` present) is submitted with
`objective.metric != "oos_fold_mean"`, default it to `oos_fold_mean` + warn (so the silent-in-sample
trap can't recur). Cheap; do alongside 2b-1.

## Leftover artifact
`nifty-atm-value-verify` v1.3.0 (draft) was registered for the recipe/fold-fix validation — harmless
(draft, unpublished); keep as the OOS-recipe reference or archive later.
