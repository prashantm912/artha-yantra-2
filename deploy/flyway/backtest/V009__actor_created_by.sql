-- Research-fidelity audit §5 T3 / EVO §13 row 4: job + run rows carry NO actor identity, so the
-- owner (API), a scheduled/optimizer batch, and the future EVO engine are indistinguishable on the
-- backtest lineage. `strategy_versions.created_by` already exists (strategy V002:36, default 'owner')
-- — but `jobs` and `backtest_runs` have no equivalent. EVO needs "who created this" (human vs engine)
-- as a first-class, prefix-filterable column BEFORE campaigns write versions/jobs/runs.
--
-- Vocabulary (small, prefix-parseable): 'owner' (human via API / UI, and system-seeded rows),
-- 'optimizer:{sweepId}' (optimizer-promoted versions + TRIAL jobs + their runs), 'optimizer' (the
-- OPTIMIZATION sweep parent, whose own id IS the sweep id), 'scheduler:<name>' (scheduled batches),
-- 'evo:{campaignId}' (future). The run row inherits its parent JOB's created_by (a run exists on
-- behalf of whoever submitted the job — the worker copies it forward, mirroring how engine_sha
-- differs between the submitting service and the executing worker in V008).
--
-- Additive + nullable: pre-existing rows stay NULL (no actor was recorded then, and back-filling a
-- guess would be worse than an honest NULL). Existing table GRANTs cover all columns (V002:30 jobs,
-- V003:78 backtest_runs are table-level DML to ay_backtest), so no new grant is needed.
ALTER TABLE jobs          ADD COLUMN created_by TEXT;
ALTER TABLE backtest_runs ADD COLUMN created_by TEXT;
