"""The §12 E1 evolution read surface — the {items} envelopes, the campaign detail with
its generations, the scorecard passthrough, and the 404 idiom — driven through a
TestClient over EvoReadService + an in-memory FakeEvoRepo (no Postgres)."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import evolution
from app.errors import ApiError, api_error_handler
from app.evolution import EvoReadService
from tests.fakes import FakeEvoRepo

CAMPAIGN = {
    "id": "camp-1",
    "strategyId": "11111111-1111-1111-1111-111111111111",
    "family": "manas-arora",
    "evidencePolicy": "SIM_FIRST",
    "objectiveSpec": {"weights": {"oos_return": 0.22}},
    "searchSpace": {"dims": 4},
    "budget": {"maxGenerations": 8},
    "status": "ACTIVE",
    "championVersionId": None,
    "createdAt": "2026-07-11T00:00:00+00:00",
    "updatedAt": "2026-07-11T00:00:00+00:00",
}
GENERATION = {
    "id": "gen-1",
    "campaignId": "camp-1",
    "n": 1,
    "proposal": {"sampler": "random", "seed": 7},
    "searchSpaceHash": "abc123",
    "engineSha": "deadbeef",
    "dataEpoch": {"epoch": 1},
    "stressTouches": 0,
    "status": "SCORED",
    "startedAt": "2026-07-11T00:00:00+00:00",
    "finishedAt": None,
}
CANDIDATE = {
    "id": "cand-1",
    "generationId": "gen-1",
    "versionId": "22222222-2222-2222-2222-222222222222",
    "parentCandidateId": None,
    "mutationKind": "PARAMS",
    "params": {"atr_mult": 3.25},
    "structureDiff": None,
    "sweepJobId": "33333333-3333-3333-3333-333333333333",
    "holdoutRunId": "44444444-4444-4444-4444-444444444444",
    "scorecard": {"robustScore": 1.42, "rank": 2},
    "state": "SURVIVOR",
    "updatedAt": "2026-07-11T00:00:00+00:00",
}


def _client(repo: FakeEvoRepo) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.evo = EvoReadService(repo_factory=lambda: repo)
    app.include_router(evolution.router)
    return TestClient(app)


def test_campaigns_empty_returns_empty_envelope():
    resp = _client(FakeEvoRepo()).get("/api/v1/evolution/campaigns")
    assert resp.status_code == 200
    assert resp.json() == {"items": []}


def test_campaigns_lists_rows():
    resp = _client(FakeEvoRepo(campaigns=[CAMPAIGN])).get("/api/v1/evolution/campaigns")
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert len(items) == 1
    assert items[0]["id"] == "camp-1"
    assert items[0]["evidencePolicy"] == "SIM_FIRST"
    assert items[0]["budget"] == {"maxGenerations": 8}  # jsonb passthrough


def test_campaign_detail_includes_generations():
    repo = FakeEvoRepo(campaigns=[CAMPAIGN], generations={"camp-1": [GENERATION]})
    resp = _client(repo).get("/api/v1/evolution/campaigns/camp-1")
    assert resp.status_code == 200
    body = resp.json()
    assert body["id"] == "camp-1"
    assert len(body["generations"]) == 1
    assert body["generations"][0]["n"] == 1
    assert body["generations"][0]["proposal"] == {"sampler": "random", "seed": 7}


def test_campaign_detail_unknown_is_404():
    resp = _client(FakeEvoRepo()).get("/api/v1/evolution/campaigns/nope")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CAMPAIGN"


def test_campaign_candidates_returns_scorecard():
    repo = FakeEvoRepo(campaigns=[CAMPAIGN], candidates={"camp-1": [CANDIDATE]})
    resp = _client(repo).get("/api/v1/evolution/campaigns/camp-1/candidates")
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert len(items) == 1
    assert items[0]["id"] == "cand-1"
    assert items[0]["state"] == "SURVIVOR"
    assert items[0]["scorecard"] == {"robustScore": 1.42, "rank": 2}  # jsonb passthrough


def test_campaign_candidates_empty_returns_empty_envelope():
    resp = _client(FakeEvoRepo(campaigns=[CAMPAIGN])).get(
        "/api/v1/evolution/campaigns/camp-1/candidates"
    )
    assert resp.status_code == 200
    assert resp.json() == {"items": []}


def test_campaign_candidates_unknown_campaign_is_404():
    resp = _client(FakeEvoRepo()).get("/api/v1/evolution/campaigns/nope/candidates")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CAMPAIGN"
