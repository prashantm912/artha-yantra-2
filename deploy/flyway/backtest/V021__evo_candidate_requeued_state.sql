-- PF-01 round-6 #3: add the REQUEUED evo-candidate state. A STALE-SIGNATURE candidate — one whose
-- (engine SHA, data epoch) differs from its generation's dominant comparability partition — is NOT
-- comparable this generation and must be RE-QUEUED (re-run under the current epoch), NEVER retired
-- (design §1.3 "the stale side is re-queued"). Selection parks such a candidate in REQUEUED, which
-- is EXCLUDED from re-selection as-is; an external scheduler resubmits it under the authoritative
-- epoch and flips it back to SCORED with fresh evidence. The state column's CHECK is re-created to
-- admit the new value (V011 is applied + checksum-locked, so this is a new suffix migration).
ALTER TABLE evo_candidates DROP CONSTRAINT IF EXISTS evo_candidates_state_check;
ALTER TABLE evo_candidates ADD CONSTRAINT evo_candidates_state_check
  CHECK (state IN ('PROPOSED', 'EVALUATING', 'SCORED', 'SURVIVOR', 'RETIRED',
                   'PAPER', 'TAKE_ELIGIBLE', 'PROMOTED', 'ROLLED_BACK', 'REQUEUED'));
