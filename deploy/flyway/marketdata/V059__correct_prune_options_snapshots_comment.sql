-- A10 residue: the COMMENT this function publishes has been FALSE since 2026-07-12.
--
-- V010 stamped it "Manual storage relief (A2: no auto-retention) ... older than `retain`
-- (default 5y)". Both halves were true when written and neither is true now: the owner decided a
-- 365-day retention on 2026-07-12 (#749) and `OptionsSnapshotPruneJob` runs it on a schedule, so
-- there IS auto-retention and the horizon is 365 days, not five years.
--
-- Why a new migration rather than an edit: applied migrations are checksum-locked, so V010 cannot be
-- touched in place -- correcting it there would fail `flyway validate` and block every later
-- migration. This is the documented remedy (CLAUDE.md, "Database / migrations").
--
-- Why it is worth a migration at all: this comment is not a source-code comment, it is an object
-- description the DATABASE serves to anyone running \df+ or reading pg_proc. The supersession is
-- already recorded in OptionsSnapshotPruneJob's javadoc, but nothing carried it to the DB, so an
-- operator inspecting the live schema during a disk-pressure incident -- exactly when this function
-- gets looked up -- would read that options history is never auto-dropped and size their response to
-- a five-year horizon.
--
-- COMMENT ON only. No behaviour, no signature, no grants; the function body stays V046's plpgsql
-- PERFORM variant. The `retain` DEFAULT is deliberately NOT changed: this function is the MANUAL
-- escape hatch and its default is not the scheduled horizon -- the scheduled job passes its own
-- value from artha.market.snapshot-retention-days. Changing the default here would be a behaviour
-- change smuggled in under a comment fix.
COMMENT ON FUNCTION marketdata.prune_options_snapshots(INTERVAL) IS
  'Manual storage relief. NOTE: auto-retention EXISTS -- the owner set a 365-day horizon on '
  '2026-07-12 (#749) and OptionsSnapshotPruneJob applies it on a schedule via '
  'artha.market.snapshot-retention-days (env ARTHA_SNAPSHOT_RETENTION_DAYS). This supersedes '
  'amendment A2''s ">= 5 year floor, no auto-drop", which V006 and V010 still describe and which '
  'cannot be edited there because applied migrations are checksum-locked. This function remains the '
  'MANUAL escape hatch and its own `retain` default (5y) is NOT the scheduled horizon: run it by '
  'hand only when disk is tight, e.g. SELECT marketdata.prune_options_snapshots(INTERVAL ''365 days''). '
  'Returns an empty set (plpgsql PERFORM variant, V046): re-resolving a just-dropped chunk''s '
  'regclass would error, so preview the drop set with public.show_chunks() instead.';
