-- F9 paper risk application layer (2026-07-05, owner numbers: per-trade risk 1%, daily-loss 3%,
-- portfolio heat cap 60%). Two parts, both additive/forward-only:
--   1. Advisory annotation columns on paper_positions — the engine-computed risk-based lot count and
--      the SPAN margin the position ties up (populated fail-soft at open; NULL when unpriced, e.g. a
--      cash-equity leg Upstox does not margin, or the analytics token off / market-data down).
--   2. A per-book `heat_cap_pct` governor row — max total SPAN margin-at-risk as % of book equity.
--      Enabled only for the options book (scalper); the cash-equity swing books carry no SPAN margin
--      so the gate is seeded INERT there. The heat-cap BLOCK is gated at the app layer behind
--      `artha.paper.risk.enabled` (default OFF → advisory: it audits + ntfy-alerts on breach but does
--      not block a new entry until the owner flips the flag after a clean advisory week).
-- The daily-loss governor already exists (RiskService reads `daily_loss_limit`); this only tightens the
-- scalper book to the owner's 3% — a strictly-safer value change to a proven, already-live gate.

-- ── 1. advisory annotation columns ────────────────────────────────────────────────────────────────
ALTER TABLE paper_positions ADD COLUMN advised_lots     BIGINT;         -- risk-based suggested qty (1% of book ÷ stop distance)
ALTER TABLE paper_positions ADD COLUMN margin_snapshot  NUMERIC(14,2);  -- broker-real SPAN margin (₹) this position ties up, at open
ALTER TABLE paper_positions ADD COLUMN margin_pct       NUMERIC(6,2);   -- margin_snapshot as % of book equity at open

COMMENT ON COLUMN paper_positions.advised_lots IS
  'F9 advisory: the risk-based quantity a 1%-of-book / stop-distance sizing would suggest, computed at open. NULL when the position carries no stop. Advisory only — never overrides the actual filled qty.';
COMMENT ON COLUMN paper_positions.margin_snapshot IS
  'F9 advisory: broker-real SPAN margin (Upstox) this position ties up, priced once at open. NULL when unpriced (cash-equity leg / analytics off / market-data unreachable).';

-- ── 2. per-book portfolio heat-cap governor ───────────────────────────────────────────────────────
-- 60% for the options book; INERT (enabled=false) on the cash-equity books, which have no SPAN margin.
INSERT INTO risk_settings (book, key, value) VALUES
  ('scalper',     'heat_cap_pct', '{"enabled": true,  "value": 60.0}'::jsonb),
  ('minervini',   'heat_cap_pct', '{"enabled": false, "value": 60.0}'::jsonb),
  ('manas-arora', 'heat_cap_pct', '{"enabled": false, "value": 60.0}'::jsonb),
  ('other',       'heat_cap_pct', '{"enabled": false, "value": 60.0}'::jsonb)
ON CONFLICT (book, key) DO NOTHING;

-- ── 3. tighten the scalper daily-loss governor to the owner's 3% (was 10%) ─────────────────────────
UPDATE risk_settings
   SET value = '{"enabled": true, "mode": "pct", "value": 3}'::jsonb
 WHERE book = 'scalper' AND key = 'daily_loss_limit';
