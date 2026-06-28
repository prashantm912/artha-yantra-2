-- E10 daily-target-caps (roadmap §3.1d, 2026-06-28): seed the daily PROFIT target (1.5% of equity)
-- and the per-day DEPLOYMENT cap (20% of equity). Both gate RiskService.entryAllowed() account-side
-- (a trip pauses ENTRY emission for the IST day). The daily_loss_limit was already seeded at 10%
-- (owner number) in V011 — it is deliberately NOT re-seeded here.
--   daily_profit_target: mode=pct -> stop once dayPnl >= equity*value/100 (§2.3 r14, the 1-2%/day target).
--   max_deployment_pct : no "mode" key — value is ALWAYS a % of equity; block once capitalUsed >= cap
--                        (§2.14 r62, the ≤10-12%/day blowout guard, seeded at the outer 20%).
-- risk_settings (V006) ships with no rows. Idempotent: ON CONFLICT DO NOTHING never clobbers a UI edit.
INSERT INTO risk_settings (key, value) VALUES
  ('daily_profit_target', '{"enabled": true, "mode": "pct", "value": 1.5}'::jsonb),
  ('max_deployment_pct',  '{"enabled": true, "value": 20.0}'::jsonb)
ON CONFLICT (key) DO NOTHING;
