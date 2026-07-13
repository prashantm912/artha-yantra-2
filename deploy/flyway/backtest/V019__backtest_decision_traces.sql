-- D4 P2-8 / FID audit B12: opt-in per-day rejected-entry diagnostics for backtest runs.
CREATE TABLE backtest_decision_days (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id           UUID NOT NULL REFERENCES backtest_runs(id) ON DELETE CASCADE,
  session_date     DATE NOT NULL,
  reason           TEXT NOT NULL,
  bars             INTEGER NOT NULL,
  max_composite    NUMERIC,
  sample_bucket    TIMESTAMPTZ,
  sample_breakdown JSONB,
  UNIQUE (run_id, session_date, reason)
);

CREATE INDEX idx_bdd_run_date ON backtest_decision_days (run_id, session_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON backtest_decision_days TO ay_backtest;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA backtest TO ay_backtest;
