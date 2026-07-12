"""The §12 E6 item-16 autonomy SCHEDULER — advance logic, budgets, cadence, expiry, durability.

Driven through SchedulerService + the in-memory fakes (no Postgres, no backtest svc, no threads).
The scheduler ORCHESTRATES the E1–E4 services (SweepService.submit, EvoRecorderService.record +
select, ProposalService.generate) — the tests wire the REAL recorder/proposals services over the
fakes and a fake SweepService, so the advance path exercises the actual record→select→propose chain.

Covers: SIM_FIRST launch → wait → record → relaunch → budget-exhaust (the full happy path + the
budget stop); the evidence-policy REFUSAL for LIVE_FIRST / SIM_BLOCKED; the per-campaign cadence
gate; durable resume of a failed/orphaned pending sweep; the 409-idempotent record after a crash;
the proposal-expiry sweep; and that PAUSED campaigns / bad searchSpaces never advance. Every clock
is INJECTED (repo.now) — no wall-clock nondeterminism.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import scheduler
from app.errors import ApiError, api_error_handler
from app.evolution import EvoRecorderService, RetroScoreService
from app.proposals import ProposalService
from app.scheduler import SchedulerService
from tests.fakes import (
    FakeBacktest,
    FakeEvoRepo,
    FakeJobs,
    FakeNtfy,
    FakeTrials,
    _strip_seq,
)

# Three OOS folds over three regimes (mirrors the recorder suite) so gates can be assessed + scored.
_FOLDS = [
    {"fold": 0, "regimeOos": {"UP_QUIET": {"sharpe": "1.0"}},
     "oosMetrics": {"totalReturn": "5.0", "sortino": "1.2", "maxDrawdown": "10.0",
                    "maxDrawdownDurationBars": 3, "expectancy": "100.0", "tradeCount": 40}},
    {"fold": 1, "regimeOos": {"DOWN_QUIET": {"sharpe": "0.6"}},
     "oosMetrics": {"totalReturn": "7.0", "sortino": "1.4", "maxDrawdown": "12.0",
                    "maxDrawdownDurationBars": 4, "expectancy": "120.0", "tradeCount": 45}},
    {"fold": 2, "regimeOos": {"UP_TURBULENT": {"sharpe": "0.5"}},
     "oosMetrics": {"totalReturn": "3.0", "sortino": "0.9", "maxDrawdown": "8.0",
                    "maxDrawdownDurationBars": 2, "expectancy": "90.0", "tradeCount": 30}},
]
_RESULTS = {"metrics": {"totalReturn": "12.0", "maxDrawdown": "12.0", "tradeCount": 115},
            "dataHash": "hash-1", "engineSha": "sha-1", "caveats": []}

_SEARCH_SPACE = {"from": "2024-01-01", "to": "2024-06-30", "parameters": []}


class SchedulerFakeRepo(FakeEvoRepo):
    """FakeEvoRepo + the V018 scheduler columns (isolated in a per-campaign side-dict, exactly as
    the real EvoRepo isolates them behind dedicated methods). ``now`` is the injected clock shared
    with the SchedulerService (cadence) and the expiry sweep — advancing it moves both together.
    """

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.sched: dict[str, dict[str, Any]] = {}
        self.now: datetime = datetime(2026, 7, 12, tzinfo=UTC)

    def _sched(self, campaign_id: str) -> dict[str, Any]:
        return self.sched.setdefault(
            campaign_id,
            {"schedulerState": None, "pendingSweepJobId": None, "lastScheduledAt": None},
        )

    def _target(self, campaign: dict[str, Any] | None) -> dict[str, Any] | None:
        if campaign is None:
            return None
        return {**campaign, **self._sched(campaign["id"])}

    def list_active_scheduler_targets(self) -> list[dict[str, Any]]:
        return [self._target(c) for c in self.campaigns if c["status"] == "ACTIVE"]

    def get_scheduler_row(self, campaign_id: str) -> dict[str, Any] | None:
        return self._target(self.get_campaign(campaign_id))

    def claim_pending_sweep(
        self, campaign_id: str, sweep_job_id: str, scheduled_at: Any
    ) -> dict[str, Any] | None:
        anchor = scheduled_at.isoformat() if isinstance(scheduled_at, datetime) else scheduled_at
        self._sched(campaign_id).update(
            schedulerState="EVALUATING", pendingSweepJobId=sweep_job_id, lastScheduledAt=anchor
        )
        return self._target(self.get_campaign(campaign_id))

    def resolve_pending_sweep(self, campaign_id: str, next_state: str) -> dict[str, Any] | None:
        self._sched(campaign_id).update(schedulerState=next_state, pendingSweepJobId=None)
        return self._target(self.get_campaign(campaign_id))

    def set_scheduler_state(self, campaign_id: str, state: str) -> dict[str, Any] | None:
        self._sched(campaign_id)["schedulerState"] = state
        return self._target(self.get_campaign(campaign_id))

    def expire_pending_proposals(self) -> list[dict[str, Any]]:
        expired: list[dict[str, Any]] = []
        for row in self.proposals:
            if row["status"] != "PENDING" or not row.get("expiresAt"):
                continue
            if datetime.fromisoformat(row["expiresAt"]) < self.now:
                row.update(status="EXPIRED", actor="evo:expiry", decidedAt=self.now.isoformat())
                expired.append(_strip_seq(row))
        return expired


class FakeSweeps:
    """A stand-in SweepService: ``submit`` inserts a (queued) OPTIMIZATION job and returns its id;
    ``job_status`` reads it back. The test drives completion explicitly (``_complete``) so the
    launch→wait→record progression is asserted step by step."""

    def __init__(self, jobs: FakeJobs, trials: FakeTrials) -> None:
        self.jobs = jobs
        self.trials = trials
        self.submitted: list[tuple[str, dict[str, Any]]] = []

    def submit(self, request: dict[str, Any]) -> str:
        echo = {
            "parameters": request.get("parameters") or [],
            "objective": request.get("objective"),
        }
        sweep_id = self.jobs.insert_sweep(None, echo)
        self.submitted.append((sweep_id, request))
        return sweep_id

    def job_status(self, job_id: str) -> dict[str, Any]:
        row = self.jobs.get(job_id)
        if row is None:
            raise ApiError(404, "NOT_FOUND_JOB", f"no such job: {job_id}")
        completed = self.trials.list_for_sweep(job_id, "COMPLETE", 1000, 0)
        return {"jobId": job_id, "status": row["status"], "progress": row["progress"],
                "trialsCompleted": len(completed)}


def _build(
    *, budget: dict[str, Any] | None = None, policy: str = "SIM_FIRST",
    search_space: dict[str, Any] | None = _SEARCH_SPACE,
) -> tuple[SchedulerService, SchedulerFakeRepo, FakeJobs, FakeTrials, FakeSweeps, str]:
    repo = SchedulerFakeRepo()
    jobs = FakeJobs()
    trials = FakeTrials(jobs)
    backtest = FakeBacktest(folds=_FOLDS, results=_RESULTS)
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    recorder = EvoRecorderService(repo_factory=lambda: repo, scorer=scorer)
    proposals = ProposalService(repo_factory=lambda: repo, ntfy=FakeNtfy())
    sweeps = FakeSweeps(jobs, trials)
    service = SchedulerService(
        repo_factory=lambda: repo, sweeps=sweeps, recorder=recorder, proposals=proposals,
        clock=lambda: repo.now, top_k=15,
    )
    campaign = repo.create_campaign(
        "11111111-1111-1111-1111-111111111111", "manas-arora", policy,
        None, search_space, budget or {"maxGenerations": 8},
    )
    return service, repo, jobs, trials, sweeps, campaign["id"]


def _complete(jobs: FakeJobs, trials: FakeTrials, sweep_id: str, n: int = 2) -> None:
    """Seed a launched sweep with ``n`` COMPLETE trials + mark it completed (the recorder's bar)."""
    for i in range(n):
        row_id = trials.insert(sweep_id, i, {"period": 10 + i})
        trials.complete(row_id, {"oos_fold_mean": 1.0}, f"run-{sweep_id}-{i}")
    jobs.set_status(sweep_id, "completed", 100)


def _only(result: scheduler.SchedulerTickResult) -> scheduler.CampaignAdvance:
    assert len(result.campaigns) == 1, result.campaigns
    return result.campaigns[0]


# --- launch → wait → record (the happy path, step by step) --------------------------------------

def test_tick_launches_first_generation_for_sim_first():
    service, repo, jobs, _trials, sweeps, campaign_id = _build()
    advance = _only(service.tick())
    assert advance.action == "LAUNCHED"
    assert advance.sweepJobId is not None
    assert len(sweeps.submitted) == 1
    # the sweep request was formed from the campaign's searchSpace + strategyId
    _sweep_id, request = sweeps.submitted[0]
    assert request["from"] == "2024-01-01"
    assert request["strategyId"] == "11111111-1111-1111-1111-111111111111"
    # durable state: EVALUATING with the pending sweep pinned
    row = repo.get_scheduler_row(campaign_id)
    assert row["schedulerState"] == "EVALUATING"
    assert row["pendingSweepJobId"] == advance.sweepJobId


def test_tick_waits_while_sweep_in_flight():
    service, _repo, _jobs, _trials, sweeps, _campaign_id = _build()
    service.tick()  # LAUNCHED (sweep stays queued)
    advance = _only(service.tick())
    assert advance.action == "WAITING_SWEEP"
    assert len(sweeps.submitted) == 1  # no second sweep launched while one is in flight


def test_tick_records_generation_when_sweep_completes():
    service, repo, jobs, trials, sweeps, campaign_id = _build()
    launch = _only(service.tick())
    _complete(jobs, trials, launch.sweepJobId, n=2)

    advance = _only(service.tick())
    assert advance.action == "RECORDED"
    assert advance.generationN == 1
    # the generation + its scored candidates landed via the REAL recorder
    gens = repo.list_generations(campaign_id)
    assert len(gens) == 1 and gens[0]["n"] == 1
    cands = repo.list_candidates_for_campaign(campaign_id)
    assert len(cands) == 2
    assert all(c["scorecard"]["robustScore"] is not None for c in cands)
    # back to IDLE, pending cleared — ready for the next generation
    row = repo.get_scheduler_row(campaign_id)
    assert row["schedulerState"] == "IDLE"
    assert row["pendingSweepJobId"] is None


def test_full_loop_advances_until_budget_exhausted():
    # maxGenerations=2 → the scheduler runs exactly two generations, then stops at EXHAUSTED and
    # launches no more (the budget stop). Every launch is owner-gate-free RESEARCH; nothing arms.
    service, repo, jobs, trials, sweeps, campaign_id = _build(budget={"maxGenerations": 2})

    for _ in range(2):
        launch = _only(service.tick())          # IDLE → LAUNCHED
        assert launch.action == "LAUNCHED"
        _complete(jobs, trials, launch.sweepJobId, n=2)
        record = _only(service.tick())          # EVALUATING(completed) → RECORDED
        assert record.action == "RECORDED"

    assert len(repo.list_generations(campaign_id)) == 2
    assert repo.get_scheduler_row(campaign_id)["schedulerState"] == "EXHAUSTED"

    # further ticks are inert — no third sweep, stays EXHAUSTED
    exhausted = _only(service.tick())
    assert exhausted.action == "BUDGET_EXHAUSTED"
    assert len(sweeps.submitted) == 2

    # nothing self-published: every proposal is owner-PENDING or an auto-RETIRE acknowledge (§8.2) —
    # never an autonomously-APPROVED PUBLISH_PAPER/PROMOTE.
    approved_kinds = {p["kind"] for p in repo.proposals if p["status"] == "APPROVED"}
    assert approved_kinds <= {"RETIRE"}


def test_idle_campaign_at_budget_marks_exhausted_without_launching():
    # A campaign that already has maxGenerations generations but sits IDLE (e.g. armed after a
    # manual backfill) is marked EXHAUSTED on the next tick — never launches an over-budget one.
    service, repo, jobs, trials, sweeps, campaign_id = _build(budget={"maxGenerations": 1})
    launch = _only(service.tick())
    _complete(jobs, trials, launch.sweepJobId, n=1)
    service.tick()  # records gen 1 → EXHAUSTED already (count 1 >= 1)
    assert repo.get_scheduler_row(campaign_id)["schedulerState"] == "EXHAUSTED"
    assert len(sweeps.submitted) == 1


# --- evidence-policy refusal --------------------------------------------------------------------

def test_live_first_campaign_is_policy_refused():
    service, _repo, _jobs, _trials, sweeps, _campaign_id = _build(policy="LIVE_FIRST")
    advance = _only(service.tick())
    assert advance.action == "POLICY_REFUSED"
    assert "LIVE_FIRST" in advance.reason
    assert sweeps.submitted == []  # the scheduler NEVER launches a sim sweep for a live family


def test_sim_blocked_campaign_is_policy_refused():
    service, _repo, _jobs, _trials, sweeps, _campaign_id = _build(policy="SIM_BLOCKED")
    advance = _only(service.tick())
    assert advance.action == "POLICY_REFUSED"
    assert sweeps.submitted == []


# --- cadence ------------------------------------------------------------------------------------

def test_cadence_defers_relaunch_until_interval_elapses():
    # cadenceSeconds=3600: after recording gen 1, the next generation is DEFERRED until an hour of
    # (injected) time elapses.
    service, repo, jobs, trials, sweeps, _campaign_id = _build(
        budget={"maxGenerations": 5, "cadenceSeconds": 3600}
    )
    launch = _only(service.tick())
    _complete(jobs, trials, launch.sweepJobId, n=1)
    service.tick()  # RECORDED → IDLE (lastScheduledAt anchored at repo.now)

    within = _only(service.tick())  # still inside the cadence window
    assert within.action == "WAITING_CADENCE"
    assert len(sweeps.submitted) == 1

    repo.now = repo.now + timedelta(hours=2)  # advance past the interval
    after = _only(service.tick())
    assert after.action == "LAUNCHED"
    assert len(sweeps.submitted) == 2


# --- durability: failed / orphaned / missing pending sweep --------------------------------------

def test_failed_pending_sweep_resets_to_idle_for_relaunch():
    # A restart marks an in-flight sweep failed (fail_orphaned_sweeps). The next tick resets the
    # campaign to IDLE (durable resume) rather than recording a partial cohort.
    service, repo, jobs, _trials, sweeps, campaign_id = _build()
    launch = _only(service.tick())
    jobs.set_status(launch.sweepJobId, "failed")  # simulate the boot orphan-fail

    advance = _only(service.tick())
    assert advance.action == "SWEEP_FAILED"
    row = repo.get_scheduler_row(campaign_id)
    assert row["schedulerState"] == "IDLE"
    assert row["pendingSweepJobId"] is None
    # and the next tick relaunches a fresh generation
    relaunch = _only(service.tick())
    assert relaunch.action == "LAUNCHED"
    assert len(sweeps.submitted) == 2


def test_missing_pending_sweep_resets_to_idle():
    # The pending sweep's job row is gone (404) — treated like a failure, reset to IDLE, no crash.
    service, repo, jobs, _trials, _sweeps, campaign_id = _build()
    launch = _only(service.tick())
    del jobs.rows[launch.sweepJobId]
    advance = _only(service.tick())
    assert advance.action == "SWEEP_FAILED"
    assert "missing" in advance.reason
    assert repo.get_scheduler_row(campaign_id)["schedulerState"] == "IDLE"


def test_record_is_idempotent_after_crash_between_record_and_clear():
    # Simulate a crash AFTER record_generation committed but BEFORE the pending marker cleared: the
    # campaign is left EVALUATING pointing at the (now completed + already-recorded) sweep. The next
    # tick re-records → 409 absorbed → still exactly one generation, and the tick completes cleanly.
    service, repo, jobs, trials, _sweeps, campaign_id = _build()
    launch = _only(service.tick())
    _complete(jobs, trials, launch.sweepJobId, n=2)
    service.tick()  # RECORDED (gen 1); pending cleared → IDLE

    # re-pin the SAME completed sweep as pending (the crash-state)
    repo.claim_pending_sweep(campaign_id, launch.sweepJobId, repo.now)
    advance = _only(service.tick())
    assert advance.action == "RECORDED"
    assert advance.generationN == 1                     # re-derived from the existing generation
    assert len(repo.list_generations(campaign_id)) == 1  # NOT duplicated
    assert repo.get_scheduler_row(campaign_id)["pendingSweepJobId"] is None


# --- non-advancing campaigns --------------------------------------------------------------------

def test_paused_campaign_is_not_advanced():
    service, repo, _jobs, _trials, sweeps, campaign_id = _build()
    repo.get_campaign(campaign_id)["status"] = "PAUSED"
    result = service.tick()
    assert result.campaigns == []          # PAUSED campaigns are not scheduler targets
    assert sweeps.submitted == []


def test_incomplete_search_space_is_skipped():
    service, _repo, _jobs, _trials, sweeps, _campaign_id = _build(search_space={"parameters": []})
    advance = _only(service.tick())
    assert advance.action == "NO_SEARCH_SPACE"
    assert sweeps.submitted == []


# --- proposal expiry sweep (§8.2 7-day) ---------------------------------------------------------

def _seed_proposal(repo: SchedulerFakeRepo, campaign_id: str, expires_at: str) -> str:
    row = repo.insert_proposal(campaign_id, "cand-x", "PUBLISH_PAPER", {"note": "x"})
    # override the fixed expiry the fake stamps at insert
    for stored in repo.proposals:
        if stored["id"] == row["id"]:
            stored["expiresAt"] = expires_at
    return row["id"]


def test_expire_proposals_flips_pending_past_expiry():
    service, repo, _jobs, _trials, _sweeps, campaign_id = _build()
    stale = _seed_proposal(repo, campaign_id, "2026-07-01T00:00:00+00:00")  # before repo.now
    fresh = _seed_proposal(repo, campaign_id, "2026-07-20T00:00:00+00:00")  # after repo.now

    expired = service.expire_proposals()
    assert expired == [stale]
    by_id = {p["id"]: p for p in repo.proposals}
    assert by_id[stale]["status"] == "EXPIRED"
    assert by_id[stale]["actor"] == "evo:expiry"
    assert by_id[fresh]["status"] == "PENDING"  # not yet due


def test_tick_expires_stale_proposals():
    service, repo, _jobs, _trials, _sweeps, campaign_id = _build(policy="LIVE_FIRST")
    _seed_proposal(repo, campaign_id, "2026-07-01T00:00:00+00:00")
    result = service.tick()
    assert result.expiredProposals == 1


# --- the wired endpoint -------------------------------------------------------------------------

def test_scheduler_tick_endpoint_returns_advances():
    service, _repo, jobs, trials, _sweeps, _campaign_id = _build()
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.scheduler = service
    app.include_router(scheduler.router)
    client = TestClient(app)

    body = client.post("/api/v1/evolution/scheduler/tick").json()
    assert body["campaigns"][0]["action"] == "LAUNCHED"
    assert "ranAt" in body and body["expiredProposals"] == 0


def test_expire_endpoint_reports_expired_ids():
    service, repo, _jobs, _trials, _sweeps, campaign_id = _build()
    stale = _seed_proposal(repo, campaign_id, "2026-07-01T00:00:00+00:00")
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.scheduler = service
    app.include_router(scheduler.router)
    client = TestClient(app)

    body = client.post("/api/v1/evolution/proposals/expire").json()
    assert body["expired"] == 1
    assert body["proposalIds"] == [stale]
