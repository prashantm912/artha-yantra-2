-- Correctness fix for V010's manual on-demand prune wrapper.
-- V010 declared marketdata.prune_options_snapshots as a LANGUAGE-sql function returning the
-- SETOF regclass that public.drop_chunks yields. That never worked when it actually dropped a
-- chunk: on return the SQL executor re-resolves each yielded regclass OID back to a relation name,
-- but the chunk it names has just been dropped, so the call errors
--   ERROR: relation "_timescaledb_internal._hyper_N_M_chunk" does not exist
--   CONTEXT: SQL function "prune_options_snapshots" statement 1
-- (empirically reproduced on Timescale 2.17.2-pg17). The documented manual usage
-- `SELECT marketdata.prune_options_snapshots();` therefore always failed whenever anything was
-- eligible to drop. A10's scheduled OptionsSnapshotPruneJob calls public.drop_chunks DIRECTLY and
-- never touches this wrapper, so nothing in the running system depended on the broken path — this
-- is purely a correctness fix for the manual/documented operator path.
--
-- Fix: a plpgsql PERFORM variant. PERFORM runs drop_chunks for its side effect and discards the
-- result set, so the just-dropped chunks' regclass OIDs are never re-resolved to text and the call
-- cannot error on a missing relation. The signature (name + INTERVAL arg + SETOF regclass return)
-- is kept identical so the V010 documentation stays true and CREATE OR REPLACE is legal. The one
-- behavioural change: the function now always returns an EMPTY set — it can no longer echo the
-- dropped chunk names, because re-resolving a just-dropped chunk's regclass IS the bug. Preview the
-- drop set BEFORE calling with `SELECT public.show_chunks('marketdata.options_chain_snapshots',
-- older_than => INTERVAL '5 years');`, or read the server log. (When nothing was old enough to drop,
-- V010 already returned an empty set successfully, so this is byte-identical for the only input V010
-- ever handled without erroring.)
CREATE OR REPLACE FUNCTION marketdata.prune_options_snapshots(retain INTERVAL DEFAULT INTERVAL '5 years')
RETURNS SETOF regclass
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM public.drop_chunks('marketdata.options_chain_snapshots', older_than => retain);
    RETURN;
END;
$$;

COMMENT ON FUNCTION marketdata.prune_options_snapshots(INTERVAL) IS
    'Manual storage relief (A2: no auto-retention). Drops options_chain_snapshots chunks older than '
    '`retain` (default 5y). Run by hand only when disk is tight: '
    'SELECT marketdata.prune_options_snapshots();  -- or pass a longer INTERVAL. '
    'Returns an empty set (plpgsql PERFORM variant, V046): re-resolving a just-dropped chunk''s '
    'regclass would error, so preview the drop set with public.show_chunks() instead.';
