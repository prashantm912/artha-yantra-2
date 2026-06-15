-- Phase F / oipulse 1b: NSE daily security-wise bhavcopy + delivery (sec_bhavdata_full) —
-- per-symbol EOD OHLCV + delivery qty/%. ~3.2k rows/day. Hypertable on trade_date,
-- monthly chunks, compress after 7d. A2 keep-history (no auto-retention).
CREATE TABLE nse_eod_bhavcopy (
    trade_date     DATE          NOT NULL,
    symbol         TEXT          NOT NULL,
    series         TEXT          NOT NULL,   -- 'EQ' | 'BE' | 'GS' | ...
    prev_close     NUMERIC(18,4),
    open_price     NUMERIC(18,4),
    high_price     NUMERIC(18,4),
    low_price      NUMERIC(18,4),
    last_price     NUMERIC(18,4),
    close_price    NUMERIC(18,4),
    avg_price      NUMERIC(18,4),
    ttl_trd_qnty   BIGINT,
    turnover_lacs  NUMERIC(20,2),
    no_of_trades   BIGINT,
    deliv_qty      BIGINT,                   -- null for non-deliverable rows ('-')
    deliv_per      NUMERIC(8,2),             -- null for non-deliverable rows ('-')
    fetched_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (trade_date, symbol, series)
);

SELECT public.create_hypertable('nse_eod_bhavcopy', 'trade_date',
    chunk_time_interval => INTERVAL '1 month');

ALTER TABLE nse_eod_bhavcopy SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'series',
    timescaledb.compress_orderby = 'trade_date, symbol'
);
SELECT public.add_compression_policy('nse_eod_bhavcopy', INTERVAL '7 days');
-- NO retention policy (A2): >= 5y floor, default no-drop.
