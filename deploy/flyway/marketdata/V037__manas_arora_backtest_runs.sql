-- Manas Arora swing backtest: persisted per-run backtest reports over candles@1d (~11y). One row per
-- completed run; the service holds the latest in memory and reads the newest row on a cold boot. The
-- full report (per-setup win-rate / payoff / expectancy / avg-hold + portfolio stats) rides as JSONB
-- — a compact aggregate (a handful of setups + variants), never the raw trade list. Forks V035
-- (minervini_backtest_runs) exactly. Lives in the marketdata lineage per OD-5 (the producer
-- ManasAroraBacktestService runs inside market-data-service under the single-writer 'artha' role);
-- the CD-1 backtest role inherits the SELECT grant on marketdata via ALTER DEFAULT PRIVILEGES
-- (admin V001), so no explicit GRANT is needed here (mirrors V035).
CREATE TABLE manas_arora_backtest_runs (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_date  DATE        NOT NULL,
    run_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    report     JSONB       NOT NULL
);

CREATE INDEX idx_manas_arora_backtest_runs_run_at ON manas_arora_backtest_runs (run_at DESC);
