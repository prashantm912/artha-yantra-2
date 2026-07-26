-- 2026-07-17 incident follow-up: the EOD swing missed-batch DETECTOR.
--
-- The incident was three days of not KNOWING the Manas/Minervini batch had stopped running. This
-- change closes that and nothing more: it pages a human. It never re-runs a batch and never touches
-- a paper position — the auto-replay half was deliberately parked (PR #1036), so nothing here is
-- named "catch-up" and nothing here carries a retry/queue shape.
--
-- V025 `swing_batch_runs` remains the successful-run ledger and is the oracle for "did it run".
-- These two tables are the detector's own state.

-- Why intent must be FROZEN at schedule time, not read from today's flags: the detector's whole job
-- is to tell "deliberately disarmed that session" apart from "armed but missed it". Reading the
-- CURRENT `artha.<family>.swing.enabled` flag cannot do that — disabling a family retroactively
-- hides a real miss, and re-enabling one retroactively invents a miss that never was. So each
-- scheduler records what it was ACTUALLY armed to do when the session came due, and the detector
-- only ever reads history.
--
-- A session has NO row here whenever the scheduler never fired at all — which is exactly the
-- container-down case this detector exists for. So a missing row is NOT read as "not armed": the
-- detector falls back to the newest intent at or before that session (see
-- `SwingBatchIntentRepository.lastKnownArmedOnOrBefore`). Missing with NO prior intent anywhere —
-- a fresh deploy — is the genuine fail-closed case and stays silent.
CREATE TABLE swing_batch_schedule_intents (
    batch        TEXT        NOT NULL,   -- 'minervini' | 'manas-arora' (SwingDoctrine.batchName)
    session_date DATE        NOT NULL,   -- the IST trading session the scheduler was firing for
    armed        BOOLEAN     NOT NULL,   -- the family's EFFECTIVE arming at that moment
    scheduled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch, session_date)
);

COMMENT ON TABLE swing_batch_schedule_intents IS
  'Effective swing-family arming state captured when the scheduled session was due. The detector must never infer historical intent from today''s flag.';

-- The page rate-limiter — neither a one-shot latch nor an unbounded repeat. `claimed_at` is a
-- LEASE: once it expires the detector pages the session again, up to a fixed ceiling
-- (SwingBatchCanary.MAX_PAGES_PER_SESSION). The lease is ~20 h against a once-per-weekday-morning
-- sweep, so a still-missing session pages on that many separate mornings and then goes quiet.
--
-- One-shot was rejected: the alert goes to an @Async listener that swallows every exception, so a
-- terminal "already paged" mark would be written on the basis of ENQUEUE, never delivery — and an
-- ntfy 5xx, a saturated notifier queue, or an unconfigured channel would bury the one and only page
-- forever. This repo already has that failure on record (the 2026-07-20 outage went unnoticed
-- because every call site logged and moved on).
--
-- An UNBOUNDED repeat was rejected too, and this is the part that is easy to get wrong: a missed
-- session can NEVER acquire a run marker retroactively, because SwingBatchRecorder stamps the run
-- date as today — a manual rerun records today, not the session that was missed. So "repeat until
-- it runs" has no exit, and one incident would page every weekday morning until the session aged
-- out of the detector's scan. The ceiling is what makes the repeat terminate.
CREATE TABLE swing_missed_batch_alerts (
    batch        TEXT        NOT NULL,
    session_date DATE        NOT NULL,
    pages        INTEGER     NOT NULL DEFAULT 1,  -- mornings paged; stops at the detector's ceiling
    claimed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (batch, session_date)
);

COMMENT ON TABLE swing_missed_batch_alerts IS
  'Page-rate lease for the swing missed-batch detector: one page per session per lease window, for a BOUNDED number of windows. A missed session can never acquire a run marker retroactively (a manual rerun stamps today), so the repeat has to terminate on a count rather than on the session being fixed. No automatic batch replay is performed.';

COMMENT ON COLUMN swing_missed_batch_alerts.pages IS
  'How many separate mornings this session has been paged. Paging stops at the detector''s ceiling, so a row sitting at that ceiling is an unresolved session it has deliberately gone quiet about.';

GRANT SELECT, INSERT ON swing_batch_schedule_intents TO ay_strategy;
GRANT SELECT, INSERT, UPDATE ON swing_missed_batch_alerts TO ay_strategy;
