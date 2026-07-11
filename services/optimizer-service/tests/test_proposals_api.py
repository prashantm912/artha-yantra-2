"""The EVO E4 proposals substrate (§12 item 11) — generation rules + inbox API + ntfy — driven
through a TestClient over ProposalService + the in-memory FakeEvoRepo / FakeNtfy (no Postgres, no
network).

Covers: PUBLISH_PAPER generation for a SURVIVOR+rankable candidate (the queryable §8.2 gates);
one-per-new-proposal ntfy; idempotency (regeneration refreshes, no dup / no second push); the
near-champion bar applied only when the champion RobustScore is queryable (a VERIFIED miss blocks,
an unpopulated input caveats via pendingInputs, never fabricated); the holdout-consumed caveat;
non-rankable / non-SURVIVOR exclusion; the inbox list filters; approve/reject marking + audit +
nextSteps (substrate-only) + the 404 / 409 idioms; and that nothing self-arms (a decision only
marks the row)."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import proposals
from app.errors import ApiError, api_error_handler
from app.proposals import ProposalService
from tests.fakes import FakeEvoRepo, FakeNtfy

_CAMPAIGN_ID = "camp-1"


def _campaign(champion=None, family="manas-arora", campaign_id=_CAMPAIGN_ID):
    return {
        "id": campaign_id, "strategyId": "11111111-1111-1111-1111-111111111111", "family": family,
        "evidencePolicy": "SIM_FIRST", "objectiveSpec": None, "searchSpace": None, "budget": None,
        "status": "ACTIVE", "championVersionId": champion,
        "createdAt": "2026-07-11T00:00:00+00:00", "updatedAt": "2026-07-11T00:00:00+00:00",
    }


def _cand(cid, *, state="SURVIVOR", robust=1.0, rankable=True, holdout=None, version_id=None):
    return {
        "id": cid, "generationId": "gen-1", "versionId": version_id, "parentCandidateId": None,
        "mutationKind": "PARAMS", "params": {"period": 15}, "structureDiff": None,
        "sweepJobId": "sweep-1", "holdoutRunId": holdout,
        "scorecard": {
            "robustScore": robust, "rankable": rankable,
            "gates": [{"id": "oos_sign", "status": "PASS"},
                      {"id": "drawdown_cap", "status": "PASS"}],
        },
        "state": state, "updatedAt": "2026-07-11T00:00:00+00:00",
    }


def _client(repo: FakeEvoRepo, ntfy: FakeNtfy | None = None) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.proposals = ProposalService(repo_factory=lambda: repo, ntfy=ntfy or FakeNtfy())
    app.include_router(proposals.router)
    return TestClient(app)


# --- generation rules ---------------------------------------------------------------------------

def test_generate_publish_paper_for_survivor_and_pushes_ntfy():
    ntfy = FakeNtfy()
    repo = FakeEvoRepo(
        campaigns=[_campaign()],
        candidates={_CAMPAIGN_ID: [_cand("c-surv"), _cand("c-scored", state="SCORED")]},
    )
    client = _client(repo, ntfy)
    resp = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["generated"] == 1 and body["refreshed"] == 0
    assert len(body["items"]) == 1
    prop = body["items"][0]
    assert prop["kind"] == "PUBLISH_PAPER"
    assert prop["candidateId"] == "c-surv"
    assert prop["status"] == "PENDING"
    assert prop["evidence"]["robustScore"] == 1.0
    assert prop["evidence"]["blastRadius"]["family"] == "manas-arora"
    # the SCORED (non-SURVIVOR) candidate is not proposed
    assert all(p["candidateId"] != "c-scored" for p in body["items"])
    # exactly one ntfy for the one NEW proposal
    assert len(ntfy.sent) == 1
    assert "PUBLISH_PAPER" in ntfy.sent[0][0]


def test_generation_is_idempotent_refresh_no_dup_no_second_ntfy():
    ntfy = FakeNtfy()
    repo = FakeEvoRepo(campaigns=[_campaign()], candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo, ntfy)
    first = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    assert first["generated"] == 1
    second = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    assert second["generated"] == 0 and second["refreshed"] == 1
    # one OPEN proposal per (candidate, kind) — still exactly one row
    assert len(repo.proposals) == 1
    # no second push on a refresh
    assert len(ntfy.sent) == 1


def test_champion_bar_verified_miss_blocks():
    # champion RobustScore is queryable (the champion version is a scored candidate) and the
    # challenger is BELOW champion - 0.1 → a VERIFIED miss → not eligible, no proposal.
    champ = _cand("c-champ", robust=2.0, version_id="ver-champ")
    weak = _cand("c-weak", robust=1.5)  # 1.5 < 2.0 - 0.1
    repo = FakeEvoRepo(
        campaigns=[_campaign(champion="ver-champ")],
        candidates={_CAMPAIGN_ID: [champ, weak]},
    )
    client = _client(repo)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    assert body["generated"] == 0
    assert repo.proposals == []


def test_champion_bar_near_champion_passes_no_pending_caveat():
    champ = _cand("c-champ", robust=2.0, version_id="ver-champ")
    near = _cand("c-near", robust=1.95)  # 1.95 >= 2.0 - 0.1
    repo = FakeEvoRepo(
        campaigns=[_campaign(champion="ver-champ")],
        candidates={_CAMPAIGN_ID: [champ, near]},
    )
    client = _client(repo)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    props = {p["candidateId"]: p for p in body["items"]}
    assert "c-near" in props
    ev = props["c-near"]["evidence"]
    assert ev["championRobustScore"] == 2.0
    assert ev["margin"] == -0.05
    # the near-champion bar was VERIFIED (both scores present) → no champion pendingInput
    assert not any("near-champion bar" in c for c in ev["pendingInputs"])


def test_no_champion_stamps_pending_input_but_generates():
    repo = FakeEvoRepo(campaigns=[_campaign(champion=None)],
                       candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    assert body["generated"] == 1
    ev = body["items"][0]["evidence"]
    assert ev["championRobustScore"] is None
    assert any("no champion set" in c for c in ev["pendingInputs"])


def test_champion_set_but_not_a_scored_candidate_caveats_not_fabricated():
    # champion_version_id points at a version that is NOT a scored candidate in this campaign → the
    # champion RobustScore is not queryable → pendingInput caveat, and generation proceeds (never a
    # fabricated pass).
    repo = FakeEvoRepo(campaigns=[_campaign(champion="ver-elsewhere")],
                       candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    assert body["generated"] == 1
    ev = body["items"][0]["evidence"]
    assert ev["championRobustScore"] is None
    assert any("champion RobustScore not queryable" in c for c in ev["pendingInputs"])


def test_holdout_caveat_present_when_not_consumed_absent_when_consumed():
    repo = FakeEvoRepo(
        campaigns=[_campaign()],
        candidates={_CAMPAIGN_ID: [
            _cand("c-noholdout", holdout=None),
            _cand("c-holdout", holdout="run-h1"),
        ]},
    )
    client = _client(repo)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    ev = {p["candidateId"]: p["evidence"] for p in body["items"]}
    assert ev["c-noholdout"]["holdoutConsumed"] is False
    assert any("holdout not yet consumed" in c for c in ev["c-noholdout"]["pendingInputs"])
    assert ev["c-holdout"]["holdoutConsumed"] is True
    assert not any("holdout not yet consumed" in c for c in ev["c-holdout"]["pendingInputs"])


def test_non_rankable_survivor_is_not_eligible():
    repo = FakeEvoRepo(campaigns=[_campaign()],
                       candidates={_CAMPAIGN_ID: [_cand("c-failed", rankable=False)]})
    client = _client(repo)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    assert body["generated"] == 0
    assert repo.proposals == []


def test_generate_unknown_campaign_is_404():
    client = _client(FakeEvoRepo())
    resp = client.post("/api/v1/evolution/campaigns/nope/proposals")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CAMPAIGN"


# --- inbox: list + approve/reject ---------------------------------------------------------------

def _seed_one(client: TestClient) -> str:
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    return body["items"][0]["id"]


def test_inbox_list_filters_status_kind_campaign():
    repo = FakeEvoRepo(campaigns=[_campaign()], candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo)
    _seed_one(client)
    # all
    assert len(client.get("/api/v1/evolution/proposals").json()["items"]) == 1
    # status filter
    assert client.get("/api/v1/evolution/proposals?status=PENDING").json()["items"]
    assert client.get("/api/v1/evolution/proposals?status=APPROVED").json()["items"] == []
    # kind filter
    assert client.get("/api/v1/evolution/proposals?kind=PUBLISH_PAPER").json()["items"]
    assert client.get("/api/v1/evolution/proposals?kind=PROMOTE").json()["items"] == []
    # campaign filter
    assert client.get(f"/api/v1/evolution/proposals?campaignId={_CAMPAIGN_ID}").json()["items"]
    assert client.get("/api/v1/evolution/proposals?campaignId=other").json()["items"] == []


def test_approve_marks_row_and_returns_next_steps():
    repo = FakeEvoRepo(campaigns=[_campaign()], candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo)
    pid = _seed_one(client)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/approve", json={"actor": "prashant"})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["proposal"]["status"] == "APPROVED"
    assert body["proposal"]["actor"] == "prashant"
    assert body["proposal"]["decidedAt"] is not None
    # nextSteps names the slice-2 action, honestly not-yet-wired
    assert body["nextSteps"]["action"] == "PUBLISH_PAPER"
    assert body["nextSteps"]["status"] == "PENDING_SLICE_2"
    note = body["nextSteps"]["note"]
    assert "NOT yet wired" in note or "not yet wired" in note


def test_approve_defaults_actor_to_owner():
    repo = FakeEvoRepo(campaigns=[_campaign()], candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo)
    pid = _seed_one(client)
    body = client.post(f"/api/v1/evolution/proposals/{pid}/approve").json()
    assert body["proposal"]["actor"] == "owner"


def test_reject_marks_row_no_next_steps():
    repo = FakeEvoRepo(campaigns=[_campaign()], candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo)
    pid = _seed_one(client)
    body = client.post(f"/api/v1/evolution/proposals/{pid}/reject").json()
    assert body["proposal"]["status"] == "REJECTED"
    assert body["nextSteps"] is None


def test_second_decision_is_409():
    repo = FakeEvoRepo(campaigns=[_campaign()], candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo)
    pid = _seed_one(client)
    assert client.post(f"/api/v1/evolution/proposals/{pid}/approve").status_code == 200
    dup = client.post(f"/api/v1/evolution/proposals/{pid}/reject")
    assert dup.status_code == 409
    assert dup.json()["code"] == "PROPOSAL_ALREADY_DECIDED"


def test_decide_unknown_proposal_is_404():
    client = _client(FakeEvoRepo())
    resp = client.post("/api/v1/evolution/proposals/nope/approve")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_PROPOSAL"


def test_a_decided_proposal_is_excluded_from_regeneration_open_check():
    # After a proposal is APPROVED, a regeneration mints a FRESH proposal (the OPEN check only sees
    # PENDING rows — a terminal decision never blocks a new proposal for the same candidate).
    repo = FakeEvoRepo(campaigns=[_campaign()], candidates={_CAMPAIGN_ID: [_cand("c-surv")]})
    client = _client(repo)
    pid = _seed_one(client)
    client.post(f"/api/v1/evolution/proposals/{pid}/approve")
    regen = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/proposals").json()
    assert regen["generated"] == 1  # a new PENDING proposal, not a refresh of the APPROVED one
    assert len(repo.proposals) == 2
