-- 2026-07-17 incident follow-up: the swing-batch CATCH-UP claim ledger. The did-not-run canary
-- (V025 swing_batch_runs) only DETECTS a missed batch; SwingBatchCatchUp re-runs it. This table is
-- the catch-up's DURABLE, ATOMIC execution claim, taken (INSERT/UPDATE ... RETURNING) BEFORE any
-- money-effecting run: two overlapping catch-up invocations — or a catch-up racing a manual
-- POST /run — must not both emit for one session and double a paper position (PaperService.upsertPosition
-- AVERAGES a second open into the first, so uq_paper_positions_open guards the ROW, never the qty).
-- It also carries the per-session attempt count so a session whose inputs never arrive AGES OUT
-- instead of retrying forever.
--
-- Completeness lives in V025 swing_batch_runs (the on-time run OR a COMPLETE catch-up stamps a row
-- for the session), NOT here — this table is only the lock + attempt ledger. A row is created the
-- first time the catch-up touches a session and reaches a terminal 'DONE'/'ABANDONED' or a
-- retryable 'PENDING'.
CREATE TABLE swing_catchup_runs (
    batch        TEXT        NOT NULL,   -- 'minervini' | 'manas-arora' (SwingDoctrine.batchName)
    session_date DATE        NOT NULL,   -- the missed IST trading session being caught up
    status       TEXT        NOT NULL    -- see below
                 CHECK (status IN ('RUNNING', 'PENDING', 'DONE', 'ABANDONED', 'DISARMED')),
    attempts     INTEGER     NOT NULL DEFAULT 0,
    claimed_at   TIMESTAMPTZ,            -- when the current RUNNING claim was taken (stale-lease reclaim)
    reason       TEXT,                   -- why a terminal row was ABANDONED/DISARMED/refused; NULL otherwise
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch, session_date)
);

-- status: RUNNING  = a claim is held (money actions in flight)
--         PENDING  = a partial attempt, retryable next sweep (a held bar was missing)
--         DONE     = the session was fully caught up AND its swing_batch_runs marker was written
--         ABANDONED= exhausted its attempt budget (a held bar never landed) — terminal
--         DISARMED = the family was intentionally OFF for this session; recorded so a later re-arm
--                    does NOT replay a session the owner deliberately skipped — terminal, never claimed

COMMENT ON TABLE swing_catchup_runs IS
  'Durable atomic claim + attempt ledger for the swing-batch catch-up (SwingBatchCatchUp). The claim is taken before any money-effecting run so overlapping invocations cannot double-fill a paper position.';

-- Match the lineage's least-privilege convention (every prior CREATE TABLE grants to ay_strategy).
GRANT SELECT, INSERT, UPDATE ON swing_catchup_runs TO ay_strategy;
