-- AYDB-01 (2026-07-19): enable compression + add a compression policy to each of the five candle
-- continuous aggregates. When V004/V029 created candles_5m/15m/1h/1d/1w they were left UNCOMPRESSED
-- (only the base `candles` hypertable got compression, V003) — so every rolled-up bar since boot has
-- accrued in the row-store. candles_5m alone was ~15 GB live (AYDB-03); the 30-day windows below
-- compress everything older than a month, so the bulk (the fat recent body, not just an old tail)
-- compresses ~10x — the ~18 GB one-time reclaim on a 100 GB DB budget. This migration only registers
-- metadata + a background compression policy per cagg — it does NOT compress synchronously
-- (no compress_chunk loop), so the migration itself is fast and light; the policy jobs compress aged
-- chunks lazily on their own schedule (immediate reclaim is a separate bounded compress_chunk pass).
--
-- SAFE ON TimescaleDB >= 2.18.1 (the DB was upgraded 2.17.2 -> 2.18.2-pg17 on 2026-07-19, the whole
-- reason this migration can ship): from 2.18.1 a compressed cagg region CAN be refreshed, so the manual
-- historical refresh paths (CandleRepository.refreshAll / CorporateActionJob CA recovery / gap backfill)
-- no longer error when they re-refresh a window that has since compressed. The `compress_after >
-- refresh start_offset` margins below (start_offsets 5m=1d, 15m=2d, 1h=7d, 1d=14d, 1w=60d — all cleared
-- with wide margin) are therefore belt-and-suspenders now, not a hard requirement. The recent
-- (uncompressed) window each policy leaves hot is what the live SignalEngine + charts read at fine
-- granularity; older chunks compress.
--
-- Segmentby/orderby mirror the base `candles` hypertable (V003) minus the `interval` column, which a
-- cagg has no equivalent of (each cagg is a single fixed interval): segment by the instrument key
-- (exchange, tradingsymbol), order by bucket DESC. This is also exactly what TimescaleDB would derive
-- automatically (segmentby = the GROUP BY columns, orderby = the time_bucket column); we set it
-- explicitly to pin it and match the hypertable.
--
-- Runs OUTSIDE a transaction (see the .sql.conf) — matches the repo convention for every cagg-touching
-- migration (V004/V019/V027/V029); TimescaleDB compression DDL demands it.
--
--   * FRESH DB (CI / Testcontainers): the caggs have no chunks, so enabling compression + adding the
--     policy applies instantly (nothing to compress yet).
--   * LIVE DB: the policy jobs compress aged chunks incrementally on their next run — NO one-shot
--     materialization or bulk compress in THIS migration (the #2bE1 / candles_3m OOM lesson). The
--     immediate reclaim is done separately as a BOUNDED per-chunk compress_chunk pass (never an
--     unbounded loop); the background jobs then keep new aged chunks compressed. Only ever REDUCES size.

-- candles_5m: refresh start_offset = 1 day. compress_after = 30 days (the biggest cagg, ~15 GB; keep a
-- month of fine-grained bars hot for the live engine + intraday charts, compress everything older).
ALTER MATERIALIZED VIEW candles_5m SET (
    timescaledb.compress = true,
    timescaledb.compress_segmentby = 'exchange, tradingsymbol',
    timescaledb.compress_orderby = 'bucket DESC');
SELECT public.add_compression_policy('candles_5m', compress_after => INTERVAL '30 days');

-- candles_15m: refresh start_offset = 2 days. compress_after = 30 days (same 30-day hot window as 5m).
ALTER MATERIALIZED VIEW candles_15m SET (
    timescaledb.compress = true,
    timescaledb.compress_segmentby = 'exchange, tradingsymbol',
    timescaledb.compress_orderby = 'bucket DESC');
SELECT public.add_compression_policy('candles_15m', compress_after => INTERVAL '30 days');

-- candles_1h: refresh start_offset = 7 days (V029). compress_after = 30 days (30 > 7; intraday hourly
-- overlays rarely read >1 month at fine granularity).
ALTER MATERIALIZED VIEW candles_1h SET (
    timescaledb.compress = true,
    timescaledb.compress_segmentby = 'exchange, tradingsymbol',
    timescaledb.compress_orderby = 'bucket DESC');
SELECT public.add_compression_policy('candles_1h', compress_after => INTERVAL '30 days');

-- candles_1d: refresh start_offset = 14 days. compress_after = 90 days (daily charts/screeners often
-- read a recent quarter; keep ~3 months hot, compress older; 90 > 14).
ALTER MATERIALIZED VIEW candles_1d SET (
    timescaledb.compress = true,
    timescaledb.compress_segmentby = 'exchange, tradingsymbol',
    timescaledb.compress_orderby = 'bucket DESC');
SELECT public.add_compression_policy('candles_1d', compress_after => INTERVAL '90 days');

-- candles_1w: refresh start_offset = 60 days. compress_after = 180 days (weekly charts span years; a
-- ~6-month hot window is generous; 180 > 60). candles_1w is hierarchical (rolled from candles_1d), and
-- its 60-day refresh window reads candles_1d data younger than that cagg's 90-day compress threshold,
-- so a candles_1w refresh never reads a compressed candles_1d chunk.
ALTER MATERIALIZED VIEW candles_1w SET (
    timescaledb.compress = true,
    timescaledb.compress_segmentby = 'exchange, tradingsymbol',
    timescaledb.compress_orderby = 'bucket DESC');
SELECT public.add_compression_policy('candles_1w', compress_after => INTERVAL '180 days');
