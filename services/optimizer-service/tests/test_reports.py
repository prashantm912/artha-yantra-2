"""The §12 E6 item-16 per-campaign REPORT — GET /api/v1/evolution/campaigns/{id}/report.

Driven through CampaignReportService + the in-memory fakes. A campaign is seeded end-to-end (record
a generation via the REAL recorder → score candidates → select survivors → auto-RETIRE the rest →
mint PUBLISH_PAPER proposals), then the report is asserted to summarize generations, scores,
survivors, the proposal tallies, the budget spend + scheduler sub-state, and the §8.3 graveyard.
"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import reports
from app.errors import ApiError, api_error_handler
from app.evolution import EvoRecorderService, RetroScoreService
from app.proposals import ProposalService
from app.reports import CampaignReportService
from tests.fakes import FakeBacktest, FakeJobs, FakeNtfy, FakeTrials
from tests.test_scheduler import _FOLDS, _RESULTS, SchedulerFakeRepo


def _seed_campaign_with_generation(
    *, n_trials: int = 4, top_k: int = 2, budget: dict[str, Any] | None = None
) -> tuple[SchedulerFakeRepo, str]:
    """A campaign with one recorded generation of ``n_trials`` scored candidates, selected to
    ``top_k`` SURVIVORs (the rest RETIRED with a recorded reason), and its PUBLISH_PAPER proposals
    minted — the full research trail the report reads."""
    repo = SchedulerFakeRepo()
    jobs = FakeJobs()
    trials = FakeTrials(jobs)
    backtest = FakeBacktest(folds=_FOLDS, results=_RESULTS)
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    recorder = EvoRecorderService(repo_factory=lambda: repo, scorer=scorer)
    proposals = ProposalService(repo_factory=lambda: repo, ntfy=FakeNtfy())

    campaign = repo.create_campaign(
        "11111111-1111-1111-1111-111111111111", "manas-arora", "SIM_FIRST",
        None, {"from": "2024-01-01", "to": "2024-06-30"},
        budget or {"maxGenerations": 4, "maxTrialsPerGen": 10, "holdoutTouches": 3},
    )
    campaign_id = campaign["id"]

    sweep_id = jobs.insert_sweep(
        None, {"parameters": [], "objective": {"metric": "oos_fold_mean", "direction": "maximize"}}
    )
    for i in range(n_trials):
        row_id = trials.insert(sweep_id, i, {"period": 10 + i})
        trials.complete(row_id, {"oos_fold_mean": 1.0 - i * 0.01}, f"run-{i}")
    jobs.set_status(sweep_id, "completed", 100)

    gen = recorder.record_generation(campaign_id, {"sweepJobId": sweep_id})
    recorder.select_survivors(campaign_id, gen.n, top_k)
    proposals.generate_for_campaign(campaign_id)
    return repo, campaign_id


def _report(repo: SchedulerFakeRepo, campaign_id: str) -> dict[str, Any]:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.reports = CampaignReportService(repo_factory=lambda: repo)
    app.include_router(reports.router)
    client = TestClient(app)
    resp = client.get(f"/api/v1/evolution/campaigns/{campaign_id}/report")
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_report_summarizes_generations_and_scores():
    repo, campaign_id = _seed_campaign_with_generation(n_trials=4, top_k=2)
    body = _report(repo, campaign_id)

    assert body["campaignId"] == campaign_id
    assert body["evidencePolicy"] == "SIM_FIRST"
    assert body["status"] == "ACTIVE"
    assert len(body["generations"]) == 1
    generation = body["generations"][0]
    assert generation["n"] == 1
    assert generation["candidateCount"] == 4
    assert generation["survivorCount"] == 2
    assert generation["bestRobustScore"] is not None


def test_report_candidate_states_and_survivors():
    repo, campaign_id = _seed_campaign_with_generation(n_trials=4, top_k=2)
    body = _report(repo, campaign_id)
    assert body["candidateStates"] == {"SURVIVOR": 2, "RETIRED": 2}
    assert body["survivors"] == 2


def test_report_graveyard_carries_retire_reasons():
    # §8.3: the graveyard is a first-class output — every RETIRED candidate with WHY (the recorded
    # selection reason), so the report proves the search is not cherry-picking.
    repo, campaign_id = _seed_campaign_with_generation(n_trials=4, top_k=2)
    body = _report(repo, campaign_id)
    assert len(body["graveyard"]) == 2
    for entry in body["graveyard"]:
        assert entry["generationN"] == 1
        assert entry["reason"]  # a recorded reason (dominated / hard-gate fail), never blank


def test_report_proposal_tallies():
    repo, campaign_id = _seed_campaign_with_generation(n_trials=4, top_k=2)
    body = _report(repo, campaign_id)
    tallies = {(t["kind"], t["status"]): t["count"] for t in body["proposals"]}
    # two survivors → two PENDING PUBLISH_PAPER; two retires → two auto-APPROVED RETIRE acks
    assert tallies.get(("PUBLISH_PAPER", "PENDING")) == 2
    assert tallies.get(("RETIRE", "APPROVED")) == 2


def test_report_budget_spend_and_scheduler_state():
    repo, campaign_id = _seed_campaign_with_generation(n_trials=3, top_k=2)
    # simulate an in-flight next generation so the report surfaces the scheduler sub-state
    repo.claim_pending_sweep(campaign_id, "22222222-2222-2222-2222-222222222222", repo.now)
    body = _report(repo, campaign_id)

    budget = body["budget"]
    assert budget["maxGenerations"] == 4
    assert budget["generationsUsed"] == 1
    assert budget["maxTrialsPerGen"] == 10
    assert budget["holdoutTouches"] == 3
    assert budget["candidatesScored"] == 3
    assert budget["schedulerState"] == "EVALUATING"
    assert budget["pendingSweepJobId"] == "22222222-2222-2222-2222-222222222222"


def test_report_unknown_campaign_is_404():
    repo = SchedulerFakeRepo()
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.reports = CampaignReportService(repo_factory=lambda: repo)
    app.include_router(reports.router)
    client = TestClient(app)
    resp = client.get("/api/v1/evolution/campaigns/nope/report")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CAMPAIGN"


def test_report_empty_campaign_is_clean():
    # A brand-new campaign (no generations yet) reports empty collections, not a crash.
    repo = SchedulerFakeRepo()
    campaign = repo.create_campaign(
        "11111111-1111-1111-1111-111111111111", "manas-arora", "SIM_FIRST", None, None, None
    )
    body = _report(repo, campaign["id"])
    assert body["generations"] == []
    assert body["candidateStates"] == {}
    assert body["survivors"] == 0
    assert body["graveyard"] == []
    assert body["proposals"] == []
    assert body["budget"]["generationsUsed"] == 0
