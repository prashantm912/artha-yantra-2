-- Phase 1 (OpenAlgo routing, plan §17.4/§17.11): expand candles.source to accept OPENALGO (the §4
-- routing cutover) + EXPIRYTRACK/OPENCHART (§5 backfill). The four V003 values stay valid (the new
-- set is a superset, so existing rows pass).
--
-- candles has compression ENABLED (V003), and TimescaleDB blocks ADD/DROP CONSTRAINT on a
-- compression-enabled hypertable ("operation not supported on hypertables that have compression
-- enabled" — note ADD COLUMN is allowed, which is why V015 could extend futures_oi_snapshots, but a
-- CHECK swap is not). So we run the supported sequence: pause the compression policy, decompress
-- every chunk, disable compression, swap the CHECK, then re-enable compression + the policy
-- (identical settings to V003).
--
--   * FRESH DB (CI / Testcontainers): candles has no chunks, so the decompress is a no-op and this
--     applies instantly.
--   * LIVE DB: this DECOMPRESSES the existing candle history ONCE (bounded to the candles table —
--     far smaller than options_chain_snapshots). The re-added policy recompresses aged chunks on its
--     next run, so expect a temporary, bounded disk increase until then. Apply with disk headroom.
--
-- DROP CONSTRAINT uses the deterministic auto-name from the V003 inline CHECK (candles_source_check)
-- and is NOT guarded with IF EXISTS, so a name mismatch fails loudly rather than leaving the old
-- CHECK (which would still reject OPENALGO). Runs OUTSIDE a transaction (see the .sql.conf) —
-- TimescaleDB compression DDL requires it.

SELECT public.remove_compression_policy('candles', if_exists => true);

SELECT public.decompress_chunk(c, if_compressed => true)
FROM public.show_chunks('candles') c;

ALTER TABLE candles SET (timescaledb.compress = false);

ALTER TABLE candles DROP CONSTRAINT candles_source_check;
ALTER TABLE candles ADD CONSTRAINT candles_source_check
    CHECK (source IN ('KITE', 'TICK_AGG', 'MOCK', 'BACKFILL', 'OPENALGO', 'EXPIRYTRACK', 'OPENCHART'));

ALTER TABLE candles SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'exchange, tradingsymbol, "interval"',
    timescaledb.compress_orderby = 'bucket DESC');
SELECT public.add_compression_policy('candles', compress_after => INTERVAL '7 days');
