-- Phase A (EOD bhavcopy → candles, plan eod-bhavcopy): expand candles.source to accept BHAVCOPY —
-- the bulk daily-EOD universe loader projects NSE/BSE security-wise bhavcopy rows into candles as
-- 1d EQ bars (source='BHAVCOPY'), so charts/backtests cover the whole equity universe without
-- per-stock Kite calls. The V003/V018 values stay valid (the new set is a superset).
--
-- candles has compression ENABLED (V003) and TimescaleDB blocks a CHECK swap on a compression-enabled
-- hypertable, so we repeat the V018 decompress-dance: pause the compression policy, decompress every
-- chunk, disable compression, swap the CHECK, then re-enable compression + the policy (identical
-- settings to V003/V018).
--
--   * FRESH DB (CI / Testcontainers): candles has no chunks, so the decompress is a no-op.
--   * LIVE DB: DECOMPRESSES the existing candle history ONCE (bounded to candles). The re-added policy
--     recompresses aged chunks on its next run; expect a temporary, bounded disk increase. Apply with
--     disk headroom.
--
-- DROP CONSTRAINT uses the deterministic auto-name (candles_source_check) and is NOT guarded with
-- IF EXISTS, so a name mismatch fails loudly. Runs OUTSIDE a transaction (.sql.conf) — TimescaleDB
-- compression DDL requires it.

SELECT public.remove_compression_policy('candles', if_exists => true);

SELECT public.decompress_chunk(c, if_compressed => true)
FROM public.show_chunks('candles') c;

ALTER TABLE candles SET (timescaledb.compress = false);

ALTER TABLE candles DROP CONSTRAINT candles_source_check;
ALTER TABLE candles ADD CONSTRAINT candles_source_check
    CHECK (source IN ('KITE', 'TICK_AGG', 'MOCK', 'BACKFILL', 'OPENALGO', 'EXPIRYTRACK', 'OPENCHART', 'BHAVCOPY'));

ALTER TABLE candles SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'exchange, tradingsymbol, "interval"',
    timescaledb.compress_orderby = 'bucket DESC');
SELECT public.add_compression_policy('candles', compress_after => INTERVAL '7 days');
