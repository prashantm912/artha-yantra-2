-- Stage D Phase 30 (§D.3): one row per completed engine replay + its queryable
-- trades. The FULL column set is created here additive-first (D17) even though
-- some writers land later: fold/degradation columns (Phases 31/32), the
-- 2026-06-12 analytics columns (premium_source 30A; alpha/beta/IR/excess_cagr,
-- benchmark_curve, montecarlo_summary 32A) and universe_checksum (Stage F) are
-- all NULL-able from day one so no later migration churns the schema. Phase 30
-- itself writes touch_basis on every closed trade.

CREATE TABLE backtest_runs (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id              UUID NOT NULL REFERENCES jobs(id),
  strategy_version_id UUID,                       -- soft cross-schema reference
  exchange            TEXT NOT NULL,
  tradingsymbol       TEXT NOT NULL,
  "interval"          TEXT NOT NULL,
  start_ts            TIMESTAMPTZ NOT NULL,
  end_ts              TIMESTAMPTZ NOT NULL,
  initial_equity      NUMERIC(18,2) NOT NULL,
  final_equity        NUMERIC(18,2) NOT NULL,
  params_override     JSONB,                      -- the trial's sampled values (NULL for plain runs)
  seed                BIGINT NOT NULL,            -- reproducibility triple leg 2 (recorded even if unused)
  data_hash           TEXT NOT NULL,              -- triple leg 3: SHA-256 over the candles actually read
  -- the D15/D7 comparison metric set (leaderboard-sortable explicit columns)
  total_return        NUMERIC(18,6) NOT NULL,
  sharpe              NUMERIC(18,6) NOT NULL,
  sortino             NUMERIC(18,6) NOT NULL,
  max_drawdown        NUMERIC(18,6) NOT NULL,
  win_rate            NUMERIC(18,6) NOT NULL,
  profit_factor       NUMERIC(18,6) NOT NULL,
  trade_count         INT NOT NULL,
  -- the full §D.9 catalog (CAGR, maxDD duration, expectancy, exposure, avg trade, …)
  metrics             JSONB NOT NULL,
  -- anti-overfitting columns — NULL-able; writers land in Phases 31/32
  sharpe_degradation  NUMERIC(18,6),
  fold_metrics        JSONB,
  oos_fold_mean       NUMERIC(18,6),
  oos_fold_std        NUMERIC(18,6),
  universe_checksum   TEXT,
  -- 2026-06-12 owner-selection analytics columns — NULL-able; writers land in 30A / 32A
  premium_source      TEXT CHECK (premium_source IN ('SNAPSHOT', 'SYNTHETIC_B76', 'NA')),
  alpha               NUMERIC(18,6),
  beta                NUMERIC(18,6),
  information_ratio   NUMERIC(18,6),
  excess_cagr         NUMERIC(18,6),
  benchmark_curve     JSONB,
  montecarlo_summary  JSONB,
  -- downsampled curves + traceability
  equity_curve        JSONB NOT NULL,             -- ~500 points for lightweight-charts
  drawdown_curve      JSONB NOT NULL,
  engine_version      TEXT NOT NULL,
  completed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE backtest_trades (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id       UUID NOT NULL REFERENCES backtest_runs(id) ON DELETE CASCADE,
  seq          INT NOT NULL,
  side         TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
  qty          BIGINT NOT NULL,
  entry_ts     TIMESTAMPTZ NOT NULL,
  entry_price  NUMERIC(18,4) NOT NULL,
  exit_ts      TIMESTAMPTZ,
  exit_price   NUMERIC(18,4),
  pnl          NUMERIC(18,2) NOT NULL,
  pnl_pct      NUMERIC(18,6) NOT NULL,
  exit_reason  TEXT,
  bars_held    INT NOT NULL,
  touch_basis  TEXT CHECK (touch_basis IN ('CLOSE_EVAL', 'INTRABAR_1M', 'BAR_HL_WORSTOF')),
  contributions JSONB                            -- per-trade indicator contributions (entry breakdown)
);

-- §D.3 indexing strategy (plan §6.7)
CREATE INDEX idx_runs_job ON backtest_runs (job_id);
CREATE INDEX idx_runs_strategy ON backtest_runs (strategy_version_id, completed_at DESC);
CREATE INDEX idx_runs_sharpe ON backtest_runs (sharpe DESC);
CREATE INDEX idx_trades_run_seq ON backtest_trades (run_id, seq);

GRANT SELECT, INSERT, UPDATE, DELETE ON backtest_runs TO ay_backtest;
GRANT SELECT, INSERT, UPDATE, DELETE ON backtest_trades TO ay_backtest;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA backtest TO ay_backtest;
