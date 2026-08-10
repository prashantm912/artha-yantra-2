-- The swing day is being SPLIT (owner decision 2026-08-10): exits run at 16:00 IST, half an hour
-- after the close, so the book is settled as soon as the session's own daily bar exists; entries
-- run the next morning at 08:35 through SwingBatchCatchUp, once the screen has certainly landed.
--
-- Exits can move that early because they do not need the bhavcopy at all. Measured on 2026-08-07:
-- of the held symbols' 1d bars, ELEVEN were written by KITE at 20:00:47-20:05:33 -- fetched by the
-- swing batch itself, on demand, through the cache-first path -- and only four came from BHAVCOPY
-- at 19:30. The bhavcopy is what the ~1800-symbol SCREEN needs, not what a stop on eighteen held
-- names needs. NSE publishes it anywhere between 17:52 and 19:30+, which is exactly why entries,
-- and only entries, wait for the morning.
--
-- That split breaks one existing assumption. SwingBatchCatchUp skips any session already present in
-- swing_batch_runs, because until now a row there meant the whole batch ran. An exits-only 16:00 run
-- writes such a row -- correctly, since it DID evaluate every held stop and the 08:30 canary must
-- stay quiet -- and the catch-up would then skip the session and never take its entries. The marker
-- would be true and the inference drawn from it false.
--
-- So the row now records WHICH passes ran. NULL means a row written before this column existed, and
-- is deliberately read as "entries ran": every historical row came from a full batch, and the
-- fail-safe direction for a legacy row is to leave it alone rather than re-enter months-old names.
ALTER TABLE swing_batch_runs ADD COLUMN entries_enabled boolean;

COMMENT ON COLUMN swing_batch_runs.entries_enabled IS
    'Whether the ENTRY pass ran in this recorded batch. false = an exits-only run (the 16:00 IST '
    'settle), so SwingBatchCatchUp must still take this session''s entries. NULL = pre-V060 row, '
    'read as true.';
