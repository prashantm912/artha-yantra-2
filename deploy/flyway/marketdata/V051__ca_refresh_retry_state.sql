-- task_6903cd5e (2026-07-31): a FAILED corporate-action event was UNRESUMABLE, so a symbol whose
-- cagg refresh errored kept its rebuilt base but permanently stale aggregates — 13 symbols sat live
-- at ~1% of a control symbol's candles_5m rows, every one of them with a FAILED event. The A14
-- checkpoint resumed only BASE_REBUILT, and its own reasoning ("post-rebuild the cache == Kite ⇒ no
-- fresh DETECTED row") applies to a FAILED row just as much: nothing ever re-fires for it.
--
-- The blocker was that `status` is ONE overwritten column, so the moment a run failed, the fact that
-- it had ever reached BASE_REBUILT was destroyed — and the two failure classes need OPPOSITE
-- treatment. Failing before the base commits (purge / 1d prefetch / 1m prefetch) leaves the base
-- incomplete: resuming refresh-only there would materialise aggregates over half-purged data.
-- Failing at the cagg refresh leaves the base committed: refresh-only is exactly right. So RECORD
-- the phase instead of inferring it —
--   REFRESH_FAILED    the base is committed, the cagg refresh threw; RESUMABLE, still retrying
--   REFRESH_ABANDONED the retry bound is spent; TERMINAL, an operator must act
--   FAILED            keeps its exact prior meaning: failed before the base committed; TERMINAL
-- and bound the retry with a counter that survives a process death (an in-memory count would reset
-- on every restart, and the sweep is daily).
--
-- V006_2 + V039 are applied and checksum-locked, so this is a new suffix migration, never an
-- in-place edit. Existing rows: the CHECK is widened, never narrowed, so every current value stays
-- valid, and existing FAILED rows deliberately STAY FAILED — the phase they died in was never
-- recorded, so auto-promoting them to REFRESH_FAILED would risk precisely the incomplete-base
-- refresh this change exists to prevent. Re-arming a stranded symbol stays a deliberate operator
-- action. refresh_attempts defaults to 0, which is the truth for every pre-existing row: none of
-- them has a RECORDED refresh attempt.
ALTER TABLE marketdata.corporate_action_events
  DROP CONSTRAINT IF EXISTS corporate_action_events_status_check;

ALTER TABLE marketdata.corporate_action_events
  ADD CONSTRAINT corporate_action_events_status_check
  CHECK (status IN ('DETECTED', 'REBACKFILL_RUNNING', 'BASE_REBUILT', 'RESOLVED', 'FAILED',
                    'REFRESH_FAILED', 'REFRESH_ABANDONED'));

ALTER TABLE marketdata.corporate_action_events
  ADD COLUMN IF NOT EXISTS refresh_attempts INT NOT NULL DEFAULT 0;

-- The marketdata schema is owned by `artha` (admin V001), so no explicit GRANT is needed here —
-- and a table-level grant already covers a newly added column.
