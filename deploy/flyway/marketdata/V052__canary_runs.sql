-- task_e2e01c: durable "this canary already ran for this IST day" marker, so a boot-time catch-up
-- can replay a morning canary whose @Scheduled cron ticked while the stack was down (E2E audit
-- 2026-07-31 §2.1: down 02:29-08:56 IST, the 08:45 ingest-coverage sweep never fired and Spring
-- never replays a missed fire). An in-memory flag is defeated by exactly the restart this exists
-- for, so the marker is a row.
--
-- run_day is the IST calendar day the canary ran FOR (derived in Java from the clock via Ist.ZONE,
-- never `now()::date` — the container clock is UTC, so a 02:xx-IST row lands on the previous
-- calendar day there). `source` records which door the run came through: SCHEDULED (the cron) or
-- BOOT_CATCHUP (this feature).
--
-- ⚠️ The row is TWO-STATE, and that is the whole point (cross-vendor review round 2, Critical).
-- A single-state "claimed" row commits BEFORE the alert is published, so a process that died — or a
-- publish that threw — between the two left the day permanently claimed and every later door
-- suppressed an alert that never went out: silence for that day, forever, from the one component
-- whose entire job is to break silence. So:
--   CLAIMED  a door is publishing, or WAS and did not finish. Suppresses other doors only while the
--            lease is fresh (IngestCoverageCanary.CLAIM_LEASE); past it the row is stealable and the
--            day is retried. claimed_at is stamped from the injected Clock, never now(), so a fixed
--            clock makes the lease deterministic in tests.
--   DONE     publication completed. THE ONLY state that suppresses permanently.
-- The invariant: suppress on confirmed completion, never on mere intent. Absence of evidence must
-- not buy silence.
--
-- The lease is what separates "another door is mid-publish right now" from "a previous attempt
-- died" — the two situations a bare CLAIMED cannot tell apart.
--
-- No explicit GRANT is needed here. The SCHEMA is owned by `ay_marketdata` (admin V001:
-- `CREATE SCHEMA marketdata AUTHORIZATION ay_marketdata`), but Flyway runs as `artha` and so owns
-- every TABLE it creates, and the service connects as `artha` too (D10 single-writer) — owner
-- privileges, nothing to grant. Cross-schema readers are covered by V001's
-- `ALTER DEFAULT PRIVILEGES FOR ROLE artha IN SCHEMA marketdata GRANT SELECT ... TO ay_backtest`.
-- (V040 ingest_runs / V044 / V047 carry the same comment attributing the schema to `artha`; that
-- attribution is wrong, the grant conclusion is right. Left alone there — applied migrations are
-- checksum-locked.)
CREATE TABLE canary_runs (
    canary       TEXT        NOT NULL,
    run_day      DATE        NOT NULL,
    state        TEXT        NOT NULL,
    source       TEXT        NOT NULL,
    claimed_at   TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (canary, run_day),
    CONSTRAINT canary_runs_state_ck CHECK (state IN ('CLAIMED', 'DONE'))
);
