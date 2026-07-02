-- Audit P2 (candles-3m-cagg-lineage-drift): V019 created candles_3m + a 3-minutely refresh
-- policy, but the runtime NEVER reads the cagg — 3m is served by a read-time 1m→3m rollup
-- (CandleRepository.rangeRolledFromOneMinute, #365) precisely because re-aggregating the
-- stitched CONT + expired-contract 1m series OOM-crashed the live DB twice. Applied migrations
-- are immutable, so remove it forward: drop the policy first (idempotent), then the view. Without
-- this, every `ay reset-db` / disaster-restore / CI rebuild resurrects the OOM-prone background
-- job and a materialized view nothing consumes — the lineage no longer reproduces production.
SELECT public.remove_continuous_aggregate_policy('candles_3m', if_exists => true);
DROP MATERIALIZED VIEW IF EXISTS candles_3m;
