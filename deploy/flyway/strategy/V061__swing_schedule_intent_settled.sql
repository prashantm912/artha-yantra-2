-- The schedule-intent ledger gained a SECOND writer (the intraday ticks, 2026-08-10) and inherited
-- write semantics designed for one. That is the whole defect: `armed` alone cannot say whether the
-- value came from the authoritative 16:00 settle or from a 09:05 guess that the settle never got to
-- correct.
--
-- Why a guess exists at all: the catch-up refuses a session with no intent row, so writing intent
-- ONLY from the settle meant a container down at 16:00 -- the incident class the whole redesign is
-- for -- left no row and forfeited that session's entries. The ticks fix that. But a tick observes
-- the flag hours before the settle, and the flag can move in between.
--
-- Both directions were wrong, and cross-vendor review found them across two rounds:
--   * armed at 09:05, DISARMED by the settle, and the settle's write FAILS (it is deliberately
--     fail-soft, since bookkeeping must never cost a settle) -> the row still reads armed, the
--     disabled settle wrote no run marker, and the catch-up replays the session through the
--     explicitly-armed historical overload. A deliberately disabled family takes entries.
--   * disarmed at 09:05, ARMED by the settle, same failed write -> the row reads disarmed and every
--     entry for that session is forfeited while the family is live.
--
-- `settled` is that distinction made explicit, so a provisional value can never be MISTAKEN for an
-- authoritative one:
--   * the ticks upsert only over a row that is still provisional, so a LATER observation supersedes
--     an earlier one (a restart that sees a changed flag is no longer ignored) but can never clobber
--     a settled value;
--   * the settle always wins and sets settled = true;
--   * the catch-up requires settled = true before it will take ENTRIES on the strength of the row
--     alone. A provisional row with a run marker is still enough -- the marker proves the family was
--     armed, because only an armed run writes one -- and a provisional row with NO marker runs EXITS
--     ONLY and alerts. You can always decline to enter; you can never decline to leave.
--
-- Existing rows were all written by the settle path (the ticks did not exist), so they backfill to
-- TRUE. Resolving them the other way would demote real history to guesses and make the catch-up
-- refuse entries for every past session.
ALTER TABLE swing_batch_schedule_intents
    ADD COLUMN settled boolean NOT NULL DEFAULT true;

-- New rows are provisional unless a writer says otherwise; both writers pass it explicitly, so this
-- only governs any future third writer that forgets to.
ALTER TABLE swing_batch_schedule_intents
    ALTER COLUMN settled SET DEFAULT false;

COMMENT ON COLUMN swing_batch_schedule_intents.settled IS
    'true = written by the settle itself (authoritative arming at schedule time). false = a '
    'provisional intraday observation; the catch-up will not take ENTRIES on it alone.';
