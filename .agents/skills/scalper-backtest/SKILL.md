---
name: scalper-backtest
description: Use when running an intraday/scalper strategy backtest or an optimizer sweep through the job-based pipeline (backtest-service /api/v1/backtests + optimizer-service /api/v1/optimizations) — submission identity, auto-warm, result keying, and how to judge the output honestly.
---

# scalper-backtest

The job-based pipeline for registry strategies (scalpers, premium-replay). Distinct from
the swing sims ([swing-backtest]). Runs through the gateway (auth required —
[run-artha-yantra] has the login/XSRF flow) or, for reads, the socat sidecars.

## Submit

`POST /api/v1/backtests` essentials:
- **`strategyId` = the registry UUID, NOT the slug.** Omit `strategyVersion` → pins the
  latest **published**, else latest draft.
- Window must sit inside the bundled market-calendar years (**currently 2024–2026**) —
  outside 500s with "NSE holiday calendar covers years […]". A horizon-canary test goes
  red ~45 days before coverage ends.
- Submission + worker **auto-warm** primary 1m + benchmark (+contexts) cache-first, so a
  fresh window no longer 422s; the regime pre-flight still needs ~272 daily `NIFTY 50`
  sessions — on a cold mock stack backfill 1d first (see [mock-walk] §3).
- Mock-stack candle data is rolling-from-boot: **derive a recent covered window, never
  hardcode dates**.

## Poll + read

- Terminal job status string is **`completed`**.
- Results/trades/folds/montecarlo are keyed by the **run id = the job's `resultRef`**,
  NOT the jobId: `backtest_runs` (`total_return`, `trade_count`) + `backtest_trades`.

## Optimizer sweeps (Python service)

`POST /api/v1/optimizations/run` (optimizer-service, not backtest-service):
- The strategy YAML must carry a `backtest.optimize` block (`method` + `max_trials` +
  `objective` + `parameters`, all required) else 422.
- **`walkForward` + `objective` + `maxTrials` come from the REQUEST body, not the YAML** —
  OOS fold tuning needs `objective:{metric: oos_fold_mean}` + a `walkForward:{train_days,
  test_days,step_days}` block in the request (YAML-only `walk_forward` runs a plain sweep
  with empty OOS folds).

## Judging the output (the honesty rules)

- **Armed live-gates ≈ 0 backtest trades is a data artifact, not a verdict** — the
  backtest never runs the live OI-confluence gate faithfully; derived-history OI mutes
  Dow+IV to NEUTRAL. Judge OI-led strategies on FORWARD paper with real captured OI.
- `backtest.relax_session` is the "get trades at all" lever for functional verification.
- **Backtest-tuned scalper params OVERFIT** (proven at scale) — the pipeline is for
  functional verification; TUNE ON LIVE via the rejection-forensics loop
  ([session-analysis], `docs/signal-analysis/`).
- 3m primaries are a read-time 1m→3m rollup — valid in both the live engine and the
  tick-wise runner; there is no `candles_3m` cagg.
