-- Phase 43A (F-43A / FP-41/42/43, amendment A12): the paper-account capital base,
-- global risk limits and the engine-stamped suggested quantity. All additive (D17).
-- Equity is COMPUTED on demand (starting_capital + realized + mark-to-market
-- unrealized) - never stored. Global risk limits live on DB rows, never YAML, so a
-- toggle never mints a strategy version or perturbs a D18 checksum.

-- single-row capital base (single-user app)
CREATE TABLE paper_account (
  id               SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  starting_capital NUMERIC(18,2) NOT NULL,
  cash             NUMERIC(18,2) NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO paper_account (id, starting_capital, cash) VALUES (1, 1000000.00, 1000000.00);

-- global risk limits as typed JSONB rows (never YAML): max_open_paper_positions,
-- daily_loss_limit (INR or % of equity), kill_switch
CREATE TABLE risk_settings (
  key        TEXT PRIMARY KEY,
  value      JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- append-only audit of every risk trip / kill-switch flip (the strategy audit log is
-- strategy-scoped with a fixed action enum, so global risk events get their own log)
CREATE TABLE risk_audit (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  key        TEXT NOT NULL,
  action     TEXT NOT NULL,
  detail     TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_audit_timeline ON risk_audit (created_at DESC);

-- the engine-computed sizing at emission time (A12). NUMERIC NULL, OUTSIDE the frozen
-- score_breakdown JSONB (a suggested_qty key inside score_breakdown is a FAIL).
ALTER TABLE signals ADD COLUMN suggested_qty NUMERIC;

GRANT SELECT, INSERT, UPDATE, DELETE ON paper_account, risk_settings TO ay_strategy;
GRANT SELECT, INSERT ON risk_audit TO ay_strategy;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA strategy TO ay_strategy;
