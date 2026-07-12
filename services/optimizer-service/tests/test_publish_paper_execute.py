"""The EVO E4 slice-2 APPROVED → PUBLISH_PAPER action (§8.2 materialization / §12 item 12): on an
APPROVED PUBLISH_PAPER proposal, create + publish the sibling evo paper-lane clone and stamp the
linkage back onto the candidate + proposal. Driven through a TestClient over ProposalService (+ the
recorder + selection) with the in-memory fakes — no Postgres, no strategy-signal service.

Covers: the FULL pipeline (record → score → select → generate → approve → EXECUTE) end-to-end; the
clone identity (§8.2 slug/name/tags + the candidate's tuned params overlaid); the SURVIVOR→PAPER
transition + version linkage; the proposal execution stamp; the §1.4.3 per-family cap refusal
(nothing created); a registry slug/name-collision 409; and the guardrail 409/422 idioms
(not-approved / already-executed / wrong-kind). The safety line: execution runs ONLY via the
explicit /execute call on an APPROVED proposal — nothing self-arms."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import evolution, proposals
from app.errors import ApiError, api_error_handler
from app.evolution import EvoReadService, EvoRecorderService, RetroScoreService
from app.proposals import ProposalService
from tests.fakes import FakeBacktest, FakeEvoRepo, FakeJobs, FakeNtfy, FakeStrategy, FakeTrials

_CAMPAIGN_ID = "camp-1"
_STRATEGY_ID = "11111111-1111-1111-1111-111111111111"

# The base version config the FakeStrategy returns — carries the leaf the candidate's tuned param
# (indicators[0].params.period) overlays, plus the id/name/tags the clone rewrite reads.
_CONFIG = {
    "id": "manas-arora-breakout",
    "name": "Manas Arora Breakout",
    "tags": ["manas-arora"],
    "indicators": [{"alias": "ema", "type": "ema", "params": {"period": 20}}],
}

# Positive, multi-regime OOS folds so the recorder scores rankable candidates (mirrors the recorder
# suite's fixture — both trials pass the SIM_FIRST hard gates).
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


def _app(repo, jobs, trials, backtest, strategy, cap=2) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    app.state.retro = scorer
    app.state.evo = EvoReadService(repo_factory=lambda: repo)
    app.state.evo_writer = EvoRecorderService(repo_factory=lambda: repo, scorer=scorer)
    app.state.proposals = ProposalService(
        repo_factory=lambda: repo, ntfy=FakeNtfy(),
        strategy_client=strategy, jobs_factory=lambda: jobs, evo_paper_cap=cap,
    )
    app.include_router(evolution.router)
    app.include_router(proposals.router)
    return TestClient(app)


# --- the full pipeline: record -> score -> select -> generate -> approve -> execute --------------

def test_end_to_end_record_score_select_generate_approve_execute():
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    strategy = FakeStrategy(_CONFIG)
    client = _app(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS), strategy)

    campaign_id = client.post("/api/v1/evolution/campaigns", json={
        "strategyId": _STRATEGY_ID, "family": "manas-arora", "evidencePolicy": "SIM_FIRST",
    }).json()["id"]

    # a completed sweep whose request carries strategyId + version (the execute base-config source)
    request = {"parameters": [], "objective": {"metric": "oos_fold_mean", "direction": "maximize"},
               "strategyId": _STRATEGY_ID, "strategyVersion": "1.0.1"}
    sweep_id = jobs.insert_sweep(None, request)
    for i in range(2):
        row_id = trials.insert(sweep_id, i, {"indicators[0].params.period": 10 + i})
        trials.complete(row_id, {"oos_fold_mean": 1.0}, f"run-{i}")
    jobs.set_status(sweep_id, "completed", 100)

    # record + score
    gen = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                      json={"sweepJobId": sweep_id}).json()
    assert gen["candidatesRecorded"] == 2

    # select top-1 -> exactly one SURVIVOR
    sel = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations/1/select",
                      json={"topK": 1}).json()
    assert sel["survivors"] == 1 and sel["retired"] == 1

    # generate proposals -> one PUBLISH_PAPER for the survivor (no champion -> pendingInput, still
    # generated end-to-end because selection produced a SURVIVOR)
    props = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/proposals").json()
    assert props["generated"] == 1
    pid = props["items"][0]["id"]

    # approve — marks the row; still arms nothing
    approve = client.post(f"/api/v1/evolution/proposals/{pid}/approve").json()
    assert approve["proposal"]["status"] == "APPROVED"

    # EXECUTE — the explicit owner-clicked action creates + publishes the clone
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 200, resp.text
    ex = resp.json()
    assert ex["candidateState"] == "PAPER"
    assert ex["clonedSlug"].startswith("manas-arora-breakout--evo-g1-")
    assert ex["clonedTags"][:2] == ["manas-arora", "evo"]   # family FIRST, evo SECOND (§8.2)
    assert ex["clonedVersionId"] is not None

    # the clone was created (draft) then published; its config is the base + the tuned param, with
    # the sibling identity rewritten
    assert len(strategy.created) == 1
    created = strategy.created[0]
    assert created["config"]["id"] == ex["clonedSlug"]
    assert created["config"]["tags"][:2] == ["manas-arora", "evo"]
    assert created["config"]["indicators"][0]["params"]["period"] == 10  # trial-0 tuned value
    assert created["createdBy"] == "evo:manas-arora"
    assert strategy.published == [ex["clonedStrategyId"]]

    # the candidate advanced SURVIVOR -> PAPER and links to the published clone version
    paper = [c for c in repo.candidates[campaign_id] if c["state"] == "PAPER"]
    assert len(paper) == 1
    assert paper[0]["versionId"] == ex["clonedVersionId"]

    # the proposal carries the execution linkage (append-only stamp)
    prop = next(p for p in repo.proposals if p["id"] == pid)
    assert prop["evidence"]["execution"]["executed"] is True
    assert prop["evidence"]["execution"]["clonedStrategyId"] == ex["clonedStrategyId"]


# --- direct-seed error / guard paths ------------------------------------------------------------

def _campaign():
    return {"id": _CAMPAIGN_ID, "strategyId": _STRATEGY_ID, "family": "manas-arora",
            "evidencePolicy": "SIM_FIRST", "objectiveSpec": None, "searchSpace": None,
            "budget": None, "status": "ACTIVE", "championVersionId": None,
            "createdAt": "2026-07-11T00:00:00+00:00", "updatedAt": "2026-07-11T00:00:00+00:00"}


def _generation():
    return {"id": "gen-1", "campaignId": _CAMPAIGN_ID, "n": 1, "proposal": None,
            "searchSpaceHash": None, "engineSha": None, "dataEpoch": None, "stressTouches": 0,
            "status": "DONE", "startedAt": None, "finishedAt": None}


def _seed(repo, jobs, *, cand_state="SURVIVOR", prop_status="APPROVED", kind="PUBLISH_PAPER",
          candidate_id="c-surv", executed=False, with_sweep=True, sweep_version="1.0.1"):
    repo.campaigns.append(_campaign())
    repo.generations[_CAMPAIGN_ID] = [_generation()]
    sweep_id = None
    if with_sweep:
        request = {"parameters": [], "objective": {"metric": "oos_fold_mean"},
                   "strategyId": _STRATEGY_ID}
        if sweep_version is not None:
            request["strategyVersion"] = sweep_version
        sweep_id = jobs.insert_sweep(None, request)
        jobs.set_status(sweep_id, "completed", 100)
    if candidate_id is not None:
        repo.candidates[_CAMPAIGN_ID] = [{
            "id": candidate_id, "generationId": "gen-1", "versionId": None,
            "parentCandidateId": None, "mutationKind": "PARAMS",
            "params": {"indicators[0].params.period": 10}, "structureDiff": None,
            "sweepJobId": sweep_id, "holdoutRunId": None,
            "scorecard": {"robustScore": 1.5, "rankable": True,
                          "gates": [{"id": "oos_sign", "status": "PASS"}]},
            "state": cand_state, "updatedAt": "2026-07-11T00:00:00+00:00",
        }]
    evidence = {"kind": kind, "candidateId": candidate_id}
    if executed:
        evidence["execution"] = {"executed": True, "clonedSlug": "prev--evo-g1-old",
                                 "clonedStrategyId": "prev-clone"}
    repo.proposals.append({
        "_seq": 1, "id": "prop-1", "campaignId": _CAMPAIGN_ID, "candidateId": candidate_id,
        "kind": kind, "evidence": evidence, "status": prop_status,
        "actor": "owner" if prop_status != "PENDING" else None, "decidedAt": None,
        "expiresAt": None, "createdAt": "2026-07-11T00:00:00+00:00",
    })
    return "prop-1"


def _existing_evo_clone(cid, family="manas-arora"):
    return {"id": cid, "slug": f"{cid}-slug", "name": cid, "tags": [family, "evo"],
            "status": "published", "versionId": f"ver-{cid}", "version": "1.0.0"}


def test_execute_refuses_past_the_family_cap_and_creates_nothing():
    repo, jobs = FakeEvoRepo(), FakeJobs()
    # two live evo clones already in the family book -> cap (2) reached
    strategy = FakeStrategy(_CONFIG, existing=[_existing_evo_clone("evo-a"),
                                              _existing_evo_clone("evo-b")])
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), strategy, cap=2)
    pid = _seed(repo, jobs)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 409, resp.text
    assert resp.json()["code"] == "EVO_PAPER_CAP_REACHED"
    # nothing was created or published — the cap is enforced BEFORE the registry write
    assert strategy.created == [] and strategy.published == []


def test_execute_cap_counts_only_the_same_family():
    repo, jobs = FakeEvoRepo(), FakeJobs()
    # two evo clones exist but in a DIFFERENT family — the manas-arora book is still empty
    strategy = FakeStrategy(_CONFIG, existing=[_existing_evo_clone("evo-x", family="scalper"),
                                              _existing_evo_clone("evo-y", family="scalper")])
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), strategy, cap=2)
    pid = _seed(repo, jobs)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 200, resp.text
    assert len(strategy.created) == 1


def test_execute_cap_exempts_demoted_champion_counterfactuals():
    # §1.4.3 cap governs NEW candidate clones only: a demoted-champion counterfactual (the
    # `counterfactual` tag — §8.2 MANDATES its 6-week retention) must not consume a budget slot,
    # so 1 candidate clone + 1 counterfactual in the family leaves room at cap 2.
    repo, jobs = FakeEvoRepo(), FakeJobs()
    cf_clone = _existing_evo_clone("evo-cf")
    cf_clone["tags"] = ["manas-arora", "evo", "counterfactual"]
    strategy = FakeStrategy(_CONFIG, existing=[_existing_evo_clone("evo-a"), cf_clone])
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), strategy, cap=2)
    pid = _seed(repo, jobs)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 200, resp.text
    assert len(strategy.created) == 1


def test_execute_resolves_the_base_config_when_the_sweep_pins_no_version():
    # a sweep that omitted strategyVersion (optimizer pinned latest) → execute resolves the base
    # config via the registry's current resolution, not a hard version fetch.
    repo, jobs = FakeEvoRepo(), FakeJobs()
    strategy = FakeStrategy(_CONFIG)
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), strategy)
    pid = _seed(repo, jobs, sweep_version=None)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 200, resp.text
    assert len(strategy.created) == 1


def test_execute_maps_a_registry_slug_collision_to_409():
    repo, jobs = FakeEvoRepo(), FakeJobs()
    strategy = FakeStrategy(_CONFIG, create_conflict=True)
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), strategy)
    pid = _seed(repo, jobs)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 409
    assert resp.json()["code"] == "CLONE_ALREADY_EXISTS"


def test_execute_on_a_not_yet_approved_proposal_is_409():
    repo, jobs = FakeEvoRepo(), FakeJobs()
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), FakeStrategy(_CONFIG))
    pid = _seed(repo, jobs, prop_status="PENDING")
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 409
    assert resp.json()["code"] == "PROPOSAL_NOT_APPROVED"


def test_execute_is_not_double_publishable():
    repo, jobs = FakeEvoRepo(), FakeJobs()
    strategy = FakeStrategy(_CONFIG)
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), strategy)
    pid = _seed(repo, jobs, executed=True)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 409
    assert resp.json()["code"] == "PROPOSAL_ALREADY_EXECUTED"
    assert strategy.created == []


def test_execute_rejects_a_non_executable_kind():
    # RETIRE rows are auto-APPROVED acknowledge items, never executed (PROMOTE/ROLLBACK ARE
    # executable as of slice 3, so the unexecutable case is now RETIRE).
    repo, jobs = FakeEvoRepo(), FakeJobs()
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), FakeStrategy(_CONFIG))
    pid = _seed(repo, jobs, kind="RETIRE")
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 422
    assert resp.json()["code"] == "PROPOSAL_KIND_NOT_EXECUTABLE"


def test_execute_unknown_proposal_is_404():
    repo, jobs = FakeEvoRepo(), FakeJobs()
    client = _app(repo, jobs, FakeTrials(), FakeBacktest(folds=[]), FakeStrategy(_CONFIG))
    resp = client.post("/api/v1/evolution/proposals/nope/execute")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_PROPOSAL"


def test_execute_without_wired_registry_client_is_500():
    # the substrate-only construction (no strategy client / jobs factory) refuses honestly rather
    # than half-executing.
    service = ProposalService(repo_factory=lambda: FakeEvoRepo(), ntfy=FakeNtfy())
    try:
        service.execute("prop-1", "owner")
        raise AssertionError("expected ApiError")
    except ApiError as exc:
        assert exc.status == 500 and exc.code == "EXECUTE_NOT_WIRED"
