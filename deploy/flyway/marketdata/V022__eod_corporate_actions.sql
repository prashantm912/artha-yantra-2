-- Phase C (split/bonus adjustment): ratios for read-time back-adjustment of BHAVCOPY equity 1d bars.
-- Source is the NSE corporate-actions feed (the `subject` text → ratio); an event keyed on the NSE
-- listing is cross-applied to the BSE listing by ISIN. `ratio` is the PRE-ex-date price multiplier
-- (the adjuster multiplies bars dated before ex_date by the cumulative product of later ratios):
--   * SPLIT  Rs10 -> Rs2  ratio = 2/10  = 0.2   (price divides by 5)
--   * BONUS  a:b (a free per b held)  ratio = b/(a+b)   e.g. 1:1 -> 0.5, 1:3 -> 0.75
-- Dividends do NOT adjust price here (ignored by the parser). Small table (a few thousand lifetime
-- rows) — a plain table, not a hypertable.
CREATE TABLE eod_corporate_actions (
    exchange       TEXT          NOT NULL,
    tradingsymbol  TEXT          NOT NULL,
    ex_date        DATE          NOT NULL,
    ratio          NUMERIC(18,8) NOT NULL CHECK (ratio > 0),  -- pre-ex multiplier (<1 for splits & bonuses)
    kind           TEXT          NOT NULL CHECK (kind IN ('SPLIT','BONUS')),
    isin           TEXT,
    subject        TEXT,                                       -- raw CA text (audit)
    source         TEXT          NOT NULL DEFAULT 'NSE_CA_API',
    detected_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- PK indexes (exchange, tradingsymbol, ex_date) — serves the per-symbol ordered ratio read.
    PRIMARY KEY (exchange, tradingsymbol, ex_date)
);
