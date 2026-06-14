-- Phase 43 (F-43 / Q1): the paper-trading ledger. Simulated fills run through the
-- SAME FillSimulator (ltp_slippage/v1) in the strategy-engine JAR that backtest
-- replay uses, so paper P&L is comparable to the backtests that motivated the
-- strategy BY CONSTRUCTION (a paper-local fill path outside the JAR is the FAIL).
-- The fill-audit columns are stamped from the JAR's FillSimulator. Unrealized P&L
-- is NEVER stored - it is computed on demand from the Redis last-tick map. The
-- signal_id is a SAME-schema FK; there is no cross-schema FK (D10).

CREATE TABLE paper_orders (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  signal_id        BIGINT REFERENCES signals(id),
  exchange         TEXT NOT NULL,
  tradingsymbol    TEXT NOT NULL,
  side             TEXT NOT NULL CHECK (side IN ('BUY','SELL')),
  qty              BIGINT NOT NULL,
  order_type       TEXT NOT NULL DEFAULT 'MARKET',
  limit_price      NUMERIC(18,4),
  status           TEXT NOT NULL DEFAULT 'FILLED'
                   CHECK (status IN ('OPEN','FILLED','CANCELLED')),
  placed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  filled_at        TIMESTAMPTZ,
  fill_price       NUMERIC(18,4),
  -- fill-audit metadata sourced from the engine JAR's FillSimulator (§F.6)
  fill_simulator   TEXT,
  slippage_applied NUMERIC(18,4),
  quote_bid        NUMERIC(18,4),
  quote_ask        NUMERIC(18,4)
);

-- ledger view: newest orders first (§F.6 indexing)
CREATE INDEX idx_paper_orders_placed ON paper_orders (placed_at DESC);

CREATE TABLE paper_positions (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  exchange         TEXT NOT NULL,
  tradingsymbol    TEXT NOT NULL,
  side             TEXT NOT NULL CHECK (side IN ('BUY','SELL')),
  qty              BIGINT NOT NULL,
  avg_entry_price  NUMERIC(18,4) NOT NULL,
  realized_pnl     NUMERIC(18,4) NOT NULL DEFAULT 0,
  status           TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
  opened_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at        TIMESTAMPTZ
);

-- the §F.6 partial-unique open key: at most one OPEN position per (exchange, symbol, side)
CREATE UNIQUE INDEX uq_paper_positions_open
  ON paper_positions (exchange, tradingsymbol, side) WHERE status = 'OPEN';

GRANT SELECT, INSERT, UPDATE, DELETE ON paper_orders TO ay_strategy;
GRANT SELECT, INSERT, UPDATE, DELETE ON paper_positions TO ay_strategy;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA strategy TO ay_strategy;
