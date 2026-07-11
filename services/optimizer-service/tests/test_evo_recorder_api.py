"""The §12 E1 campaign/generation RECORDER — the two write endpoints
(POST /api/v1/evolution/campaigns and POST /api/v1/evolution/campaigns/{id}/generations) — driven
through a TestClient over EvoRecorderService + the in-memory fakes (no Postgres, no backtest svc).

Covers: campaign creation + evidence-policy validation; recording a completed sweep as generation
n (cohort scored into SCORED candidates); the §3.1 pre-registration proposal freeze; engine-SHA /
data-epoch / job-timestamp lifting; incrementing n; the SIM_BLOCKED refusal-before-scoring
keystone; the LIVE_FIRST descriptive caveat; sweep idempotency (409); the 404 idioms; and that the
persisted scorecard is byte-identical to the retro-score read (evidence the assembly is reused, not
duplicated)."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import evolution
from app.errors import ApiError, api_error_handler
from app.evolution import EvoReadService, EvoRecorderService, RetroScoreService
from tests.fakes import FakeBacktest, FakeEvoRepo, FakeJobs, FakeTrials

# Three OOS folds over three regimes (mirrors the retro-score suite) so gates can be assessed.
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


def _client(
    repo: FakeEvoRepo, jobs: FakeJobs, trials: FakeTrials, backtest: FakeBacktest
) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    app.state.retro = scorer
    app.state.evo = EvoReadService(repo_factory=lambda: repo)
    app.state.evo_writer = EvoRecorderService(repo_factory=lambda: repo, scorer=scorer)
    app.include_router(evolution.router)
    return TestClient(app)


def _seed_sweep(jobs: FakeJobs, trials: FakeTrials, n: int = 2) -> str:
    """A walk-forward sweep (objective oos_fold_mean); each trial COMPLETE with a run id."""
    request = {"parameters": [], "objective": {"metric": "oos_fold_mean", "direction": "maximize"}}
    sweep_id = jobs.insert_sweep(None, request)
    for i in range(n):
        row_id = trials.insert(sweep_id, i, {"period": 10 + i})
        trials.complete(row_id, {"oos_fold_mean": 1.0}, f"run-{i}")
    return sweep_id


def _create_campaign(client: TestClient, policy: str = "SIM_FIRST") -> str:
    resp = client.post("/api/v1/evolution/campaigns", json={
        "strategyId": "11111111-1111-1111-1111-111111111111",
        "family": "manas-arora",
        "evidencePolicy": policy,
        "budget": {"maxGenerations": 8},
    })
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


# --- create_campaign ----------------------------------------------------------------------------

def test_create_campaign_returns_active_campaign():
    client = _client(FakeEvoRepo(), FakeJobs(), FakeTrials(), FakeBacktest(folds=[]))
    resp = client.post("/api/v1/evolution/campaigns", json={
        "strategyId": "11111111-1111-1111-1111-111111111111",
        "family": "manas-arora",
        "evidencePolicy": "SIM_FIRST",
        "objectiveSpec": {"weights": {"oos_return": 0.22}},
        "budget": {"maxGenerations": 8},
    })
    assert resp.status_code == 201
    body = resp.json()
    assert body["status"] == "ACTIVE"
    assert body["evidencePolicy"] == "SIM_FIRST"
    assert body["family"] == "manas-arora"
    assert body["budget"] == {"maxGenerations": 8}          # jsonb passthrough
    assert body["objectiveSpec"] == {"weights": {"oos_return": 0.22}}
    assert body["championVersionId"] is None


def test_create_campaign_unknown_evidence_policy_is_422():
    client = _client(FakeEvoRepo(), FakeJobs(), FakeTrials(), FakeBacktest(folds=[]))
    resp = client.post("/api/v1/evolution/campaigns", json={
        "strategyId": "11111111-1111-1111-1111-111111111111",
        "family": "manas-arora",
        "evidencePolicy": "SIM_MAYBE",
    })
    assert resp.status_code == 422
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_create_campaign_missing_field_is_400():
    client = _client(FakeEvoRepo(), FakeJobs(), FakeTrials(), FakeBacktest(folds=[]))
    resp = client.post("/api/v1/evolution/campaigns", json={"family": "manas-arora",
                                                            "evidencePolicy": "SIM_FIRST"})
    assert resp.status_code == 400
    assert "strategyId" in resp.json()["message"]


# --- record_generation (happy path + persistence) -----------------------------------------------

def test_record_generation_scores_and_persists_candidates():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS))
    campaign_id = _create_campaign(client)
    sweep_id = _seed_sweep(jobs, trials, n=2)

    resp = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                       json={"sweepJobId": sweep_id})
    assert resp.status_code == 201, resp.text
    gen = resp.json()
    assert gen["n"] == 1
    assert gen["status"] == "DONE"
    assert gen["candidatesRecorded"] == 2

    # the candidate rows surface through the EXISTING read endpoint (no new read shape added)
    cands = client.get(f"/api/v1/evolution/campaigns/{campaign_id}/candidates").json()["items"]
    assert len(cands) == 2
    for cand in cands:
        assert cand["state"] == "SCORED"
        assert cand["mutationKind"] == "PARAMS"
        assert cand["versionId"] is None                     # registry materialization is E2+
        assert cand["sweepJobId"] == sweep_id
        assert cand["scorecard"]["robustScore"] is not None   # the §6.3 card persisted verbatim
        assert "gates" in cand["scorecard"]


def test_record_generation_freezes_proposal_and_lifts_provenance():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS))
    campaign_id = _create_campaign(client)
    sweep_id = _seed_sweep(jobs, trials, n=1)
    # the source sweep job carries started/finished timestamps — the generation lifts them
    jobs.rows[sweep_id]["startedAt"] = "2026-07-10T09:00:00+00:00"
    jobs.rows[sweep_id]["finishedAt"] = "2026-07-10T09:30:00+00:00"

    gen = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                     json={"sweepJobId": sweep_id}).json()
    # proposal is the §3.1 pre-registration ledger: sampler-context frozen from the sweep
    assert gen["proposal"] == {
        "source": "manual",
        "sweepJobId": sweep_id,
        "objective": {"metric": "oos_fold_mean", "direction": "maximize"},
        "direction": "maximize",
        "parameters": [],
    }
    # engine_sha / data_epoch lifted from the sweep's run evidence
    assert gen["engineSha"] == "sha-1"
    assert gen["dataEpoch"] == {"dataHash": "hash-1"}
    # started/finished lifted from the source job timestamps
    assert gen["startedAt"] == "2026-07-10T09:00:00+00:00"
    assert gen["finishedAt"] == "2026-07-10T09:30:00+00:00"


def test_record_generation_null_engine_sha_when_run_evidence_absent():
    # A sweep whose runs predate #703 SHA-stamping (no engineSha/dataHash on results) records with
    # a NULL engine_sha / data_epoch — provenance degrades, it is never fabricated.
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    results_no_sha = {"metrics": {"totalReturn": "12.0"}, "caveats": []}
    client = _client(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=results_no_sha))
    campaign_id = _create_campaign(client)
    sweep_id = _seed_sweep(jobs, trials, n=1)
    gen = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                     json={"sweepJobId": sweep_id}).json()
    assert gen["engineSha"] is None
    assert gen["dataEpoch"] is None


def test_record_generation_assigns_incrementing_n():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS))
    campaign_id = _create_campaign(client)
    first = _seed_sweep(jobs, trials, n=1)
    second = _seed_sweep(jobs, trials, n=1)
    n1 = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                    json={"sweepJobId": first}).json()["n"]
    n2 = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                    json={"sweepJobId": second}).json()["n"]
    assert (n1, n2) == (1, 2)


def test_record_generation_empty_sweep_records_zero_candidates():
    # A sweep with no COMPLETE trials still records a generation — an honest empty iteration, not a
    # crash (the 409 idempotency then guards against re-recording it once trials land).
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=[]))
    campaign_id = _create_campaign(client)
    request = {"parameters": [], "objective": {"metric": "sharpe"}}
    sweep_id = jobs.insert_sweep(None, request)  # zero trials
    resp = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                      json={"sweepJobId": sweep_id})
    assert resp.status_code == 201
    assert resp.json()["candidatesRecorded"] == 0


# --- the evidence-policy keystone + idempotency + 404s -------------------------------------------

def test_record_generation_sim_blocked_refuses_before_scoring():
    # The design keystone: a SIM_BLOCKED campaign 422s and the engine NEVER scores — no backtest
    # evidence is read, no generation/candidate is written.
    repo = FakeEvoRepo(campaigns=[{"id": "camp-blocked", "evidencePolicy": "SIM_BLOCKED"}])
    jobs, trials = FakeJobs(), FakeTrials()
    backtest = FakeBacktest(folds=_FOLDS, results=_RESULTS)
    client = _client(repo, jobs, trials, backtest)
    sweep_id = _seed_sweep(jobs, trials, n=2)
    resp = client.post("/api/v1/evolution/campaigns/camp-blocked/generations",
                      json={"sweepJobId": sweep_id})
    assert resp.status_code == 422
    assert resp.json()["code"] == "EVIDENCE_POLICY_BLOCKED"
    assert backtest.calls == []              # scoring never touched the backtest evidence plane
    assert repo.generations == {}            # nothing persisted
    assert repo.candidates == {}


def test_record_generation_live_first_stamps_descriptive_caveat():
    # LIVE_FIRST allows recording, but every persisted scorecard is caveated: sim evidence is
    # functional-smoke only for this family and never ranks (§1.2).
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS))
    campaign_id = _create_campaign(client, policy="LIVE_FIRST")
    sweep_id = _seed_sweep(jobs, trials, n=2)
    resp = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                      json={"sweepJobId": sweep_id})
    assert resp.status_code == 201
    cands = client.get(f"/api/v1/evolution/campaigns/{campaign_id}/candidates").json()["items"]
    assert cands
    for cand in cands:
        assert any("retro-score is descriptive" in c for c in cand["scorecard"]["caveats"])


def test_record_generation_duplicate_sweep_is_409():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS))
    campaign_id = _create_campaign(client)
    sweep_id = _seed_sweep(jobs, trials, n=1)
    first = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                       json={"sweepJobId": sweep_id})
    assert first.status_code == 201
    dup = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                     json={"sweepJobId": sweep_id})
    assert dup.status_code == 409
    assert dup.json()["code"] == "CONFLICT_SWEEP_RECORDED"
    # still exactly one generation recorded
    detail = client.get(f"/api/v1/evolution/campaigns/{campaign_id}").json()
    assert len(detail["generations"]) == 1


def test_record_generation_unknown_campaign_is_404():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS))
    sweep_id = _seed_sweep(jobs, trials, n=1)
    resp = client.post("/api/v1/evolution/campaigns/nope/generations",
                      json={"sweepJobId": sweep_id})
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_CAMPAIGN"


def test_record_generation_unknown_sweep_is_404():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=[]))
    campaign_id = _create_campaign(client)
    resp = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                      json={"sweepJobId": "no-such-sweep"})
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_JOB"


def test_record_generation_missing_sweep_id_is_400():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=[]))
    campaign_id = _create_campaign(client)
    resp = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations", json={})
    assert resp.status_code == 400
    assert "sweepJobId" in resp.json()["message"]


# --- reuse: the persisted scorecard is identical to the retro-score read ------------------------

def test_persisted_scorecard_matches_retro_score_read():
    # Evidence the recorder REUSES the retro assembly + score_cohort (not a re-implementation): the
    # SIM_FIRST persisted scorecard equals the retro-score card for the same sweep — modulo the
    # standing retro caveat, which the read always appends but the SIM_FIRST recorder does not.
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    client = _client(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS))
    campaign_id = _create_campaign(client)
    sweep_id = _seed_sweep(jobs, trials, n=2)
    client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
               json={"sweepJobId": sweep_id})

    retro = {i["trialNumber"]: i
             for i in client.get(f"/api/v1/evolution/retro-score/{sweep_id}").json()["items"]}
    persisted = {c["scorecard"]["trialNumber"]: c["scorecard"]
                 for c in client.get(
                     f"/api/v1/evolution/campaigns/{campaign_id}/candidates").json()["items"]}
    assert retro.keys() == persisted.keys()
    for trial_no, retro_card in retro.items():
        card = persisted[trial_no]
        # Same scoring output (the read is Pydantic-serialized, so it injects note=None on gates —
        # compare the substance: RobustScore, gate verdicts, and component z's are byte-identical).
        assert card["robustScore"] == retro_card["robustScore"]
        assert card["params"] == retro_card["params"]
        assert {g["id"]: g["status"] for g in card["gates"]} == \
               {g["id"]: g["status"] for g in retro_card["gates"]}
        assert {c["id"]: c["z"] for c in card["components"]} == \
               {c["id"]: c["z"] for c in retro_card["components"]}
        # SIM_FIRST persists no retro caveat; the read always carries it
        assert not any("retro-score is descriptive" in c for c in card["caveats"])
        assert any("retro-score is descriptive" in c for c in retro_card["caveats"])
