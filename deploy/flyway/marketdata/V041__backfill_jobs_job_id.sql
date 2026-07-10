-- App-platform audit §8 V12 — backfill uuid ↔ ledger row.
-- The backfill trigger endpoints mint an in-memory jobId UUID and return it to the caller
-- ({"jobId": <uuid>}), but that handle was never persisted, so a backfill_jobs ledger row could not
-- be joined back to the status poll that quotes the same UUID. Persist it as an ADDITIVE, nullable
-- column (pre-existing rows have no handle) with an index for the correlation lookup. Plain table
-- (V030 GENERATED-IDENTITY PK), so this is a plain column + index — no hypertable concerns.
--
-- The OI-backfill path (kind='OI') now writes its own ledger rows carrying this handle too — it wrote
-- NO row before this change — but that needs no schema change (kind is free TEXT).
ALTER TABLE backfill_jobs ADD COLUMN job_id UUID;

-- Correlation lookup: resolve the ledger row for a given API job handle.
CREATE INDEX ix_backfill_jobs_job_id ON backfill_jobs (job_id);
