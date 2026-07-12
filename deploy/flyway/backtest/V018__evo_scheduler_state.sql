-- Strategy-evolution engine — E6 item 16 (design §11 / §12 E6 / §1.3 control loop / §1.4 safety):
-- the AUTONOMY scheduler's durable per-campaign state. The scheduler advances RUNNING (status
-- ACTIVE) campaigns one step per tick — launch the next generation's sweep when idle + within
-- budget + past cadence, then record/score/select/propose when that sweep completes — and MUST
-- resume across a restart (design §11 "durable orchestration": the in-memory sweep-thread model is
-- insufficient for week-long campaigns; state is persisted per step, mirroring the P2-1 pattern and
-- the E2 STRESSING boot-reaper). All autonomy is DEFAULT-OFF at the app layer
-- (ARTHA_EVO_SCHEDULER_ENABLED=false) and NOTHING here self-arms or self-publishes — the scheduler
-- only advances RESEARCH (sweeps/scoring/proposals); publish/promote stay owner-clicked (§1.4.1).
--
-- Three additive nullable columns on evo_campaigns (D17 additive-first; the E1 writer churns no
-- existing column). No change to the owner-facing `status` enum (ACTIVE/PAUSED/CLOSED stays the
-- OWNER lifecycle) — the autonomy sub-state is a SEPARATE `scheduler_state` axis so the two never
-- conflate: an ACTIVE campaign whose budget is spent sits at scheduler_state='EXHAUSTED' (the
-- terminal/awaiting state — the scheduler advances it no further; the owner reviews proposals, then
-- extends the budget or CLOSEs it). This keeps the checksum-locked status CHECK (V011:36) untouched.
--
--   scheduler_state       — the autonomy sub-state: NULL/IDLE (no in-flight sweep, ready to launch),
--                           EVALUATING (a sweep is in-flight — pending_sweep_job_id set),
--                           EXHAUSTED (budget spent; awaiting owner). CHECK-guarded (a NEW column in
--                           a NEW migration, so the constraint is free to add).
--   pending_sweep_job_id  — the sweep the scheduler LAUNCHED but has not yet recorded as a
--                           generation (the durable link a launched-but-unrecorded sweep otherwise
--                           has NOWHERE — a generation stores its sweepJobId only AFTER completion).
--                           UUID soft link into jobs, no FK (cross-table lifecycle), mirroring
--                           evo_candidates.sweep_job_id (V011:68).
--   last_scheduled_at     — the cadence anchor: when the scheduler last launched a generation's
--                           sweep. The per-campaign cadence gate (budget.cadenceSeconds — swing
--                           weekly, §1.3 step 7) refuses a relaunch until now - last_scheduled_at
--                           clears the interval.
--
-- evo_campaigns is one row per base strategy (a handful of rows) — no index is warranted on these
-- columns (the scheduler's ACTIVE-campaign scan is a trivial seq scan). Existing table GRANTs
-- (V011:104, SELECT/INSERT/UPDATE/DELETE to ay_backtest) cover the new columns — no new grant.

ALTER TABLE evo_campaigns
  ADD COLUMN scheduler_state       TEXT,
  ADD COLUMN pending_sweep_job_id  UUID,
  ADD COLUMN last_scheduled_at     TIMESTAMPTZ;

ALTER TABLE evo_campaigns
  ADD CONSTRAINT ck_evo_campaigns_scheduler_state
    CHECK (scheduler_state IS NULL OR scheduler_state IN ('IDLE', 'EVALUATING', 'EXHAUSTED'));
