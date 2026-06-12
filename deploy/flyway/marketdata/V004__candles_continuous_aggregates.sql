-- Phase 10 (B-7/B-21): continuous aggregates 1m -> 5m/15m/1h/1d, plus the
-- 1w HIERARCHICAL aggregate rolled from candles_1d (cagg-on-cagg; Kite has
-- no weekly API - 1w is only ever rolled, never fetched). All REAL-TIME
-- (materialized_only=false) so in-progress buckets compute live. Runs
-- outside a transaction (see the .conf file) - cagg DDL demands it.
-- 1d/1w buckets are IST-day / IST-Monday-week aligned via the timezone
-- parameter (PG week origin 2000-01-03 is a Monday - B-21).

CREATE MATERIALIZED VIEW candles_5m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT exchange, tradingsymbol,
       public.time_bucket(INTERVAL '5 minutes', bucket) AS bucket,
       public.first(open, bucket)  AS open,
       max(high)                   AS high,
       min(low)                    AS low,
       public.last(close, bucket)  AS close,
       sum(volume)                 AS volume,
       public.last(oi, bucket)     AS oi
FROM candles
WHERE "interval" = '1m'
GROUP BY exchange, tradingsymbol, public.time_bucket(INTERVAL '5 minutes', bucket)
WITH NO DATA;

CREATE MATERIALIZED VIEW candles_15m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT exchange, tradingsymbol,
       public.time_bucket(INTERVAL '15 minutes', bucket) AS bucket,
       public.first(open, bucket)  AS open,
       max(high)                   AS high,
       min(low)                    AS low,
       public.last(close, bucket)  AS close,
       sum(volume)                 AS volume,
       public.last(oi, bucket)     AS oi
FROM candles
WHERE "interval" = '1m'
GROUP BY exchange, tradingsymbol, public.time_bucket(INTERVAL '15 minutes', bucket)
WITH NO DATA;

CREATE MATERIALIZED VIEW candles_1h
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT exchange, tradingsymbol,
       public.time_bucket(INTERVAL '1 hour', bucket) AS bucket,
       public.first(open, bucket)  AS open,
       max(high)                   AS high,
       min(low)                    AS low,
       public.last(close, bucket)  AS close,
       sum(volume)                 AS volume,
       public.last(oi, bucket)     AS oi
FROM candles
WHERE "interval" = '1m'
GROUP BY exchange, tradingsymbol, public.time_bucket(INTERVAL '1 hour', bucket)
WITH NO DATA;

CREATE MATERIALIZED VIEW candles_1d
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT exchange, tradingsymbol,
       public.time_bucket(INTERVAL '1 day', bucket, 'Asia/Kolkata') AS bucket,
       public.first(open, bucket)  AS open,
       max(high)                   AS high,
       min(low)                    AS low,
       public.last(close, bucket)  AS close,
       sum(volume)                 AS volume,
       public.last(oi, bucket)     AS oi
FROM candles
WHERE "interval" = '1m'
GROUP BY exchange, tradingsymbol, public.time_bucket(INTERVAL '1 day', bucket, 'Asia/Kolkata')
WITH NO DATA;

-- B-21: hierarchical weekly cagg rolled from the 1d aggregate
CREATE MATERIALIZED VIEW candles_1w
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT exchange, tradingsymbol,
       public.time_bucket(INTERVAL '1 week', bucket, 'Asia/Kolkata') AS bucket,
       public.first(open, bucket)  AS open,
       max(high)                   AS high,
       min(low)                    AS low,
       public.last(close, bucket)  AS close,
       sum(volume)                 AS volume,
       public.last(oi, bucket)     AS oi
FROM candles_1d
GROUP BY exchange, tradingsymbol, public.time_bucket(INTERVAL '1 week', bucket, 'Asia/Kolkata')
WITH NO DATA;

-- refresh policies (B-7): 5m every 5 min (1-min offset); 15m/1h every 15
-- min; 1d daily anchored 16:00 IST (post-close); 1w daily 16:10 IST after
-- the 1d refresh. Real-time reads do not depend on these firing.
SELECT public.add_continuous_aggregate_policy('candles_5m',
    start_offset => INTERVAL '1 day', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '5 minutes');
SELECT public.add_continuous_aggregate_policy('candles_15m',
    start_offset => INTERVAL '2 days', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '15 minutes');
SELECT public.add_continuous_aggregate_policy('candles_1h',
    start_offset => INTERVAL '7 days', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '15 minutes');
SELECT public.add_continuous_aggregate_policy('candles_1d',
    start_offset => INTERVAL '14 days', end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 day',
    initial_start => ((date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') + INTERVAL '1 day 16 hours') AT TIME ZONE 'Asia/Kolkata'));
SELECT public.add_continuous_aggregate_policy('candles_1w',
    start_offset => INTERVAL '60 days', end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 day',
    initial_start => ((date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') + INTERVAL '1 day 16 hours 10 minutes') AT TIME ZONE 'Asia/Kolkata'));
