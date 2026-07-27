-- 2026-07-26 swing catch-up round 3: fail-closed effect decisions and exact close targets.
-- V050 may already be applied by a test or shared database; keep this correction additive.
ALTER TABLE swing_paper_effects
    ADD COLUMN decision TEXT NOT NULL DEFAULT 'UNDECIDED'
        CHECK (decision IN ('UNDECIDED', 'REQUIRED', 'SKIPPED'));

ALTER TABLE swing_paper_effects
    ADD COLUMN target_position_ids BIGINT[];

COMMENT ON COLUMN swing_paper_effects.decision IS
  'Durable paper-effect decision. UNDECIDED is fail-closed; only REQUIRED may be replayed.';

COMMENT ON COLUMN swing_paper_effects.target_position_ids IS
  'Exact paper_positions ids captured before an EXIT close; never infer targets from the reusable position key.';
