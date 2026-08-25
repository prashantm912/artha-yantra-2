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
-- ⚠️ THREE origins, not two, and the first cut of this migration only had room for two (cross-vendor
-- review, 2026-08-25). The catch-up ALSO writes an exits-only marker when the arming is known but
-- the funnel is not as-of the session (SwingBatchCatchUp:643-645, reason SCREEN_NOT_AS_OF_SESSION).
-- That run is entries-disabled exactly like the evening settle, so a pass DERIVED from
-- entries_enabled labelled it SETTLE and it landed on the settle's key -- reproducing the defect
-- this migration exists to fix, and repeatedly, because that branch leaves the session retryable.
-- The pass is therefore DECLARED by each call site (SwingBatchRunRepository.Pass), and the three
-- values below are the three origins.
--
-- Measured 2026-08-25, and it is not latent: strategy.swing_batch_runs rows for run_date
-- 2026-08-17..2026-08-21 ALL carry ran_at from the NEXT MORNING's 08:35 catch-up, so the evening
-- settle's record for those five sessions no longer exists. NO MONEY IS AT RISK -- paper_positions,
-- swing_paper_effects and the P&L are correct and complete; what was destroyed is the batch-run
-- AUDIT TRAIL, which is the surface a forward-paper reliability verdict reads.
ALTER TABLE swing_batch_runs
    ADD COLUMN pass TEXT;

-- Backfill BEFORE the NOT NULL. Going forward the pass is DECLARED by the call site, but a row that
-- already exists has no declaration to read, so entries_enabled (V060) is the only evidence there
-- is. Each of the three branches below is a MEASURED bucket, not a defensive default -- computed
-- 2026-08-25 against the live strategy.swing_batch_runs (68 rows):
--
--   entries_enabled = TRUE   20 rows, ran_at 08:35:34..08:36:04 IST, run_date 2026-08-11..08-24.
--                            The post-V060 08:35 catch-up entry pass. ENTRIES, unambiguously.
--   entries_enabled IS NULL  48 rows, ran_at 20:00:02..20:05:30 IST, run_date 2026-07-06..08-10.
--                            Pre-V060, from the era when ONE 20:00 batch did entries AND exits.
--                            They are COMBINED runs, and ENTRIES is the honest label for the half
--                            that matters here: the discriminator answers "did the entry pass run
--                            for this session", and for these it did. A fourth COMBINED value would
--                            be marginally more descriptive and would have to be carried in
--                            hasRunWithEntries', recentProbes' and seedMissing's predicates forever
--                            for a population that is frozen at 48 rows and can never grow --
--                            where forgetting it re-opens sessions the book has already entered.
--                            (Resolving these to SETTLE is the actively dangerous option: it would
--                            tell hasRunWithEntries that every historical session still owes its
--                            entries and hand the catch-up the whole archive at once.)
--   entries_enabled = FALSE   0 rows. Not a hypothetical branch -- it is the shape a pre-V063
--                            exits-only row WOULD have, and there are none only because the defect
--                            destroyed every one of them: a settle row survived from ~18:53 until
--                            the next 08:35 overwrote it, and never past a night. Such a row can be
--                            either origin and nothing in the schema can tell them apart, so it
--                            takes SETTLE -- the overwhelmingly more common of the two, and the one
--                            that reads correctly for any row a mock/dev stack wrote this evening.
--                            RECOVERY_EXITS is never backfilled; it can only be declared.
UPDATE swing_batch_runs
   SET pass = CASE
                WHEN entries_enabled IS TRUE THEN 'ENTRIES'
                WHEN entries_enabled IS NULL THEN 'ENTRIES'
                ELSE 'SETTLE'
              END;

ALTER TABLE swing_batch_runs
    ALTER COLUMN pass SET NOT NULL,
    ADD CONSTRAINT swing_batch_runs_pass_ck
        CHECK (pass IN ('ENTRIES', 'SETTLE', 'RECOVERY_EXITS'));

-- ⚠️ The key swap is the point of this migration. Dropping the old PK is safe HERE and nowhere else:
-- the backfill above gives every existing row exactly one pass value, so no existing (batch,
-- run_date) can collide with itself under the wider key.
ALTER TABLE swing_batch_runs
    DROP CONSTRAINT swing_batch_runs_pkey,
    ADD CONSTRAINT swing_batch_runs_pkey PRIMARY KEY (batch, run_date, pass);

COMMENT ON COLUMN swing_batch_runs.pass IS
  'Which pass wrote this row, DECLARED by the call site (SwingBatchRunRepository.Pass), never inferred from entries_enabled: ENTRIES (the 08:35 catch-up entry pass, or a manual POST /run), SETTLE (the scheduled evening exits-only settle at 18:52/18:53), RECOVERY_EXITS (the catch-up''s exits-only run when the arming is known but the funnel is not as-of the session -- entries withheld, session left retryable). Part of the primary key since V063 -- before it all three shared one key and the later write destroyed the earlier one (ledger H23). SETTLE and RECOVERY_EXITS both carry entries_enabled = false, which is why that column cannot be the discriminator.';

COMMENT ON TABLE swing_batch_runs IS
  'One row per swing-batch run per IST date PER PASS (upserted by the batch schedulers). The 08:30 IST canary alerts when an armed batch has no row of any pass for the last NSE trading day.';

-- New column inherits the V025 table-level grant; restated for the lineage's audit convention.
GRANT SELECT, INSERT, UPDATE ON swing_batch_runs TO ay_strategy;
