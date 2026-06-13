-- Phase 23 (C-2.12): generated calls with the explainability payload. Every
-- signal pins the EXACT config that fired (strategy_version_id) and carries
-- the frozen C-2.6 ScoreBreakdown JSONB - the same record the backtest engine
-- will persist per trade (one shape, two producers). suggested_qty arrives as
-- an ADDITIVE column in Stage F Phase 43A (D17), deliberately absent here.

CREATE TABLE signals (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  strategy_version_id UUID NOT NULL REFERENCES strategy_versions(id),
  exchange            TEXT NOT NULL,
  tradingsymbol       TEXT NOT NULL,
  "interval"          TEXT NOT NULL,
  signal_type         TEXT NOT NULL CHECK (signal_type IN ('ENTRY', 'EXIT')),
  side                TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
  entry_price         NUMERIC(18,4),
  stop_loss           NUMERIC(18,4),
  target              NUMERIC(18,4),
  composite_score     NUMERIC(8,4) NOT NULL,
  score_breakdown     JSONB NOT NULL,
  status              TEXT NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'EXPIRED', 'TAKEN', 'DISMISSED')),
  generated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at          TIMESTAMPTZ
);

-- C-2.12 indexing table: dashboard active panel; per-symbol history
CREATE INDEX idx_signals_generated ON signals (generated_at DESC);
CREATE INDEX idx_signals_active ON signals (status) WHERE status = 'ACTIVE';
CREATE INDEX idx_signals_symbol ON signals (exchange, tradingsymbol, generated_at DESC);

GRANT SELECT, INSERT, UPDATE ON signals TO ay_strategy;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA strategy TO ay_strategy;
