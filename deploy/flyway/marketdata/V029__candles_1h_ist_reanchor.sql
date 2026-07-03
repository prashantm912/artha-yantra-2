-- (2026-07-04) candles_1h was bucketed on UTC clock hours (`time_bucket('1 hour', bucket)`) while
-- its candles_1d / candles_1w siblings are IST-aligned via the 'Asia/Kolkata' timezone parameter
-- (V004). On the IST +05:30 offset a UTC-hour bucket splits at :30 IST (a "1h" bar spans e.g.
-- 09:30–10:30 IST), so the 1h series never lined up with the IST clock hours a 1h chart/overlay
-- expects. Re-anchor it to IST clock hours for consistency with 1d/1w.
--
-- Applied migrations are immutable, so this is a forward drop + recreate. Recreated WITH NO DATA +
-- the same refresh policy as V004, so the LIVE Timescale never does a heavy one-shot materialization
-- (the #2bE1 / candles_3m OOM lesson — never materialize a cagg over a wide window on the busy live
-- DB): the policy backfills the 7-day window incrementally, exactly as before. Reads during
-- flyway-init are impossible (init container runs before the app serves), so the drop/recreate gap
-- is invisible.

SELECT public.remove_continuous_aggregate_policy('candles_1h', if_exists => true);
DROP MATERIALIZED VIEW IF EXISTS candles_1h;

CREATE MATERIALIZED VIEW candles_1h
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT exchange, tradingsymbol,
       public.time_bucket(INTERVAL '1 hour', bucket, 'Asia/Kolkata') AS bucket,
       public.first(open, bucket)  AS open,
       max(high)                   AS high,
       min(low)                    AS low,
       public.last(close, bucket)  AS close,
       sum(volume)                 AS volume,
       public.last(oi, bucket)     AS oi
FROM candles
WHERE "interval" = '1m'
GROUP BY exchange, tradingsymbol, public.time_bucket(INTERVAL '1 hour', bucket, 'Asia/Kolkata')
WITH NO DATA;

SELECT public.add_continuous_aggregate_policy('candles_1h',
    start_offset => INTERVAL '7 days', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '15 minutes');
