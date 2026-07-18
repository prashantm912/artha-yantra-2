"""The EVO E4 slice-2 SELECT step (§8.1 / §12 item 12) — promote a generation's SCORED candidates
to SURVIVOR (gate-passing top-K by RobustScore) or RETIRE the rest, recording the rationale on each
scorecard. Driven through a TestClient over EvoRecorderService + the in-memory FakeEvoRepo.

Covers: top-K promotion + domination retire; hard-gate-FAIL retire (excluded from the ranking
cohort); the rationale (decision / reason / rank / topK) written onto each scorecard; idempotency (a
re-run recomputes the same split and writes nothing); advanced-state candidates (PAPER) are never
demoted; the default topK; and the 404 idioms (unknown campaign / generation)."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import evolution
from app.errors import ApiError, api_error_handler
from app.evolution import EvoReadService, EvoRecorderService
from tests.fakes import FakeEvoRepo

_CAMPAIGN_ID = "camp-1"
_GEN_ID = "gen-1"


def _campaign(campaign_id=_CAMPAIGN_ID):
    return {
        "id": campaign_id, "strategyId": "11111111-1111-1111-1111-111111111111",
        "family": "manas-arora", "evidencePolicy": "SIM_FIRST", "objectiveSpec": None,
        "searchSpace": None, "budget": None, "status": "ACTIVE", "championVersionId": None,
        "createdAt": "2026-07-11T00:00:00+00:00", "updatedAt": "2026-07-11T00:00:00+00:00",
    }


def _gen(n=1, gen_id=_GEN_ID, campaign_id=_CAMPAIGN_ID):
    return {
        "id": gen_id, "campaignId": campaign_id, "n": n, "proposal": None,
        "searchSpaceHash": None, "engineSha": None, "dataEpoch": None, "stressTouches": 0,
        "status": "DONE", "startedAt": None, "finishedAt": None,
    }


def _cand(cid, *, state="SCORED", robust=1.0, rankable=True, ready=None, gates=None, trial=0,
          gen_id=_GEN_ID):
    # audit PF-01: SURVIVOR selection consumes the fail-closed ``stageReadiness.ready`` (not the
    # permissive ``rankable``). ``ready`` mirrors ``rankable`` unless overridden.
    stage_ready = rankable if ready is None else ready
    return {
        "id": cid, "generationId": gen_id, "versionId": None, "parentCandidateId": None,
        "mutationKind": "PARAMS", "params": {"indicators[0].params.period": 15},
        "structureDiff": None, "sweepJobId": "sweep-1", "holdoutRunId": None,
        "scorecard": {
            "robustScore": robust, "rankable": rankable, "trialNumber": trial,
            "stageReadiness": {"evidencePolicy": "SIM_FIRST", "stage": "SCORED_TO_SURVIVOR",
                               "ready": stage_ready, "requiredGates": [], "blockedBy": []},
            "gates": gates or [{"id": "oos_sign", "status": "PASS"}],
        },
        "state": state, "updatedAt": "2026-07-11T00:00:00+00:00",
    }


def _client(repo: FakeEvoRepo) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.evo = EvoReadService(repo_factory=lambda: repo)
    app.state.evo_writer = EvoRecorderService(repo_factory=lambda: repo, scorer=None)
    app.include_router(evolution.router)
    return TestClient(app)


def _select(client: TestClient, top_k=None):
    body = {} if top_k is None else {"topK": top_k}
    return client.post(
        f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/generations/1/select", json=body
    )


def _cands_by_id(client: TestClient):
    rows = client.get(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/candidates").json()["items"]
    return {c["id"]: c for c in rows}


# --- top-K promotion + retire -------------------------------------------------------------------

def test_select_promotes_top_k_and_retires_the_rest():
    repo = FakeEvoRepo(
        campaigns=[_campaign()],
        generations={_CAMPAIGN_ID: [_gen()]},
        candidates={_CAMPAIGN_ID: [
            _cand("c-hi", robust=2.0, trial=0),
            _cand("c-mid", robust=1.5, trial=1),
            _cand("c-lo", robust=1.0, trial=2),
        ]},
    )
    client = _client(repo)
    resp = _select(client, top_k=2)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["survivors"] == 2 and body["retired"] == 1
    assert body["topK"] == 2 and body["generationN"] == 1
    cands = _cands_by_id(client)
    assert cands["c-hi"]["state"] == "SURVIVOR"
    assert cands["c-mid"]["state"] == "SURVIVOR"
    assert cands["c-lo"]["state"] == "RETIRED"
    # rationale recorded on the scorecard
    hi_sel = cands["c-hi"]["scorecard"]["selection"]
    assert hi_sel["decision"] == "SURVIVOR" and hi_sel["rank"] == 1
    assert "top-2 by RobustScore" in hi_sel["reason"]
    lo_sel = cands["c-lo"]["scorecard"]["selection"]
    assert lo_sel["decision"] == "RETIRED" and lo_sel["rank"] == 3
    assert "dominated" in lo_sel["reason"]


def test_gate_fail_candidate_retires_and_is_out_of_the_ranking_cohort():
    # A non-rankable (hard-gate FAIL) candidate never occupies a survivor slot and is excluded from
    # the rankable cohort count — only the two rankable candidates compete.
    repo = FakeEvoRepo(
        campaigns=[_campaign()],
        generations={_CAMPAIGN_ID: [_gen()]},
        candidates={_CAMPAIGN_ID: [
            _cand("c-ok", robust=1.2, trial=0),
            _cand("c-ok2", robust=1.1, trial=1),
            _cand("c-fail", robust=9.9, rankable=False, trial=2,
                  gates=[{"id": "drawdown_cap", "status": "FAIL"},
                         {"id": "oos_sign", "status": "PASS"}]),
        ]},
    )
    client = _client(repo)
    body = _select(client, top_k=5).json()
    assert body["survivors"] == 2 and body["retired"] == 1
    cands = _cands_by_id(client)
    # the high-raw-score FAIL candidate is RETIRED, not a survivor (gates override raw score)
    fail_sel = cands["c-fail"]["scorecard"]["selection"]
    assert cands["c-fail"]["state"] == "RETIRED"
    assert fail_sel["rank"] is None
    assert "hard-gate FAIL: drawdown_cap" in fail_sel["reason"]
    # the rankable cohort excludes the FAIL candidate
    assert cands["c-ok"]["scorecard"]["selection"]["rankableCohort"] == 2


def test_select_is_idempotent_second_run_writes_nothing():
    repo = FakeEvoRepo(
        campaigns=[_campaign()],
        generations={_CAMPAIGN_ID: [_gen()]},
        candidates={_CAMPAIGN_ID: [
            _cand("c-hi", robust=2.0, trial=0),
            _cand("c-lo", robust=1.0, trial=1),
        ]},
    )
    client = _client(repo)
    first = _select(client, top_k=1).json()
    assert first["updated"] == 2  # both rows changed state SCORED -> SURVIVOR/RETIRED
    second = _select(client, top_k=1).json()
    assert second["updated"] == 0  # same split recomputed; nothing to write
    assert second["survivors"] == 1 and second["retired"] == 1


def test_select_never_demotes_an_advanced_state_candidate():
    # A candidate already in PAPER (published clone accruing live evidence) is excluded from the
    # selection entirely — never re-ranked, never demoted.
    repo = FakeEvoRepo(
        campaigns=[_campaign()],
        generations={_CAMPAIGN_ID: [_gen()]},
        candidates={_CAMPAIGN_ID: [
            _cand("c-paper", state="PAPER", robust=0.1, trial=0),
            _cand("c-new", robust=2.0, trial=1),
        ]},
    )
    client = _client(repo)
    body = _select(client, top_k=1).json()
    # only the SCORED candidate is selected; the PAPER one is untouched
    assert body["survivors"] == 1 and body["retired"] == 0
    cands = _cands_by_id(client)
    assert cands["c-paper"]["state"] == "PAPER"
    assert "selection" not in cands["c-paper"]["scorecard"]
    assert cands["c-new"]["state"] == "SURVIVOR"


def test_select_default_top_k_promotes_all_rankable():
    repo = FakeEvoRepo(
        campaigns=[_campaign()],
        generations={_CAMPAIGN_ID: [_gen()]},
        candidates={_CAMPAIGN_ID: [_cand(f"c-{i}", robust=float(i), trial=i) for i in range(3)]},
    )
    client = _client(repo)
    body = _select(client).json()  # default topK=15 > 3 rankable
    assert body["topK"] == 15
    assert body["survivors"] == 3 and body["retired"] == 0


# --- §8.2 RETIRE acknowledge-rows (slice 3) -----------------------------------------------------

def test_retired_candidates_get_auto_approved_retire_acks():
    repo = FakeEvoRepo(
        campaigns=[_campaign()],
        generations={_CAMPAIGN_ID: [_gen()]},
        candidates={_CAMPAIGN_ID: [
            _cand("c-hi", robust=2.0, trial=0),
            _cand("c-lo", robust=1.0, trial=1),
            _cand("c-fail", robust=9.9, rankable=False, trial=2,
                  gates=[{"id": "drawdown_cap", "status": "FAIL"}]),
        ]},
    )
    client = _client(repo)
    body = _select(client, top_k=1).json()
    # c-lo (dominated) + c-fail (gate FAIL) retire -> two auto-APPROVED RETIRE acknowledge-rows
    assert body["retired"] == 2 and body["retireAcks"] == 2
    acks = [p for p in repo.proposals if p["kind"] == "RETIRE"]
    assert len(acks) == 2
    for ack in acks:
        assert ack["status"] == "APPROVED"
        assert ack["actor"] == f"evo:{_CAMPAIGN_ID}"
        assert ack["evidence"]["decision"] == "RETIRED" and ack["evidence"]["reason"]
    # idempotent: a re-select finds the existing acks and mints none
    second = _select(client, top_k=1).json()
    assert second["retireAcks"] == 0
    assert len([p for p in repo.proposals if p["kind"] == "RETIRE"]) == 2


def test_select_unknown_campaign_is_404():
    client = _client(FakeEvoRepo())
    resp = _select(client)
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CAMPAIGN"


def test_select_unknown_generation_is_404():
    repo = FakeEvoRepo(campaigns=[_campaign()], generations={_CAMPAIGN_ID: []})
    client = _client(repo)
    resp = _select(client)
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_GENERATION"
