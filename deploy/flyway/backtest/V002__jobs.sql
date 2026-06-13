-- Stage D Phase 28 (D12 / §D.3): the authoritative `jobs` table — Postgres is
-- the single source of truth, Redis Streams are transport only. One table
-- discriminated by `kind` (BACKTEST / OPTIMIZATION / TRIAL) realizes the spec's
-- separate backtest_jobs / optimization_jobs. `parent_job_id` self-FK links a
-- sweep to its trials. Workers claim idempotently against this row (conditional
-- UPDATE … WHERE status='queued'); stale `running` rows are re-queued on boot.

CREATE TABLE jobs (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  kind                TEXT NOT NULL CHECK (kind IN ('BACKTEST', 'OPTIMIZATION', 'TRIAL')),
  parent_job_id       UUID REFERENCES jobs(id),
  status              TEXT NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('queued', 'running', 'completed', 'failed', 'cancelled')),
  progress            SMALLINT NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  strategy_version_id UUID,                       -- soft cross-schema ref (strategy schema; no FK)
  request             JSONB NOT NULL,             -- symbols, interval, range, method, overrides, universe, purpose
  error               TEXT,                       -- populated on failure
  worker_id           TEXT,                       -- owner for stale-running re-queue on startup
  correlation_id      TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at          TIMESTAMPTZ,
  finished_at         TIMESTAMPTZ
);

-- §D.3 indexing: partial index on live statuses for the claim / re-queue scans;
-- parent index for sweep -> trial rollups.
CREATE INDEX idx_jobs_live ON jobs (status, created_at) WHERE status IN ('queued', 'running');
CREATE INDEX idx_jobs_parent ON jobs (parent_job_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON jobs TO ay_backtest;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA backtest TO ay_backtest;
