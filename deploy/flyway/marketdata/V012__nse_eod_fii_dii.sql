-- Phase F / oipulse 1b: NSE daily FII/DII cash buy/sell/net. Plain table (~2 rows/day). A2 keep.
CREATE TABLE nse_eod_fii_dii (
    trade_date  DATE          NOT NULL,
    category    TEXT          NOT NULL,           -- 'DII' | 'FII/FPI'
    buy_value   NUMERIC(18,2),
    sell_value  NUMERIC(18,2),
    net_value   NUMERIC(18,2),
    fetched_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (trade_date, category)
);
