-- Stage D Phase 33 (§D.3): per-sweep trial ledger, app-managed (Optuna runs
-- in-memory per sweep; a sweep resumes by replaying these rows through
-- study.add_trial). One row per ask()/tell() cycle, keyed by the sweep's jobs row.

CREATE TABLE optimization_trials (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sweep_job_id     UUID NOT NULL REFERENCES jobs(id),
  trial_number     INT NOT NULL,
  params           JSONB NOT NULL,             -- sampled values keyed by optimize.parameters[].path
  objective_values JSONB,                      -- multi-objective (NSGA-II) safe; NULL until told
  state            TEXT NOT NULL DEFAULT 'RUNNING'
                     CHECK (state IN ('RUNNING', 'COMPLETE', 'PRUNED', 'FAILED')),
  backtest_run_id  UUID,                        -- soft link to the run that scored it (no FK: cross-table lifecycle)
  started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at     TIMESTAMPTZ
);

-- §D.3 indexing strategy (plan §6.7): unique trial number per sweep; state filter for leaderboards.
CREATE UNIQUE INDEX uq_trials_sweep_number ON optimization_trials (sweep_job_id, trial_number);
CREATE INDEX idx_trials_sweep_state ON optimization_trials (sweep_job_id, state);

GRANT SELECT, INSERT, UPDATE, DELETE ON optimization_trials TO ay_backtest;
