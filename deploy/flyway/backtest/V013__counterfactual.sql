-- EVO E3 item 9 (§3.3.4 / §12): the counterfactual-replay job type — the live-signal-analysis
-- runbook's §4.B "counterfactual band grid" formalized as a submittable job. It replays REAL observed
-- entries' CAPTURED option premium under alternative exit-knob sets, producing per-variant PnL/metrics.
-- This is the offline evidence plane for LIVE_FIRST exit-knob candidates the shadow book cannot express
-- (premium exit-band changes) — captured (real) candle premium only, never derived history.
--
-- Two changes, both additive/non-destructive:
--   1. Widen the jobs.kind CHECK to admit 'COUNTERFACTUAL' (the smallest honest identity — a
--      counterfactual job carries no strategy replay / no single instrument, so kind='BACKTEST' with a
--      request discriminator would masquerade as a strategy run in the jobs list). The new kind rides
--      the EXISTING queue/WorkerPool (dispatched onto jobs.backtest, claimed like a BACKTEST) — no new
--      stream. The three kind-hardcoded queue-consumer sites (requeueStaleRunning / findQueuedIds /
--      pruneStaleTerminal) are widened in JobRepository so crash-recovery re-queues a counterfactual
--      job and the monthly prune protects its result row. jobs is a plain table (not a compression-
--      enabled hypertable), so the CHECK swap is ordinary transactional DDL — no decompress dance.
--   2. counterfactual_runs — one row per completed counterfactual job (resultRef = its id, keyed like
--      backtest_runs). The per-variant metrics live in the variants JSONB (an N-variant, M-entry result
--      does not fit the single-instrument/single-strategy-version backtest_runs shape).

ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check
    CHECK (kind IN ('BACKTEST', 'OPTIMIZATION', 'TRIAL', 'COUNTERFACTUAL'));

CREATE TABLE counterfactual_runs (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id        UUID NOT NULL REFERENCES jobs(id),   -- the originating COUNTERFACTUAL job
  window_from   TIMESTAMPTZ NOT NULL,                -- the request's [from, to) entry-eligibility window
  window_to     TIMESTAMPTZ NOT NULL,
  entry_count   INT NOT NULL,                        -- observed entries replayed
  variant_count INT NOT NULL,                        -- exit-knob variants scored
  variants      JSONB NOT NULL,                      -- per-variant metrics (net PnL Rs + premium points,
                                                     -- win rate, expectancy, maxDD, exit-reason counts)
  created_by    TEXT,                                -- audit actor (T3): 'owner' on the API path
  completed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per job (crash-recovery re-run replaces via DELETE-by-job_id, mirroring backtest_runs).
CREATE UNIQUE INDEX uq_counterfactual_runs_job ON counterfactual_runs (job_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON counterfactual_runs TO ay_backtest;
