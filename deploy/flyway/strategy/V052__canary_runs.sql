-- task_7e754e11: durable "this canary already ran for this IST day" ledger, so a boot-time catch-up
-- can replay a morning canary whose @Scheduled cron ticked while the stack was down (E2E audit
-- 2026-07-31 §2.1: down 02:29-08:56 IST, so the 08:30 notifier-health check never fired and Spring
-- never replays a missed fire). An in-memory flag is defeated by exactly the restart this exists
-- for, so the marker is a row.
--
-- Deliberately NOT the marketdata V050 canary_runs table that PR #1155 added for the sibling 08:45
-- ingest-coverage canary: different schema, different Flyway lineage, independent merge order. The
-- strategy service must never read a marketdata table to decide whether its own canary ran.
--
-- run_day is the IST calendar day the canary ran FOR, derived in Java from the injected Clock via
-- Ist.ZONE — never `now()::date`, because the container clock is UTC and a 02:xx-IST row would land
-- on the previous calendar day there. `source` records which door the run came through: SCHEDULED
-- (the cron) or BOOT_CATCHUP (this feature).
--
-- TWO-STATE, NOT ONE (cross-vendor review, Critical 2). A single "the day is taken" row suppresses
-- on the wrong fact: it proves the day was CLAIMED, never that the alert was PUBLISHED. A process
-- that dies between the claim and the push would leave the day permanently claimed and every later
-- door silently suppressed — a canary going quiet forever on the one morning it mattered. So the
-- claim is PROVISIONAL:
--   status='CLAIMED' + claimed_at  = a door holds the day and is publishing
--   status='DONE'    + ran_at      = publication completed; ONLY this suppresses a later door
-- A CLAIMED row whose claimed_at has aged past the lease is reclaimable, so an interrupted run is
-- retried by the next door instead of silencing it. The lease is what still makes two OVERLAPPING
-- doors safe: while it is fresh, the loser of the atomic upsert publishes nothing. Same shape as
-- V049 swing_catchup_runs, which carries this design because it hit this failure.
--
-- The upsert is the whole idempotency gate and must stay ONE statement:
--   INSERT .. ON CONFLICT (canary, run_day) DO UPDATE .. WHERE status <> 'DONE' AND claimed_at < ?
-- affecting exactly 1 row iff this caller may publish. A read-then-write would let two doors both
-- see "not done" and both alert.
CREATE TABLE canary_runs (
    canary     TEXT        NOT NULL,
    run_day    DATE        NOT NULL,
    source     TEXT        NOT NULL,
    status     TEXT        NOT NULL CHECK (status IN ('CLAIMED', 'DONE')),
    claimed_at TIMESTAMPTZ NOT NULL,
    ran_at     TIMESTAMPTZ,
    PRIMARY KEY (canary, run_day)
);

COMMENT ON TABLE canary_runs IS
  'Per-IST-day run ledger for this service''s scheduled canaries. CLAIMED = a door is publishing under a lease; DONE = publication completed, and only DONE suppresses a later door.';

COMMENT ON COLUMN canary_runs.claimed_at IS
  'When the CURRENT claim was taken, from the injected Clock. A CLAIMED row older than the lease is reclaimable; it also CASes the completion, so a superseded holder cannot mark another door''s claim DONE.';

-- Match the lineage's least-privilege convention (every prior CREATE TABLE grants to ay_strategy).
-- UPDATE is required here: the claim is an upsert and completion transitions CLAIMED -> DONE.
GRANT SELECT, INSERT, UPDATE ON canary_runs TO ay_strategy;
