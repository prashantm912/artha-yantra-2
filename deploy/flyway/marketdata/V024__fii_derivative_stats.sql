-- Phase 4 / ADR-0002 U6: FII daily net activity across the four F&O derivative segments (oipulse
-- "FII Derivative Stats"). Upstox Market-Information only — NSE publishes no EOD equivalent. Plain
-- table (~4 rows/day, one per segment). Values in ₹ crore. A2 keep.
CREATE TABLE fii_derivative_stats (
    trade_date  DATE          NOT NULL,
    segment     TEXT          NOT NULL,   -- INDEX_FUTURES | INDEX_OPTIONS | STOCK_FUTURES | STOCK_OPTIONS
    buy_value   NUMERIC(18,2),
    sell_value  NUMERIC(18,2),
    net_value   NUMERIC(18,2),
    fetched_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (trade_date, segment)
);
