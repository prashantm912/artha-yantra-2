-- Data-Ops console audit trail: one row per backfill RUN. Coverage (expired_contracts) records what
-- data exists and the StatusPage polls the CURRENT live job, but neither keeps a history of the runs
-- themselves. This table is that run-audit ledger — kind (EXPIRED / EQUITY_DAILY), the request params,
-- the terminal status (RUNNING → COMPLETED | FAILED), rows written, any error, and timings — surfaced
-- read-only at GET /api/v1/market/admin/backfill-jobs. Written fail-soft: a logging-DB hiccup must
-- never break the backfill it is auditing.
CREATE TABLE backfill_jobs (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind         TEXT         NOT NULL,                       -- EXPIRED / EQUITY_DAILY
    params       JSONB,                                       -- the trigger request (indices, window, ...)
    status       TEXT         NOT NULL,                       -- RUNNING / COMPLETED / FAILED
    rows_written BIGINT,
    error        TEXT,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

-- The history read is newest-first over the whole (small) ledger.
CREATE INDEX ix_backfill_jobs_started_at ON backfill_jobs (started_at DESC);
