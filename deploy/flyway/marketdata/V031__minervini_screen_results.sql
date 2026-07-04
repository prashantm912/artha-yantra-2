-- Phase-5 Track-1 (Minervini SEPA): persisted daily Trend-Template screen output.
-- One row per (screen_date, symbol). Plain table (~hundreds of passing rows/day),
-- append/replace per run. Lives in the marketdata lineage per OD-5 (the producer
-- MinerviniScheduler runs inside market-data-service, which writes the marketdata
-- schema under the single-writer 'artha' role); the CD-1 backtest role inherits the
-- SELECT grant on marketdata. See docs/adr/0005-minervini-universe-low-cap-equities.md.
CREATE TABLE minervini_screen_results (
    screen_date        DATE          NOT NULL,
    symbol             TEXT          NOT NULL,
    exchange           TEXT          NOT NULL DEFAULT 'NSE',
    close_price        NUMERIC(18,4) NOT NULL,
    sma50              NUMERIC(18,4),
    sma150             NUMERIC(18,4),
    sma200             NUMERIC(18,4),
    high_52w           NUMERIC(18,4),
    low_52w            NUMERIC(18,4),
    pct_from_high      NUMERIC(10,4),   -- (close - high_52w) / high_52w
    pct_above_low      NUMERIC(10,4),   -- (close - low_52w)  / low_52w
    rs_rank            NUMERIC(6,2),    -- 0..100 cross-sectional percentile (IBD-style)
    avg_turnover_50    NUMERIC(20,2),   -- avg(close*volume) over 50 sessions (liquidity)
    -- Low-cap gate inputs — filled by the fundamentals layer (Upstox); NULL until then.
    free_float_mcap_cr NUMERIC(18,2),
    free_float_pct     NUMERIC(8,2),
    gate1 BOOLEAN NOT NULL, gate2 BOOLEAN NOT NULL, gate3 BOOLEAN NOT NULL,
    gate4 BOOLEAN NOT NULL, gate5 BOOLEAN NOT NULL, gate6 BOOLEAN NOT NULL,
    gate7 BOOLEAN NOT NULL, gate8 BOOLEAN NOT NULL,
    gates_passed       SMALLINT      NOT NULL,
    passes_all         BOOLEAN       NOT NULL,
    stage              SMALLINT,        -- MV-2.9 Weinstein/Minervini Stage 1-4 (derived label)
    computed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (screen_date, symbol)
);

CREATE INDEX idx_minervini_passes
    ON minervini_screen_results (screen_date, passes_all, rs_rank DESC);
