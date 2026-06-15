-- Phase F / oipulse: manual on-demand prune for options_chain_snapshots.
-- A2 keeps options OI history by default (no AUTO retention; compression after 7d lives in V006).
-- The full-chain 3-min snapshotter grows the hypertable ~0.5M rows/day, so give an operator a safe,
-- explicit way to reclaim disk when it's tight: drop whole chunks older than `retain` (default 5y,
-- the A2 floor). Nothing schedules this — it is called by hand only.
CREATE OR REPLACE FUNCTION marketdata.prune_options_snapshots(retain INTERVAL DEFAULT INTERVAL '5 years')
RETURNS SETOF regclass
LANGUAGE sql
AS $$
    SELECT public.drop_chunks('marketdata.options_chain_snapshots', older_than => retain);
$$;

COMMENT ON FUNCTION marketdata.prune_options_snapshots(INTERVAL) IS
    'Manual storage relief (A2: no auto-retention). Drops options_chain_snapshots chunks older than '
    '`retain` (default 5y). Run by hand only when disk is tight: '
    'SELECT marketdata.prune_options_snapshots();  -- or pass a longer INTERVAL.';
