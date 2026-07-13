-- FID P2-4 / audit D8: nightly per-symbol data completeness across options-chain capture,
-- primary-instrument 1m candles, and NSE EQ bhavcopy day-over-day presence. One generic row per
-- (trading day, scope, symbol); the writer upserts, so correction re-runs stay idempotent.
--
-- The marketdata schema is owned by `artha` (admin V001), so no explicit GRANT is needed here
-- (mirrors V040 ingest_runs / V044 equity_breadth_daily / V046 snapshot pruning).
CREATE TABLE data_quality_days (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trading_date  DATE          NOT NULL,
    scope         TEXT          NOT NULL,
    symbol        TEXT          NOT NULL,
    expected      BIGINT        NOT NULL DEFAULT 0,
    present       BIGINT        NOT NULL DEFAULT 0,
    coverage_pct  NUMERIC(5,2),
    ok            BOOLEAN       NOT NULL,
    detail        TEXT,
    computed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (trading_date, scope, symbol)
);

CREATE INDEX ix_data_quality_days_date ON data_quality_days (trading_date DESC, scope);
