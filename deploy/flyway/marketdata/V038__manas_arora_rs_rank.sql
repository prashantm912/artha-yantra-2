-- Phase 9 / Manas Arora screener — RS-priority admission (doctrine §4.1: cross-sectional RS-rank is
-- "the single biggest edge a filter can add"). Persist the IBD-style RS-rank (0..100 percentile of the
-- weighted trailing relative strength, same weightedRs math as the Minervini trend-template screen) on
-- each Manas screen row so the funnel can admit the highest-RS candidates first when the book's slots
-- are scarce — matching the backtest's RS-priority ordering. Nullable: a name without a full trailing
-- window (bhavcopy ~1y) gets a NULL rank and sorts last (NULLS LAST), never blocking the screen.
ALTER TABLE marketdata.manas_arora_screen_results
  ADD COLUMN IF NOT EXISTS rs_rank NUMERIC;
