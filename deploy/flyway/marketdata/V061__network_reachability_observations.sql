-- NEW-13 — a durable record of outbound-network reachability, so "was it us or the vendor?" has an
-- answer AFTER the fact rather than being re-derived by hand from container logs each time.
--
-- ⚠️ WHY A TABLE AND NOT A COUNTER, because a metric is the obvious choice and it is the wrong one.
-- Micrometer counters are PROCESS-LIFETIME. The event this records is a host network death, and on
-- all three observed occasions it was followed by the box going down — so a counter is reset by the
-- very incident it exists to measure. The platform has already paid for this lesson twice:
-- `ay_paper_fill_no_tick_total` made a weekly arming report structurally unable to do its job, and
-- the H26 session rate peaks died with their process. Durable or useless.
--
-- ⚠️ WHY ONE ROW PER OBSERVATION AND NOT ONE PER EPISODE, and this is the whole design (owner,
-- 2026-09-03). The first five revisions of this feature opened a row when the quorum first said
-- UNREACHABLE and closed it when reachability returned. That shape is a state machine, and across
-- six review rounds it produced thirteen findings — seven of them introduced while fixing earlier
-- ones. The last was a Critical no amount of care removes: if a close WRITE fails and a new outage
-- begins before the next healthy pass, the writer sees a row already open, declines to open a
-- second, and a later recovery closes the FIRST row — yielding one authoritative row spanning
-- outage A, a healthy gap, and outage B. A record that merges two incidents is worse than no
-- record, because it is confidently wrong rather than absent.
--
-- A row here is instead an unconditionally TRUE statement: DURING THE PASS ENDING AT observed_at,
-- this many of these probed destinations were unreachable. No sequence of passes, no failed write,
-- no restart and no clock step can make an existing row false.
--
-- ⚠️ "the pass ending at", not "the instant of": destinations are probed SEQUENTIALLY and the clock
-- is read after the loop, so a row covers a window as wide as that pass's timeouts allow (five
-- destinations at a five-second connect timeout is up to twenty-five seconds). It bounds the
-- outage from ABOVE. Reading observed_at as a point sample would make a gap-based grouping look
-- more precise than it is. A failed insert loses exactly one observation and
-- needs no retry, because the next pass makes its own.
--
-- The cost is that INCIDENTS are derived at read time rather than stored. That is where the
-- derivation belongs: the rule for "how long a gap separates two outages" is a judgement that can
-- change, and doing it in SQL cannot corrupt the underlying facts. One row per five minutes while
-- the host's network is dead — a clean day writes NOTHING AT ALL, and 2026-09-01's six-hour
-- outage would have written on the order of eighty rows.
--
--   -- consecutive observations, and the gap since the previous one:
--   SELECT observed_at, unreachable_count, probed_count, failed_names,
--          observed_at - lag(observed_at) OVER (ORDER BY observed_at) AS gap
--     FROM network_reachability_observations
--    ORDER BY observed_at;
--
-- ⚠️ ONLY QUORUM-MET PASSES ARE WRITTEN. One destination failing is that vendor being down; most or
-- all failing inside one window is the host's own outbound network, which is the finding this table
-- exists to make recoverable. A below-quorum pass is LOGGED and not stored, deliberately: a single
-- vendor that starts refusing our probe would otherwise write 288 rows a day forever, burying the
-- incidents under exactly the noise the per-observation shape is accused of creating.
--
-- Record-only by design (owner, 2026-09-02). It never pages: when this fires, the paging channels
-- are among the things that are down -- measured 2026-09-01, telegram AND ntfy both dead while the
-- stack looked healthy -- so an alert is precisely the mechanism that cannot be relied upon. The
-- value is a trustworthy after-the-fact record, which is what all three incidents actually needed.

CREATE TABLE network_reachability_observations (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- ⚠️ The END of the probe pass, not a point sample: the destinations are walked sequentially
    -- and the clock is read once, afterwards.
    observed_at    TIMESTAMPTZ NOT NULL,

    -- Both numbers are kept, never just the ratio: "3 of 5 failed" and "3 of 3 failed" are
    -- different findings, and a stored ratio cannot be re-disaggregated later.
    probed_count      SMALLINT NOT NULL,
    unreachable_count SMALLINT NOT NULL,

    -- The THRESHOLD in force when this row was written, not just the observation. Without it a row
    -- cannot be re-judged later: "3 of 5 failed" means something different under a quorum of 3 than
    -- under a quorum of 5, and the config can change between an incident and the day it is read.
    quorum_count      SMALLINT NOT NULL,

    -- Which destinations failed, so a vendor-specific pattern stays visible in the record itself.
    -- Names only -- never a URL, because a probe target can carry a credential in its path (an ntfy
    -- topic URL IS the credential) and this table is read by humans and dumps.
    failed_names   TEXT        NOT NULL,

    CONSTRAINT network_reachability_counts_sane
        CHECK (probed_count > 0 AND unreachable_count >= 0 AND unreachable_count <= probed_count),
    -- unreachable_count >= quorum_count is what makes "only quorum-met passes are stored" a
    -- database rule rather than a convention the writer is trusted to keep.
    CONSTRAINT network_reachability_quorum_sane
        CHECK (quorum_count >= 1 AND quorum_count <= probed_count
               AND unreachable_count >= quorum_count)
);

COMMENT ON TABLE network_reachability_observations IS
    'NEW-13. One row per PROBE PASS in which a multi-destination quorum said the host itself lost '
    'outbound network. Not one row per incident: incidents are derived at read time by grouping '
    'consecutive rows, so no failed write or restart can make a stored row false. Durable because a '
    'process-lifetime counter is reset by the very event it measures. Record-only: this never '
    'alerts, because the paging channels are among the things that go down.';

COMMENT ON COLUMN network_reachability_observations.unreachable_count IS
    'How many of probed_count destinations failed together. The QUORUM is the diagnosis: one '
    'failing destination is that vendor being down; most or all failing inside one window is the '
    'host''s own outbound network. Misreading that distinction is why the 2026-08-19 and 2026-08-20 '
    'incidents were first filed as Kite outages.';

CREATE INDEX network_reachability_observed_at_idx
    ON network_reachability_observations (observed_at DESC);
