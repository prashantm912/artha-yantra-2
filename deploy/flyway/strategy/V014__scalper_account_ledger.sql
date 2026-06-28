-- E10 five-account-ledgers (risk-governance.md §3.2 / operative doc L85-86, r15-16/r19):
-- Siva's discipline of splitting capital across 5 logical sub-accounts to cap blow-up risk and
-- lock psychological profit. A scalper entry is round-robin assigned to a non-frozen sub-account;
-- an account freezes for the IST day on its first LOSING trade (the first-loss freeze, r19).
--
-- This migration adds the ledger SCHEMA only. The per-account first-loss freeze reads it; stamping
-- subaccount_idx at open (the assignment write path) lands in a follow-up. A NULL subaccount_idx is
-- a non-scalper / legacy trade — invisible to the 5-account model (the aggregate fallback in
-- ScalperAccountModel preserves the prior day-granularity loss-count semantics when no trade carries
-- an idx). The per-account 1%-target bank (r15-16) reuses capital_fraction in a later step.

CREATE TABLE scalper_subaccount (
  idx              SMALLINT PRIMARY KEY CHECK (idx BETWEEN 1 AND 5),
  capital_fraction NUMERIC(6,4) NOT NULL DEFAULT 0.2000  -- equal split by default
);
INSERT INTO scalper_subaccount (idx) VALUES (1), (2), (3), (4), (5);

-- which sub-account a closed paper trade was charged to (the per-account ledger key)
ALTER TABLE paper_positions
  ADD COLUMN subaccount_idx SMALLINT REFERENCES scalper_subaccount(idx);
CREATE INDEX idx_paper_subaccount ON paper_positions (subaccount_idx)
  WHERE subaccount_idx IS NOT NULL;

GRANT SELECT ON scalper_subaccount TO ay_strategy;
