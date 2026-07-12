-- Strategy-evolution engine — E6 item 16 (design §11 / §12 E6 / §1.3 control loop / §1.4 safety):
-- the AUTONOMY scheduler's durable per-campaign state. The scheduler advances ENROLLED + ACTIVE
-- campaigns one step per tick — launch the next generation's sweep when idle + within budget + past
-- cadence, then probe / record / cost-stress / select / propose as it completes — and MUST resume
-- across a restart (design §11 "durable orchestration": the in-memory sweep-thread model is
-- insufficient for week-long campaigns; state is persisted per step, mirroring the P2-1 pattern and
-- the E2 STRESSING boot-reaper). All autonomy is DEFAULT-OFF at the app layer
-- (ARTHA_EVO_SCHEDULER_ENABLED=false) and NOTHING here self-arms or self-publishes — the scheduler
-- only advances RESEARCH (sweeps/scoring/proposals); publish/promote stay owner-clicked (§1.4.1).
--
-- Three additive nullable columns on evo_campaigns (D17 additive-first). No change to the
-- owner-facing `status` enum (ACTIVE/PAUSED/CLOSED stays the OWNER lifecycle) — the autonomy
-- sub-state is a SEPARATE `scheduler_state` axis so the two never conflate, and the checksum-locked
-- status CHECK (V011:36) stays untouched.
--
--   scheduler_state       — the autonomy sub-state. NULL = NOT ENROLLED (the default for every
--                           existing and new campaign): arming the global flag advances NOTHING
--                           until the owner enrolls a campaign explicitly (POST
--                           /campaigns/{id}/scheduler/enroll → IDLE; /withdraw → NULL). Then:
--                           IDLE (ready to launch), EVALUATING (generation sweep in flight),
--                           PROBING (neighbor probes draining, §3.2.3), STRESSING (cost-stress
--                           round in flight post-record, §3.2.5), EXHAUSTED (budget spent; awaiting
--                           owner — re-enroll after a budget bump restarts it).
--   pending_sweep_job_id  — the sweep the scheduler LAUNCHED but has not yet finished processing
--                           (a generation stores its sweepJobId only after recording). UUID soft
--                           link into jobs, no FK (cross-table lifecycle), mirroring
--                           evo_candidates.sweep_job_id (V011:68).
--   last_scheduled_at     — the cadence anchor: when the scheduler last launched a generation's
--                           sweep. The per-campaign cadence gate (budget.cadenceSeconds — swing
--                           weekly, §1.3 step 7) refuses a relaunch until now - last_scheduled_at
--                           clears the interval.
--
-- evo_campaigns is one row per base strategy (a handful of rows) — no index is warranted on these
-- columns. Existing table GRANTs (V011:104) cover the new columns — no new grant.

ALTER TABLE evo_campaigns
  ADD COLUMN scheduler_state       TEXT,
  ADD COLUMN pending_sweep_job_id  UUID,
  ADD COLUMN last_scheduled_at     TIMESTAMPTZ;

ALTER TABLE evo_campaigns
  ADD CONSTRAINT ck_evo_campaigns_scheduler_state
    CHECK (scheduler_state IS NULL
           OR scheduler_state IN ('IDLE', 'EVALUATING', 'PROBING', 'STRESSING', 'EXHAUSTED'));

-- Duplicate-proposal DB backstop (adversarial review 2026-07-12, finding 1): the proposal
-- generators' find-then-insert dedup ("one OPEN proposal per (candidate, kind)") had no unique
-- index behind it — a race (armed driver + manual tick, or any future second writer) could mint
-- duplicate PENDING rows + double-ntfy, permanently. Partial-unique over PENDING rows only:
-- terminal (APPROVED/REJECTED/EXPIRED) rows never block a fresh proposal, and campaign-level rows
-- (candidate_id NULL, e.g. REVIEW_GATE) are exempt (NULLs compare distinct in a unique index).
CREATE UNIQUE INDEX uq_evo_proposals_pending_candidate_kind
  ON evo_proposals (candidate_id, kind) WHERE status = 'PENDING';
