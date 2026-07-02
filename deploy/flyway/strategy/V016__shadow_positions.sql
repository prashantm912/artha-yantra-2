-- Shadow book (signal-analysis §7.1/§7.2): a REJECTED scalper entry opened as a virtual 1-lot
-- long-premium position so the exit machinery labels it with a real PnL — "what would this
-- blocked trade have done?". Rows are opened by ShadowBookService from the live rejection path
-- (composite passed but a rail vetoed) and closed by ShadowExitMonitor (premium brackets /
-- structural stop on the signal future / 15:12 square-off). COMPLETELY separate from
-- paper_positions by design: never counts against risk limits, capital, kill switch or the
-- E9 runbook's executed-trade analysis. LIVE-only (the replay never reaches the gate).

CREATE TABLE shadow_positions (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rejection_id         BIGINT NOT NULL REFERENCES signal_rejections(id),
  strategy_version_id  UUID NOT NULL REFERENCES strategy_versions(id),
  strategy_slug        TEXT NOT NULL,
  side                 TEXT NOT NULL,          -- CE/PE (directional only; straddles are out of v1)
  underlying           TEXT NOT NULL,          -- option root (e.g. NIFTY 50) — the chain-LTP fallback key
  exchange             TEXT NOT NULL,          -- option leg exchange (NFO/BFO)
  tradingsymbol        TEXT NOT NULL,          -- the picked option leg
  strike               NUMERIC(12,2),
  expiry               DATE,
  entry_ltp            NUMERIC(12,2) NOT NULL, -- option premium at the rejected bar (StrikePicker candidate LTP)
  stop_loss            NUMERIC(12,2),          -- premium bracket from the YAML premium_pct rules (null = none)
  take_profit          NUMERIC(12,2),
  structural_stop      NUMERIC(12,2),          -- index-future level (the gate's structural stop; null = none)
  signal_exchange      TEXT,                   -- the signal future the structural stop is read against
  signal_tradingsymbol TEXT,
  blocking_rail        TEXT NOT NULL,          -- which rail vetoed the real entry (the analysis join key)
  composite_score      NUMERIC(8,4),
  bar_time             TIMESTAMPTZ NOT NULL,
  opened_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  status               TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN / CLOSED
  exit_ltp             NUMERIC(12,2),
  close_reason         TEXT,                   -- TAKE_PROFIT / STOP_LOSS / STRUCTURAL_STOP / SQUARE_OFF / STALE
  closed_at            TIMESTAMPTZ,
  pnl_points           NUMERIC(12,2),          -- (exit - entry) premium points, 1-lot notional-free
  pnl_pct              NUMERIC(8,2)
);

CREATE INDEX idx_shadow_positions_status ON shadow_positions (status);
CREATE INDEX idx_shadow_positions_opened ON shadow_positions (opened_at DESC);
CREATE INDEX idx_shadow_positions_rejection ON shadow_positions (rejection_id);

GRANT SELECT, INSERT, UPDATE ON shadow_positions TO ay_strategy;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA strategy TO ay_strategy;
