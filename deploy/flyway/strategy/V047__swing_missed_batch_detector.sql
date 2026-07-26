-- 2026-07-17 incident follow-up: the EOD swing missed-batch DETECTOR.
--
-- The incident was three days of not KNOWING the Manas/Minervini batch had stopped running. This
-- change closes that and nothing more: it pages a human. It never re-runs a batch and never touches
-- a paper position — the auto-replay half was deliberately parked (PR #1036), so nothing here is
-- named "catch-up".
--
-- V025 `swing_batch_runs` remains the successful-run ledger and is the oracle for "did it run".
-- These two tables are the detector's own state.

-- Why intent must be FROZEN at schedule time, not read from today's flags: the detector's whole job
-- is to tell "deliberately disarmed that session" apart from "armed but missed it". Reading the
-- CURRENT `artha.<family>.swing.enabled` flag cannot do that — disabling a family retroactively
-- hides a real miss, and re-enabling one retroactively invents a miss that never was. So each
-- scheduler records what it was ACTUALLY armed to do when the session came due, and the detector
-- only ever reads history.
CREATE TABLE swing_batch_schedule_intents (
    batch        TEXT        NOT NULL,   -- 'minervini' | 'manas-arora' (SwingDoctrine.batchName)
    session_date DATE        NOT NULL,   -- the IST trading session the scheduler was firing for
    armed        BOOLEAN     NOT NULL,   -- the family's EFFECTIVE arming at that moment
    scheduled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch, session_date)
);

COMMENT ON TABLE swing_batch_schedule_intents IS
  'Effective swing-family arming state captured when the scheduled session was due. The detector must never infer historical intent from today''s flag.';

-- One page per missed session, surviving restarts. A row appears only when the detector has decided
-- to alert; absence means "nothing was wrong", not "not yet checked".
CREATE TABLE swing_missed_batch_alerts (
    batch        TEXT        NOT NULL,
    session_date DATE        NOT NULL,
    status       TEXT        NOT NULL
                 CHECK (status IN ('RUNNING', 'ABANDONED')),
    attempts     INTEGER     NOT NULL DEFAULT 0,
    claimed_at   TIMESTAMPTZ,            -- when the current alert episode claim was taken
    reason       TEXT,                   -- why the terminal state was recorded; NULL while RUNNING
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch, session_date)
);

-- status: RUNNING   = an alert episode claim is held (a stale one is re-claimable, so a crash
--                     mid-alert re-pages; a duplicate page is strictly better than a missed one)
--         ABANDONED = terminal latch, this session has been paged
COMMENT ON TABLE swing_missed_batch_alerts IS
  'Durable per-session alert latch for the swing missed-batch detector. No automatic batch replay is performed.';

GRANT SELECT, INSERT ON swing_batch_schedule_intents TO ay_strategy;
GRANT SELECT, INSERT, UPDATE ON swing_missed_batch_alerts TO ay_strategy;
