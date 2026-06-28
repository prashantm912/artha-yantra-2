-- E10 #1 (rule-1, owner-set 2026-06-28): seed the GLOBAL daily-loss cap at 10% of equity.
-- This is the account-level risk-1 limit (distinct from the per-strategy `max_daily_loss_pct` YAML 2.0):
-- a trip pauses ENTRY emission for the IST day (RiskService.entryAllowed; mode=pct -> equity*value/100).
-- risk_settings (V006) ships with NO rows, so the cap was OFF; seed it ENABLED at 10%.
-- Idempotent: ON CONFLICT DO NOTHING so a hand-set row (UI upsert) is never clobbered.
INSERT INTO risk_settings (key, value)
VALUES ('daily_loss_limit', '{"enabled": true, "mode": "pct", "value": 10}'::jsonb)
ON CONFLICT (key) DO NOTHING;
