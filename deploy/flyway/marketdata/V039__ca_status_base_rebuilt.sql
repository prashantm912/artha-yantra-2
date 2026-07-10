-- A14 (2026-07-10 corporate-action rebuild hardening): the remediation pipeline now checkpoints a
-- symbol at BASE_REBUILT once its base 1m/1d re-backfill COMMITS but BEFORE the chunked cagg
-- refresh — so a hard crash mid-refresh (the 3× live-Postgres OOM incident) is RESUMABLE
-- (refresh-only) instead of a misleading FAILED that a retry would re-purge ~12 years for. Widen
-- the status CHECK to admit the new BASE_REBUILT state. V006_2 is applied + checksum-locked, so
-- this is a new suffix migration (never an in-place edit). The inline column CHECK auto-names as
-- <table>_<column>_check.
ALTER TABLE marketdata.corporate_action_events
  DROP CONSTRAINT IF EXISTS corporate_action_events_status_check;

ALTER TABLE marketdata.corporate_action_events
  ADD CONSTRAINT corporate_action_events_status_check
  CHECK (status IN ('DETECTED', 'REBACKFILL_RUNNING', 'BASE_REBUILT', 'RESOLVED', 'FAILED'));
