-- Phase 10 (B-7): the candles hypertable - DB-as-cache for Kite OHLCV plus
-- tick-aggregated 1m bars. NUMERIC everywhere (no float/double); source is
-- CHECK-enforced one-casing; PK includes the partition column.

CREATE TABLE candles (
    exchange      TEXT        NOT NULL,
    tradingsymbol TEXT        NOT NULL,
    "interval"    TEXT        NOT NULL CHECK ("interval" IN ('1m', '5m', '15m', '1h', '1d')),
    bucket        TIMESTAMPTZ NOT NULL,
    open          NUMERIC(18,4) NOT NULL,
    high          NUMERIC(18,4) NOT NULL,
    low           NUMERIC(18,4) NOT NULL,
    close         NUMERIC(18,4) NOT NULL,
    volume        BIGINT      NOT NULL DEFAULT 0,
    oi            BIGINT,
    source        TEXT        NOT NULL CHECK (source IN ('KITE', 'TICK_AGG', 'MOCK', 'BACKFILL')),
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (exchange, tradingsymbol, "interval", bucket)
);

-- 1-week chunks (~0.4M rows/chunk at ~200 instruments x 375 1m bars/day);
-- no space partitioning - one NVMe disk, segmentby already gives locality
SELECT public.create_hypertable('candles', 'bucket', chunk_time_interval => INTERVAL '7 days');

ALTER TABLE candles SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'exchange, tradingsymbol, "interval"',
    timescaledb.compress_orderby = 'bucket DESC'
);

-- compress after 7 days; NO retention policy - DB-as-cache, no-drop (B-7)
SELECT public.add_compression_policy('candles', compress_after => INTERVAL '7 days');
