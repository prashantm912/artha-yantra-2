-- Roadmap F8 (execution-quality): cost-adjusted shadow PnL. Paper realized_pnl has been net of
-- the full statutory schedule since Phase 43 (shared engine FillSimulator); the shadow book only
-- carried raw premium points — a scalp edge thinner than its costs read as a winner. Both columns
-- are 1-lot INR, computed at close through the SAME engine fill model (zero added slippage — the
-- entry/exit LTPs are the shadow's fills; costs are the only adjustment). Null on STALE closes
-- and on rows closed before this migration.
ALTER TABLE shadow_positions
    ADD COLUMN cost    NUMERIC(12,2),
    ADD COLUMN pnl_net NUMERIC(14,2);
