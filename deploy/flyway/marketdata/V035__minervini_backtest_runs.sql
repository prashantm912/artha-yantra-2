-- Phase-9 (Minervini swing backtest): persisted per-run backtest reports over candles@1d (~11y). One
-- row per completed run; the service holds the latest in memory and reads the newest row on a cold
-- boot. The full report (per-setup win-rate / payoff / expectancy / avg-hold) rides as JSONB — it is a
-- compact aggregate (a handful of setups), never the raw trade list.
CREATE TABLE minervini_backtest_runs (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_date  DATE        NOT NULL,
    run_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    report     JSONB       NOT NULL
);

CREATE INDEX idx_minervini_backtest_runs_run_at ON minervini_backtest_runs (run_at DESC);
