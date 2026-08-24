-- Ledger H23 (2026-08-25): the 08:35 entries catch-up overwrites the evening settle's run record.
--
-- V025 made this table PRIMARY KEY (batch, run_date) -- one row per batch per session -- on the
-- assumption that a session has ONE batch run. The 16:00/08:35 split (#1333) broke that assumption
-- without changing the key: an exits-only SETTLE writes the row, and the next morning's ENTRIES
-- catch-up upserts the SAME key for the SAME run_date. The second write wins.
--
-- ⚠️ Both passes legitimately compute every column, which is why no merge rule can fix this. The
-- settle counts the exits it fired at 18:52; the catch-up counts the exits IT saw at 08:36 the next
-- morning, against different prices. Neither number is wrong and neither is the other's -- a
-- GREATEST or a COALESCE would just pick one and lose the attribution. The row needs to say WHICH
-- pass it describes.
--
-- Measured 2026-08-25, and it is not latent: strategy.swing_batch_runs rows for run_date
-- 2026-08-17..2026-08-21 ALL carry ran_at from the NEXT MORNING's 08:35 catch-up, so the evening
-- settle's record for those five sessions no longer exists. NO MONEY IS AT RISK -- paper_positions,
-- swing_paper_effects and the P&L are correct and complete; what was destroyed is the batch-run
-- AUDIT TRAIL, which is the surface a forward-paper reliability verdict reads.
ALTER TABLE swing_batch_runs
    ADD COLUMN pass TEXT;

-- Backfill BEFORE the NOT NULL, and derive it from entries_enabled (V060) using the SAME reading
-- the code uses everywhere else: NULL means a pre-V060 row, and every one of those was written when
-- the batch still did entries and exits in one pass, so it reads as ENTRIES. Resolving NULL to
-- SETTLE here would tell hasRunWithEntries that every historical session still owes its entries and
-- hand the catch-up the entire archive at once.
UPDATE swing_batch_runs
   SET pass = CASE WHEN entries_enabled IS FALSE THEN 'SETTLE' ELSE 'ENTRIES' END;

ALTER TABLE swing_batch_runs
    ALTER COLUMN pass SET NOT NULL,
    ADD CONSTRAINT swing_batch_runs_pass_ck CHECK (pass IN ('ENTRIES', 'SETTLE'));

-- ⚠️ The key swap is the point of this migration. Dropping the old PK is safe HERE and nowhere else:
-- the backfill above gives every existing row exactly one pass value, so no existing (batch,
-- run_date) can collide with itself under the wider key.
ALTER TABLE swing_batch_runs
    DROP CONSTRAINT swing_batch_runs_pkey,
    ADD CONSTRAINT swing_batch_runs_pkey PRIMARY KEY (batch, run_date, pass);

COMMENT ON COLUMN swing_batch_runs.pass IS
  'Which pass wrote this row: ENTRIES (the 08:35 catch-up / a manual run that takes entries) or SETTLE (the evening exits-only settle). Part of the primary key since V063 -- before it, the two passes shared one key and the later write destroyed the earlier one (ledger H23).';

COMMENT ON TABLE swing_batch_runs IS
  'One row per swing-batch run per IST date PER PASS (upserted by the batch schedulers). The 08:30 IST canary alerts when an armed batch has no row of any pass for the last NSE trading day.';

-- New column inherits the V025 table-level grant; restated for the lineage's audit convention.
GRANT SELECT, INSERT, UPDATE ON swing_batch_runs TO ay_strategy;
