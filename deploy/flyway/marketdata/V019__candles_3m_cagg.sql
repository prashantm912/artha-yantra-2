-- Phase 1 (plan §17.4/§17.11 + §7.5): 3-minute continuous aggregate for the Siva
-- scalper's execution timeframe. Mirrors the V004 intraday caggs exactly:
-- REAL-TIME (materialized_only=false, so in-progress buckets compute live),
-- rolled from raw 1m candles, same first/max/min/last/sum reductions incl. the
-- per-bar OI carried as last(oi). IST-origin time_bucket so 3m buckets align to
-- the 09:15 IST session open (3 evenly divides the +05:30 = 330-minute offset, so
-- this is bucket-identical to a UTC-origin aggregate, but the timezone form states
-- the scalper's alignment intent explicitly and matches the 1d/1w caggs). The
-- engine reads this via CandleReader like candles_5m; LiveSeriesStore's 3m
-- resampling must reproduce these exact buckets for live<->replay parity (§7.5).
-- Runs OUTSIDE a transaction (see the .sql.conf) — cagg DDL demands it.

CREATE MATERIALIZED VIEW candles_3m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT exchange, tradingsymbol,
       public.time_bucket(INTERVAL '3 minutes', bucket, 'Asia/Kolkata') AS bucket,
       public.first(open, bucket)  AS open,
       max(high)                   AS high,
       min(low)                    AS low,
       public.last(close, bucket)  AS close,
       sum(volume)                 AS volume,
       public.last(oi, bucket)     AS oi
FROM candles
WHERE "interval" = '1m'
GROUP BY exchange, tradingsymbol, public.time_bucket(INTERVAL '3 minutes', bucket, 'Asia/Kolkata')
WITH NO DATA;

-- refresh every 3 minutes (1-min end offset), mirroring candles_5m's policy
SELECT public.add_continuous_aggregate_policy('candles_3m',
    start_offset => INTERVAL '1 day', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '3 minutes');
