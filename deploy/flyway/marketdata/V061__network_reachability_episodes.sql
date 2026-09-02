-- NEW-13 — a durable record of outbound-network reachability, so "was it us or the vendor?" has an
-- answer AFTER the fact rather than being re-derived by hand from container logs each time.
--
-- ⚠️ WHY A TABLE AND NOT A COUNTER, because a metric is the obvious choice and it is the wrong one.
-- Micrometer counters are PROCESS-LIFETIME. The event this records is a host network death, and on
-- all three observed occasions it was followed by the box going down — so a counter is reset by the
-- very incident it exists to measure. The platform has already paid for this lesson twice:
-- `ay_paper_fill_no_tick_total` made a weekly arming report structurally unable to do its job, and
-- the H26 session rate peaks died with their process and were unrecoverable. Durable or useless.
--
-- ⚠️ WHY EPISODES AND NOT ONE ROW PER PROBE. A pass every few minutes would write hundreds of rows
-- a day to say "fine", burying the handful that matter. A row is opened when the quorum first says
-- UNREACHABLE and closed when reachability returns, so the table holds one row per INCIDENT with its
-- real duration. That shape is copied from `strategy.blind_windows`, which already solved the same
-- problem (open/close, crash-safe, idempotent by key) and is proven in production; it lives in
-- another service, so the shape is reused rather than the table.
--
-- Record-only by design (owner, 2026-09-02). It never pages: when this fires, the paging channels
-- are among the things that are down -- measured 2026-09-01, telegram AND ntfy both dead while the
-- stack looked healthy -- so an alert is precisely the mechanism that cannot be relied upon. The
-- value is a trustworthy after-the-fact record, which is what all three incidents actually needed.

CREATE TABLE network_reachability_episodes (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Idempotency for a RETRY of the same open: a re-issued insert carrying the same key is a
    -- no-op rather than a duplicate.
    -- ⚠️ This UNIQUE does NOT enforce one-open-at-a-time, and an earlier revision of this file
    -- claimed that it did. The key embeds the episode's start millisecond, so a second pass
    -- generates a DIFFERENT key and inserts cleanly past it. The rule is enforced by
    -- network_reachability_one_open_idx below; this constraint only makes a retry safe.
    episode_key    TEXT        NOT NULL UNIQUE,

    started_at     TIMESTAMPTZ NOT NULL,
    ended_at       TIMESTAMPTZ,

    -- The quorum at the moment the episode opened. Both numbers are kept, never just the ratio:
    -- "3 of 5 failed" and "3 of 3 failed" are different findings, and a stored ratio cannot be
    -- re-disaggregated later.
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

    detail         TEXT        NOT NULL,

    CONSTRAINT network_reachability_counts_sane
        CHECK (probed_count > 0 AND unreachable_count >= 0 AND unreachable_count <= probed_count),
    CONSTRAINT network_reachability_quorum_sane
        CHECK (quorum_count >= 1 AND quorum_count <= probed_count
               AND unreachable_count >= quorum_count),
    CONSTRAINT network_reachability_window_sane
        CHECK (ended_at IS NULL OR ended_at >= started_at)
);

COMMENT ON TABLE network_reachability_episodes IS
    'NEW-13. One row per outbound-reachability INCIDENT, not per probe. Opened when a multi-'
    'destination quorum says the host itself lost outbound network; closed when reachability '
    'returns. Durable because a process-lifetime counter is reset by the very event it measures. '
    'Record-only: this never alerts, because the paging channels are among the things that go down.';

COMMENT ON COLUMN network_reachability_episodes.unreachable_count IS
    'How many of probed_count destinations failed together. The QUORUM is the diagnosis: one '
    'failing destination is that vendor being down; most or all failing inside one window is the '
    'host''s own outbound network. Misreading that distinction is why the 2026-08-19 and 2026-08-20 '
    'incidents were first filed as Kite outages.';

-- ⚠️ AT MOST ONE OPEN EPISODE, ENFORCED BY THE DATABASE. Indexing the constant expression
-- (ended_at IS NULL) over only the open rows makes every open row collide on the same index key, so
-- a second one cannot be inserted at all.
--
-- This is not belt-and-braces. The writer reads "is an episode already open?" and that read FAILS
-- SOFT to "no" on a transient DB error -- so the very failure mode this table exists to record is
-- the one that makes the process try to open a second episode. The application-side guard cannot
-- close that race by itself, and the UNIQUE on episode_key cannot either, because each attempt
-- mints a new timestamp key. Only this index can.
CREATE UNIQUE INDEX network_reachability_one_open_idx
    ON network_reachability_episodes ((ended_at IS NULL))
    WHERE ended_at IS NULL;
