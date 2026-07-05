-- Manas Arora selection screener: persisted daily selection-filter output + per-candidate base
-- geometry. A separate strategy family from Minervini (its own tables), long-only cash equities.
-- Both tables live in the marketdata lineage per OD-5 (the producer ManasScheduler runs inside
-- market-data-service, which writes the marketdata schema under the single-writer 'artha' role);
-- the CD-1 backtest role inherits the SELECT grant on marketdata via ALTER DEFAULT PRIVILEGES
-- (admin V001), so no explicit GRANT is needed here (mirrors V031/V033).

-- The §4.1 six-criteria selection + §1.2 universe + §4.3 liquidity output. One row per
-- (screen_date, symbol) for EVERY scanned candidate (not just the passers) so a fail is explainable.
CREATE TABLE manas_arora_screen_results (
    screen_date        DATE          NOT NULL,
    symbol             TEXT          NOT NULL,
    exchange           TEXT          NOT NULL DEFAULT 'NSE',
    close_price        NUMERIC(18,4) NOT NULL,
    sma50              NUMERIC(18,4),
    sma200             NUMERIC(18,4),
    high_52w           NUMERIC(18,4),
    low_52w            NUMERIC(18,4),
    avg_vol_20         NUMERIC(20,2),   -- §4.3 ~20-day average traded volume (shares)
    avg_vol_50         NUMERIC(20,2),   -- §4.3 ~10-week (50-session) average volume (shares)
    turnover_50        NUMERIC(20,2),   -- avg(close*volume) over 50 sessions
    within_high_pct    NUMERIC(10,4),   -- (close - high_52w) / high_52w
    above_low_pct      NUMERIC(10,4),   -- (close - low_52w)  / low_52w
    within_high        BOOLEAN NOT NULL, -- §1.2 within 25% of the 52-week high (universe gate)
    above_sma50        BOOLEAN NOT NULL, -- §4.1 gate-4 soft flag: preferably also above the 50-SMA
    liquid_volume      BOOLEAN NOT NULL, -- §4.3 20d-avg-volume absolute veto passed
    liquid_depth       BOOLEAN NOT NULL, -- §4.3 10wk-avg-volume depth check passed
    low_cap            BOOLEAN NOT NULL, -- §4.4 float gate (default-off; TRUE = passes / unknown)
    -- Low-cap gate inputs — filled by the fundamentals layer (Upstox); NULL until then.
    free_float_mcap_cr NUMERIC(18,2),
    free_float_pct     NUMERIC(8,2),
    -- The 6 §4.1 selection gates: (1) price>=30, (2) 200-SMA rising, (3) 50>200, (4) close>200,
    -- (5) >=100% above 52w-low, (6) new 52w-high in the last ~4-6 months.
    gate1 BOOLEAN NOT NULL, gate2 BOOLEAN NOT NULL, gate3 BOOLEAN NOT NULL,
    gate4 BOOLEAN NOT NULL, gate5 BOOLEAN NOT NULL, gate6 BOOLEAN NOT NULL,
    gates_passed       SMALLINT      NOT NULL,
    passes_all         BOOLEAN       NOT NULL,
    computed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (screen_date, symbol)
);

CREATE INDEX idx_manas_passes
    ON manas_arora_screen_results (screen_date, passes_all, above_low_pct DESC);

-- Per-candidate base geometry. TWO rows per passer — one per setup_type ('vcp' | 'breakout') —
-- keyed (screen_date, symbol, setup_type). Populated only for the day's passers (the geometry scan
-- is per-symbol and expensive). is_valid=false rows keep reject_reason so a "why no base" is
-- queryable. The vcp footprint is emitted by the reused VcpDetector; breakout by ConsolidationBreakout.
CREATE TABLE manas_arora_setups (
    screen_date   DATE          NOT NULL,
    symbol        TEXT          NOT NULL,
    setup_type    TEXT          NOT NULL,   -- 'vcp' | 'breakout'
    is_valid      BOOLEAN       NOT NULL,
    footprint     TEXT,                     -- vcp: "[wk]W [deepest/tightest] [n]T"; breakout: "[wk]W range [pct]%"
    pivot         NUMERIC(18,4),            -- buy trigger = nearest swing high of the base
    base_weeks    SMALLINT,
    reject_reason TEXT,                     -- NULL when is_valid; else why it is not a valid base
    computed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (screen_date, symbol, setup_type)
);

CREATE INDEX idx_manas_setups_valid
    ON manas_arora_setups (screen_date, setup_type, is_valid);
