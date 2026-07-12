-- App-platform audit §2.7 (Phase 2): the strategy enabled-toggle records ENABLE / DISABLE
-- lifecycle rows in the append-only strategy_audit_log. The V002 CHECK constraint only
-- allowed the version-lifecycle actions, so the enum is widened here. The append-only grant
-- (ay_strategy = INSERT + SELECT) already covers these rows — no grant change needed.
--
-- Postgres named the inline V002 CHECK deterministically as strategy_audit_log_action_check
-- ({table}_{column}_check, the first check on that column). Drop it by that exact name (no
-- IF EXISTS — a rename would mean a masked stale constraint that still rejects ENABLE/DISABLE)
-- and re-add the widened set.
ALTER TABLE strategy_audit_log DROP CONSTRAINT strategy_audit_log_action_check;

ALTER TABLE strategy_audit_log
  ADD CONSTRAINT strategy_audit_log_action_check
  CHECK (action IN ('CREATE', 'UPDATE_DRAFT', 'PUBLISH', 'ROLLBACK', 'ARCHIVE', 'ENABLE', 'DISABLE'));
