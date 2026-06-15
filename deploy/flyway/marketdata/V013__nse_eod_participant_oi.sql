-- Phase F / oipulse 1b: NSE daily participant-wise OI (Client/DII/FII/Pro/TOTAL futures+options
-- long/short, no. of contracts). Plain table (~5 rows/day). A2 keep-history.
CREATE TABLE nse_eod_participant_oi (
    trade_date              DATE        NOT NULL,
    client_type             TEXT        NOT NULL,   -- 'Client' | 'DII' | 'FII' | 'Pro' | 'TOTAL'
    future_index_long       BIGINT,
    future_index_short      BIGINT,
    future_stock_long       BIGINT,
    future_stock_short      BIGINT,
    option_index_call_long  BIGINT,
    option_index_put_long   BIGINT,
    option_index_call_short BIGINT,
    option_index_put_short  BIGINT,
    option_stock_call_long  BIGINT,
    option_stock_put_long   BIGINT,
    option_stock_call_short BIGINT,
    option_stock_put_short  BIGINT,
    total_long_contracts    BIGINT,
    total_short_contracts   BIGINT,
    fetched_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (trade_date, client_type)
);
