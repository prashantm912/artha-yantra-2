# ArthaYantra 2.0 — Stage D: Backtesting + Optimization

**Stage letter / name:** D — Backtesting + optimization
**Plan macro-phase:** Phase 3 ("Backtesting + optimization")
**Phases covered:** 28–34 **+ 30A, 32A** (backtest-service jobs spine → FillSimulator + cost model → replay engine + parity → **options replay fidelity contract + synthetic premium mode (30A)** → walk-forward folds → regime attribution + stress guard → **benchmark-relative metrics + Monte Carlo run analytics (32A)** → optimizer core → optimizer TPE/NSGA-II + promote). A suffixed phase runs immediately after its base number: 30, 30A, 31, 32, 32A, 33 [FP-4, FP-31, FP-32, owner selection 2026-06-12].
**Prerequisite stages:** A (Foundations — compose, Flyway/schemas/roles, gateway WS bridge, CI), B (Market data spine — `candles` hypertable + caggs + `options_chain_snapshots`, read-only `marketdata` access for backtest-service, `MarketCalendar`, aggregated system status), C (Strategy engine MVP — the `strategy-engine` JAR with indicators/gates/composite scoring/score-breakdown contract, `strategy-schema/v1` frozen, registry CRUD + immutable versions + `/diff`, `index_constituents` accrual)
**Common reference:** [ARTHAYANTRA_2_COMMON_REFERENCE.md](ARTHAYANTRA_2_COMMON_REFERENCE.md) — app-wide conventions, ADR D1–D18 + amendments A1–A13, stack-version table, error-code taxonomy, repo layout, phase index, timeline, CD-1..CD-17 defaults.

**Stage goal (one paragraph).** Turn the frozen, parity-grade `strategy-engine` JAR from Stage C into a full backtesting and optimization platform. backtest-service gains an authoritative Postgres `jobs` table with crash-safe Redis-Streams dispatch, a bounded worker pool, and a real bar-by-bar replay engine that reads candles read-only from `marketdata`, runs them through the *same* engine JAR as live, and proves byte-identical results via the live-vs-replay parity golden test — the architecture's core promise. On top of the replay engine sit the anti-overfitting controls (walk-forward folds, computed T−1 regime attribution, `fold_aggregation`, the `sharpe_degradation` diagnostic, and the stress-test contamination guard) and the deterministic `FillSimulator` + full cost model shared by replay and the paper ledger. optimizer-service (Python/FastAPI/Optuna) then drives grid/random/TPE/NSGA-II sweeps over `optimize.parameters`, fold-fed MedianPruner early stopping, a plateau-adjusted leaderboard, and winner promotion to a new strategy draft via REST. Stage exit = the plan §15.2 Phase-3 row: `POST /backtests/run → 202 {jobId}` with WS progress, the engine-parity test green, and a 200-trial sweep completing and ranking configs. **2026-06-12 owner-selection additions:** the stage additionally carries Phase **30A** (options replay fidelity contract + synthetic premium mode, ADR A10 [FP-4]) and Phase **32A** (benchmark-relative metrics + Monte Carlo run analytics [FP-31, FP-32]), plus the A9 execution-semantics extensions to Phases 29/30 — futures cost legs [FP-7], `at_close` fills [FP-6], intra-bar exit-touch detection with `touch_basis` [FP-5], the BTST pre-close bar view [FP-6] — and the extended pre-flight (context instruments [FP-19], corporate-action warning [FP-1], lot-size as-of [FP-3]); all such content below is tagged `[FP-N, owner selection 2026-06-12]`.

> **Named de-scope unit.** The anti-overfitting block — **S1A (regime attribution) + S1B (`sharpe_degradation`) + S1C (stress test) + BPC (fold reporting) + S8 universe-pinning** — is **one** §15.6 lever-1 de-scope unit (~9–10 d, Phases 3–5). Its items cross-reference each other (guards 6/7, `fold_metrics`, the publish-dialog badge) and are pulled together or not at all, riding the same lever as deferring optimizer-service entirely. `regimeMix` is the one nullable seam allowing BPC's fold reporting to survive alone if the block is ever cut. See [Design reference §D.10](#d10-de-scope-unit--§156-lever-1).

---

## Part 1 — Design reference (inlined source content for Stage D)

Everything Stage D needs at implementation time is inlined here, organized by topic with source breadcrumbs. App-wide material (ADR decision tables, stack versions, error-code taxonomy families, repo layout, the global conventions in phases-doc §0.3, the CD-1..CD-17 defaults) lives in [COMMON](ARTHAYANTRA_2_COMMON_REFERENCE.md) and is cited rather than re-copied.

### D.1 — Service specs: backtest-service & optimizer-service [plan §5.2.4, §5.2.5; ADR D7]

**backtest-service** (Java 21, Boot 3.5.x + `strategy-engine` JAR, `mem_limit: 896m` — sweep burst; ~400 MB idle; internal port 8083). Ships **without** Spring Modulith — it is a single-purpose engine, following the D7 row's plain "Boot 3.5.x"; D6's "all always-on services" is read as not mandating module seams here ([COMMON CD-17](ARTHAYANTRA_2_COMMON_REFERENCE.md#4-chosen-by-default-decisions-cd-1cd-17-revisable-flagged-per-the-prompts-rule)).

- **Responsibilities:** the **backtest engine** — bounded worker pool (`cores − 2` platform workers; virtual threads for IO) replaying cached candles and chain snapshots through the *same* `strategy-engine` JAR as live (golden-vector parity, [§D.8](#d8-testing--golden-vectors-plan-103-104-106-107-108)); persists trades + metrics (returns, Sharpe, max drawdown, win rate, trade count); executes optimizer trial jobs from Redis Streams.
- **Owned data:** PG schema `backtest` (`jobs`, `backtest_runs`, `backtest_trades`, `optimization_trials`); **read-only** grant on `marketdata` (single-writer rule, D10) — replays read candles directly, never over REST.
- **Events:** consumes Streams `jobs.backtest` (single-run dispatch, consumer group `cg-backtest`) and `jobs.backtest.trials` (optimizer trial fan-out, consumer group `cg-trials`); publishes `jobs.progress` (pub/sub) and Stream `optimizations.results` (trial metrics for the optimizer).
- **Endpoints:** `/api/v1/backtests/run` (202 + jobId), `/api/v1/backtests/jobs/{jobId}` (status/progress/cancel), `/api/v1/backtests/{id}/results`, comparison listings — full endpoint table in [§D.5](#d5-backtest--optimization-job-design-plan-74).

**optimizer-service** (Python 3.12, FastAPI 0.115 + Optuna 4.x, `mem_limit: 256m`; internal port 8084). The single pre-approved Python deviation (D6) — Optuna's samplers are not worth re-writing in Java. The optimizer **never evaluates a strategy itself** — it only proposes parameter vectors; all evaluation runs in backtest-service via the shared JAR (D6).

- **Responsibilities:** parameter sweeps over a strategy version's `backtest.optimize` block — grid, random, TPE, NSGA-II; Optuna ask/tell loop proposing parameter vectors, dispatching each as a trial job to backtest-service, telling results back; median-pruner early stopping; writes sweep leaderboards; promotes a winning parameter set back to strategy-signal-service as a **new draft version** (never auto-published).
- **Owned data:** PG schema `backtest` (`optimization_trials` — co-located with the results it ranks; the `jobs` table is shared, backtest-service is the writer of TRIAL/BACKTEST rows, optimizer writes OPTIMIZATION/TRIAL parent+child rows per [§D.7](#d7-optimizer-execution-model-plan-74-flow-5)).
- **Events:** publishes Stream `jobs.backtest.trials` (trial jobs, tagged with parent sweep id; consumed by backtest-service group `cg-trials`); consumes Stream `optimizations.results` (consumer group `cg-optuna`) for the ask/tell loop; publishes `jobs.progress` for sweep-level progress.
- **Endpoints:** `/api/v1/optimizations/run`, `/api/v1/optimizations/{sweepId}` (status, trial leaderboard), `/api/v1/optimizations/{sweepId}/promote` — full endpoint table in [§D.5](#d5-backtest--optimization-job-design-plan-74).
- **Dependency management (CD-15):** `requirements.txt` with hashes + plain `pip`; ruff for lint/format ([COMMON CD-15](ARTHAYANTRA_2_COMMON_REFERENCE.md#4-chosen-by-default-decisions-cd-1cd-17-revisable-flagged-per-the-prompts-rule)).

### D.2 — Async jobs & scheduling: the jobs spine [plan §5.8; ADR D12]

Per ADR D12, the Postgres `jobs` table is the **single source of truth** — `queued → running → completed | failed | cancelled`, `progress` 0–100, `parent_job_id` for optimizer trials, payload/result refs as JSONB. Redis Streams (canonical names: `jobs.backtest` with consumer group `cg-backtest` for single runs; `jobs.backtest.trials` with group `cg-trials` for optimizer trial fan-out; `optimizations.results` read by the optimizer's group `cg-optuna`) are **transport only**, at-least-once; workers claim idempotently against the table, and stale `running` rows are re-queued on service startup, so a mid-sweep `docker compose restart` loses nothing. Progress updates write the table and publish `jobs.progress` → gateway WS → live progress bars.

**At-least-once reconciliation against the table.** A worker claiming a Stream message first does a conditional `UPDATE jobs SET status='running' WHERE id=? AND status='queued'`; **losing that race acks and drops the duplicate** (`XACK` then discard). `XACK` happens **only on a terminal state**. `XAUTOCLAIM` on startup re-claims pending entries; any `running` row whose owning worker vanished is re-queued.

**Bounded pool & virtual threads (D12).** backtest-service runs the bounded pool of `max(1, cores − 2)` **platform** threads for CPU-bound replay; **virtual threads** are used only for IO. The optimizer-service drives Optuna ask/tell with pruning.

**Scheduled work** uses Spring `@Scheduled` in the owning service (no scheduler container), all consulting one shared `MarketCalendar` library (09:15–15:30 IST Mon–Fri + NSE holidays). Stage D adds **one scheduled job of its own** — the monthly stale-terminal-jobs pruning task in backtest-service ([§D.3](#d3-backtest-schema-pg-schema-backtest--full-column-tables-plan-64) stale-job hygiene, plan §6.5) — plus the stale-`running` re-queue on boot as backtest-service's only other "schedule-like" behavior; the market-data schedules (08:30 sync, 5-min snapshots, token health, EOD backfill) and strategy-signal schedules belong to Stages B/C.

> Cross-ref: the §5.8 job-submission endpoint bodies and result payloads are specified in [§D.5](#d5-backtest--optimization-job-design-plan-74) (was "Section 7" in the source).

### D.3 — Backtest schema (PG schema `backtest`) — full column tables [plan §6.4]

All money math in `NUMERIC`/`BigDecimal`; all timestamps `TIMESTAMPTZ` normalized to `Asia/Kolkata`. The `backtest` role holds **read-only** on `marketdata` and read-write on `backtest` ([COMMON CD-1](ARTHAYANTRA_2_COMMON_REFERENCE.md#4-chosen-by-default-decisions-cd-1cd-17-revisable-flagged-per-the-prompts-rule) — the admin Flyway lineage creates roles/grants).

#### `jobs` — one authoritative table, discriminated by `kind`

The spec's *backtest_jobs* and *optimization_jobs* realized as **one authoritative table** (ADR D12: Postgres is truth, Redis Streams is transport).

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | The `jobId` in every `202` response |
| `kind` | TEXT | BACKTEST / OPTIMIZATION / TRIAL |
| `parent_job_id` | UUID NULL self-FK | Sweep id for trials |
| `status` | TEXT | queued / running / completed / failed / cancelled (CHECK) |
| `progress` | SMALLINT 0–100 | Streamed to `jobs.progress` → gateway WS |
| `strategy_version_id` | UUID | Soft cross-schema reference (no FK — strategy lives in the `strategy` schema) |
| `request` | JSONB | Symbol(s), interval, date range, method, max_trials, overrides, resolved universe copy, `purpose` |
| `error` | TEXT | Populated on failure |
| `worker_id` | TEXT | For stale-`running` re-queue on startup |
| `created_at`, `started_at`, `finished_at` | TIMESTAMPTZ | |

Indexing (Phase 28): a **partial index on live statuses** (`(status, created_at) WHERE status IN ('queued','running')`) for the claim/re-queue scans, plus an index on `(parent_job_id)` for sweep→trial rollups.

**Stale-job hygiene [plan §6.5]:** `jobs` rows in terminal states older than 180 days are pruned by a monthly `@Scheduled` task in backtest-service *only if* no `backtest_runs` row references them; runs/trades/trials themselves are kept indefinitely (they are the research record).

#### `backtest_runs` — one row per completed engine replay

One row per completed engine replay (a standalone backtest or one trial's run).

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `job_id` | FK → `jobs` | |
| `strategy_version_id` | UUID | Soft reference |
| `exchange`, `tradingsymbol`, `interval` | TEXT | |
| `start_ts`, `end_ts` | TIMESTAMPTZ | |
| `initial_equity`, `final_equity` | NUMERIC(18,2) | |
| `params_override` | JSONB | The trial's sampled parameter values |
| `seed` | BIGINT | RNG seed used by the replay (recorded even when no stochastic component ran) — leg 2 of [§D.6](#d6-backtest-execution-model--reproducibility-plan-74)'s reproducibility triple; echoed as `seed` by `GET /api/v1/backtests/{id}/results` |
| `data_hash` | TEXT | SHA-256 over the ordered tuple set (instrument keys, interval, from/to, bar count, max `fetched_at`) of the candles actually read — leg 3 of the triple; returned as `dataHash` and the key for cross-sweep like-for-like comparison ([§D.9](#d9-metrics-catalog-leaderboard--promotion-plan-76)); mismatched rows are flagged on the leaderboard |
| `total_return`, `sharpe`, `sortino`, `max_drawdown`, `win_rate`, `profit_factor` | NUMERIC | The D15/D7 comparison metric set |
| `trade_count` | INT | |
| `sharpe_degradation` | NUMERIC NULL | `train_sharpe − oos_sharpe` when a train/test structure exists (guard 7, [§D.4](#d4-overfitting-guards-17-plan-75)) — a **difference, not a ratio**, stable for zero/negative Sharpes; NULL for plain full-window backtests |
| `fold_metrics` | JSONB NULL | When `walk_forward` ran: ordered array of `{fold, train:{from,to}, test:{from,to}, trainMetrics, oosMetrics, regimeMix}` (≤ ~25 folds; regime mix per guard 6; `regimeMix` nullable until Phase 32); serves the fold-details endpoints |
| `oos_fold_mean`, `oos_fold_std` | NUMERIC NULL | Across-fold OOS objective mean and dispersion — guard 2's fold-dispersion report in queryable, sortable form |
| `universe_checksum` | TEXT NULL | SHA-256 over the ordered resolved universe copied into `jobs.request` at submission ([§D.6](#d6-backtest-execution-model--reproducibility-plan-74) universe pinning); NULL for explicit/single-instrument runs where `data_hash` alone suffices |
| `premium_source` | TEXT NULL | `SNAPSHOT \| SYNTHETIC_B76 \| NA` — options-premium provenance ([§D.15](#d15-options-replay-fidelity-contract--synthetic-premium-mode-adr-a10-fp-4-owner-selection-2026-06-12)); leaderboard/compare views flag mixed-`premium_source` comparisons exactly like `data_hash` mismatches; NULL until Phase 30A populates it [FP-4, owner selection 2026-06-12] |
| `alpha`, `beta`, `information_ratio`, `excess_cagr` | NUMERIC NULL | The benchmark-relative metric set vs `backtest.defaults.benchmark` ([§D.16](#d16-run-analytics-benchmark-relative-metrics--monte-carlo-fp-31-fp-32-owner-selection-2026-06-12)); NULL — flagged, never silently zero — when benchmark coverage is absent; populated from Phase 32A [FP-32, owner selection 2026-06-12] |
| `benchmark_curve` | JSONB NULL | Downsampled (~500 points, same downsampler as `equity_curve`) benchmark buy-and-hold curve over the run window, returned beside `equityCurve` for overlay [FP-32, owner selection 2026-06-12] |
| `montecarlo_summary` | JSONB NULL | Persisted Monte Carlo summary — equity bands (5/50/95), drawdown distribution, risk-of-ruin, CIs on CAGR/Sharpe, plus the **MC seed recorded beside the run seed**; computed on demand via `GET /api/v1/backtests/{id}/montecarlo` ([§D.16](#d16-run-analytics-benchmark-relative-metrics--monte-carlo-fp-31-fp-32-owner-selection-2026-06-12)) [FP-31, owner selection 2026-06-12] |
| `equity_curve` | JSONB | Downsampled (~500 points) for lightweight-charts; full curve recomputable |
| `engine_version` | TEXT | Golden-vector traceability |
| `completed_at` | TIMESTAMPTZ | |

Leg 1 of the triple, `strategyChecksum`, is **not duplicated** here: it resolves through `strategy_version_id → strategy.strategy_versions.checksum` (immutable by design). Same `(strategyChecksum, seed, data_hash)` ⇒ byte-identical trade list ([§D.6](#d6-backtest-execution-model--reproducibility-plan-74)).

> **Migration note (Phase 30 creates the full column set).** `fold_metrics`, `oos_fold_mean/std`, `sharpe_degradation`, `universe_checksum` are all created **NULL-able in Phase 30's `V003__runs_trades.sql`** even though their writers land in Phases 31/32/Stage F — additive-first (D17), so no later ALTER churns the schema. Phases 31/32 only populate them. The 2026-06-12 owner-selection columns ride the **same V003 pattern**: `premium_source`, `alpha`, `beta`, `information_ratio`, `excess_cagr`, `benchmark_curve`, `montecarlo_summary` on `backtest_runs` and `touch_basis` on `backtest_trades` are created NULL-able in the same migration; their writers land in Phase 30 itself (`touch_basis`), Phase 30A (`premium_source`) and Phase 32A (the analytics columns) [FP-4, FP-5, FP-31, FP-32, owner selection 2026-06-12].

#### `backtest_trades`

`id BIGINT PK, run_id FK → backtest_runs, seq INT, side, qty, entry_ts, entry_price NUMERIC, exit_ts, exit_price NUMERIC, pnl NUMERIC, pnl_pct NUMERIC, exit_reason TEXT, bars_held INT, touch_basis TEXT NULL`. Replaces v1's lossy `results JSONB` blob — trades become queryable rows. `touch_basis` records how the trade's exit level was detected — `CLOSE_EVAL | INTRABAR_1M | BAR_HL_WORSTOF` — per the [§D.11](#d11-fillsimulator-port--full-cost-model-q1-plan-71) intra-bar exit-touch rule (A9) [FP-5, owner selection 2026-06-12]. Per-trade indicator contributions are returned on the trades endpoint; the **entry-day regime tag** (Phase 32) is persisted with the per-fold metrics rather than on every trade row except as part of `fold_metrics`.

#### `optimization_trials` — app-managed

Optuna 4.x runs ask/tell with **in-memory** storage per sweep; persisting its ~15-table RDB schema into our migrations is avoided, and sweeps resume by replaying this table back through `study.add_trial`.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `sweep_job_id` | FK → `jobs` | |
| `trial_number` | INT | UNIQUE `(sweep_job_id, trial_number)` |
| `params` | JSONB | Sampled values keyed by `optimize.parameters[].path` |
| `objective_values` | JSONB | Multi-objective (NSGA-II) safe |
| `state` | TEXT | COMPLETE / PRUNED / FAILED |
| `backtest_run_id` | UUID NULL | Links to the run that scored it |
| `started_at`, `completed_at` | TIMESTAMPTZ | |

#### ER overview (Stage-D-relevant edges) [plan §6.4]

```
STRATEGY_VERSIONS ||..o{ JOBS : "tested by (cross-schema soft)"
JOBS ||--o{ JOBS : "sweep parent"
JOBS ||--o{ BACKTEST_RUNS : produces
BACKTEST_RUNS ||--o{ BACKTEST_TRADES : contains
JOBS ||--o{ OPTIMIZATION_TRIALS : "sweep yields"
OPTIMIZATION_TRIALS |o..|| BACKTEST_RUNS : "scored by"
```

All cross-schema references to `strategy.*` are **soft** (no FK) per the §6.2 no-cross-schema-FK rule.

#### Indexing strategy (backtest schema) [plan §6.7]

| Table | Index | Serves |
|---|---|---|
| `jobs` | Partial `(status, created_at) WHERE status IN ('queued','running')`; `(parent_job_id)` | Worker pickup + stale-job re-queue; sweep drill-down |
| `backtest_runs` | `(job_id)`; `(strategy_version_id, completed_at DESC)`; `(sharpe DESC)` | Results page; "best runs per strategy" leaderboard |
| `backtest_trades` | `(run_id, seq)` | Trade-list rendering, pagination |
| `optimization_trials` | unique `(sweep_job_id, trial_number)`; `(sweep_job_id, state)` | ECharts heatmap/parallel-coordinates pulls a whole sweep |

The `jobs` indexes land in Phase 28 (`backtest/V002__jobs.sql`); `backtest_runs`/`backtest_trades` in Phase 30 (`backtest/V003__runs_trades.sql`); `optimization_trials` in Phase 33 (`backtest/V004__optimization_trials.sql`).

### D.4 — Overfitting guards 1–7 [plan §7.5; review S1A, S1B, S1C, BPC]

All guards are configurable in the strategy's `optimize` block and **on by default for sweeps**. Guards 6–7, the stress-test runs ([§D.6](#d6-backtest-execution-model--reproducibility-plan-74)), and the fold-reporting endpoints/panel together form the single [§D.10 de-scope unit](#d10-de-scope-unit--§156-lever-1).

1. **Train/test split.** Without `walk_forward`, a 70/30 chronological split is implicit: parameters are scored on train, the reported headline objective is the **test** value; both are stored (`metrics` vs `oosMetrics`). **Scope pin (Phase 31):** the implicit 70/30 split applies **only to optimization/TRIAL-context and stress runs** — plain `POST /backtests/run` jobs stay **full-window** with `sharpe_degradation`/`fold_metrics` NULL.
2. **Walk-forward windows.** `walk_forward: {train_days, test_days, step_days, anchored}` evaluates each trial as a sequence of rolling (or anchored/expanding) folds; the objective is the **mean of out-of-sample fold objectives**, and the fold dispersion is reported so the owner can see strategies that only worked in one regime. Folds also feed the pruner — a trial whose first folds are below the running median is stopped early.
3. **Minimum trade count.** `constraints.min_trades` (default 30) marks under-trading trials/folds **invalid** — a 3-trade "Sharpe 4.0" never tops a leaderboard, and is never ranked.
4. **Parameter plateau preference.** After the sweep, the leaderboard's default sort is **plateau-adjusted objective**: each top-K trial is re-scored as the **median objective of its parameter-space neighbors** (within ±1 grid step or a normalized ε-ball for continuous params, using already-computed trials — no extra backtests). A sharp spike surrounded by mediocrity sinks; a broad ridge rises. Raw-objective sort remains one click away (one param away in the API).
5. **Pareto framing for nsga2.** Multi-objective sweeps **never collapse to a single number**; the owner picks from the Pareto front with drawdown explicitly on the axis.
6. **Regime attribution (computed, never declared)** — review **S1A**. backtest-service derives daily regime labels from a configurable benchmark index (`backtest.defaults.benchmark`, default `NSE:NIFTY 50`), read from `marketdata` read-only (D10): **trend = close vs 200-day SMA** (up/down) × **volatility = 20-day realized vol vs its trailing 1-year median** (quiet/turbulent) — **four labels**, deterministic functions of persisted candles. The label for day T is computed from benchmark data **through the prior session's close (T−1)** — never the entry day's own close (same-session look-ahead). The pre-flight coverage check covers the benchmark series **including warm-up depth** (~200 trading days for the trend label, ~1 year + 20 days for the volatility label) → 422 if missing; labels never silently degrade to partial coverage. Every walk-forward OOS fold records its `regimeMix`; every closed trade is tagged with its entry-day regime (persisted with per-fold metrics). The leaderboard reports **per-regime OOS performance** and flags sweeps whose data window never contained a regime — robustness may only be claimed across regimes actually tested. The schema carries **no hand-labeled date ranges and no regime-filter expressions** (the D18 grammar stays closed). `optimize.objective` accepts an optional `fold_aggregation: mean | min | mean_minus_std` (default `mean`) over OOS fold objectives: `min` is the conservative worst-fold (maximin) choice. Folds failing `min_trades` are **excluded from `min`** (the minimum of small-sample Sharpes is noise) and any such exclusion surfaces as an explicit **"n folds excluded" flag**, never a silent drop. **Pruned trials** are likewise excluded from `min`/`mean_minus_std` aggregations and from cross-trial fold comparisons — flagged **partial-coverage** rather than compared on truncated fold sets. `objective.fold_aggregation` is part of `strategy-schema/v1` **at the Phase-2 (Stage C) freeze** — validated from day one; its backend consumer lands here in Phase 32. (A regime-robustness objective for NSGA-II is deferred until the S3 spike characterizes objective noise — in v1, regimes are **reported, not optimized**.)
   - *Rejected S1A mechanisms (do not implement):* hand-labeled calendar "regimes" (a `2024-H1=trending_up` label spans the June-2024 election crash), a `market_regime_filter` expression DSL the D18 grammar excludes, and a `multi_regime` mode whose named partitions have no train/test split (in-sample by construction). `trial.suggest_objective(...)` and a two-argument `atr(14,...)` are fabricated APIs; the `min/max(fold Sharpe)` robustness score classifies two all-negative folds as "robust".
7. **Train→OOS degradation diagnostic** — review **S1B**. Every run/trial with a train/test structure persists `sharpe_degradation = train_sharpe − oos_sharpe` (a **difference, not a ratio** — stable for zero and negative Sharpes; across-fold means under walk-forward). The leaderboard renders it as a **traffic-light badge**: **< 0.3 consistent / 0.3–1.0 degrades / > 1.0 distrust**, **suppressed** with an **"n/a — weak train signal"** state when `train_sharpe < 0.5` or the trial is invalid under `min_trades`. **Display-only: it never feeds the pruner** — the fold-fed MedianPruner on OOS fold objectives (guard 2) remains the **sole** early-stopping mechanism, because the objective *is* the OOS value and divergence is a diagnostic, not an objective. The rejected S1B `OverfitIndex = (train − oos)/train` divides by zero at train Sharpe 0 and sign-flips for negatives; "prune at first sign of train/OOS divergence" is rejected outright (it kills still-leading trials and keeps uniformly-bad ones).

**S1C — pre-publication stress test** (the stress-test mechanism itself is specified in [§D.6](#d6-backtest-execution-model--reproducibility-plan-74) "Stress-test runs"). Design posture, near-verbatim from the review disposition: a stress test is an **ordinary backtest job tagged `purpose: stress_test`**; on submission backtest-service validates the window against **all prior jobs of the strategy lineage — sweeps *and* manual backtests** (manual quick-backtests leak information exactly as sweeps do) and refuses the label (**422 `WINDOW_CONTAMINATED`**) on overlap. Enforcement against the owner is impossible (the human is the leak), so the posture is **advisory-plus-honest-accounting**: the publish dialog (Stage E) **warns, never blocks**, shows the latest stress result with the guard-7 degradation badge, **counts holdout reuse** ("3rd stress test against this window — treat as contaminated"), and **acknowledges the clone-launder limitation** (re-creating a strategy under a new id resets lineage). Runs below `min_trades` render **"insufficient sample"**, never pass/fail.

### D.5 — Backtest & optimization job design: endpoints + state machine [plan §7.4]

#### Endpoints

| Method | Path | Body / Query | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/backtests/run` | `{strategyId, strategyVersion?, from, to, interval?, universeOverride?, initialCapital?, costs?, seed?, purpose?}` | 202 `{jobId, status:"queued"}` | 400, 404 strategy, **422 `DATA_GAP`** (cache coverage check fails — see error-pin below), 422 `WINDOW_CONTAMINATED` (stress only) |
| GET | `/api/v1/backtests/jobs` | `?status=&strategyId=&limit&offset` | 200 paged job list | — |
| GET | `/api/v1/backtests/jobs/{jobId}` | — | 200 `{jobId, status, progress:0-100, startedAt?, finishedAt?, error?, resultRef?}` | 404 |
| DELETE | `/api/v1/backtests/jobs/{jobId}` | — | 202 `{status:"cancelling"}` (running) or 204 (still queued) | 404, 409 already terminal (`CONFLICT_JOB_TERMINAL`) |
| GET | `/api/v1/backtests/{backtestId}/results` | — | 200 `{metrics{…§D.9}, equityCurve[], drawdownCurve[], dataHash, seed, strategyChecksum}` | 404 |
| GET | `/api/v1/backtests/{backtestId}/trades` | `?limit&offset` | 200 paged trades incl. per-trade indicator contributions | 404 |
| GET | `/api/v1/backtests/{backtestId}/folds` | — | 200 per-fold train/OOS metric array incl. regime mix where available (guard 6; empty when run had no `walk_forward`) | 404 |
| GET | `/api/v1/backtests/{backtestId}/montecarlo` | `?n=&seed=` (defaults N=1000, fresh recorded seed) | 200 `{mcSeed, n, equityBands{p5,p50,p95}, drawdownDistribution, riskOfRuin, ci{cagr,sharpe}}` — persisted to `backtest_runs.montecarlo_summary` on first computation and served from there on repeat calls ([§D.16](#d16-run-analytics-benchmark-relative-metrics--monte-carlo-fp-31-fp-32-owner-selection-2026-06-12)) [FP-31, owner selection 2026-06-12] | 404, 422 no closed trades to resample |
| GET | `/api/v1/backtests/stress-window` | `?strategyId=` | 200 suggested clean window `{from, to}` (day after the latest tested `to`, through the latest cached candle) | 404 |
| POST | `/api/v1/optimizations/run` | `{strategyId, strategyVersion, parameters? (override optimize block), method: grid\|random\|tpe\|nsga2, maxTrials, objective, constraints?, walkForward?, seed?, earlyStopping?: true}` | 202 `{jobId}` (sweep id) | 400, 404, 422 no tunable parameters |
| GET | `/api/v1/optimizations/jobs/{jobId}` | — | 200 `{status, progress, trialsCompleted, trialsTotal, bestSoFar:{trialId, params, objectiveValue}}` | 404 |
| GET | `/api/v1/optimizations/{sweepId}/trials` | `?sort=objective&order=desc&limit&offset&state=` | 200 paged `[{trialId, params, state, metrics, oosMetrics?}]` | 404 |
| GET | `/api/v1/optimizations/{sweepId}/trials/{trialId}/folds` | — | 200 same payload as `/backtests/{id}/folds`, resolved via the trial's `backtest_run_id` | 404 |
| GET | `/api/v1/optimizations/{sweepId}/best` | `?top=10` | 200 leaderboard rows (metric matrix, §D.9) | 404 |
| POST | `/api/v1/optimizations/{sweepId}/promote` | `{trialId, notes?}` | 201 `{strategyId, newVersion, status:"draft"}` | 404, 409 trial invalid/failed |
| DELETE | `/api/v1/optimizations/jobs/{jobId}` | — | 202 sweep cancellation (running trials finish or abort at checkpoint; queued trials dropped) | 404, 409 |

> **Error-code pin (phases-doc §0.3 → [COMMON §3](ARTHAYANTRA_2_COMMON_REFERENCE.md#3-global-conventions-apply-to-every-phase)).** Backtest coverage failures use **`DATA_GAP`** (the §5.4 taxonomy family code), which **supersedes** §7.4's older `INSUFFICIENT_DATA` label. `DATA_GAP` is HTTP **422** with `details` listing the missing windows. The full error envelope `{ code, message, details }` and the `DATA_*`/`CONFLICT_*`/`VALIDATION_*` families are in [COMMON §8.3](ARTHAYANTRA_2_COMMON_REFERENCE.md#83-error-envelope--error-code-taxonomy).
>
> **Version-resolution default (plan §7.2):** when `strategyVersion` is omitted, the job pins an explicit version at submission — the latest **draft** for editor quick-tests, the latest **published** version otherwise — never "whatever is current", which would destroy reproducibility.

**Progress transport** (D9/D12): services publish `{jobId, parentJobId?, status, progress, bestSoFar?}` deltas to the Redis `jobs.progress` channel; `edge-gateway` relays them to the browser as STOMP frames on `/topic/jobs/{jobId}` over native WebSocket (`/ws`). No SSE. Polling `GET .../jobs/{jobId}` remains the fallback **and the source of truth**, because the Postgres `jobs` table is authoritative and Redis pub/sub is fire-and-forget.

#### Job state machine [plan §7.4]

```
[*] --> queued      : 202 accepted, row inserted, XADD to Redis Stream
queued --> running  : worker XREADGROUP claim (consumer group per service)
queued --> cancelled: DELETE before claim
running --> completed: replay/trials finished, results persisted
running --> failed  : engine error, data gap, or schema mismatch
running --> cancelled: cancel flag observed at bar-batch checkpoint
running --> queued  : stale 'running' re-queued on service restart (D12)
completed --> [*]   ;  failed --> [*]  ;  cancelled --> [*]
```

At-least-once delivery from Redis Streams is reconciled against the table: a worker claiming a message first does a conditional `UPDATE jobs SET status='running' WHERE id=? AND status='queued'`; **losing that race acks and drops the duplicate** (see [§D.2](#d2-async-jobs--scheduling-the-jobs-spine-plan-58-adr-d12)).

### D.6 — Backtest execution model & reproducibility [plan §7.4]

- **Data path.** backtest-service reads candles **directly from the `marketdata` schema read-only** (D10) — the 1m hypertable plus continuous aggregates for 5m/15m/1h/1d; options strategies replay `options_chain_snapshots` (since 2026-06-12 under the explicit fidelity contract of [§D.15](#d15-options-replay-fidelity-contract--synthetic-premium-mode-adr-a10-fp-4-owner-selection-2026-06-12) — native 5-min granularity, archive-coverage pre-flight, `premium_source` provenance [FP-4, owner selection 2026-06-12]). **No REST hop for bulk data.** A **pre-flight coverage check** (expected vs present bars per `MarketCalendar`) fails fast with **422 `DATA_GAP`** and tells the UI which range to backfill via market-data-service. The check explicitly extends to the **benchmark series** used for regime attribution (guard 6), including warm-up depth — ~200 trading days before the window start for the trend label, ~1 year + 20 days for the volatility label — so regime labels never silently degrade to partial coverage.
- **Engine.** Bar-by-bar replay through the **same `strategy-engine` JAR** (ta4j 0.22.x indicator backend) as live: candles → indicator series → gates → composite score → simulated fills via the JAR's **`FillSimulator` port** ([§D.11](#d11-fillsimulator-port--full-cost-model-q1-plan-71)), **NUMERIC/BigDecimal end to end, IST clocks**, single-threaded per job for determinism. The port consumes the `costs` block in full — brokerage legs, slippage, and the optional statutory fee schedule `fees{}`. Because backtest-service and strategy-signal-service link the **same JAR**, backtest fills and paper fills are one implementation — the validate-in-backtest→trust-paper loop holds by construction.
- **Parallelism.** A bounded worker pool of **`max(1, cores − 2)`** platform threads for CPU-bound replay (virtual threads only for IO per D12) — on the owner's 8-core machine: 6 concurrent trials, each single-threaded for determinism. Memory governor: the pool shrinks to stay under the container's **896 MB** `mem_limit`. A single backtest is parallel-safe with a live sweep — trials are independent jobs in the same queue.
- **Determinism & reproducibility — the triple.** Every result records: **(1) `strategyChecksum`** (immutable version, resolved via `strategy_version_id`), **(2) `seed`** (used for any stochastic component and **recorded even when unused**), **(3) `dataHash`** — SHA-256 over the ordered tuple set (instrument keys, interval, from/to, **bar count**, **max(`fetched_at`)**) of the candles actually read. **Same triple ⇒ byte-identical trade list** — what the golden-vector parity tests assert, and what makes leaderboard comparisons honest (rows with differing `dataHash` are visually flagged as not like-for-like).
- **Optimizer loop (the backtest-service half).** TRIAL-kind jobs apply `params_override` via the **closed path grammar** (validated again service-side, [§D.12](#d12-parameter-path-whitelist-grammar-plan-114)) onto the pinned version's JSONB — **never persisted as a strategy version** — and emit trial metrics onto `optimizations.results`.
- **Universe pinning (S8; submission-time pinning lands in Stage F, the accrual table in Stage C).** For `index_constituents` and filtered universes, strategy-signal-service resolves the universe **at backtest/optimization submission and at publish** — index membership obtained via market-data-service's constituents REST endpoint (strategy-signal-service never reads the `marketdata` schema directly, per the D7/D10 grant model) plus instrument-master filters — and the resolved-universe snapshot is stored **by copy**: the resolved `(exchange, tradingsymbol)` list is embedded in the job's `request` JSONB and `backtest_runs.universe_checksum` records the SHA-256 over the ordered list. **Soft references only — no cross-schema FK.** Every trial in a sweep reuses the embedded list, so a constituent rebalance mid-sweep can never split a leaderboard; `data_hash` continues to flag any drift in the candles actually read. v1 resolves *current* membership — survivorship bias over long lookbacks is **documented, not hidden**; point-in-time `as_of: trade_date` over the accrued constituents history is a noted later enhancement. *(In Stage D, `universe_checksum` is populated only when the submission already carries a resolved universe; the wiring of the resolve-at-submission path itself is a Stage F deliverable — Stage D leaves the column NULL-able and writes it when present.)*

**Stress-test runs.** `POST /api/v1/backtests/run` accepts `purpose: stress_test`. backtest-service then validates the requested window against **every historical backtest and optimization job for the strategy lineage** (date ranges live in `jobs.request`; manual quick-backtests leak information exactly as sweeps do) and rejects the label with **422 `WINDOW_CONTAMINATED`** — **listing the intersecting jobs** — if any overlap exists; `GET /api/v1/backtests/stress-window?strategyId=` returns the suggested clean window (day after the latest tested `to`, through the latest cached candle). **Known limitation:** re-creating a strategy under a new id resets lineage tracking, so the check is **honest accounting, not tamper-proof prevention**. Stress runs closing fewer than `constraints.min_trades` trades report **"insufficient sample — extend the window"**, never a verdict. Repeated stress tests against the same `(strategy, window)` are **counted and surfaced** (a holdout-reuse counter), since each re-tune-after-failure cycle contaminates the holdout through the owner's decisions.

**Execution-model extensions (ratified 2026-06-12).** Four surgical extensions to the data path, pre-flight, and sizing above:

- **Context-instrument coverage [FP-19, owner selection 2026-06-12].** The pre-flight coverage check extends to every indicator-level `instrument: {exchange, tradingsymbol}` override (the **A7** cross-instrument context mechanism — e.g. `RS_VS_INDEX`, `VIX_LEVEL`): each context series is checked at its **declared timeframe including warm-up depth**, with the same 422 `DATA_GAP` semantics and missing-window listing as the primary series. Context series never silently degrade to partial coverage — exactly the guard-6 benchmark posture, generalized.
- **Continuous-futures replay [FP-11, owner selection 2026-06-12].** `universe.mode: futures_of_underlying` backtests replay the synthetic per-underlying **CONT series** built in Stage B Phase 15B (reading `marketdata.roll_events` for adjusted/unadjusted views), while live trades the **actual front contract** — the **roll-day basis divergence between the two is documented, not hidden** (ADR A11): the results payload carries a per-run note whenever the window crosses a roll event.
- **Corporate-action pre-flight warning [FP-1, owner selection 2026-06-12].** When a requested window crosses a `marketdata.corporate_action_events` row (Stage B Phase 16A) that is unresolved, or whose cache rebuild post-dates prior runs of the same strategy, submission succeeds but carries an explicit **corporate-action warning** in the job's request echo and results payload. `data_hash` already flags pre-event runs as not-like-for-like (A8 bumps `fetched_at` on rebuild) — the warning makes the *cause* legible instead of leaving an unexplained hash mismatch.
- **Lot-size as-of sizing [FP-3, owner selection 2026-06-12].** F&O replay sizing resolves **lot/tick size as-of trade date** from `marketdata.contract_spec_history` (accrued from Stage B Phase 9A by diffing the daily instrument sync), so a 2022 trade never sizes with today's lot. Windows predating the accrual start fall back to current specs and carry the **pre-accrual data-quality honesty flag** — the same accrue-from-today posture as `index_constituents` (A11).

### D.7 — Optimizer execution model [plan §7.4, Flow 5]

**Method/sampler guidance [plan §7.5]:**

| Method | Optuna 4.x sampler | Best for | Typical trials | Notes |
|---|---|---|---|---|
| `grid` | `GridSampler` | ≤ 3 params with small discrete sets; exhaustive certainty; final neighborhood scans | product of choices (cap ~500) | Deterministic; `maxTrials` truncates; embarrassingly parallel |
| `random` | `RandomSampler` | Baseline; high-dimension first pass; sanity check against fancier methods | 50–200 | Surprisingly competitive; fully parallel |
| `tpe` (Bayesian) | `TPESampler` | **Default.** Continuous/mixed spaces, expensive objectives, 3–10 params | 50–150 to converge | Sequential-ish (works with 6 parallel via constant-liar); supports pruning |
| `nsga2` (evolutionary) | `NSGAIISampler` | Multi-objective (return ↑ vs drawdown ↓); rugged landscapes | 200–500 | Returns a Pareto front, not one winner; UI shows front in ECharts scatter |

- **Optimizer loop.** optimizer-service expands the sweep: Optuna 4.x `ask()` produces a parameter set, the service **materializes a patched config** (applying `parameters[].path` values onto the pinned version's JSONB — **never persisted as a strategy version**), enqueues a `trial` job to the `jobs.backtest.trials` Stream, awaits the result, `tell()`s the objective back, and prunes (MedianPruner on walk-forward fold results) — per D12's ask/tell mandate. **Sweep progress = completed trials / `maxTrials`.**
- **Authoritative trial rows.** Per trial, the optimizer **INSERTs a queued `jobs` row (kind=TRIAL, `parent_job_id`=sweep)** — the authoritative record the worker's conditional claim requires (D12) — **then** `XADD jobs.backtest.trials`. A sweep's TRIAL row count in `jobs` must equal its dispatched stream entries (a Phase 33 acceptance check).
- **Resumability.** Optuna runs **in-memory storage per sweep**; the `optimization_trials` table is app-managed and a sweep resumes by replaying that table back through `study.add_trial`. Seeded sampler ⇒ reproducible trial sequence (golden, [§D.8](#d8-testing--golden-vectors-plan-103-104-106-107-108)).
- **Promotion via REST only.** The optimizer **never writes strategy versions directly to the DB** — promotion goes through strategy-signal-service's versioning REST API (and only in Phase 34's promote path). See [§D.9](#d9-metrics-catalog-leaderboard--promotion-plan-76).

**Flow 5 — Optimization sweep fan-out + real-time signal generation [plan §3.4].** Two halves of the platform's center of gravity: Optuna-driven tuning, and the live engine that consumes what tuning produces.

```
Part A — optimization fan-out (Optuna ask/tell)
  B  -> GW : POST /api/v1/optimizations/run (strategyId, method=tpe, maxTrials=200)
  GW -> OPT: proxy
  OPT-> PG : INSERT parent sweep job (queued)
  OPT-> B  : 202 jobId
  loop until maxTrials or early-stop / pruning:
    OPT-> OPT: study.ask() -> param set from optimize.parameters paths
    OPT-> PG : INSERT queued TRIAL jobs row (kind=TRIAL, parent_job_id=sweep)   [D12 authoritative]
    OPT-> R  : XADD jobs.backtest.trials (trialId, params)
    R  -> BTS: XREADGROUP cg-trials (shares cores-2 worker pool)
    BTS-> BTS: run trial via strategy-engine JAR
    BTS-> PG : persist trial metrics (backtest schema)
    BTS-> R  : XADD optimizations.results (trialId, sharpe, maxDD, winRate, trades [, per-fold OOS])
    R  -> OPT: XREADGROUP cg-optuna -> study.tell(), prune laggards
    OPT-> R  : PUBLISH jobs.progress (sweep pct, best-so-far)
    R  -> GW : relay -> /topic/jobs/{jobId} -> ECharts trial heatmap updates live
  OPT-> SSS: POST /api/v1/strategies/{id}/versions (winner -> new DRAFT)    [promote, Phase 34]

Part B — real-time signal generation (continuous, market hours; Stage C — context only here)
  R  -> SSS: ticks.* + candles.1m.* for published strategies' symbols
  SSS-> SSS: composite = (Σ req w·s + Σ activated-optional w·s)/(Σ req w + Σ activated-optional w) ≥ threshold
             (normative §7.1 formula, ADR A1); optionals activate per optional_min_score / optional_gate_margin
  SSS-> PG : INSERT signal + per-indicator breakdown JSONB
  SSS-> R  : PUBLISH signals  ->  GW relay  ->  STOMP /topic/signals
```

The promoted draft from Part A is **inert** until the owner reviews and publishes it (D18 lifecycle) — the optimizer can **propose, never deploy**.

> **CD-12 — fold-fed pruning transport** ([COMMON CD-12](ARTHAYANTRA_2_COMMON_REFERENCE.md#4-chosen-by-default-decisions-cd-1cd-17-revisable-flagged-per-the-prompts-rule)): trial workers stream per-fold intermediate OOS objectives onto `optimizations.results`; the optimizer applies MedianPruner and cancels pruned trials **via the job-cancel path** (the `DELETE /optimizations/jobs/{jobId}` checkpoint mechanism, applied per-trial). The S3 spike calibrates `n_startup_trials` / warm-up folds ([§D.13](#d13-s3-spike--pruner-calibration-plan-172)).

### D.8 — Testing & golden vectors [plan §10.3, §10.4, §10.6, §10.7, §10.8]

**Backtest determinism — golden tests (§10.7).** The golden-vector suite (D15) pins the platform's core promise: **same YAML + same candles → identical signals and metrics, live and backtest**. Fixtures are committed: five trading days of synthetic 1m NIFTY candles (generated once by `kite-simulator` with a fixed seed, then frozen), plus one strategy YAML per schema feature.

| Family | Assertion |
|---|---|
| Metric exactness | Fixed dataset + strategy → metrics file (`returns`, `sharpe`, `maxDrawdown`, `winRate`, `tradeCount`) matched as **exact decimal strings** — any engine change altering output requires an explicit golden update in the same PR |
| **Live/backtest parity** | The same candle stream is (a) pushed through the **signal engine** tick-wise and (b) **replayed** by the backtest engine; the resulting signal lists (timestamps, scores, **per-indicator breakdowns**) must be **byte-identical** — guards the shared `strategy-engine` JAR seam (D7). This is the Phase 30 headline gate. |
| Version immutability | Re-evaluating an archived strategy version reproduces its stored SHA-256 checksum and original results (D18) |
| Optimizer reproducibility | Optuna 4.x with a fixed sampler seed reproduces the same trial sequence and best-trial params (pytest) |

**Relevant §10.3 unit targets for Stage D:**

| Target | Service / module | What is asserted |
|---|---|---|
| Composite scoring (parity input) | `strategy-engine` JAR | Weight-normalized composite per the §7.1 normative formula (ADR A1) — `(Σ req w·s + Σ activated-optional w·s)/(Σ req w + Σ activated-optional w) ≥ threshold` — truth table incl. optional-activation cases; per-indicator breakdown satisfies the renderer invariant `composite = Σ contributions / weightDenominator`. (Built in Stage C; Stage D's parity test re-asserts byte-identity.) |
| Sweep expansion / ask-tell | optimizer-service (pytest 8) | Grid/random/TPE/NSGA-II parameter expansion from `optimize.parameters[].path/range`; early stopping; **respx-stubbed** backtest dispatch |
| FillSimulator vectors | `strategy-engine` JAR | Committed fill fixtures per asset class × slippage form × fee combination; NUMERIC string-compare ([§D.11](#d11-fillsimulator-port--full-cost-model-q1-plan-71)) |

**Integration testing (§10.4) relevant here:** Testcontainers with `timescale/timescaledb:2.17.x-pg17` + `redis:7.4-alpine`; a test asserts the **`backtest` role can `SELECT` but not `INSERT` into `marketdata`** (single-writer rule, D10); Redis Streams consumer-group at-least-once redelivery via `XAUTOCLAIM` after a simulated worker crash, and the D12 job state machine — Postgres `jobs` row is authoritative, a `running` job orphaned by container kill re-queues on startup.

**Contract testing (§10.6).** Committed OpenAPI 3.1 specs under `/contracts` ([COMMON CD-8](ARTHAYANTRA_2_COMMON_REFERENCE.md#4-chosen-by-default-decisions-cd-1cd-17-revisable-flagged-per-the-prompts-rule)): springdoc-generated for backtest-service, **FastAPI-native** for optimizer-service. **Inter-service consumer verification:** the **optimizer→backtest** calls are tested against WireMock/respx stubs generated from the provider spec, so a provider change that breaks the consumer fails in the consumer's suite before deployment.

**Coverage gates (phases-doc §0.3):** `libs/strategy-engine` ≥ 70 % branch; Java services ≥ 60 % line; **optimizer ≥ 75 % line** — each enforced in its stack's CI workflow.

**Load (§10.8), informational for Stage D:** `backtest-saturation` — submit 4× (cores−2) one-year-1m backtest jobs plus a 200-trial sweep; pool never exceeds cores−2; queue drains with **zero lost jobs** (Postgres↔Streams reconciliation); interactive REST p95 unchanged during sweep; backtest-service stays under 896 MB. (The binding load run is Stage G's k6.)

### D.9 — Metrics catalog, leaderboard & promotion [plan §7.6]

Computed **once** by backtest-service per run/trial, persisted in `backtest_runs`/`optimization_trials`, **all money math in NUMERIC/BigDecimal**:

| Metric | Definition (in words) |
|---|---|
| Total return | Final equity minus initial capital, as a percentage of initial capital, **net of configured costs** |
| Annualized return (CAGR) | The constant yearly growth rate that compounds initial to final equity over the tested calendar span |
| Sharpe ratio | Mean of periodic portfolio returns minus the risk-free rate (**default 6.5 % Indian T-bill, configurable**), divided by the standard deviation of those returns, scaled to annual (**√252 daily, √(252×375) for 1m bars**) |
| Sortino ratio | Same as Sharpe but the denominator uses only the standard deviation of *negative* periodic returns — doesn't punish upside volatility |
| Max drawdown | Largest peak-to-trough equity decline, as a percentage of the peak |
| Max drawdown duration | Longest stretch (calendar days / bars) between an equity peak and the first new peak that exceeds it |
| Win rate | Closed trades with positive net P&L divided by total closed trades |
| Profit factor | Gross profit of all winning trades divided by gross loss of all losing trades |
| Expectancy | Average net P&L per trade: win rate × average win minus loss rate × average loss (₹ and R-multiple forms) |
| Average trade | Mean net P&L per closed trade; reported alongside average win, average loss, and average holding time |
| Exposure | Percentage of tested bars during which at least one position was open |
| Trade count | Total closed trades (the denominator that gives every other metric its credibility) |
| Alpha (vs benchmark) | Annualized intercept of the regression of strategy periodic returns on benchmark periodic returns (`backtest.defaults.benchmark`) [FP-32, owner selection 2026-06-12] |
| Beta (vs benchmark) | Slope of the same regression — the strategy's sensitivity to benchmark moves [FP-32, owner selection 2026-06-12] |
| Information ratio | Mean active return (strategy minus benchmark per period) divided by tracking error (standard deviation of active returns), annualized [FP-32, owner selection 2026-06-12] |
| Excess CAGR | Strategy CAGR minus the benchmark's buy-and-hold CAGR over the same window [FP-32, owner selection 2026-06-12] |

> **Benchmark-relative rows (Phase 32A) [FP-32, owner selection 2026-06-12].** Computed against `backtest.defaults.benchmark` — **the same series guard 6 already pre-flights** ([§D.4](#d4-overfitting-guards-17-plan-75)), so no new coverage machinery; persisted as `alpha`/`beta`/`information_ratio`/`excess_cagr`, NULL — flagged, never silently zero — when benchmark coverage is absent. Optional **up/down capture** ratios are returned in the results payload only (not persisted). **Price-index-not-TRI caveat:** NIFTY indices on Kite are price indices (dividends excluded), which mildly flatters benchmark-relative numbers — documented, not hidden. Full definitions: [§D.16](#d16-run-analytics-benchmark-relative-metrics--monte-carlo-fp-31-fp-32-owner-selection-2026-06-12).

**Leaderboard & comparison.** `GET /api/v1/optimizations/{sweepId}/best?top=N` returns the metric matrix; the UI (Stage E) renders a sortable PrimeNG DataTable with **plateau-adjusted default sort** (guard 4), **`dataHash` parity badges**, **per-regime OOS Sharpe/expectancy columns** with a **regimes-covered badge** and an explicit **"n folds excluded" flag** where `min` aggregation dropped folds (guard 6), the **sortable train→OOS degradation badge** (guard 7), and per-row links to full results, trades, and equity curve. Cross-sweep comparison works because results are keyed by `(strategyId, version, dataHash)`. Mixed-`premium_source` comparisons (a `SNAPSHOT` run beside a `SYNTHETIC_B76` one) are **flagged exactly like `dataHash` mismatches** — never silently compared as like-for-like ([§D.15](#d15-options-replay-fidelity-contract--synthetic-premium-mode-adr-a10-fp-4-owner-selection-2026-06-12)) [FP-4, owner selection 2026-06-12].

**Persist-best-params (promote) flow.** `POST /api/v1/optimizations/{sweepId}/promote {trialId}` makes optimizer-service call strategy-signal-service's versioning API: it applies the trial's parameter values onto the source version's config and creates a **new draft version** (minor bump) with provenance recorded in the version notes and audit log (e.g. `source: sweep 91f3, trial 217, objective sharpe=1.84 OOS`; `created_by='optimizer:{jobId}'`). **409 for invalid/failed trials.** The owner then reviews the diff, optionally quick-backtests, and publishes — the optimizer **never silently changes a live strategy** (D18: "the optimizer writes winners back as new drafts").

### D.10 — De-scope unit & §15.6 lever 1 [plan §15.6; review TIMELINE/§5]

The anti-overfitting block — **S1A + S1B + S1C + BPC + S8 universe-pinning (~9–10 d, Phases 3–5)** — is declared as a **single** §15.6 lever-1 unit. Its items cross-reference each other (guards 6/7, `fold_metrics`, the publish-dialog badge) and are **pulled together or not at all**; `regimeMix` is the one **nullable** seam allowing BPC's fold reporting to survive alone if ever required. The lever itself is "**defer optimizer-service entirely**" (saves ~1.5–2 FT weeks): ship single backtests + manual parameter edits; the Redis Streams job spine and `optimize` YAML block already exist, so Optuna bolts on later, and the anti-overfitting block defers with it. **Non-negotiable even under this pressure** (cutting any recreates the top risks): Flyway-managed schemas, mock mode, the **shared engine JAR with golden-vector parity tests**, NUMERIC/IST conventions, credential hygiene (D13).

### D.11 — FillSimulator port + full cost model (Q1) [plan §7.1 "Costs & fills"; review Q1]

The fill model lives as a **`FillSimulator` port in the shared `strategy-engine` JAR**, consumed identically by backtest replay (Phase 30) and the paper ledger (Stage F) — a paper-local implementation would make paper and backtest P&L **diverge by construction**. A second fill implementation anywhere outside this JAR is the explicit FAIL condition.

**Shipped implementation `ltp_slippage/v1`** — thin and deterministic:

- **Reference price** = **next-bar open in replay**; **next-tick LTP in the live paper ledger**. **No partial fills.**
- **Slippage:** fill = reference price **±** slippage, where slippage is `slippage_ticks` (integer ticks) **⊕** `slippage_bps` (basis points of fill price) — **at most one** of the two. When **neither** is set, per-instrument-class fallbacks apply:
  - **equities: 5 bps**;
  - **options: `max(1 tick, half the quoted spread)`** when bid/ask is known (from last tick or chain snapshot), **degrading to 1 tick** without a quote.
  - *Why the options fallback (decisions log):* a flat 10 bps of premium is quantitatively wrong — **one ₹0.05 tick on a ₹10 OTM premium is already 50 bps**. The asserted golden case: ₹0.05 tick on ₹10 premium = 50 bps; flat-bps would be wrong.
- **Cost legs — the model is FULL, not slippage-only.** The brokerage legs are preserved (the review's bps-only framing was a regression):
  - **brokerage:** `per_lot_inr` (options) **or** `pct_per_side` (equities);
  - **optional statutory fee schedule `fees{}`** applied **identically in backtest and paper** — flat per-order brokerage plus side-aware percentage legs: **STT** on **sell-side option premium**, **exchange transaction charge** on premium, **GST** on (brokerage + transaction charge), **stamp duty** on the **buy side**, **SEBI turnover fee** — defaulted from the **current Zerodha/NSE schedule** when omitted.
  - **Documented caveat:** a flat per-lot default **understates the premium-proportional statutory charges** on option strategies, which is why the schedule exists.
  - **Fee defaults live in one constants file** with a **source + date comment** and a config-refresh note (decisions log §4.3 — current Zerodha brokerage and NSE/SEBI/GST/STT/stamp rates pinned at implementation).
- **Schema-freeze status.** `slippage_bps` and `fees{}` are part of `strategy-schema/v1` **at the Stage C freeze** — validated from day one; their backend consumers land in Phase 29/30 and Stage F — so **no post-freeze schema-shape change** occurs.
- **Future escape hatch (not built now).** Richer microstructure (bid/ask-aware option fills, partial fills) is a **later `FillSimulator` implementation** plus an **additive `paper_fills` child table** (D17 additive-first) — never a ledger refactor. Fill-audit columns land on **`paper_orders`** in Stage F (the review's `paper_trades`/`paper_drops` tables do not exist).

**A9 extensions (ratified 2026-06-12) — futures legs, `at_close` fills, intra-bar exits [FP-5, FP-6, FP-7, owner selection 2026-06-12].** ADR amendment **A9** extends this port; the `ltp_slippage/v1` semantics above are unchanged for all existing cases:

- **Futures cost legs [FP-7].** A **futures instrument class** joins the cost model: brokerage **min-of-pct/flat**, **sell-side futures STT**, exchange transaction charge, **buy-side stamp duty**, SEBI turnover fee — values pinned at implementation in the **same fee-constants file** (source + date comment, decisions log §4.3), and a **futures slippage fallback** joins the per-class fallback table. Applied identically in backtest and paper, like every other leg.
- **`at_close` fill timing [FP-6].** When `risk.session.fill_timing: at_close` (the default when `style: btst`, per A7), the **reference price is the signal bar's close** instead of next-bar open — in both replay and the live paper ledger. `next_open` remains the default for every other style.
- **Intra-bar exit-touch rule [FP-5].** When `risk.session.exit_intrabar: true` (default when the primary timeframe > 1m, per A7), stop / take-profit / trailing levels are evaluated on **each closed 1m bar** in **both live and replay** — parity at the **1m floor, NOT tick-level**. Replay drills into the cached 1m candles inside each primary bar; where 1m coverage is missing it falls back to **primary-bar high/low worst-of** (gap-through fills at bar open), and every trade records **`touch_basis`** (`CLOSE_EVAL | INTRABAR_1M | BAR_HL_WORSTOF`, persisted on `backtest_trades`, [§D.3](#d3-backtest-schema-pg-schema-backtest--full-column-tables-plan-64)). **Entry evaluation stays primary-bar-close** — only exits gain intra-bar resolution.
- **BTST pre-close bar view [FP-6].** BTST evaluation uses a **deterministic pre-close bar view** assembled from 1m candles up to `risk.session.pre_close_at` (default `"15:20"`, per A7), built **identically in live and replay** — the pre-close clock plus `at_close` fills capture the overnight gap that an after-close evaluation with next-open fills structurally misses.

**Fill-vector suite (Phase 29).** Committed fixtures per **asset class × slippage form × fee combination**; **NUMERIC string-compare**; fee math exact to the paisa; identical inputs ⇒ identical fills across runs. The A9 extensions widen the matrix: **futures** join the asset classes, and dedicated fixtures cover **`at_close` fills**, the **intra-bar exit-touch rule** (1m drill, worst-of fallback, gap-through-at-open, `touch_basis` classification) and the **BTST pre-close bar view** [FP-5, FP-6, FP-7, owner selection 2026-06-12].

### D.12 — Parameter-path whitelist grammar [plan §11.4]

Both optimizer-service (at sweep submission) and backtest-service (again before applying a trial's overrides) enforce the **same closed grammar**; anything outside it is rejected with the standard envelope, **`400 INVALID_PARAMETER_PATH`**. The full grammar lives in [COMMON §12.5](ARTHAYANTRA_2_COMMON_REFERENCE.md#125-parameter-path-whitelist-grammar-closed); restated here for the optimizer path:

```
path            := indicator-path | exit-path | scoring-path | risk-path
indicator-path  := "indicators[" selector "].params." ident
exit-path       := "exit_rules[" selector "].params." ident
scoring-path    := "entry_rules.scoring." ident            # e.g. entry_rules.scoring.threshold
risk-path       := "risk.position_sizing." ident           # fields enumerated in strategy-schema/v1 only
selector        := "alias=" ident | "type=" ident | int    # bare positional index accepted but linted
ident           := [a-z][a-z0-9_]*        int := [0-9]+    # literal match only — no wildcards, quoting, or nesting
```

Selectors are matched **literally** against the *validated* config tree — **no reflection, no expression evaluation**, so path strings cannot reach arbitrary object graphs. Every resolved path must land on a **leaf that exists** in the validated config and whose JSON Schema **type matches** the supplied `range`/`choices`. Resolution is a pure walk of the parsed config tree.

### D.13 — S3 spike — pruner calibration [plan §17.2; review SPIKES]

**S3 — Optimizer pruner calibration on noisy objectives.** The plan's fold-fed `MedianPruner` design **stands** (guard 2) — per-fold intermediate reporting *is* the design, so feasibility is not the question, **calibration** is.

- **Question:** what `n_startup_trials` and warm-up-fold counts avoid both **pruning on noise** (Sharpe on limited trades) and **early-regime selection bias** from oldest-first walk-forward folds? Do TPE/NSGA-II still converge usefully under those settings?
- **Method:** a **pure-Python study** — synthetic strategy with known optimum + bootstrapped backtest windows; compare grid vs TPE vs NSGA-II. **No backtest-service dependency.**
- **Timing:** weeks 2–3 (the Phase 2–3 / Stage C–D boundary), as the **entry gate** to Phase 33.
- **Gate (hard, optimizer-service only):** `n_startup_trials` / warm-up-fold / `max_trials` / sampler-pruner defaults are **recorded as a dated ADR amendment (decisions log §4.5)** before sweeps ship, and configured here — **or pruning is explicitly disabled until they are** (a gate checkbox in `PHASE_GATES.md`). **The backtest engine (Phase 30, "3a") does not consume it.** The spike's outputs become the recorded defaults, never predeclared inputs (the review's `max_trials=500, pruner=MedianPruner` predeclaration is rejected).

### D.14 — Flow 4 — Backtest job lifecycle [plan §3.4]

```
B  -> GW : POST /api/v1/backtests/run (strategyId, version, symbol, interval, range)
GW -> BTS: proxy
BTS-> SSS: GET /api/v1/strategies/{id}/versions/{v} (immutable, checksum-verified, Caffeine-cached)
BTS-> PG : INSERT jobs row status=queued (backtest schema — authoritative)
BTS-> R  : XADD jobs.backtest jobId
BTS-> B  : 202 jobId, status=queued
R  -> BTS: XREADGROUP cg-backtest (bounded pool, cores-2)
BTS-> PG : UPDATE jobs SET status=running   [conditional claim: WHERE status='queued']
BTS-> PG : read candles from marketdata (read-only role, no REST hop)
BTS-> BTS: replay through strategy-engine JAR (identical to live engine)
loop every N bars:
  BTS-> R : PUBLISH jobs.progress (jobId, pct)  ->  GW relay  ->  STOMP /topic/jobs/{jobId}
BTS-> PG : persist trades + metrics (returns, Sharpe, maxDD, win rate, trade count)
BTS-> PG : UPDATE jobs SET status=completed, resultRef
BTS-> R  : XACK + PUBLISH jobs.progress pct=100
B  -> GW : GET /api/v1/backtests/{backtestId}/results -> render equity curve
```

Strategy-version reads from strategy-signal-service are **Caffeine-cached** because immutable versions never change ([COMMON D11](ARTHAYANTRA_2_COMMON_REFERENCE.md#d11--caching)).

### D.15 — Options replay fidelity contract + synthetic premium mode [ADR A10; FP-4, owner selection 2026-06-12]

Per **A10** (ratified 2026-06-12), the previously load-bearing one-liner "options strategies replay `options_chain_snapshots`" becomes an explicit **fidelity contract**, implemented in Phase 30A:

- **Native snapshot granularity.** Options premium series replay at the archive's **native 5-minute snapshot granularity** — never interpolated to finer bars. A strategy whose primary timeframe is finer than 5m **cannot be served snapshot-grade premiums**, and the contract says so up front instead of silently fabricating sub-snapshot prices. Underlying-side indicators continue to read candles at their declared timeframes as before.
- **Coverage pre-flight against the archive.** For options strategies, the [§D.6](#d6-backtest-execution-model--reproducibility-plan-74) pre-flight runs against **`options_chain_snapshots` archive coverage** (expected vs present 5-min snapshot slots per `MarketCalendar`), not candle coverage alone → **422 `DATA_GAP`** with the missing snapshot windows listed. The archive only accrues from Stage B onward, so windows predating it legitimately fail the snapshot-grade check — that is the honest answer, and the synthetic mode below is the explicit, labelled alternative.
- **Synthetic premium mode (`SYNTHETIC_B76`).** A **clearly-labelled approximate mode** reconstructs the premium series from **underlying 1m candles via Black-76**: IV is taken from the **nearest archived snapshot** (time-nearest, matching expiry/strike bucket), with a **flat-IV assumption for windows predating the archive** — both assumptions surface as run-level caveats in the results payload, never buried.
- **`premium_source` provenance.** Every run records `backtest_runs.premium_source` — **`SNAPSHOT | SYNTHETIC_B76 | NA`** (`NA` for non-options runs). The results endpoint echoes it, and leaderboard/compare views **flag mixed-`premium_source` comparisons exactly like `dataHash` mismatches** ([§D.9](#d9-metrics-catalog-leaderboard--promotion-plan-76)). A synthetic-premium run can **never masquerade as snapshot-grade** — rendering one without its `SYNTHETIC_B76` flag is the FAIL condition.
- **`libs/black76-math` hoist.** The pure Black-76 pricing/IV math is hoisted into a small **dependency-free `libs/black76-math`** consumed by **both** market-data-service (the Phase 14 Greeks engine — **behavior unchanged**, proven by the existing Greeks goldens) and backtest-service — one implementation, no drift between live Greeks and synthetic replay premiums.

### D.16 — Run analytics: benchmark-relative metrics + Monte Carlo [FP-31, FP-32, owner selection 2026-06-12]

Both blocks are **pure post-processing** over rows the engine already persists — no replay change, determinism untouched. Implemented in Phase 32A.

**Benchmark-relative metrics [FP-32].** Every metric in the [§D.9](#d9-metrics-catalog-leaderboard--promotion-plan-76) catalog is absolute; Phase 32A adds the relative set, computed against **`backtest.defaults.benchmark`** (default `NSE:NIFTY 50`) — **the same series guard 6 already pre-flights including warm-up** ([§D.4](#d4-overfitting-guards-17-plan-75)), so no new coverage machinery:

- **Alpha / beta:** regression of strategy periodic returns on benchmark periodic returns over the run window — beta is the slope, alpha the annualized intercept.
- **Information ratio:** mean active return (strategy − benchmark per period) divided by tracking error (std of active returns), annualized with the same √252-family scaling as Sharpe.
- **Excess CAGR:** strategy CAGR minus the benchmark's buy-and-hold CAGR over the identical window.
- **Up/down capture (optional):** strategy-vs-benchmark mean-return ratios over benchmark-up and benchmark-down periods — returned in the results payload, **not persisted**.
- Persisted on `backtest_runs` (`alpha`, `beta`, `information_ratio`, `excess_cagr` — [§D.3](#d3-backtest-schema-pg-schema-backtest--full-column-tables-plan-64)); **NULL — flagged, never silently zero —** when benchmark coverage is absent for the window.
- **Benchmark buy-and-hold curve:** the benchmark's normalized buy-and-hold equity over the run window, downsampled by the same ~500-point downsampler, persisted in `benchmark_curve` and returned **beside `equityCurve`** for direct overlay.
- **Price-index-not-TRI caveat (documented, not hidden):** NIFTY indices on Kite are **price indices, not total-return indices** — dividends are excluded, which mildly flatters every benchmark-relative number.

**Monte Carlo resampling [FP-31].** A **seeded bootstrap** of the run's persisted `backtest_trades` (trade-sequence resampling with replacement), **N = 1000 resamples by default** (per-request override):

- Outputs: **drawdown distribution**, **5/50/95 equity bands**, **risk-of-ruin** (probability of breaching a configurable equity floor), and **confidence intervals on CAGR and Sharpe**.
- The **MC seed is recorded beside the run seed** — same `(trade list, mcSeed, N)` ⇒ byte-identical bands, the same reproducibility posture as the [§D.6](#d6-backtest-execution-model--reproducibility-plan-74) triple.
- **Computed on demand** via `GET /api/v1/backtests/{id}/montecarlo` ([§D.5](#d5-backtest--optimization-job-design-plan-74) endpoint table); the summary is **persisted in `backtest_runs.montecarlo_summary`** on first computation and served from there on repeat calls (an explicit different `seed`/`n` recomputes and replaces it).
- Runs with **no closed trades → 422** (nothing to resample); runs below `constraints.min_trades` carry the same **"insufficient sample"** framing used elsewhere — wide bands are reported honestly, never hidden.

---

## Part 2 — Phase specs (28–34 + 30A, 32A)

Each phase is independently buildable, runnable, and testable on the mock profile (`SPRING_PROFILES_ACTIVE=mock`) with zero Kite credentials. Cross-references that read "plan §x", "Dn", "Sx/BPx/Qx", or "CD-n" are resolved to Part 1 sections above or to [COMMON](ARTHAYANTRA_2_COMMON_REFERENCE.md). Conventions (commit/Conventional-Commit, `ay`/`mvnw` invocation, ports, coverage gates) are the phases-doc §0.3 globals in [COMMON §3](ARTHAYANTRA_2_COMMON_REFERENCE.md#3-global-conventions-apply-to-every-phase).

### Phase 28 — backtest-service skeleton + jobs spine (table, Streams, progress)

**Objective.** Create backtest-service with the authoritative Postgres `jobs` table, Redis Streams dispatch, a bounded worker pool (cores−2), live progress on `jobs.progress`, cancel, and stale-job re-queue (D12, the [§D.5 state machine](#d5-backtest--optimization-job-design-plan-74)) — workers initially execute a no-op replay **stub**.

**Why this phase is independent.** The job spine is fully testable with stub work: submit → queue → run → progress → complete, all observable via REST + WS. The real replay engine lands in Phase 30.

**Deliverables.**
- `services/backtest-service/` — Boot app (**no Modulith** — [CD-17](#d1-service-specs-backtest-service--optimizer-service-plan-524-525-adr-d7)), Dockerfile + compose entry (internal **8083**, `mem_limit: 896m`); reads `marketdata` via the **read-only** role.
- Migration: `jobs` ([§D.3](#d3-backtest-schema-pg-schema-backtest--full-column-tables-plan-64) — `kind` BACKTEST/OPTIMIZATION/TRIAL, `parent_job_id`, status CHECK, `progress`, `strategy_version_id` soft ref, `request` JSONB, `error`, `worker_id`, timestamps; partial index `(status, created_at) WHERE status IN ('queued','running')` on live statuses + `(parent_job_id)` — the [§D.3 indexing strategy](#d3-backtest-schema-pg-schema-backtest--full-column-tables-plan-64), plan §6.7).
- Streams: `jobs.backtest` + consumer group `cg-backtest` (also **declares** `jobs.backtest.trials`/`cg-trials` and `optimizations.results` for Stage D); claim via conditional `UPDATE … WHERE status='queued'`; `XACK` only on terminal state; `XAUTOCLAIM` + stale-`running` re-queue on startup.
- Endpoints: `POST /api/v1/backtests/run` → validates strategy version exists via strategy-signal REST (Caffeine-cached immutable versions) → `202 {jobId}`; `GET /backtests/jobs` + `/jobs/{id}`; `DELETE /jobs/{id}` (cancel at checkpoint; 409 terminal — `CONFLICT_JOB_TERMINAL`).
- Worker pool `max(1, cores−2)` **platform** threads; progress writes (table + `jobs.progress` publish); correlation id persisted on the row and propagated.
- Metrics `ay_backtest_queue_depth`, `ay_backtest_workers_busy`, `ay_redis_stream_pending`.
- `jobs:summary` Redis key (queued/running counts) maintained by the worker pool — Stage B Phase 17's system-status rollup consumes it (IT asserts the field now appears in `GET /api/v1/system/status`).
- `contracts/backtest-service.openapi.json` committed; `ci-contracts` matrix extended; `gen:api` rerun (the Stage C Phase 24 pattern).

**Minimal code/config.** At-least-once reconciliation: losing the conditional-claim race ⇒ ACK and drop the duplicate.

**DB changes.** `backtest/V002__jobs.sql`.

**Build & Run.**
```
./mvnw -pl services/backtest-service -am verify
./ay.sh up && curl -X POST 127.0.0.1:8080/api/v1/backtests/run -d '{…}' -b cookies.txt   # 202 {jobId}
```

**Tests & Verification.**
- IT (Timescale+Redis containers): submit → stub-complete with progress 0→100 visible on the channel; kill worker mid-job (simulated) → `XAUTOCLAIM` + re-queue on restart → completes exactly once; cancel during run honored at checkpoint; duplicate Stream delivery harmless.

**Acceptance criteria.**
- PASS: full D12 state machine demonstrated incl. crash recovery; `/topic/jobs/{jobId}` progress reaches the STOMP probe.
- FAIL: Redis treated as source of truth; jobs lost on restart.

**Commit message.** `feat(backtest): job spine with authoritative jobs table, redis streams dispatch and crash-safe workers`
**PR title.** `Phase 28: backtest-service jobs spine`
**Time estimate.** 120–150 min. **Token size target.** ≤ 35k output tokens.
**If phase too big.** (a) service + migration + submit/status endpoints; (b) Streams workers + progress + recovery.

---

### Phase 29 — FillSimulator port + full cost model in engine JAR (Q1)

**Objective.** Add the `FillSimulator` port to the `strategy-engine` JAR with the shipped `ltp_slippage/v1` implementation and the full cost model (brokerage legs + slippage + optional statutory fee schedule), pinned by fill golden vectors — shared by backtest replay (Phase 30) and the paper ledger (Stage F Phase 43) **by construction**. Full spec: [§D.11](#d11-fillsimulator-port--full-cost-model-q1-plan-71).

**Why this phase is independent.** Pure library work on the already-frozen schema keys; vector-tested without any service.

**Deliverables.**
- `FillSimulator` port: fill = reference price (**next-bar open in replay; next-tick LTP live**) ± slippage; **no partial fills**; deterministic.
- Slippage: `slippage_ticks` ⊕ `slippage_bps`; per-class fallbacks when absent — **equities 5 bps**; **options `max(1 tick, half quoted spread)`** when bid/ask known, **degrading to 1 tick**.
- Cost legs: `per_lot_inr` / `pct_per_side` brokerage; optional `fees{}` — **STT (sell-side option premium), exchange transaction charge (premium), GST (brokerage+txn), stamp (buy side), SEBI fee** — defaults from the current Zerodha/NSE schedule **pinned as constants** with a config-refresh note (decisions log §4.3).
- **A9 extensions** ([§D.11](#d11-fillsimulator-port--full-cost-model-q1-plan-71) A9 block) [FP-5, FP-6, FP-7, owner selection 2026-06-12]:
  - **Futures cost legs [FP-7]:** futures instrument class — brokerage **min-of-pct/flat**, **sell-side futures STT**, exchange txn charge, **buy-side stamp**, SEBI fee — pinned in the **same constants file** (source + date comment); futures slippage fallback added to the per-class table.
  - **`at_close` fill timing [FP-6]:** reference price = **signal bar close** when `risk.session.fill_timing: at_close` (btst default per A7).
  - **Intra-bar exit-touch rule [FP-5]:** stop/TP/trailing evaluated on **each closed 1m bar** when `exit_intrabar: true`; **worst-of (primary-bar high/low, gap-through at open) fallback** semantics and the `touch_basis` classification (`CLOSE_EVAL | INTRABAR_1M | BAR_HL_WORSTOF`) defined **in the JAR** — replay's 1m drill-down wiring lands in Phase 30; entries stay primary-bar-close.
  - **BTST pre-close bar-view assembly [FP-6]:** deterministic bar from 1m candles up to `risk.session.pre_close_at` (default `"15:20"`), one implementation for live and replay.
- Fill-vector suite: committed fixtures **per asset class × slippage form × fee combination**; **NUMERIC string-compare**. Extended for every new A9 semantic: futures × fee combinations, `at_close` fixtures, intra-bar touch fixtures (1m drill, worst-of gap-through, `touch_basis`), pre-close-view fixtures [FP-5, FP-6, FP-7, owner selection 2026-06-12].

**Minimal code/config.** Fee defaults live in **one constants file** with source + date comment.

**DB changes.** none.

**Build & Run.**
```
./mvnw -pl libs/strategy-engine -am verify
```

**Tests & Verification.**
- Vector suite green; the **one-tick-vs-10bps options case** from the decisions log asserted (₹0.05 tick on ₹10 premium = 50 bps — flat-bps would be wrong).
- A9 vectors green [FP-5, FP-6, FP-7, owner selection 2026-06-12]: futures fee math **exact to the paisa** (incl. sell-side-only futures STT and buy-side-only stamp); `at_close` fixture fills at the signal bar's close; touch-rule fixtures classify `INTRABAR_1M` vs `BAR_HL_WORSTOF` correctly incl. the **gap-through-at-open** case; pre-close bar-view fixture is byte-identical when assembled from the same 1m candles twice.

**Acceptance criteria.**
- PASS: identical inputs ⇒ identical fills across runs; **fee math exact to the paisa** (futures legs included [FP-7, owner selection 2026-06-12]).
- FAIL: a second fill implementation anywhere **outside** this JAR; tick-level (sub-1m) exit evaluation anywhere — the A9 parity floor is the closed 1m bar [FP-5, owner selection 2026-06-12].

**Commit message.** `feat(strategy-engine): fillsimulator port with ltp_slippage/v1 and full statutory cost model`
**PR title.** `Phase 29: FillSimulator + cost model`
**Time estimate.** 90–120 min. **Token size target.** ≤ 25k output tokens.
**If phase too big.** Originally "Not applicable"; the A9 extensions add a seam [owner selection 2026-06-12]: (a) the original port + cost model + base vectors; (b) the A9 extensions (futures legs, `at_close`, intra-bar touch rule, pre-close bar view) + their vectors.

---

### Phase 30 — Backtest replay engine + metrics + parity golden test

**Objective.** Replace the Phase 28 stub with the real replay: candles read read-only from `marketdata`, bar-by-bar through the engine JAR + `FillSimulator`, the [§D.9 metrics catalog](#d9-metrics-catalog-leaderboard--promotion-plan-76) persisted, results/trades endpoints, and the **live-vs-replay byte-identity parity test** — the architecture's core promise.

**Why this phase is independent.** All inputs exist (candles, engine, fills, jobs). Parity asserts against the Stage C Phase 23 golden fixtures.

**Deliverables.**
- **Pre-flight coverage check** (expected vs present bars per `MarketCalendar`) → **422 `DATA_GAP`** with missing windows listed (the [§D.5 error-code pin](#d5-backtest--optimization-job-design-plan-74); §7.4's `INSUFFICIENT_DATA` resolves to the `DATA_*` family code per phases-doc §0.3).
- Replay: BarSeries from 1m/caggs; gates → composite → entries; exits per rules; fills via `ltp_slippage/v1` (next-bar open) with the full `costs` block; **NUMERIC/IST end-to-end**; **single-threaded per job** for determinism; cancel checkpoints every N bars; **TRIAL-kind jobs also handled** — `params_override` applied via the closed path grammar (validated again service-side, [§D.12](#d12-parameter-path-whitelist-grammar-plan-114)) and trial metrics emitted onto `optimizations.results`.
- Migrations: `backtest_runs` (full [§D.3](#d3-backtest-schema-pg-schema-backtest--full-column-tables-plan-64) column set incl. `seed`, `data_hash`, `universe_checksum` NULL, `sharpe_degradation` NULL, `fold_metrics` NULL, `oos_fold_mean/std` NULL, downsampled `equity_curve`, `engine_version`) + `backtest_trades`; secondary indexes per the [§D.3 indexing strategy](#d3-backtest-schema-pg-schema-backtest--full-column-tables-plan-64) (plan §6.7) — `backtest_runs` `(job_id)`, `(strategy_version_id, completed_at DESC)`, `(sharpe DESC)`; `backtest_trades` `(run_id, seq)`. V003 additionally creates the 2026-06-12 NULL-able columns — `premium_source`, `alpha`, `beta`, `information_ratio`, `excess_cagr`, `benchmark_curve`, `montecarlo_summary` on `backtest_runs`; `touch_basis` on `backtest_trades` — additive-first (D17); Phase 30 writes `touch_basis`, Phases 30A/32A populate the rest [FP-4, FP-5, FP-31, FP-32, owner selection 2026-06-12].
- Metrics catalog ([§D.9](#d9-metrics-catalog-leaderboard--promotion-plan-76)): total/annualized return, Sharpe (rf **6.5 %** default, **√252 / √(252×375)** scaling), Sortino, maxDD + duration, win rate, profit factor, expectancy, average trade, exposure, trade count.
- `data_hash` = SHA-256 over ordered (instrument keys, interval, from/to, bar count, max `fetched_at`); `seed` recorded always.
- Endpoints: `GET /backtests/{id}/results` (metrics + curves + dataHash/seed/strategyChecksum), `GET /backtests/{id}/trades` (paged, per-trade contributions).
- **Intra-bar exit detection — replay wiring [FP-5, owner selection 2026-06-12]:** when `exit_intrabar: true`, exits are evaluated against the **cached 1m candles inside each primary bar** (the [§D.11](#d11-fillsimulator-port--full-cost-model-q1-plan-71) A9 rule); where 1m coverage is missing, **primary-bar high/low worst-of** with gap-through fills at bar open; `backtest_trades.touch_basis` recorded per trade (`CLOSE_EVAL | INTRABAR_1M | BAR_HL_WORSTOF`).
- **BTST pre-close bar view + `at_close` fills [FP-6, owner selection 2026-06-12]:** `style: btst` runs evaluate on the deterministic pre-close bar view (1m candles up to `risk.session.pre_close_at`) and fill at the signal bar's close under `fill_timing: at_close` — assembled byte-identically live vs replay.
- **Extended pre-flight [FP-1, FP-3, FP-19, owner selection 2026-06-12]** ([§D.6](#d6-backtest-execution-model--reproducibility-plan-74) execution-model extensions): coverage spans **context instruments** (A7 indicator overrides) at their declared timeframes incl. warm-up (422 `DATA_GAP` naming the context series); a window crossing an unresolved/rebuilt `marketdata.corporate_action_events` row carries an explicit **corporate-action warning**; F&O sizing resolves **lot/tick as-of trade date** from `marketdata.contract_spec_history` with the **pre-accrual honesty flag**.
- **Parity golden test:** same fixture candles tick-wise through the signal engine vs replayed here ⇒ **byte-identical signal lists incl. breakdowns**; metric exactness goldens (exact decimal strings) — [§D.8](#d8-testing--golden-vectors-plan-103-104-106-107-108).
- Metrics `ay_backtest_job_duration_seconds{type}`, `ay_bars_replayed_total` (Stage G Phase 45's dashboards consume them).

**Minimal code/config.** Replay reads SQL **directly — never REST** — via the read-only grant (D10).

**DB changes.** `backtest/V003__runs_trades.sql`.

**Build & Run.**
```
./mvnw -pl services/backtest-service,libs/strategy-engine -am verify
./ay.sh up   # run a backtest on seeded mock candles via curl; fetch results
```

**Tests & Verification.**
- Parity suite green (the **D15 headline gate**); metric goldens green; IT: coverage-gap request → 422 with windows; trial-kind job round-trip emits results onto the stream; throughput sanity ≥ 50k bars/s/worker on mock data (informational).
- 2026-06-12 fixtures [FP-1, FP-3, FP-5, FP-6, FP-19, owner selection 2026-06-12]: intra-bar exit run on fixture 1m candles records `INTRABAR_1M`; the same run with the 1m slice withheld falls back to `BAR_HL_WORSTOF` (gap-through at open) — both deterministic; btst fixture fills at signal-bar close and the pre-close bar view matches the live-side assembly byte-identically; context-instrument gap → 422 `DATA_GAP` naming the context series; pre-accrual lot-size window carries the honesty flag; window crossing a fixture corporate-action event carries the warning.

**Acceptance criteria.**
- PASS: same `(strategyChecksum, seed, dataHash)` ⇒ **byte-identical trade list** across two runs; parity test in CI; every closed trade carries a non-NULL `touch_basis` [FP-5, owner selection 2026-06-12].
- FAIL: replay consuming candles **over REST**; metrics in **float**; a 1m-coverage fallback that fills inside the gap instead of **gap-through at bar open** [FP-5, owner selection 2026-06-12].

**Commit message.** `feat(backtest): replay engine with full metrics catalog, reproducibility triple and live/replay parity goldens`
**PR title.** `Phase 30: backtest replay engine + parity`
**Time estimate.** 120–150 min. **Token size target.** ≤ 40k output tokens.
**If phase too big.** (a) replay + fills + runs/trades persistence; (b) metrics catalog + results/trades endpoints; (c) parity + TRIAL-kind handling; (d) the 2026-06-12 execution-semantics + pre-flight extensions — 1m drill-down exits, btst pre-close view, context/corporate-action/lot-size pre-flight [owner selection 2026-06-12].

---

### Phase 30A — Options replay fidelity contract + synthetic premium mode [FP-4]

*Added by the 2026-06-12 owner feature selection (ADR A10); runs immediately after Phase 30 [FP-4, owner selection 2026-06-12].*

**Objective.** Implement the [§D.15](#d15-options-replay-fidelity-contract--synthetic-premium-mode-adr-a10-fp-4-owner-selection-2026-06-12) fidelity contract: options strategies replay `options_chain_snapshots` at native 5-minute granularity with the pre-flight run against **archive coverage**; a clearly-labelled **synthetic premium mode** reconstructs premiums from underlying 1m candles via Black-76; every run records `premium_source`; the pure Black-76 math is hoisted into a dependency-free `libs/black76-math` shared with market-data-service.

**Why this phase is independent.** Extends the Phase 30 replay along the options path only — the candle replay, parity goldens, and jobs spine are untouched. The Black-76 hoist is pure library refactoring pinned by the existing Phase 14 Greeks goldens (behavior unchanged). Everything is fixture-testable on kite-simulator-seeded mock snapshots with zero Kite credentials.

**Deliverables.**
- `libs/black76-math` — small, dependency-free Black-76 pricing/IV module hoisted out of market-data-service's Phase 14 Greeks code; market-data-service re-pointed at it with **behavior unchanged** (existing Greeks goldens prove byte-identity); backtest-service becomes the second consumer [FP-4, owner selection 2026-06-12].
- **Snapshot-granularity replay:** options premium series read from `options_chain_snapshots` at the archive's native 5-min granularity — never interpolated finer; strategies on sub-5m primary timeframes are refused snapshot-grade replay with an explicit contract message [FP-4, owner selection 2026-06-12].
- **Archive-coverage pre-flight:** expected vs present snapshot slots per `MarketCalendar` → **422 `DATA_GAP`** listing the missing snapshot windows (the [§D.5](#d5-backtest--optimization-job-design-plan-74) error pin, applied to the archive) [FP-4, owner selection 2026-06-12].
- **Synthetic premium mode:** opt-in, clearly-labelled reconstruction of the premium series from underlying 1m candles via `libs/black76-math`; IV from the **nearest archived snapshot** (time-nearest, matching expiry/strike bucket); **flat-IV assumption** for windows predating the archive — both caveats surfaced in the results payload [FP-4, owner selection 2026-06-12].
- **`premium_source` provenance:** `backtest_runs.premium_source` populated on every run (`SNAPSHOT | SYNTHETIC_B76 | NA`; column exists from V003); echoed by `GET /backtests/{id}/results`; leaderboard/compare flag mixed-source comparisons **exactly like `dataHash` mismatches** ([§D.9](#d9-metrics-catalog-leaderboard--promotion-plan-76)) [FP-4, owner selection 2026-06-12].
- `contracts/backtest-service.openapi.json` regenerated (results payload gains `premiumSource` + caveat fields); `gen:api` rerun.

**Minimal code/config.** A synthetic run can **never masquerade as snapshot-grade** — `premium_source` is written before results persist, never defaulted.

**DB changes.** none (`premium_source` exists from Phase 30's `V003__runs_trades.sql`).

**Build & Run.**
```
./mvnw -pl libs/black76-math,services/market-data-service,services/backtest-service -am verify
./ay.sh up   # options backtest over kite-simulator-seeded mock snapshots; verify premiumSource=SNAPSHOT in results
```

**Tests & Verification.**
- `libs/black76-math` goldens: prices and IV inversions as **exact decimal strings** (NUMERIC end to end); market-data-service Greeks goldens **unchanged** post-hoist.
- IT (mock stack): options run on fixture snapshots → `premium_source=SNAPSHOT`; a window with a snapshot gap → 422 `DATA_GAP` listing snapshot windows; the same window in synthetic mode → completes with `SYNTHETIC_B76` + flat-IV caveat in the payload; sub-5m options strategy refused snapshot-grade replay with the contract message; mixed-source leaderboard comparison flagged.
- Determinism: same fixture candles + nearest-snapshot IV ⇒ identical synthetic premium series across two runs.

**Acceptance criteria.**
- PASS: market-data-service Greeks **byte-identical** before/after the hoist; every options run carries a non-NULL `premium_source`; synthetic-mode reruns reproduce identical premium series and metrics.
- FAIL: a synthetic-premium run rendered anywhere without its `SYNTHETIC_B76` flag; a second Black-76 implementation left behind in market-data-service; snapshot premiums interpolated below 5-minute granularity.

**Commit message.** `feat(backtest): options replay fidelity contract with snapshot-granularity replay and black-76 synthetic premium mode`
**PR title.** `Phase 30A: options replay fidelity + synthetic premium`
**Time estimate.** 90–120 min. **Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) `libs/black76-math` hoist + snapshot-granularity replay + archive pre-flight; (b) synthetic premium mode + `premium_source` provenance + leaderboard flagging.

---

### Phase 31 — Walk-forward folds + fold metrics + degradation (BPC, S1B)

**Objective.** Implement `walk_forward` evaluation (rolling/anchored folds), the implicit 70/30 split, `min_trades` validity, per-fold metric persistence with `/folds` endpoints, and the `sharpe_degradation` diagnostic. Full design: guards 1–3 and 7 in [§D.4](#d4-overfitting-guards-17-plan-75).

**Why this phase is independent.** Extends the Phase 30 engine with deterministic fold logic; fully testable on fixture candles.

**Deliverables.**
- Fold expansion from `walk_forward{train_days, test_days, step_days, anchored}`; train-params/test-eval per fold; **headline objective = mean of OOS fold objectives** (default). Without `walk_forward`, the implicit 70/30 chronological split applies **only to optimization/TRIAL-context and stress runs** — plain `POST /backtests/run` jobs stay **full-window** with `sharpe_degradation`/`fold_metrics` **NULL** (guard 1).
- `constraints.min_trades` (default 30): under-trading trials/folds marked **invalid**, never ranked.
- Persistence: `fold_metrics` JSONB (`{fold, train{from,to}, test{from,to}, trainMetrics, oosMetrics, regimeMix: null}` — **`regimeMix` nullable until Phase 32**), `oos_fold_mean`, `oos_fold_std`.
- `sharpe_degradation = train_sharpe − oos_sharpe` (**difference, never ratio**; across-fold means under walk-forward); n/a-suppression flags when train Sharpe < 0.5 or invalid.
- Endpoints: `GET /api/v1/backtests/{id}/folds` (empty when no `walk_forward`); trial-fold route added in Phase 34.
- **Trial-context fold streaming:** TRIAL-kind workers emit each completed fold's OOS objective as an **intermediate message on `optimizations.results`** and honor **cancellation at fold boundaries** — the backtest-service half of fold-fed pruning ([CD-12](#d7-optimizer-execution-model-plan-74-flow-5); Phase 34 consumes it).

**Minimal code/config.** none.

**DB changes.** none (columns exist from Phase 30).

**Build & Run.**
```
./mvnw -pl services/backtest-service -am verify
```

**Tests & Verification.**
- Unit: fold window math (anchored + rolling, step alignment, partial-tail handling); degradation sign conventions incl. negative-Sharpe stability.
- IT: walk-forward run persists N folds with both metric sets; `/folds` payload matches fixture; min_trades exclusion **flagged not silent**.

**Acceptance criteria.**
- PASS: fold ranges reproduce a hand-computed fixture exactly; degradation **NULL** for plain full-window runs.
- FAIL: **ratio-based** overfit score (the rejected S1B formula); folds failing min_trades **silently** included.

**Commit message.** `feat(backtest): walk-forward folds with persisted fold metrics and sharpe-degradation diagnostic`
**PR title.** `Phase 31: walk-forward + fold reporting`
**Time estimate.** 90–120 min. **Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) fold engine + persistence; (b) endpoints + degradation + min_trades.

---

### Phase 32 — Regime attribution + fold_aggregation + stress-test guard (S1A, S1C)

**Objective.** Add **computed (never declared)** regime labels with T−1 benchmark data, per-fold regime mix, the `fold_aggregation` objective knob, and the stress-test contamination guard — completing the anti-overfitting backend block. Full design: guard 6 + S1C in [§D.4](#d4-overfitting-guards-17-plan-75); stress mechanism in [§D.6](#d6-backtest-execution-model--reproducibility-plan-74).

**Why this phase is independent.** Operates on persisted candles + the Phase 31 fold structure; all deterministic and fixture-testable.

**Deliverables.**
- **Regime labeler:** benchmark from `backtest.defaults.benchmark` (default `NSE:NIFTY 50`), read-only from `marketdata`; daily label = **trend (close vs 200-d SMA)** × **volatility (20-d realized vol vs trailing 1-y median)** — four labels; **label for day T uses data through T−1 close only**.
- **Pre-flight extension:** benchmark coverage incl. warm-up (~200 trading days trend, ~1 y + 20 d vol) → 422 if missing; labels never silently partial.
- Fold `regimeMix` populated; per-trade entry-day regime tag persisted with fold metrics; **per-regime OOS aggregates (Sharpe/expectancy per regime label) persisted alongside `fold_metrics`** — the data source for Stage E's leaderboard columns.
- `objective.fold_aggregation: mean|min|mean_minus_std` consumed (default `mean`); folds failing `min_trades` excluded from `min` with an **explicit excluded-count flag**; pruned trials excluded from aggregations (flagged partial-coverage).
- **Stress runs:** `POST /backtests/run` accepts `purpose: stress_test`; window validated against **all** prior jobs of the strategy lineage (sweeps + manual; ranges from `jobs.request`) → **422 `WINDOW_CONTAMINATED`** listing intersecting jobs; `GET /api/v1/backtests/stress-window?strategyId=` suggests the clean window; **holdout-reuse counter** per (strategy, window); `min_trades` shortfall reports **"insufficient sample"**, never pass/fail.

**Minimal code/config.** none.

**DB changes.** none (JSONB shapes + request fields only).

**Build & Run.**
```
./mvnw -pl services/backtest-service -am verify
```

**Tests & Verification.**
- Unit: label math on fixture benchmark series incl. **T−1 boundary** (entry-day close never consulted); aggregation knob truth table; exclusion flags.
- IT: stress submission overlapping a prior quick-backtest → 422 with the **offending jobIds**; clean-window suggestion correct; reuse counter increments.

**Acceptance criteria.**
- PASS: same fixtures ⇒ identical labels across runs; contamination guard catches **manual-backtest leaks (not only sweeps)**.
- FAIL: any **hand-labeled** regime date range; **same-session look-ahead** in labels.

**Commit message.** `feat(backtest): computed t-1 regime attribution, fold-aggregation objective and stress-test contamination guard`
**PR title.** `Phase 32: regime attribution + stress-test guard`
**Time estimate.** 120–150 min. **Token size target.** ≤ 35k output tokens.
**If phase too big.** (a) regime labeler + regimeMix + aggregation knob; (b) stress-test endpoints + lineage validation.

---

### Phase 32A — Benchmark-relative metrics + Monte Carlo run analytics [FP-31, FP-32]

*Added by the 2026-06-12 owner feature selection; runs immediately after Phase 32 [FP-31, FP-32, owner selection 2026-06-12].*

**Objective.** Implement [§D.16](#d16-run-analytics-benchmark-relative-metrics--monte-carlo-fp-31-fp-32-owner-selection-2026-06-12): benchmark-relative metrics (alpha, beta, information ratio, excess CAGR; optional up/down capture) against `backtest.defaults.benchmark`, the benchmark buy-and-hold curve beside `equityCurve`, and on-demand seeded Monte Carlo bootstrap of persisted trades via `GET /api/v1/backtests/{id}/montecarlo` with the summary persisted in `montecarlo_summary`.

**Why this phase is independent.** Pure post-processing over rows Phases 30–32 already persist, plus the benchmark series guard 6 already reads and pre-flights — no engine change, no schema change (all columns exist from V003), no parity impact. Fully fixture-testable on the mock stack.

**Deliverables.**
- **Benchmark-relative computation at run completion:** `alpha`, `beta`, `information_ratio`, `excess_cagr` persisted on `backtest_runs` (columns from V003); up/down capture returned in the results payload only; computed against `backtest.defaults.benchmark` (default `NSE:NIFTY 50`), read-only from `marketdata`; **NULL — flagged, never silently zero — when benchmark coverage is absent**; the **price-index-not-TRI caveat** rendered with the metrics [FP-32, owner selection 2026-06-12].
- **Benchmark buy-and-hold curve:** normalized benchmark equity over the run window, same ~500-point downsampler, persisted in `benchmark_curve` and returned **beside `equityCurve`** by `GET /backtests/{id}/results` [FP-32, owner selection 2026-06-12].
- **`GET /api/v1/backtests/{id}/montecarlo`** ([§D.5](#d5-backtest--optimization-job-design-plan-74) endpoint table): seeded bootstrap of the run's persisted `backtest_trades` (trade-sequence resampling with replacement), **N = 1000 default** with per-request `?n=&seed=` overrides; returns drawdown distribution, 5/50/95 equity bands, risk-of-ruin, CIs on CAGR/Sharpe; **MC seed recorded beside the run seed**; summary persisted in `backtest_runs.montecarlo_summary` on first computation and served from there on repeat calls; 422 when the run has no closed trades; runs below `min_trades` framed **"insufficient sample"**, bands reported wide rather than hidden [FP-31, owner selection 2026-06-12].
- [§D.9](#d9-metrics-catalog-leaderboard--promotion-plan-76) metric-catalog rows live; `contracts/backtest-service.openapi.json` regenerated; `gen:api` rerun.

**Minimal code/config.** All money math in **NUMERIC/BigDecimal**; Monte Carlo never reruns a replay — it resamples persisted trades only.

**DB changes.** none (`alpha`/`beta`/`information_ratio`/`excess_cagr`/`benchmark_curve`/`montecarlo_summary` exist from Phase 30's `V003__runs_trades.sql`).

**Build & Run.**
```
./mvnw -pl services/backtest-service -am verify
./ay.sh up   # backtest on seeded mock candles; GET /api/v1/backtests/{id}/montecarlo twice -> identical summaries
```

**Tests & Verification.**
- Unit: alpha/beta/information-ratio/excess-CAGR on hand-computed fixture return series matched as **exact decimal strings**; up/down-capture edge cases (all-up window, all-down window); benchmark-coverage-absent → NULLs with flag.
- MC determinism: same `(trade list, mcSeed, N)` ⇒ byte-identical bands/summary; different explicit seed ⇒ recompute-and-replace of `montecarlo_summary`.
- IT (mock stack): `montecarlo` endpoint computes once, persists the summary, and serves the persisted summary on the second call; zero-trade run → 422; `benchmark_curve` timestamps align with `equity_curve`.

**Acceptance criteria.**
- PASS: fixed MC seed reproduces an **identical `montecarlo_summary`** across calls; benchmark metrics match the hand-computed fixtures exactly; benchmark curve overlays `equityCurve` on the same downsampled time base.
- FAIL: Monte Carlo mutating any replay result or metric; benchmark-relative metrics reported as **0 instead of NULL** when benchmark data is missing; MC implemented as a replay rerun.

**Commit message.** `feat(backtest): benchmark-relative metrics, buy-and-hold curve and seeded monte carlo run analytics`
**PR title.** `Phase 32A: benchmark metrics + Monte Carlo`
**Time estimate.** 90–120 min. **Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) benchmark-relative metrics + benchmark buy-and-hold curve; (b) Monte Carlo endpoint + persisted summary.

---

### Phase 33 — optimizer-service core: sweeps (grid/random) + trial loop

**Objective.** Create the Python optimizer: FastAPI app, sweep submission, Optuna ask/tell with grid/random samplers, trial dispatch over `jobs.backtest.trials`, result collection from `optimizations.results`, and trial persistence — end-to-end against the running backtest-service. Execution model: [§D.7](#d7-optimizer-execution-model-plan-74-flow-5).

**Why this phase is independent.** backtest-service already executes TRIAL jobs (Phase 30); a small sweep completes fully on the mock stack. **Entry gate:** the **S3 pruner-calibration spike** ([§D.13](#d13-s3-spike--pruner-calibration-plan-172) — a pure-Python study, no service dependency) runs at this stage boundary; Phase 34's pruning defaults consume its outputs.

**Deliverables.**
- `services/optimizer-service/` — **Python 3.12 + FastAPI 0.115**; `/health`; `prometheus-fastapi-instrumentator`; structlog JSON; Dockerfile (`python:3.12-slim`, `mem_limit: 256m`) + compose entry (internal **8084**); **`requirements.txt` with hashes ([CD-15](#d1-service-specs-backtest-service--optimizer-service-plan-524-525-adr-d7))**; ruff.
- `POST /api/v1/optimizations/run` — validates the `optimize` block + path grammar (mirror of [§D.12](#d12-parameter-path-whitelist-grammar-plan-114)); parent OPTIMIZATION job row; `202 {jobId}`.
- Ask/tell loop: Optuna 4.x **in-memory study per sweep** (`GridSampler`/`RandomSampler`); materialize patched config per trial (**never persisted as a version**); **per trial: INSERT a queued `jobs` row (kind=TRIAL, `parent_job_id`=sweep) — the authoritative record the worker's conditional claim requires (D12) — then `XADD jobs.backtest.trials`**; consume `optimizations.results` via `cg-optuna`; `study.tell`; sweep progress + best-so-far on `jobs.progress`.
- Persistence: `optimization_trials` migration ([§D.3](#d3-backtest-schema-pg-schema-backtest--full-column-tables-plan-64); app-managed, resumable via `study.add_trial` replay; indexes per the §D.3 indexing strategy, plan §6.7 — unique `(sweep_job_id, trial_number)` + `(sweep_job_id, state)`); **seeded-sampler reproducibility**.
- Endpoints: `GET /optimizations/jobs/{jobId}` (status/progress/bestSoFar), `GET /optimizations/{sweepId}/trials` (paged/sorted), `DELETE /optimizations/jobs/{jobId}` (cancel: queued trials dropped, running finish/abort at checkpoint).
- pytest 8 + respx: expansion math, dispatch loop with stubbed Streams/REST, cancellation; **spec-derived respx stubs verify the optimizer→backtest consumer contract** ([§D.8](#d8-testing--golden-vectors-plan-103-104-106-107-108)).
- `.github/workflows/ci-optimizer.yml` — ruff + pytest (**≥ 75 % line**) → image → GHCR; **path-filtered**, gitleaks step.
- `contracts/optimizer-service.openapi.json` (**FastAPI-native dump**) committed + diff-gated in `ci-contracts`.

**Minimal code/config.** Optimizer **never evaluates a strategy itself** — it only proposes parameter vectors (D6).

**DB changes.** `backtest/V004__optimization_trials.sql`.

**Build & Run.**
```
cd services/optimizer-service && pip install -r requirements.txt && pytest
./ay.sh up   # POST a 10-trial grid sweep; watch jobs.progress and the trials endpoint
```

**Tests & Verification.**
- pytest green; IT-style compose check: a **10-trial grid sweep** over the seed strategy completes with all trials COMPLETE and persisted, progress reaching 100.

**Acceptance criteria.**
- PASS: fixed sampler seed reproduces the **identical trial sequence** (golden, [§D.8](#d8-testing--golden-vectors-plan-103-104-106-107-108)); sweep survives an optimizer container restart (re-queue from table + `add_trial` replay); a sweep's TRIAL row count in `jobs` **equals** its dispatched stream entries.
- FAIL: optimizer writing **strategy versions directly to the DB** (must go via REST — and only in Phase 34's promote).

**Commit message.** `feat(optimizer): fastapi + optuna ask/tell core with grid/random sweeps over redis streams`
**PR title.** `Phase 33: optimizer-service core`
**Time estimate.** 120–150 min. **Token size target.** ≤ 35k output tokens.
**If phase too big.** (a) service skeleton + run endpoint + grid expansion; (b) ask/tell loop + persistence + cancel.

---

### Phase 34 — optimizer: TPE/NSGA-II + pruning + leaderboard + promote

**Objective.** Complete the optimizer: TPE and NSGA-II samplers, fold-fed MedianPruner early stopping, the leaderboard with plateau-adjusted default sort, and winner promotion to a new draft via strategy-signal REST. Leaderboard/promote design: [§D.9](#d9-metrics-catalog-leaderboard--promotion-plan-76); pruning transport: [CD-12](#d7-optimizer-execution-model-plan-74-flow-5).

**Why this phase is independent.** Pure extension of Phase 33; verified by sweeps on the mock stack and pytest.

**Deliverables.**
- `TPESampler` (**constant-liar** for the parallel pool) + `NSGAIISampler` (multi-objective; **Pareto front, never collapsed to one number**).
- **Fold-fed pruning ([CD-12](#d7-optimizer-execution-model-plan-74-flow-5)):** trial workers stream per-fold intermediate OOS objectives (emitted by Phase 31's workers); optimizer reports to `MedianPruner`; pruned → **cancel via job-cancel path**; pruned trials flagged **partial-coverage** and **excluded from `min`/`mean_minus_std`** aggregations. Pruning defaults (`n_startup_trials`, warm-up folds) come from the **S3 spike — a hard gate** per the decisions-log SPIKES row: its outputs are recorded as a dated amendment (decisions log §4.5) and configured here ([§D.13](#d13-s3-spike--pruner-calibration-plan-172)).
- `GET /api/v1/optimizations/{sweepId}/best?top=N` — metric matrix; **plateau-adjusted default sort** (median of already-computed neighbor trials within ±1 step/ε-ball; raw sort one param away); `dataHash` parity flags.
- `GET /optimizations/{sweepId}/trials/{trialId}/folds` (resolves via `backtest_run_id`).
- `POST /optimizations/{sweepId}/promote {trialId}` → applies trial params onto the source version → `POST` new **draft** via strategy-signal REST with provenance notes (`created_by='optimizer:{jobId}'`); **409 for invalid/failed trials**.
- Early stopping (`earlyStopping: true`): stop when best hasn't improved over a configured trial window.

**Minimal code/config.** none.

**DB changes.** none.

**Build & Run.**
```
cd services/optimizer-service && pytest
./ay.sh up   # 30-trial TPE sweep on mock; promote the winner; verify a new draft exists
```

**Tests & Verification.**
- pytest: TPE/NSGA-II expansion, **pruning decision table**, plateau-sort math, promote payload.
- Compose check: promote creates a draft whose **diff shows exactly the trial's parameter deltas**; audit row records optimizer provenance.

**Acceptance criteria.**
- PASS: NSGA-II sweep returns a **Pareto front**; pruned trials **never appear in `min` aggregations**; promoted winner is a **draft** (never published); **S3 spike executed and its outputs recorded as the configured pruner defaults — or pruning explicitly disabled until they are** (gate checkbox in `PHASE_GATES.md`); **Stage-D exit — `PHASE_GATES.md` mirrors the plan §15.2 Phase-3 row** (the [Stage exit gate](#part-3--stage-exit-gate-plan-152-phase-3) below).
- FAIL: pruning fed by **train/OOS divergence** (rejected S1B design) instead of OOS fold medians.

**Commit message.** `feat(optimizer): tpe/nsga2 samplers, fold-fed median pruning, plateau leaderboard and draft promotion`
**PR title.** `Phase 34: optimizer TPE/NSGA-II + promote`
**Time estimate.** 90–120 min. **Token size target.** ≤ 30k output tokens.
**If phase too big.** (a) samplers + pruning; (b) leaderboard + folds route + promote.

---

## Part 3 — Stage exit gate (plan §15.2 Phase-3 row)

Stage D closes when the plan §15.2 / [COMMON §16.2](ARTHAYANTRA_2_COMMON_REFERENCE.md#162-phase-plan-deliverables--acceptance-criteria-the-stage-exit-gates) **Phase-3 acceptance row** is green. The closing phase (34) copies this checklist into `PHASE_GATES.md`; the standing **Friday gate review (S5 ritual)** walks it against the running mock stack — any unchecked box **extends the phase, never the reverse** (plan §15.6).

**Phase 3 / Stage D — key deliverables (for context):** backtest-service worker pool (cores−2), candle replay through the **same** engine JAR, metrics (returns, Sharpe, maxDD, win rate, trades), PG `jobs` table + Redis Streams (D12); optimizer-service: FastAPI 0.115 + Optuna 4.x ask/tell, grid/random/TPE/NSGA-II over `optimize.parameters`, winners promoted to drafts. **Review additions:** computed regime attribution + `fold_aggregation` knob (S1A, +3 d); `sharpe_degradation` diagnostic column (S1B, +0.5 d); stress-test backend — `purpose: stress_test` tag, lineage window-overlap validation, suggested-window endpoint (S1C, +1 d); per-fold metrics persistence + `/folds` endpoints (BPC, +1 d); `FillSimulator` port in the engine JAR + full cost model — `slippage_ticks`/`slippage_bps` with per-class fallbacks, fill golden vectors (Q1, +1 d). **Owner-selection additions (2026-06-12):** options replay fidelity contract + synthetic premium mode incl. the `libs/black76-math` hoist (Phase 30A, A10 [FP-4]); benchmark-relative metrics + Monte Carlo run analytics (Phase 32A [FP-31, FP-32]); A9 execution-semantics extensions in Phases 29/30 — futures cost legs [FP-7], `at_close` fills [FP-6], intra-bar exit-touch + `touch_basis` [FP-5], BTST pre-close bar view [FP-6]; extended pre-flight — context instruments [FP-19], corporate-action warning [FP-1], lot-size as-of [FP-3].

**Acceptance criteria (demo-able) — the exit checklist:**

- [ ] `POST /api/v1/backtests/run` → **`202 {jobId}`** → progress via `jobs.progress` WS (`/topic/jobs/{jobId}`).
- [ ] **Engine-parity test passes** — same YAML + candles ⇒ **identical trades live vs. backtest** (byte-identical signal lists incl. per-indicator breakdowns; the D15 headline gate, Phase 30).
- [ ] **A 200-trial sweep completes and ranks configs** (grid/random/TPE/NSGA-II over `optimize.parameters`; leaderboard with plateau-adjusted sort; winner promotable to a **draft**).
- [ ] **S3 spike gate** (Phase 34 acceptance): pruner defaults (`n_startup_trials`/warm-up folds) recorded as a dated ADR amendment and configured — **or pruning explicitly disabled** until they are (checkbox in `PHASE_GATES.md`).
- [ ] **A9 execution semantics green (Phases 29/30)** [FP-5, FP-6, FP-7, owner selection 2026-06-12]: fill vectors pass for **futures cost legs**, **`at_close` fills**, and the **intra-bar exit-touch rule** (1m drill with worst-of/gap-through fallback; every closed trade records `touch_basis`); the **BTST pre-close bar view** assembles byte-identically live vs replay.
- [ ] **Extended pre-flight demonstrated (Phase 30)** [FP-1, FP-3, FP-19, owner selection 2026-06-12]: context-instrument coverage (422 `DATA_GAP` naming the context series), corporate-action window warning, lot/tick **as-of trade date** resolution with the pre-accrual honesty flag.
- [ ] **Options fidelity contract live (Phase 30A)** [FP-4, owner selection 2026-06-12]: options run on mock snapshots records `premium_source=SNAPSHOT`; archive gap → 422 `DATA_GAP` against snapshot coverage; synthetic mode completes flagged `SYNTHETIC_B76` (never masquerading as snapshot-grade); market-data-service Greeks byte-identical after the `libs/black76-math` hoist.
- [ ] **Run analytics live (Phase 32A)** [FP-31, FP-32, owner selection 2026-06-12]: results carry alpha/beta/information-ratio/excess-CAGR + the benchmark buy-and-hold curve beside `equityCurve`; `GET /api/v1/backtests/{id}/montecarlo` returns seeded, reproducible bands and persists `montecarlo_summary`.

**Stage-end notes.**
- The Stage E (frontend) leaderboard UI consumes the **per-regime OOS columns**, the **degradation badge**, the **"n folds excluded" flag**, and the **fold breakdown panel** produced here; the Stage E **advisory stress-test panel** in the publish dialog consumes the Phase 32 stress-test backend (warns, never blocks). Those UI pieces are **Stage E deliverables**, not Stage D.
- **Universe-pinning** completes in **Stage F**: Stage D leaves `backtest_runs.universe_checksum` NULL-able and writes it only when the submission already carries a resolved universe; the resolve-at-submission wiring + the editor "Published Universe (as of …)" label land in Stage F.
- **Paper fill-audit wiring** (`FillSimulator` id, `slippage_applied`, quote columns on `paper_orders`) lands in **Stage F** — the `FillSimulator` JAR itself is complete here (Phase 29).
- **Critical-path position:** Stage D is on the strictly sequential critical path **A → B → C → D → G** (plan §15.3). The optimizer (Phase 33–34, "3b") can overlap Stage E frontend work (different stack, zero shared files) and is good "blocked on live-market window" filler. Stage G hardening (k6 ≤ 150 ms p99 gate, RAM tuning) comes last.
- **2026-06-12 cross-stage seams [owner selection 2026-06-12]:** Phase 30A consumes Stage B's `options_chain_snapshots` archive and hoists `libs/black76-math` out of Phase 14's Greeks code (behavior unchanged, goldens prove it); the extended pre-flight and replay sizing consume Stage B's Phase 9A (`marketdata.contract_spec_history`), Phase 15B (CONT series + `marketdata.roll_events`) and Phase 16A (`marketdata.corporate_action_events`); Stage E's results UI consumes the `premium_source` flags, benchmark curve and Monte Carlo bands produced here; Stage F's paper ledger inherits the same A9 `FillSimulator` semantics (futures legs, `at_close`, intra-bar touch at the 1m floor) by construction via the shared JAR.





