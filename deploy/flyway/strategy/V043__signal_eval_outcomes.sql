-- Durable rollup of the SignalEngine ENTRY-EVALUATION outcome counters (chip: engine liveness
-- must be answerable RETROACTIVELY).
--
-- WHY. `ay_signal_eval_outcome_total` (SignalEngine:445) is an in-memory Micrometer counter, so it
-- resets to zero on every restart and only ever answers "what has happened since this JVM booted".
-- "Was the engine alive on 2026-07-16?" is therefore UNANSWERABLE. That gap has now produced two
-- false starvation diagnoses:
--   * 2026-07-17 — an 84-minute silence that was simply a SuperTrend-DOWN leg (SignalEngine:220-227).
--   * 2026-07-20 — a dead engine was inferred from an EMPTY strategy.signal_rejections table, which
--     triggered an unnecessary restart of a LIVE trading service. The engine had been healthy the
--     whole time (399 evaluations, 0 failures) — and the restart wiped the only evidence of that.
-- The counters are the correct liveness signal; they simply did not survive a restart. This table
-- makes them survive.
--
-- WHY NOT A ROW PER EVALUATION. A row per no-entry per strategy per 3m bar was DELIBERATELY rejected
-- when the counters were introduced: ~63 published+enabled scalpers share one 3m series, so that is
-- ~63 writes every bar all session on the sole live eval thread. Measured shape: ~7,875 evaluations/
-- day and a signal_rejections row is 2,624 bytes. This rollup instead snapshots the counters on a 3m
-- cadence -- 7 narrow rows per bucket, ~980 rows/day (140 session-window buckets x 7 outcomes) at
-- tens of bytes each. Roughly 8x fewer rows and ~100x fewer bytes, and ZERO added work on the eval
-- thread (a scheduled reader of counters that are already in memory -- never an inline write).
--
-- SHAPE: DELTA, not cumulative. eval_count is the number of evaluations that landed in the window
-- ENDING at this snapshot, i.e. (counter now - counter at the previous SUCCESSFUL snapshot). The
-- delta baseline is an in-memory field of SignalEvalOutcomeRollupJob, so it shares its JVM lifetime
-- with the counter itself: both reset together on a restart, and the first post-boot snapshot
-- therefore reports "everything since boot". Consequences, all of them deliberate:
--   * A counter reset can NEVER read as negative activity -- a negative delta is impossible by
--     construction, not by clamping.
--   * A restart is NOT a gap: SUM(eval_count) over a day stays correct across any number of
--     restarts, so the day query needs no boot-awareness and no boot marker column.
--   * A MISSED snapshot (a wedged scheduler pool, a failed write) is self-healing: the baseline is
--     advanced only after a durable write, so the next successful snapshot's delta covers both
--     windows. Nothing is lost; only the 3m resolution of that stretch degrades.
--   * The ONLY lossy case is a hard stop between the last snapshot and process exit -- at most one
--     bucket (<= 3 min) of evaluations. Bounded and documented, not silent.
-- Cumulative-per-boot was the alternative; it would have needed a boot_id column AND a boot-aware
-- query (max-per-boot, then sum across boots) to answer the same question. Deltas answer it with a
-- plain SUM.
--
-- ROWS ARE THE LIVENESS EVIDENCE. Every outcome is written every bucket, INCLUDING zeros. That is
-- what separates the three states the counters used to conflate:
--   * rows present, some eval_count > 0  -> process up AND the eval loop is evaluating.
--   * rows present, all eval_count = 0   -> process up, scheduler ticking, but the eval loop
--                                           produced nothing (no bars arriving, or a dead engine).
--   * rows ABSENT                        -> the process was down, or the default @Scheduled pool
--                                           was wedged for that stretch.
--
-- CANONICAL LIVENESS QUERY -- "was the engine evaluating on date X, and what was the outcome mix",
-- one SELECT. NOTE the +05:30 BOUNDS (never ::date -- in-container now()/::date is UTC and is
-- off-by-one across IST midnight) and the 'Asia/Kolkata' RENDER (AT TIME ZONE '+05:30' INVERTS
-- under the POSIX sign convention and would print 14:20 IST as 03:20):
--
--   SELECT outcome,
--          SUM(eval_count)                              AS evaluations,
--          COUNT(*)                                     AS buckets_recorded,
--          COUNT(*) FILTER (WHERE eval_count > 0)       AS buckets_active,
--          MIN(bucket_time) AT TIME ZONE 'Asia/Kolkata' AS first_bucket_ist,
--          MAX(bucket_time) AT TIME ZONE 'Asia/Kolkata' AS last_bucket_ist
--     FROM strategy.signal_eval_outcomes
--    WHERE bucket_time >= timestamptz '2026-07-20T00:00:00+05:30'
--      AND bucket_time <  timestamptz '2026-07-21T00:00:00+05:30'
--    GROUP BY outcome
--    ORDER BY evaluations DESC;
--
-- Reading it: buckets_recorded > 0 proves the PROCESS was up; SUM(evaluations) > 0 proves the EVAL
-- LOOP was running; the per-outcome split is the mix (e.g. an all-`composite-below-threshold` day is
-- a healthy engine on a directionless leg -- exactly the 2026-07-17 SuperTrend-DOWN shape -- NOT a
-- starved one). `confluence-blocked` is the only outcome that also writes a signal_rejections row,
-- which is why an empty rejections table has never meant a dead engine.
--
-- OBSERVABILITY ONLY. Nothing here is consulted by any trading decision. The rollup runs on the
-- default @Scheduled pool off the eval thread, reads counters and writes rows; it can never alter
-- what is traded. The golden replay boots no scheduler, so no rows are written on backtest ->
-- parity-safe by construction. Plain OLTP table (not a hypertable) -- the volume is trivial.
--
-- RETENTION: 180 days via SignalEvalOutcomeRollupJob's daily 02:30-IST prune, matching the sibling
-- risk_suppressions convention exactly (artha.signals.eval-outcome-retention-days /
-- ARTHA_SIGNALS_EVAL_OUTCOME_RETENTION_DAYS; 0 or negative DISABLES the prune rather than wiping
-- the table). ~980 rows/day * 180d ~= 176k rows -- a few MB.

CREATE TABLE signal_eval_outcomes (
  bucket_time  TIMESTAMPTZ NOT NULL, -- snapshot instant floored to a 3m boundary (IST offset).
                                     -- IST is +05:30 = 19800s = 110 * 180, so 3m boundaries coincide
                                     -- in UTC and IST and 09:15 IST is always a boundary.
  outcome      TEXT NOT NULL,        -- SignalEngine.Outcome tag: fired / confluence-blocked /
                                     -- confluence-gate-absent / discipline-paused /
                                     -- composite-below-threshold / chart-gate-failed /
                                     -- unscoreable-indicators-warming
  eval_count   BIGINT NOT NULL,      -- evaluations with this outcome in the window ENDING at
                                     -- bucket_time. Never negative (see the DELTA note above).
                                     -- Zero is meaningful: it proves the process was alive.
  PRIMARY KEY (bucket_time, outcome)
);

-- The PK's leading bucket_time column already serves the only query pattern (a date-bounded range
-- scan grouped by outcome), so no additional index is created.

-- The ON CONFLICT merge ADDS rather than replaces. Two writers can legitimately land in one bucket
-- during a blue/green restart overlap, where both instances really did evaluate; addition is the
-- delta-correct merge. A single-instance re-fire is otherwise impossible (one scheduler thread).

-- ay_strategy is the READ-ONLY per-schema role and never writes here. Unlike the append-only
-- siblings (V015/V027/V040/V042, which grant SELECT+INSERT), a bare INSERT on this table conflicts
-- on the PK -- writing needs UPDATE too, and only the `artha` owner does it. SELECT only is the
-- honest grant.
GRANT SELECT ON signal_eval_outcomes TO ay_strategy;
