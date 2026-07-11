-- Research-fidelity audit §11.4 (roadmap #20) / §13 rows 8 + 11: the "unified experiment/event read
-- layer" — a read-side contract inside backtest-service so Prompt 2 / EVO target ONE experiment API
-- instead of scraping the jobs table and stitching per-run fetches client-side.
--
-- §11.4 codifies the join graph as SQL views. This migration lands the ONE join that backtest-service
-- OWNS: `version -> run(s) -> trades`. A completed engine replay is one row in `backtest_runs`; its
-- originating `jobs` row carries the discriminator (BACKTEST vs a sweep TRIAL, `parent_job_id` linking
-- a trial to its sweep), the resolved strategy id/version, the submission `purpose`, and the actor
-- (`created_by`, V009). One row per completed run = one "experiment instance" (§9.3 runs-as-experiments;
-- a plain full-window backtest AND every optimizer trial each surface here).
--
-- The other §11.4 views (version -> signals -> paper; rejection -> shadow) live in the STRATEGY schema,
-- are owned by strategy-signal-service, and depend on the "materialize signal->book at write time" work
-- (audit T1, a SEPARATE roadmap item #19) — deliberately NOT built here, keeping the schema boundary the
-- audit itself preserves ("backtest owns research runs").
--
-- trade_count already lives on the run row (no trade-table aggregation needed); cagr is lifted out of the
-- metrics JSONB so the compare endpoint reads a flat column. A non-materialized view = zero storage,
-- always live against the base tables, no refresh policy. GRANT SELECT to the read-only per-schema role
-- (D10) mirroring the base-table grants (V003:78); services connect as `artha` (single-writer) but the
-- SET ROLE grant tests assert the read-only role can reach research reads.
CREATE VIEW experiment_runs AS
SELECT
  br.id                                   AS run_id,
  br.job_id                               AS job_id,
  j.kind                                  AS kind,
  j.parent_job_id                         AS parent_job_id,
  br.strategy_version_id                  AS strategy_version_id,
  j.request ->> 'strategyId'              AS strategy_id,
  j.request ->> 'strategyVersion'         AS strategy_version,
  j.request ->> 'purpose'                 AS purpose,
  br.exchange                             AS exchange,
  br.tradingsymbol                        AS tradingsymbol,
  br."interval"                           AS "interval",
  br.start_ts                             AS start_ts,
  br.end_ts                               AS end_ts,
  br.total_return                         AS total_return,
  br.metrics ->> 'cagr'                   AS cagr,
  br.sharpe                               AS sharpe,
  br.sortino                              AS sortino,
  br.max_drawdown                         AS max_drawdown,
  br.win_rate                             AS win_rate,
  br.profit_factor                        AS profit_factor,
  br.trade_count                          AS trade_count,
  br.alpha                                AS alpha,
  br.beta                                 AS beta,
  br.information_ratio                    AS information_ratio,
  br.data_hash                            AS data_hash,
  br.universe_checksum                    AS universe_checksum,
  br.premium_source                       AS premium_source,
  br.engine_sha                           AS engine_sha,
  br.created_by                           AS created_by,
  br.completed_at                         AS completed_at
FROM backtest_runs br
JOIN jobs j ON j.id = br.job_id;

GRANT SELECT ON experiment_runs TO ay_backtest;
