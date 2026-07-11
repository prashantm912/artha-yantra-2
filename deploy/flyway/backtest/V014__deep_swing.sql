-- Research-fidelity audit P0-3 (§10 / §12 item 16 / R3 / B13): the swing DEEP-SIM lineage hole. The
-- Manas Arora + Minervini ~11-year deep-sim backtests (market-data's ManasAroraBacktestService /
-- MinerviniBacktestService) wrote only a report JSONB into marketdata.{manas_arora,minervini}_backtest_runs
-- — no job row, no run row, no engine SHA, no per-trade rows — so EVO portfolio scoring could not consume
-- them and a swing doctrine number could not be mechanically reproduced from a run row.
--
-- The DECIDED route (the "job-pipeline route", not a parallel lineage): a NEW backtest-service job kind
-- DEEP_SWING whose worker CALLS the existing market-data deep-sim over HTTP and persists the outcome into
-- the SHIPPED backtest lineage — a backtest_runs row (total_return / sharpe / max_drawdown / trade_count +
-- the full deep-sim report kept intact in the metrics JSONB) plus per-trade backtest_trades rows, stamped
-- with engine_sha/engine_image (V008) + created_by (V009). The existing direct market-data endpoints STAY
-- (owner uses them); the job route is purely additive.
--
-- ONE change here, additive/non-destructive: widen the jobs.kind CHECK to admit 'DEEP_SWING' (the smallest
-- honest identity — a deep-swing job carries no single-instrument strategy replay; its {family, from,
-- variant} rides the request JSONB). It rides the EXISTING queue/WorkerPool (dispatched onto jobs.backtest,
-- claimed like a BACKTEST — the COUNTERFACTUAL precedent, V013), so the two crash-recovery kind filters in
-- JobRepository (requeueStaleRunning / findQueuedIds) are widened to re-queue a DEEP_SWING job on boot. No
-- new result table is needed — DEEP_SWING writes backtest_runs/backtest_trades, so:
--   * the monthly pruneStaleTerminal guard `NOT EXISTS (backtest_runs r ...)` ALREADY protects its result row;
--   * the experiment_runs view (V010) surfaces DEEP_SWING runs automatically (it has no kind filter — it
--     JOINs every backtest_runs row to jobs and exposes j.kind), so EVO / the compare endpoint see them.
-- jobs is a plain table (not a compression-enabled hypertable), so the CHECK swap is ordinary transactional
-- DDL. Existing table GRANTs cover everything (no new object), so no new grant is needed.

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('BACKTEST', 'OPTIMIZATION', 'TRIAL', 'COUNTERFACTUAL', 'DEEP_SWING'));
