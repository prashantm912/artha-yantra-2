"""The §12 E6 item-16 autonomy SCHEDULER — enrollment, advance logic, probes/stress sequencing,
budgets, cadence, slot cap, claim race, expiry, durability, and the no-self-publish invariant.

Driven through SchedulerService + the in-memory fakes (no Postgres, no backtest svc, no threads).
The scheduler ORCHESTRATES the E1–E4 services — the tests wire the REAL recorder/proposals services
over the fakes plus fake SweepService/StressService stand-ins, so the advance path exercises the
actual record→select→propose chain end-to-end. Every clock is INJECTED (repo.now) — no wall-clock
nondeterminism.
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
    """FakeEvoRepo + the V017 scheduler columns (a per-campaign side-dict, exactly as the real
    EvoRepo isolates them behind dedicated methods). ``now`` is the injected clock shared with the
    SchedulerService (cadence) and the expiry sweep — advancing it moves both together. The claim
    mirrors the CONDITIONAL UPDATE (None when a sweep is already pending)."""

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
        sched = self._sched(campaign_id)
        if sched["pendingSweepJobId"] is not None:  # the conditional-UPDATE backstop (finding 1)
            return None
        anchor = scheduled_at.isoformat() if isinstance(scheduled_at, datetime) else scheduled_at
        sched.update(
            schedulerState="EVALUATING", pendingSweepJobId=sweep_job_id, lastScheduledAt=anchor
        )
        return self._target(self.get_campaign(campaign_id))

    def resolve_pending_sweep(
        self, campaign_id: str, next_state: str | None
    ) -> dict[str, Any] | None:
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
    """A stand-in SweepService: ``submit`` inserts a (queued) OPTIMIZATION job; ``job_status`` reads
    it back (raising RuntimeError for ids in ``boom_ids`` — the fault-isolation fixture); ``probe``
    records the §3.2.3 call and returns an empty receipt; ``cancel`` records the id."""

    def __init__(self, jobs: FakeJobs, trials: FakeTrials) -> None:
        self.jobs = jobs
        self.trials = trials
        self.submitted: list[tuple[str, dict[str, Any]]] = []
        self.probed: list[tuple[str, int, int]] = []
        self.cancelled: list[str] = []
        self.boom_ids: set[str] = set()

    def submit(self, request: dict[str, Any]) -> str:
        echo = {
            "parameters": request.get("parameters") or [],
            "objective": request.get("objective"),
        }
        sweep_id = self.jobs.insert_sweep(None, echo)
        self.submitted.append((sweep_id, request))
        return sweep_id

    def job_status(self, job_id: str) -> dict[str, Any]:
        if job_id in self.boom_ids:
            raise RuntimeError("job store exploded")
        row = self.jobs.get(job_id)
        if row is None:
            raise ApiError(404, "NOT_FOUND_JOB", f"no such job: {job_id}")
        completed = self.trials.list_for_sweep(job_id, "COMPLETE", 1000, 0)
        return {"jobId": job_id, "status": row["status"], "progress": row["progress"],
                "trialsCompleted": len(completed)}

    def probe(self, sweep_id: str, top_k: int, max_probes: int) -> dict[str, Any]:
        self.probed.append((sweep_id, top_k, max_probes))
        return {"submitted": 0, "skipped": 0, "trials": []}

    def cancel(self, job_id: str) -> None:
        self.cancelled.append(job_id)


class FakeStress:
    """A stand-in StressService. ``sync=True`` (default) completes the round instantly (touches
    bumped, generation status stays DONE — the next tick finalizes); ``sync=False`` leaves the
    durable STRESSING marker for the WAITING_STRESS test to flip."""

    def __init__(self, repo: SchedulerFakeRepo, sync: bool = True) -> None:
        self._repo = repo
        self._sync = sync
        self.calls: list[tuple[str, int, list[float]]] = []

    def stress(self, generation_id: str, top_k: int, multipliers: list[float]) -> dict[str, Any]:
        self.calls.append((generation_id, top_k, multipliers))
        gen = self._repo.get_generation(generation_id)
        if self._sync:
            gen["stressTouches"] = (gen.get("stressTouches") or 0) + 1
            gen["status"] = "DONE"
        else:
            gen["status"] = "STRESSING"
        return {"generationId": generation_id, "dispatched": 1, "skipped": 0,
                "multipliers": multipliers, "candidates": []}


class RecordingProposals(ProposalService):
    """ProposalService + a spy on ``execute`` — the invariant the scheduler must uphold: the
    autonomous loop NEVER invokes the owner-clicked execute path."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.execute_calls: list[str] = []

    def execute(self, proposal_id: str, actor: str | None = None) -> Any:
        self.execute_calls.append(proposal_id)
        raise AssertionError("the scheduler must NEVER call execute (owner-clicked only)")


def _build(
    *, budget: dict[str, Any] | None = None, policy: str = "SIM_FIRST",
    search_space: dict[str, Any] | None = _SEARCH_SPACE, enroll: bool = True,
    max_concurrent: int = 1, stress_sync: bool = True,
) -> dict[str, Any]:
    repo = SchedulerFakeRepo()
    jobs = FakeJobs()
    trials = FakeTrials(jobs)
    backtest = FakeBacktest(folds=_FOLDS, results=_RESULTS)
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    recorder = EvoRecorderService(repo_factory=lambda: repo, scorer=scorer)
    proposals = RecordingProposals(repo_factory=lambda: repo, ntfy=FakeNtfy())
    sweeps = FakeSweeps(jobs, trials)
    stress = FakeStress(repo, sync=stress_sync)
    service = SchedulerService(
        repo_factory=lambda: repo, sweeps=sweeps, recorder=recorder, proposals=proposals,
        trials_factory=lambda: trials, stress=stress, clock=lambda: repo.now,
        top_k=15, max_concurrent_sweeps=max_concurrent,
    )
    campaign = repo.create_campaign(
        "11111111-1111-1111-1111-111111111111", "manas-arora", policy,
        None, search_space, budget or {"maxGenerations": 8},
    )
    if enroll:
        repo.set_scheduler_state(campaign["id"], "IDLE")
    return {"service": service, "repo": repo, "jobs": jobs, "trials": trials,
            "sweeps": sweeps, "stress": stress, "proposals": proposals,
            "campaign_id": campaign["id"]}


def _complete(jobs: FakeJobs, trials: FakeTrials, sweep_id: str, n: int = 2) -> None:
    """Seed a launched sweep with ``n`` COMPLETE trials + mark it completed (the recorder's bar)."""
    start = trials.max_trial_number(sweep_id) + 1
    for i in range(n):
        row_id = trials.insert(sweep_id, start + i, {"period": 10 + start + i})
        trials.complete(row_id, {"oos_fold_mean": 1.0}, f"run-{sweep_id}-{start + i}")
    jobs.set_status(sweep_id, "completed", 100)


def _only(result: scheduler.SchedulerTickResult) -> scheduler.CampaignAdvance:
    assert len(result.campaigns) == 1, result.campaigns
    return result.campaigns[0]


def _run_generation(b: dict[str, Any]) -> scheduler.CampaignAdvance:
    """Drive one full generation: LAUNCHED → (sweep completes) → PROBES_SUBMITTED →
    STRESS_STARTED (record inside) → GENERATION_COMPLETE. Returns the final advance."""
    launch = _only(b["service"].tick())
    assert launch.action == "LAUNCHED", launch
    _complete(b["jobs"], b["trials"], launch.sweepJobId, n=2)
    probes = _only(b["service"].tick())
    assert probes.action == "PROBES_SUBMITTED", probes
    stressed = _only(b["service"].tick())
    assert stressed.action == "STRESS_STARTED", stressed
    done = _only(b["service"].tick())
    assert done.action == "GENERATION_COMPLETE", done
    return done


def _client(service: SchedulerService) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.scheduler = service
    app.include_router(scheduler.router)
    return TestClient(app)


# --- enrollment (finding 2: the global flag alone advances nothing) ------------------------------

def test_unenrolled_campaign_is_skipped():
    b = _build(enroll=False)
    advance = _only(b["service"].tick())
    assert advance.action == "NOT_ENROLLED"
    assert b["sweeps"].submitted == []  # arming the flag/ticking does NOT auto-enroll


def test_enroll_endpoint_sets_idle_then_tick_launches():
    b = _build(enroll=False)
    client = _client(b["service"])
    resp = client.post(f"/api/v1/evolution/campaigns/{b['campaign_id']}/scheduler/enroll")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["previousState"] is None and body["schedulerState"] == "IDLE" and body["changed"]
    # idempotent second enroll
    again = client.post(f"/api/v1/evolution/campaigns/{b['campaign_id']}/scheduler/enroll").json()
    assert again["changed"] is False and again["schedulerState"] == "IDLE"
    assert _only(b["service"].tick()).action == "LAUNCHED"


def test_withdraw_clears_state_and_pending():
    b = _build()
    launch = _only(b["service"].tick())  # EVALUATING with a pending sweep
    assert launch.sweepJobId is not None
    client = _client(b["service"])
    body = client.post(
        f"/api/v1/evolution/campaigns/{b['campaign_id']}/scheduler/withdraw"
    ).json()
    assert body["previousState"] == "EVALUATING"
    assert body["schedulerState"] is None and body["pendingSweepJobId"] is None
    assert _only(b["service"].tick()).action == "NOT_ENROLLED"


def test_enroll_endpoint_unknown_campaign_is_404():
    b = _build()
    resp = _client(b["service"]).post("/api/v1/evolution/campaigns/nope/scheduler/enroll")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CAMPAIGN"


# --- the full generation lifecycle (launch → probes → record+stress → select+propose) ------------

def test_full_generation_lifecycle():
    b = _build()
    done = _run_generation(b)
    assert done.generationN == 1
    # probes were submitted on the completed sweep (§3.2.3) with the ProbeRequest defaults
    assert b["sweeps"].probed == [(done.sweepJobId, 5, 40)]
    # the stress round ran once, on the recorded generation, with the 2x/4x defaults (§3.2.5)
    assert len(b["stress"].calls) == 1
    assert b["stress"].calls[0][2] == [2.0, 4.0]
    # the generation + its scored candidates landed via the REAL recorder; selection ran
    cands = b["repo"].list_candidates_for_campaign(b["campaign_id"])
    assert len(cands) == 2
    assert all(c["state"] in ("SURVIVOR", "RETIRED") for c in cands)
    # back to IDLE, pending cleared — ready for the next generation
    row = b["repo"].get_scheduler_row(b["campaign_id"])
    assert row["schedulerState"] == "IDLE" and row["pendingSweepJobId"] is None


def test_tick_waits_while_sweep_in_flight():
    b = _build()
    b["service"].tick()  # LAUNCHED (sweep stays queued)
    advance = _only(b["service"].tick())
    assert advance.action == "WAITING_SWEEP"
    assert len(b["sweeps"].submitted) == 1


def test_tick_waits_while_probes_drain():
    b = _build()
    launch = _only(b["service"].tick())
    _complete(b["jobs"], b["trials"], launch.sweepJobId, n=1)
    assert _only(b["service"].tick()).action == "PROBES_SUBMITTED"
    # a probe trial is still RUNNING → the record must wait (the cohort freezes at record)
    row_id = b["trials"].insert(launch.sweepJobId, 99, {"period": 42})
    assert _only(b["service"].tick()).action == "WAITING_PROBES"
    b["trials"].complete(row_id, {"oos_fold_mean": 0.9}, "run-probe")
    assert _only(b["service"].tick()).action == "STRESS_STARTED"


def test_tick_waits_while_stress_round_runs():
    b = _build(stress_sync=False)  # the round leaves the durable STRESSING marker
    launch = _only(b["service"].tick())
    _complete(b["jobs"], b["trials"], launch.sweepJobId, n=2)
    b["service"].tick()  # PROBES_SUBMITTED
    stressed = _only(b["service"].tick())
    assert stressed.action == "STRESS_STARTED"
    waiting = _only(b["service"].tick())
    assert waiting.action == "WAITING_STRESS"
    # the drain finishes (apply_stress_round semantics: touches +1, status DONE)
    gen = b["repo"].get_generation_by_n(b["campaign_id"], 1)
    gen["status"] = "DONE"
    gen["stressTouches"] = 1
    assert _only(b["service"].tick()).action == "GENERATION_COMPLETE"


# --- budgets --------------------------------------------------------------------------------------

def test_budget_exhaustion_stops_the_loop_and_reenroll_resumes():
    b = _build(budget={"maxGenerations": 1})
    _run_generation(b)
    row = b["repo"].get_scheduler_row(b["campaign_id"])
    assert row["schedulerState"] == "EXHAUSTED"
    assert _only(b["service"].tick()).action == "BUDGET_EXHAUSTED"
    assert len(b["sweeps"].submitted) == 1  # no over-budget launch

    # owner bumps the budget then RE-ENROLLS (EXHAUSTED → IDLE) — the loop resumes
    b["repo"].get_campaign(b["campaign_id"])["budget"] = {"maxGenerations": 5}
    enrolled = b["service"].enroll(b["campaign_id"])
    assert enrolled.previousState == "EXHAUSTED" and enrolled.schedulerState == "IDLE"
    assert _only(b["service"].tick()).action == "LAUNCHED"


def test_generation_complete_marks_exhausted_at_budget():
    b = _build(budget={"maxGenerations": 1})
    done = _run_generation(b)  # uses the single budgeted generation
    assert "EXHAUSTED" in done.reason
    assert b["repo"].get_scheduler_row(b["campaign_id"])["schedulerState"] == "EXHAUSTED"
    assert len(b["sweeps"].submitted) == 1


# --- evidence-policy refusal ----------------------------------------------------------------------

def test_live_first_campaign_is_policy_refused_even_enrolled():
    b = _build(policy="LIVE_FIRST")
    advance = _only(b["service"].tick())
    assert advance.action == "POLICY_REFUSED"
    assert "LIVE_FIRST" in advance.reason
    assert b["sweeps"].submitted == []  # never a sim sweep for a live-evidence family


def test_sim_blocked_campaign_is_policy_refused():
    b = _build(policy="SIM_BLOCKED")
    assert _only(b["service"].tick()).action == "POLICY_REFUSED"
    assert b["sweeps"].submitted == []


def test_live_watch_runs_for_enrolled_campaign_with_paper_candidate():
    # An enrolled LIVE_FIRST campaign is live-watch-only: the §7.3 assess pass fires (here it
    # fail-softs — the substrate ProposalService lacks the live/recon repos) and is REPORTED.
    b = _build(policy="LIVE_FIRST")
    b["repo"].candidates[b["campaign_id"]] = [{
        "id": "cand-paper", "generationId": "gen-x", "versionId": "ver-1",
        "parentCandidateId": None, "mutationKind": "PARAMS", "params": {}, "structureDiff": None,
        "sweepJobId": None, "holdoutRunId": None, "scorecard": {}, "state": "PAPER",
        "updatedAt": "2026-07-12T00:00:00+00:00",
    }]
    advance = _only(b["service"].tick())
    assert advance.action == "POLICY_REFUSED"
    assert advance.liveWatch is not None and "take-eligible" in advance.liveWatch


# --- cadence --------------------------------------------------------------------------------------

def test_cadence_defers_relaunch_until_interval_elapses():
    b = _build(budget={"maxGenerations": 5, "cadenceSeconds": 3600})
    _run_generation(b)
    within = _only(b["service"].tick())  # still inside the cadence window
    assert within.action == "WAITING_CADENCE"
    assert len(b["sweeps"].submitted) == 1
    b["repo"].now = b["repo"].now + timedelta(hours=2)  # advance past the interval
    assert _only(b["service"].tick()).action == "LAUNCHED"
    assert len(b["sweeps"].submitted) == 2


# --- §1.4.6 slot cap ------------------------------------------------------------------------------

def test_slot_cap_defers_second_campaign():
    b = _build(max_concurrent=1)
    second = b["repo"].create_campaign(
        "22222222-2222-2222-2222-222222222222", "manas-arora", "SIM_FIRST",
        None, _SEARCH_SPACE, {"maxGenerations": 8},
    )
    b["repo"].set_scheduler_state(second["id"], "IDLE")
    result = b["service"].tick()
    actions = [c.action for c in result.campaigns]
    assert actions == ["LAUNCHED", "WAITING_SLOT"]
    assert len(b["sweeps"].submitted) == 1  # the cap held within a single tick too


# --- the claim race (finding 1: conditional UPDATE backstop) --------------------------------------

class _ClaimLostRepo(SchedulerFakeRepo):
    """Simulates the DB backstop firing: the conditional claim matches 0 rows."""

    def claim_pending_sweep(self, campaign_id, sweep_job_id, scheduled_at):
        return None


def test_lost_claim_cancels_the_orphan_sweep():
    b = _build()
    lost_repo = _ClaimLostRepo()
    lost_repo.campaigns = b["repo"].campaigns
    lost_repo.sched = b["repo"].sched
    service = SchedulerService(
        repo_factory=lambda: lost_repo, sweeps=b["sweeps"], recorder=None,
        proposals=b["proposals"], clock=lambda: lost_repo.now,
    )
    advance = _only(service.tick())
    assert advance.action == "CLAIM_LOST"
    assert b["sweeps"].cancelled == [advance.sweepJobId]  # the orphan sweep was cancelled


# --- durability: failed / orphaned / missing pending sweep + crash re-ticks -----------------------

def test_failed_pending_sweep_resets_to_idle_for_relaunch():
    b = _build()
    launch = _only(b["service"].tick())
    b["jobs"].set_status(launch.sweepJobId, "failed")  # the boot orphan-fail
    advance = _only(b["service"].tick())
    assert advance.action == "SWEEP_FAILED"
    row = b["repo"].get_scheduler_row(b["campaign_id"])
    assert row["schedulerState"] == "IDLE" and row["pendingSweepJobId"] is None
    assert _only(b["service"].tick()).action == "LAUNCHED"
    assert len(b["sweeps"].submitted) == 2


def test_missing_pending_sweep_resets_to_idle():
    b = _build()
    launch = _only(b["service"].tick())
    del b["jobs"].rows[launch.sweepJobId]
    advance = _only(b["service"].tick())
    assert advance.action == "SWEEP_FAILED" and "missing" in advance.reason
    assert b["repo"].get_scheduler_row(b["campaign_id"])["schedulerState"] == "IDLE"


def test_crash_between_record_and_clear_is_idempotent():
    # Crash-state: the generation was recorded (+stressed) but the pending marker never cleared —
    # the campaign is left at PROBING pointing at the already-recorded sweep. The re-tick absorbs
    # the 409, sees the stress already applied, and finalizes — exactly ONE generation.
    b = _build()
    done = _run_generation(b)
    sched = b["repo"].sched[b["campaign_id"]]
    sched.update(schedulerState="PROBING", pendingSweepJobId=done.sweepJobId)
    advance = _only(b["service"].tick())
    assert advance.action == "GENERATION_COMPLETE"
    assert advance.generationN == 1                                # re-derived, not duplicated
    assert "already applied" in advance.reason                     # stress not re-run
    assert len(b["repo"].list_generations(b["campaign_id"])) == 1
    assert len(b["stress"].calls) == 1


# --- fault isolation + the no-self-publish invariant ----------------------------------------------

def test_one_faulting_campaign_never_blocks_the_others():
    b = _build(max_concurrent=2)
    second = b["repo"].create_campaign(
        "22222222-2222-2222-2222-222222222222", "manas-arora", "SIM_FIRST",
        None, _SEARCH_SPACE, {"maxGenerations": 8},
    )
    # campaign 2 is mid-EVALUATING on a sweep whose status read explodes (a downstream fault)
    b["repo"].sched[second["id"]] = {
        "schedulerState": "EVALUATING", "pendingSweepJobId": "boom-sweep",
        "lastScheduledAt": None,
    }
    b["sweeps"].boom_ids.add("boom-sweep")
    result = b["service"].tick()
    by_id = {c.campaignId: c for c in result.campaigns}
    assert by_id[second["id"]].action == "ERROR"
    assert "RuntimeError" in by_id[second["id"]].reason
    assert by_id[b["campaign_id"]].action == "LAUNCHED"  # the healthy campaign still advanced


def test_scheduler_never_executes_or_publishes():
    # Two full generations of autonomy: the execute spy is never hit; every APPROVED proposal is a
    # §8.2 RETIRE acknowledge-row; no PUBLISH_PAPER was minted at all (no candidate has a consumed
    # holdout — the §6.1 autonomous mint-gate).
    b = _build(budget={"maxGenerations": 2})
    _run_generation(b)
    _run_generation(b)
    assert b["proposals"].execute_calls == []
    approved_kinds = {p["kind"] for p in b["repo"].proposals if p["status"] == "APPROVED"}
    assert approved_kinds <= {"RETIRE"}
    assert all(p["kind"] != "PUBLISH_PAPER" for p in b["repo"].proposals)


# --- the §6.1 holdout mint-gate + the approve→execute dedup (proposals-level) ---------------------

def _proposal_fixture(holdout: str | None):
    campaign = {
        "id": "camp-1", "strategyId": "11111111-1111-1111-1111-111111111111",
        "family": "manas-arora", "evidencePolicy": "SIM_FIRST", "objectiveSpec": None,
        "searchSpace": None, "budget": None, "status": "ACTIVE", "championVersionId": None,
        "createdAt": "2026-07-11T00:00:00+00:00", "updatedAt": "2026-07-11T00:00:00+00:00",
    }
    cand = {
        "id": "cand-1", "generationId": "gen-1", "versionId": None, "parentCandidateId": None,
        "mutationKind": "PARAMS", "params": {"period": 15}, "structureDiff": None,
        "sweepJobId": "sweep-1", "holdoutRunId": holdout,
        "scorecard": {"robustScore": 1.0, "rankable": True,
                      "gates": [{"id": "oos_sign", "status": "PASS"}]},
        "state": "SURVIVOR", "updatedAt": "2026-07-11T00:00:00+00:00",
    }
    repo = FakeEvoRepo(campaigns=[campaign], candidates={"camp-1": [cand]})
    return repo, ProposalService(repo_factory=lambda: repo, ntfy=FakeNtfy())


def test_autonomous_mint_requires_consumed_holdout():
    repo, svc = _proposal_fixture(holdout=None)
    out = svc.generate_for_campaign("camp-1", require_holdout=True)
    assert out.generated == 0 and out.refreshed == 0        # §6.1: never degraded to a caveat
    # the owner-triggered default keeps the permissive caveat behavior
    out = svc.generate_for_campaign("camp-1")
    assert out.generated == 1


def test_approved_awaiting_execute_is_not_reminted():
    repo, svc = _proposal_fixture(holdout="hold-run-1")
    first = svc.generate_for_campaign("camp-1", require_holdout=True)
    assert first.generated == 1
    svc.approve(first.items[0].id, actor="owner")
    # candidate is still SURVIVOR (execute not yet clicked) — regeneration must NOT re-mint
    again = svc.generate_for_campaign("camp-1", require_holdout=True)
    assert again.generated == 0 and again.refreshed == 0
    statuses = [p["status"] for p in repo.proposals if p["kind"] == "PUBLISH_PAPER"]
    assert statuses == ["APPROVED"]  # exactly one row: the approved one, never a duplicate


def test_rejected_proposal_allows_a_fresh_mint():
    repo, svc = _proposal_fixture(holdout="hold-run-1")
    first = svc.generate_for_campaign("camp-1", require_holdout=True)
    svc.reject(first.items[0].id, actor="owner")
    again = svc.generate_for_campaign("camp-1", require_holdout=True)
    assert again.generated == 1  # terminal REJECTED never blocks (E4 semantics preserved)


# --- proposal expiry sweep (§8.2 7-day) -----------------------------------------------------------

def _seed_proposal(repo: SchedulerFakeRepo, campaign_id: str, expires_at: str) -> str:
    row = repo.insert_proposal(campaign_id, "cand-x", "PUBLISH_PAPER", {"note": "x"})
    for stored in repo.proposals:  # override the fixed expiry the fake stamps at insert
        if stored["id"] == row["id"]:
            stored["expiresAt"] = expires_at
    return row["id"]


def test_expire_proposals_flips_pending_past_expiry():
    b = _build()
    stale = _seed_proposal(b["repo"], b["campaign_id"], "2026-07-01T00:00:00+00:00")
    fresh = _seed_proposal(b["repo"], b["campaign_id"], "2026-07-20T00:00:00+00:00")
    expired = b["service"].expire_proposals()
    assert expired == [stale]
    by_id = {p["id"]: p for p in b["repo"].proposals}
    assert by_id[stale]["status"] == "EXPIRED"
    assert by_id[stale]["actor"] == "evo:expiry"
    assert by_id[fresh]["status"] == "PENDING"


def test_tick_expires_stale_proposals():
    b = _build(policy="LIVE_FIRST")
    _seed_proposal(b["repo"], b["campaign_id"], "2026-07-01T00:00:00+00:00")
    assert b["service"].tick().expiredProposals == 1


# --- endpoints + heartbeat ------------------------------------------------------------------------

def test_scheduler_tick_endpoint_returns_advances():
    b = _build()
    client = _client(b["service"])
    body = client.post("/api/v1/evolution/scheduler/tick").json()
    assert body["campaigns"][0]["action"] == "LAUNCHED"
    assert "ranAt" in body and body["expiredProposals"] == 0


def test_expire_endpoint_reports_expired_ids():
    b = _build()
    stale = _seed_proposal(b["repo"], b["campaign_id"], "2026-07-01T00:00:00+00:00")
    body = _client(b["service"]).post("/api/v1/evolution/proposals/expire").json()
    assert body["expired"] == 1 and body["proposalIds"] == [stale]


def test_status_heartbeat_stamps_last_tick():
    b = _build()
    client = _client(b["service"])
    assert client.get("/api/v1/evolution/scheduler/status").json()["lastTickAt"] is None
    b["service"].tick()
    assert (
        client.get("/api/v1/evolution/scheduler/status").json()["lastTickAt"]
        == b["repo"].now.isoformat()
    )


# --- non-advancing campaigns ----------------------------------------------------------------------

def test_paused_campaign_is_not_advanced():
    b = _build()
    b["repo"].get_campaign(b["campaign_id"])["status"] = "PAUSED"
    result = b["service"].tick()
    assert result.campaigns == []          # PAUSED campaigns are not scheduler targets
    assert b["sweeps"].submitted == []


def test_incomplete_search_space_is_skipped():
    b = _build(search_space={"parameters": []})
    advance = _only(b["service"].tick())
    assert advance.action == "NO_SEARCH_SPACE"
    assert b["sweeps"].submitted == []
