-- Ledger F3 (2026-07-12): FIFO-vs-RS-priority slot-admission PROBE — measurement-only, never changes
-- admission. Extends the existing per-batch run marker (V025) with the admission counterfactual the
-- 20:05 swing batch computes: how many funnel candidates WOULD have opened a fresh position vs how many
-- the book governor actually ADMITTED (the slot-cap exceedance), plus the identity + admission-rank of
-- the names the cap dropped. The funnel admits in RS-priority order (buyable RS-desc, then on-deck
-- RS-desc; ManasFunnelService ORDER BY rs_rank DESC), so a dropped name's admission_rank IS its
-- RS-priority position — the raw material for the offline FIFO-vs-RS net-vs-net read (portfolioFifoNet),
-- which the source spec noted was "inferred, not measured". Same grain as the marker: one probe per
-- (batch, run_date), so these ride as columns on swing_batch_runs, not a new table.
--
-- All columns are NULLABLE: pre-migration rows (and any flag-off no-op run) read NULL = "not probed".
ALTER TABLE swing_batch_runs
    ADD COLUMN open_at_start  INTEGER,  -- family positions already held at batch start
    ADD COLUMN would_enter    INTEGER,  -- distinct non-held candidates with a firing entry, IGNORING the cap
    ADD COLUMN admitted       INTEGER,  -- of would_enter, how many the governor actually admitted (fresh opens)
    ADD COLUMN cap_exceedance INTEGER,  -- would_enter - admitted: names dropped for lack of a slot (>=0)
    ADD COLUMN cap_bound      BOOLEAN,  -- did the cap bind this run (cap_exceedance > 0)
    ADD COLUMN dropped_by_cap JSONB;    -- [{symbol, admissionRank}] of the dropped names (RS-ordered tail)

COMMENT ON COLUMN swing_batch_runs.cap_exceedance IS
  'F3 probe: would_enter - admitted — funnel candidates that fired an entry but the book governor did not admit (dominant cause: the slot cap on an over-subscribed day; also a mid-run kill-switch / daily-loss trip). NULL = not probed.';

-- New columns inherit swing_batch_runs' table-level grant (V025 granted SELECT/INSERT/UPDATE to
-- ay_strategy); no column grant needed.
