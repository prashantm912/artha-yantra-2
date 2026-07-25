-- 2026-07-26 swing catch-up persistence criticals: freeze schedule-time arming intent and retain
-- explicit reasons for terminal catch-up refusals/abandonments.
ALTER TABLE swing_catchup_runs
    ADD COLUMN reason TEXT;

COMMENT ON COLUMN swing_catchup_runs.reason IS
  'Why a terminal catch-up row was abandoned/disarmed/refused; NULL for ordinary RUNNING/PENDING/DONE rows.';

CREATE TABLE swing_batch_schedule_intents (
    batch        TEXT        NOT NULL,
    session_date DATE        NOT NULL,
    armed        BOOLEAN     NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch, session_date)
);

COMMENT ON TABLE swing_batch_schedule_intents IS
  'Effective swing-family arming state captured when the scheduled session was due; catch-up must never infer historical intent from today''s flag.';

GRANT SELECT, INSERT ON swing_batch_schedule_intents TO ay_strategy;
