-- Audit P1-10 (finding duplicate-runs-no-unique-jobid): a crash between the run INSERT and
-- markCompleted re-queued + re-executed the job on boot, persisting a SECOND backtest_runs row
-- (+ full duplicate trade set) for the same job. resultRef masked it (newest completed_at wins)
-- but run counts, leaderboards and the jobs-list MAX join silently double-counted.
--
-- Dedupe first (keep the newest run per job; backtest_trades cascades via ON DELETE), then pin
-- the invariant. The runner now DELETEs the job's prior run before inserting (replace-on-rerun),
-- so the unique index is the backstop, not the primary mechanism.
DELETE FROM backtest_runs r
USING backtest_runs newer
WHERE newer.job_id = r.job_id
  AND (newer.completed_at > r.completed_at
       OR (newer.completed_at = r.completed_at AND newer.id > r.id));

CREATE UNIQUE INDEX uq_runs_job ON backtest_runs (job_id);
