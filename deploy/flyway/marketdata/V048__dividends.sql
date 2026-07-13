-- FID P2-6 / audit D5: dividend cash flows captured from NSE/BSE corporate-action feeds.
-- Dividends are separate cash-flow records and do NOT adjust prices, unlike split/bonus ratios.
-- Raw subjects are retained so unparseable amounts can be re-parsed without re-fetching the feed.
--
-- The marketdata schema is owned by `artha` (admin V001), so no explicit GRANT is needed here
-- (mirrors V022 eod_corporate_actions / V047 data_quality_days).
CREATE TABLE dividends (
    exchange       TEXT          NOT NULL,
    tradingsymbol  TEXT          NOT NULL,
    ex_date        DATE          NOT NULL,
    amount         NUMERIC(18,4),
    subject        TEXT          NOT NULL,
    isin           TEXT,
    source         TEXT          NOT NULL,
    detected_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (exchange, tradingsymbol, ex_date)
);

CREATE INDEX idx_dividends_symbol ON dividends (tradingsymbol, ex_date DESC);
