-- Phase F / oipulse: intraday futures OI time-series (front/next/far monthly FUT).
-- A2: keep history (no auto-retention; compression after 7d). Manual prune mirrors options (V010).
CREATE TABLE futures_oi_snapshots (
    ts             TIMESTAMPTZ   NOT NULL,
    underlying     TEXT          NOT NULL,
    tradingsymbol  TEXT          NOT NULL,
    expiry         DATE          NOT NULL,
    ltp            NUMERIC(18,4),
    volume         BIGINT,
    oi             BIGINT,
    oi_change      BIGINT,
    PRIMARY KEY (ts, underlying, tradingsymbol)
);

SELECT public.create_hypertable('futures_oi_snapshots', 'ts',
    chunk_time_interval => INTERVAL '1 day');

ALTER TABLE futures_oi_snapshots SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'underlying, tradingsymbol',
    timescaledb.compress_orderby = 'ts'
);
SELECT public.add_compression_policy('futures_oi_snapshots', INTERVAL '7 days');
-- NO retention policy (A2). Manual relief mirrors V010's options prune if ever needed.
