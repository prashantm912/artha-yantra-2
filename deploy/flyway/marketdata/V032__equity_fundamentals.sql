-- Phase-5 Track-1 (Minervini): per-stock fundamentals snapshot from the Upstox Company Fundamentals
-- API (analytics token). One latest-restatement row per symbol; refreshed weekly (shares/holdings
-- barely move). Feeds the low-cap universe gate (free-float mcap + free-float %, ADR-0004/0005) and
-- the §4.8 ROE quality read. Derivations: free_float_pct = 100 - promoter%; market_cap_cr = P/E x
-- net_profit_cr; free_float_mcap_cr = market_cap_cr x free_float_pct/100. Earnings-surprise +
-- estimate-revisions stay UNMODELED (no Indian consensus feed).
CREATE TABLE equity_fundamentals (
    symbol             TEXT          PRIMARY KEY,
    isin               TEXT,
    market_cap_cr      NUMERIC(18,2),
    free_float_mcap_cr NUMERIC(18,2),
    free_float_pct     NUMERIC(8,2),
    promoter_pct       NUMERIC(8,2),
    pe                 NUMERIC(14,2),
    roe                NUMERIC(8,2),
    net_profit_cr      NUMERIC(18,2),
    revenue_cr         NUMERIC(18,2),
    revenue_prev_cr    NUMERIC(18,2),
    as_of              DATE,
    source             TEXT          NOT NULL DEFAULT 'UPSTOX',
    fetched_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
