"""EVO E4 slice 3 (§8.1-8.2 / §12 item 13): TAKE_ELIGIBLE assessment → PROMOTE proposal + execute
(champion move + demoted-champion counterfactual), ROLLBACK trigger + execute, and the RETIRE
acknowledge-rows. Driven through a TestClient over the recorder + selection + ProposalService with
the in-memory fakes — no Postgres, no strategy-signal service.

The headline test walks the FULL §8.1 state machine end-to-end on a SEEDED FIXTURE campaign
(record → score → select → PUBLISH_PAPER execute → seeded paper evidence → TAKE_ELIGIBLE → PROMOTE
approve+execute → ROLLBACK trigger → ROLLBACK approve+execute), the §12 item-13 verify. Every
registry-touching transition happens ONLY in an owner-clicked execute endpoint — nothing self-arms.
"""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import evolution, proposals
from app.errors import ApiError, api_error_handler
from app.evolution import EvoReadService, EvoRecorderService, RetroScoreService
from app.proposals import ProposalService
from tests.fakes import (
    FakeBacktest,
    FakeEvoRepo,
    FakeJobs,
    FakeLiveRepo,
    FakeNtfy,
    FakeReconRepo,
    FakeStrategy,
    FakeTrials,
)

_STRATEGY_ID = "11111111-1111-1111-1111-111111111111"
_CAMPAIGN_ID = "camp-1"

# The base version config (carries the leaf the candidate's tuned param overlays + id/name/tags).
_CONFIG = {
    "id": "manas-arora-breakout",
    "name": "Manas Arora Breakout",
    "tags": ["manas-arora"],
    "indicators": [{"alias": "ema", "type": "ema", "params": {"period": 20}}],
}

# Positive multi-regime OOS folds so the recorder scores rankable candidates. A per-fold OOS Sharpe
# is carried so the REQUIRED multiplicity_tstat gate is affirmatively evaluated (audit PF-01) — an
# absent Sharpe would leave it SKIPPED, which no longer reads as a pass.
_FOLDS = [
    {"fold": 0, "regimeOos": {"UP_QUIET": {"sharpe": "1.0"}},
     "oosMetrics": {"totalReturn": "5.0", "sortino": "1.2", "sharpe": "1.2", "maxDrawdown": "10.0",
                    "maxDrawdownDurationBars": 3, "expectancy": "100.0", "tradeCount": 40}},
    {"fold": 1, "regimeOos": {"DOWN_QUIET": {"sharpe": "0.6"}},
     "oosMetrics": {"totalReturn": "7.0", "sortino": "1.4", "sharpe": "1.0", "maxDrawdown": "12.0",
                    "maxDrawdownDurationBars": 4, "expectancy": "120.0", "tradeCount": 45}},
    {"fold": 2, "regimeOos": {"UP_TURBULENT": {"sharpe": "0.5"}},
     "oosMetrics": {"totalReturn": "3.0", "sortino": "0.9", "sharpe": "0.8", "maxDrawdown": "8.0",
                    "maxDrawdownDurationBars": 2, "expectancy": "90.0", "tradeCount": 30}},
]
_RESULTS = {"metrics": {"totalReturn": "12.0", "maxDrawdown": "12.0", "tradeCount": 115},
            "dataHash": "hash-1", "engineSha": "sha-1", "caveats": []}

# The clone's config as read back over the live-evidence repo — carries a capital base at the
# CANONICAL, schema-VALID path (backtest.defaults.initial_capital; top-level `capital` is rejected
# by additionalProperties:false — audit finding #2) so the TAKE_ELIGIBLE maxDD gate computes.
_CLONE_CONFIG = {
    "id": "manas-arora-breakout--evo-g1-x",
    "backtest": {"defaults": {"initial_capital": 100000}},
}


def _paper_book(n_win=14, n_loss=6, win=100.0, loss=-50.0):
    """A closed paper book: winners then losers (a small end-of-run drawdown), each carrying a
    realizedPnl + an increasing closedAt (the equity-curve order). PF = (14*100)/(6*50) = 4.67,
    expectancy = 55, n = 20 — clears the F7 bar; maxDD tiny vs a 100k base."""
    book = []
    stamp = 0
    for _ in range(n_win):
        stamp += 1
        book.append({"tradingsymbol": "X", "side": "BUY", "avgEntryPrice": 100.0,
                     "openedAt": f"2026-07-{stamp:02d}T09:20:00+05:30",
                     "closedAt": f"2026-07-{stamp:02d}T15:00:00+05:30",
                     "realizedPnl": win, "closeReason": "TARGET", "status": "CLOSED",
                     "scalperDetail": None})
    for _ in range(n_loss):
        stamp += 1
        book.append({"tradingsymbol": "X", "side": "BUY", "avgEntryPrice": 100.0,
                     "openedAt": f"2026-07-{stamp:02d}T09:20:00+05:30",
                     "closedAt": f"2026-07-{stamp:02d}T15:00:00+05:30",
                     "realizedPnl": loss, "closeReason": "STOP", "status": "CLOSED",
                     "scalperDetail": None})
    return book


def _app(repo, jobs, trials, backtest, strategy, live, recon, cap=2) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    scorer = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    app.state.retro = scorer
    app.state.evo = EvoReadService(repo_factory=lambda: repo)
    app.state.evo_writer = EvoRecorderService(repo_factory=lambda: repo, scorer=scorer)
    app.state.proposals = ProposalService(
        repo_factory=lambda: repo, ntfy=FakeNtfy(),
        strategy_client=strategy, jobs_factory=lambda: jobs, evo_paper_cap=cap,
        live_factory=lambda: live, recon_factory=lambda: recon,
    )
    app.include_router(evolution.router)
    app.include_router(proposals.router)
    return TestClient(app)


# --- the FULL §8.1 state-machine walk on a seeded fixture (the §12 item-13 verify) --------------

def test_full_state_machine_walk_on_fixture(capsys):
    repo, jobs, trials = FakeEvoRepo(), FakeJobs(), FakeTrials()
    strategy = FakeStrategy(_CONFIG)
    live, recon = FakeLiveRepo(), FakeReconRepo()
    client = _app(repo, jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS),
                  strategy, live, recon)
    trace: list[str] = []

    # 1. record → score
    campaign_id = client.post("/api/v1/evolution/campaigns", json={
        "strategyId": _STRATEGY_ID, "family": "manas-arora", "evidencePolicy": "SIM_FIRST",
    }).json()["id"]
    request = {"parameters": [], "objective": {"metric": "oos_fold_mean", "direction": "maximize"},
               "strategyId": _STRATEGY_ID, "strategyVersion": "1.0.1"}
    sweep_id = jobs.insert_sweep(None, request)
    # 5 trials so the now-REQUIRED stability_floor gate (≥4 plateau neighbors — audit PF-01) is
    # affirmatively assessable and the survivors are genuinely stage-ready.
    for i in range(5):
        row_id = trials.insert(sweep_id, i, {"indicators[0].params.period": 10 + i})
        trials.complete(row_id, {"oos_fold_mean": 1.0}, f"run-{i}")
    jobs.set_status(sweep_id, "completed", 100)
    gen = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations",
                      json={"sweepJobId": sweep_id}).json()
    trace.append(f"1. RECORD+SCORE  -> generation {gen['n']}, {gen['candidatesRecorded']} SCORED")

    # 2. select top-1 -> 1 SURVIVOR, 4 RETIRED (+ 4 auto-APPROVED RETIRE acks)
    sel = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/generations/1/select",
                      json={"topK": 1}).json()
    trace.append(
        f"2. SELECT topK=1 -> survivors={sel['survivors']} retired={sel['retired']} "
        f"retireAcks={sel['retireAcks']}"
    )
    assert sel["survivors"] == 1 and sel["retired"] == 4 and sel["retireAcks"] == 4

    # 3. PUBLISH_PAPER: generate -> approve -> execute (SURVIVOR -> PAPER)
    props = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/proposals").json()
    pp_id = props["items"][0]["id"]
    client.post(f"/api/v1/evolution/proposals/{pp_id}/approve")
    ex = client.post(f"/api/v1/evolution/proposals/{pp_id}/execute").json()
    clone_version_id = ex["clonedVersionId"]
    trace.append(
        f"3. PUBLISH_PAPER  -> candidate {ex['candidateState']} on clone {ex['clonedSlug']} "
        f"(version {clone_version_id})"
    )
    assert ex["candidateState"] == "PAPER"

    # 4. seed the evo clone's LIVE paper evidence (F7-clearing book + an ALIGNED reconciliation)
    live.versions[clone_version_id] = {"strategyId": "sclone", "version": "1.0.0",
                                       "config": _CLONE_CONFIG}
    live.trades[clone_version_id] = _paper_book()
    recon.insert(version_id=clone_version_id, strategy_id="sclone",
                 window_from="w0", window_to="w1", sim_job_id=None, sim_run_id=None,
                 gap={"returnGap": 0.0}, gap_z=0.1, paired_trades=25, evidence_floor_met=True,
                 verdict="ALIGNED", diagnosis=None)
    trace.append("4. SEED EVIDENCE -> 20 closed paper trades (PF 4.67, E 55) + ALIGNED recon")

    # 5. TAKE_ELIGIBLE assessment (PAPER -> TAKE_ELIGIBLE + a PROMOTE proposal)
    te = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/take-eligible").json()
    trace.append(
        f"5. TAKE_ELIGIBLE -> assessed={te['assessed']} eligible={te['eligible']} "
        f"generated={te['generated']} (PROMOTE proposal)"
    )
    assert te["assessed"] == 1 and te["eligible"] == 1 and te["generated"] == 1
    promote_id = te["items"][0]["id"]
    cand = client.get(
        f"/api/v1/evolution/campaigns/{campaign_id}/candidates"
    ).json()["items"]
    paper_cand = next(c for c in cand if c["state"] == "TAKE_ELIGIBLE")
    assert paper_cand["scorecard"]["takeEligible"]["gates"]

    # 6. PROMOTE: approve -> execute (TAKE_ELIGIBLE -> PROMOTED, champion moves, counterfactual
    # registered, the now-redundant evo PAPER clone archived)
    client.post(f"/api/v1/evolution/proposals/{promote_id}/approve")
    pr = client.post(f"/api/v1/evolution/proposals/{promote_id}/execute").json()
    trace.append(
        f"6. PROMOTE       -> candidate {pr['candidateState']}, champion -> "
        f"{pr['championVersionId']}, counterfactual={pr['counterfactual']['mode']} "
        f"(demoted {pr['demotedChampionVersion']}), clone archived="
        f"{pr['cloneArchive']['archived']}"
    )
    assert pr["candidateState"] == "PROMOTED"
    assert pr["baseStrategyId"] == _STRATEGY_ID
    assert pr["counterfactual"]["mode"] == "retained-paper-clone"  # swing (SIM_FIRST)
    # champion pointer moved on the campaign; the demoted champion is retained (a cf paper clone)
    camp = client.get(f"/api/v1/evolution/campaigns/{campaign_id}").json()
    assert camp["championVersionId"] == pr["championVersionId"]
    cf_created = [c for c in strategy.created if "evo-cf" in c["config"]["id"]]
    assert len(cf_created) == 1 and cf_created[0]["config"]["tags"][-1] == "counterfactual"
    # the candidate's own evo PAPER clone (step 3) is now redundant — archived
    assert strategy.archived == [ex["clonedStrategyId"]]
    assert pr["cloneArchive"] == {"archived": True, "clonedStrategyId": ex["clonedStrategyId"]}

    promoted_version_id = pr["championVersionId"]

    # 7. seed 2 consecutive DIVERGENT reconciliations on the promoted version (§7.3)
    for _ in range(2):
        recon.insert(version_id=promoted_version_id, strategy_id="s", window_from="a",
                     window_to="b", sim_job_id=None, sim_run_id=None, gap={"returnGap": -9.0},
                     gap_z=-2.0, paired_trades=30, evidence_floor_met=True, verdict="DIVERGENT",
                     diagnosis=None)
    trace.append("7. SEED DRIFT    -> 2 consecutive DIVERGENT reconciliations on the champion")

    # 8. ROLLBACK check (DIVERGENT x2 -> a ROLLBACK proposal)
    rc = client.post(f"/api/v1/evolution/campaigns/{campaign_id}/rollback-check").json()
    trace.append(
        f"8. ROLLBACK-CHK  -> assessed={rc['assessed']} triggered={rc['eligible']} "
        f"generated={rc['generated']}"
    )
    assert rc["assessed"] == 1 and rc["eligible"] == 1 and rc["generated"] == 1
    rollback_id = rc["items"][0]["id"]

    # 9. ROLLBACK: approve -> execute (PROMOTED -> ROLLED_BACK, champion restored)
    client.post(f"/api/v1/evolution/proposals/{rollback_id}/approve")
    rb = client.post(f"/api/v1/evolution/proposals/{rollback_id}/execute").json()
    trace.append(
        f"9. ROLLBACK      -> candidate {rb['candidateState']}, restored version "
        f"{rb['restoredVersion']} on {rb['baseStrategyId']}"
    )
    assert rb["candidateState"] == "ROLLED_BACK"
    assert rb["baseStrategyId"] == _STRATEGY_ID and rb["restoredVersion"] == "1.0.0"
    assert strategy.rollbacks == [
        {"strategyId": _STRATEGY_ID, "version": "1.0.0", "andPublish": True}
    ]
    # champion pointer restored to the copy-forward version the rollback published
    camp2 = client.get(f"/api/v1/evolution/campaigns/{campaign_id}").json()
    assert camp2["championVersionId"] == f"rb-{_STRATEGY_ID}"

    with capsys.disabled():
        print("\n--- EVO E4 slice-3 fixture state-machine walk ---")
        for line in trace:
            print("  " + line)


# --- TAKE_ELIGIBLE assessment unit paths --------------------------------------------------------

def _campaign(policy="SIM_FIRST", champion=None):
    return {"id": _CAMPAIGN_ID, "strategyId": _STRATEGY_ID, "family": "manas-arora",
            "evidencePolicy": policy, "objectiveSpec": None, "searchSpace": None, "budget": None,
            "status": "ACTIVE", "championVersionId": champion,
            "createdAt": "2026-07-11T00:00:00+00:00", "updatedAt": "2026-07-11T00:00:00+00:00"}


def _paper_candidate(cid="c-paper", version_id="ver-clone", state="PAPER", robust=1.5):
    return {"id": cid, "generationId": "gen-1", "versionId": version_id,
            "parentCandidateId": None, "mutationKind": "PARAMS",
            "params": {"indicators[0].params.period": 10}, "structureDiff": None,
            "sweepJobId": "sweep-1", "holdoutRunId": None,
            "scorecard": {"robustScore": robust, "rankable": True,
                          "gates": [{"id": "oos_sign", "status": "PASS"}]},
            "state": state, "updatedAt": "2026-07-11T00:00:00+00:00"}


def _te_app(repo, live, recon):
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.evo = EvoReadService(repo_factory=lambda: repo)
    app.state.proposals = ProposalService(
        repo_factory=lambda: repo, ntfy=FakeNtfy(),
        strategy_client=FakeStrategy(_CONFIG), jobs_factory=lambda: FakeJobs(),
        live_factory=lambda: live, recon_factory=lambda: recon,
    )
    app.include_router(evolution.router)
    app.include_router(proposals.router)
    return TestClient(app)


def _aligned_recon(recon, version_id="ver-clone"):
    """Seed an affirmative not-DIVERGENT reconciliation — required for TAKE_ELIGIBLE (audit PF-01:
    the §7.2 live-gap gate must affirmatively PASS, an absent/INSUFFICIENT one no longer reads as a
    pass)."""
    recon.insert(version_id=version_id, strategy_id="s", window_from="a", window_to="b",
                 sim_job_id=None, sim_run_id=None, gap={"returnGap": 0.0}, gap_z=0.1,
                 paired_trades=25, evidence_floor_met=True, verdict="ALIGNED", diagnosis=None)
    return recon


def test_take_eligible_generates_promote_and_advances_state():
    repo = FakeEvoRepo(campaigns=[_campaign()],
                       candidates={_CAMPAIGN_ID: [_paper_candidate()]})
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book()})
    recon = _aligned_recon(FakeReconRepo())
    client = _te_app(repo, live, recon)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    assert body["eligible"] == 1 and body["generated"] == 1
    prop = body["items"][0]
    assert prop["kind"] == "PROMOTE"
    # the live-weighted RobustScore caveat remains (a follow-up); the live-gap gate is now
    # affirmatively PASS (ALIGNED), no longer SKIPPED-and-waved-through.
    pend = prop["evidence"]["pendingInputs"]
    assert any("RobustScore(live-weighted)" in p for p in pend)
    # the F7 quant gates + the REQUIRED maxDD and live-gap gates all affirmatively PASS
    gates = {g["id"]: g["status"] for g in prop["evidence"]["gates"]}
    assert gates["paper_trade_floor"] == "PASS" and gates["profit_factor"] == "PASS"
    assert gates["max_drawdown"] == "PASS"
    assert gates["live_gap"] == "PASS"
    assert prop["evidence"]["gateReadiness"]["stage"] == "PAPER_TO_TAKE_ELIGIBLE"
    assert prop["evidence"]["gateReadiness"]["blockedBy"] == []
    cand = repo.candidates[_CAMPAIGN_ID][0]
    assert cand["state"] == "TAKE_ELIGIBLE"


def test_take_eligible_blocked_by_unevaluated_live_gap():
    # audit PF-01 fail-CLOSED: F7 metrics all clear + capital present, but NO reconciliation → the
    # §7.2 live-gap gate is SKIPPED. A REQUIRED gate that could not run must not read as a pass, so
    # the candidate is NOT eligible and stays PAPER (was silently admitted under the old fail-open).
    repo = FakeEvoRepo(campaigns=[_campaign()],
                       candidates={_CAMPAIGN_ID: [_paper_candidate()]})
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book()})
    client = _te_app(repo, live, FakeReconRepo())  # no reconciliation seeded
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    assert body["eligible"] == 0 and body["generated"] == 0
    assert repo.candidates[_CAMPAIGN_ID][0]["state"] == "PAPER"  # never advanced


def test_take_eligible_blocked_when_max_drawdown_unassessable():
    # audit PF-01 fail-CLOSED: no capital base on the clone config → maxDD is SKIPPED. maxDD is a
    # REQUIRED gate (drawdown discipline must be affirmatively verified), so the candidate is NOT
    # eligible even with an ALIGNED reconciliation and a clearing F7 book.
    repo = FakeEvoRepo(campaigns=[_campaign()],
                       candidates={_CAMPAIGN_ID: [_paper_candidate()]})
    live = FakeLiveRepo(versions={"ver-clone": {"config": {"id": "no-capital"}}},
                        trades={"ver-clone": _paper_book()})
    client = _te_app(repo, live, _aligned_recon(FakeReconRepo()))
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    assert body["eligible"] == 0 and body["generated"] == 0
    assert repo.candidates[_CAMPAIGN_ID][0]["state"] == "PAPER"


def test_take_eligible_not_eligible_under_trade_floor():
    repo = FakeEvoRepo(campaigns=[_campaign()],
                       candidates={_CAMPAIGN_ID: [_paper_candidate()]})
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book(n_win=5, n_loss=2)})  # 7 < 20 trades
    client = _te_app(repo, live, FakeReconRepo())
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    assert body["assessed"] == 1 and body["eligible"] == 0 and body["generated"] == 0
    assert repo.candidates[_CAMPAIGN_ID][0]["state"] == "PAPER"  # stays PAPER (never demoted)


def test_take_eligible_blocked_by_divergent_live_gap():
    repo = FakeEvoRepo(campaigns=[_campaign()],
                       candidates={_CAMPAIGN_ID: [_paper_candidate()]})
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book()})
    recon = FakeReconRepo()
    recon.insert(version_id="ver-clone", strategy_id="s", window_from="a", window_to="b",
                 sim_job_id=None, sim_run_id=None, gap={"returnGap": -9.0}, gap_z=-2.0,
                 paired_trades=30, evidence_floor_met=True, verdict="DIVERGENT", diagnosis=None)
    client = _te_app(repo, live, recon)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    # F7 metrics clear, but the §7.2 DIVERGENT live-gap gate is a hard FAIL → not eligible
    assert body["eligible"] == 0 and body["generated"] == 0


def test_take_eligible_is_idempotent():
    repo = FakeEvoRepo(campaigns=[_campaign()],
                       candidates={_CAMPAIGN_ID: [_paper_candidate()]})
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book()})
    client = _te_app(repo, live, _aligned_recon(FakeReconRepo()))
    first = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    assert first["generated"] == 1 and first["refreshed"] == 0
    second = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    assert second["generated"] == 0 and second["refreshed"] == 1  # OPEN PROMOTE refreshed in place


def test_take_eligible_scalper_raw_recon_never_gates():
    # audit PF-01 finding #1: a LIVE_FIRST (scalper) campaign's ONLY reconciliation is a RAW
    # backtest (gap.mode == "scalper") — a §7.2 STRUCTURAL divergence. It must NEVER be a live-gap
    # PASS NOR permanently block. live_gap is whitelisted (SKIPPED) and EXCLUDED from the LIVE_FIRST
    # required set; the F7 bars alone gate. Even a raw DIVERGENT scalper recon leaves an F7-clearing
    # candidate eligible — the raw plane is not a promotion signal for scalpers.
    repo = FakeEvoRepo(campaigns=[_campaign(policy="LIVE_FIRST")],
                       candidates={_CAMPAIGN_ID: [_paper_candidate()]})
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book()})
    recon = FakeReconRepo()
    recon.insert(version_id="ver-clone", strategy_id="s", window_from="a", window_to="b",
                 sim_job_id=None, sim_run_id=None, gap={"mode": "scalper", "returnGap": -9.0},
                 gap_z=-2.0, paired_trades=30, evidence_floor_met=True, verdict="DIVERGENT",
                 diagnosis=None)
    client = _te_app(repo, live, recon)
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    assert body["eligible"] == 1 and body["generated"] == 1
    prop = body["items"][0]
    gates = {g["id"]: g["status"] for g in prop["evidence"]["gates"]}
    assert gates["live_gap"] == "SKIPPED"          # whitelisted — NEVER a PASS, NEVER a FAIL
    sr = prop["evidence"]["gateReadiness"]
    assert sr["evidencePolicy"] == "LIVE_FIRST" and "live_gap" not in sr["requiredGates"]
    assert sr["interimExcluded"]                   # the §6 LIVE_FIRST gates are flagged, not silent


def test_take_eligible_scalper_still_blocked_by_f7_floor():
    # The F7 bars still gate scalpers — a thin paper book blocks even a LIVE_FIRST candidate.
    repo = FakeEvoRepo(campaigns=[_campaign(policy="LIVE_FIRST")],
                       candidates={_CAMPAIGN_ID: [_paper_candidate()]})
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book(n_win=5, n_loss=2)})  # 7 < 20 trades
    client = _te_app(repo, live, FakeReconRepo())
    body = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/take-eligible").json()
    assert body["eligible"] == 0 and body["generated"] == 0


def test_take_eligible_unwired_is_500():
    service = ProposalService(repo_factory=lambda: FakeEvoRepo(), ntfy=FakeNtfy())
    try:
        service.assess_take_eligible(_CAMPAIGN_ID)
        raise AssertionError("expected ApiError")
    except ApiError as exc:
        assert exc.status == 500 and exc.code == "ASSESS_NOT_WIRED"


# --- PROMOTE execute direct paths ---------------------------------------------------------------

def _promote_seed(repo, *, policy="SIM_FIRST", cand_state="TAKE_ELIGIBLE", executed=False,
                  with_clone_lineage=True):
    repo.campaigns.append(_campaign(policy=policy))
    repo.generations[_CAMPAIGN_ID] = [{
        "id": "gen-1", "campaignId": _CAMPAIGN_ID, "n": 1, "proposal": None,
        "searchSpaceHash": None, "engineSha": None, "dataEpoch": None, "stressTouches": 0,
        "status": "DONE", "startedAt": None, "finishedAt": None}]
    repo.candidates[_CAMPAIGN_ID] = [_paper_candidate(cid="c-te", version_id="ver-clone",
                                                      state=cand_state)]
    if with_clone_lineage:
        # the prior slice-2 PUBLISH_PAPER proposal whose execution stamp carries the candidate's
        # own evo clone strategy id — the archive-on-promote source.
        repo.proposals.append({
            "_seq": 1, "id": "pp-1", "campaignId": _CAMPAIGN_ID, "candidateId": "c-te",
            "kind": "PUBLISH_PAPER", "status": "APPROVED", "actor": "owner", "decidedAt": None,
            "expiresAt": None, "createdAt": "2026-07-10T00:00:00+00:00",
            "evidence": {"execution": {"executed": True, "clonedStrategyId": "clone-old",
                                       "clonedSlug": "base--evo-g1-old"}}})
    evidence = {"kind": "PROMOTE", "candidateId": "c-te"}
    if executed:
        evidence["promotion"] = {"executed": True, "championVersionId": "old"}
    repo.proposals.append({
        "_seq": 2, "id": "prop-1", "campaignId": _CAMPAIGN_ID, "candidateId": "c-te",
        "kind": "PROMOTE", "evidence": evidence, "status": "APPROVED", "actor": "owner",
        "decidedAt": None, "expiresAt": None, "createdAt": "2026-07-11T00:00:00+00:00"})
    return "prop-1"


def _exec_app(repo, strategy):
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    # Seed the clone's live evidence so the PROMOTE-execute re-validation (audit PF-01 #6: a fresh
    # readiness recheck immediately before publish) passes for the mechanic tests: a clearing paper
    # book + a canonical capital base + an ALIGNED swing reconciliation.
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book()})
    recon = _aligned_recon(FakeReconRepo())
    app.state.evo = EvoReadService(repo_factory=lambda: repo)
    app.state.proposals = ProposalService(
        repo_factory=lambda: repo, ntfy=FakeNtfy(),
        strategy_client=strategy, jobs_factory=lambda: FakeJobs(),
        live_factory=lambda: live, recon_factory=lambda: recon,
    )
    app.include_router(evolution.router)
    app.include_router(proposals.router)
    return TestClient(app)


def test_promote_execute_swing_moves_champion_and_retains_paper_counterfactual():
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    pid = _promote_seed(repo, policy="SIM_FIRST")
    client = _exec_app(repo, strategy)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["candidateState"] == "PROMOTED"
    assert body["counterfactual"]["mode"] == "retained-paper-clone"
    # the base got a new draft (the champion move) + a published counterfactual clone
    assert any(d["strategyId"] == _STRATEGY_ID for d in strategy.drafts)
    assert body["championVersionId"] == f"ver-{_STRATEGY_ID}"
    assert body["demotedChampionVersion"] == "1.0.0"
    # candidate PROMOTED + campaign champion moved
    assert repo.candidates[_CAMPAIGN_ID][0]["state"] == "PROMOTED"
    assert repo.campaigns[0]["championVersionId"] == f"ver-{_STRATEGY_ID}"
    # the candidate's now-redundant evo PAPER clone was archived (§8.2 archive semantics)
    assert strategy.archived == ["clone-old"]
    assert body["cloneArchive"] == {"archived": True, "clonedStrategyId": "clone-old"}


def test_promote_execute_archive_failure_is_fail_soft():
    # an archive fault must NEVER fail the promote (the champion already moved) — the response
    # succeeds and carries the warning in cloneArchive + the note; the evidence stamps it too.
    repo = FakeEvoRepo()
    strategy = FakeStrategy(_CONFIG, archive_fails=True)
    pid = _promote_seed(repo)
    client = _exec_app(repo, strategy)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["candidateState"] == "PROMOTED"
    assert body["cloneArchive"]["archived"] is False
    assert "archive the clone manually" in body["cloneArchive"]["warning"]
    assert "WARNING" in body["note"]
    assert strategy.archived == []
    prop = next(p for p in repo.proposals if p["id"] == pid)
    assert prop["evidence"]["promotion"]["cloneArchive"]["archived"] is False


def test_promote_execute_without_clone_lineage_warns_and_succeeds():
    # no prior PUBLISH_PAPER execution stamp (directly-seeded candidate) → the clone id is unknown;
    # archive degrades to a warning, the promote still succeeds.
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    pid = _promote_seed(repo, with_clone_lineage=False)
    client = _exec_app(repo, strategy)
    body = client.post(f"/api/v1/evolution/proposals/{pid}/execute").json()
    assert body["candidateState"] == "PROMOTED"
    assert body["cloneArchive"]["archived"] is False
    assert "lineage" in body["cloneArchive"]["warning"]
    assert strategy.archived == []


def test_promote_execute_scalper_registers_shadow_variant_counterfactual():
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    pid = _promote_seed(repo, policy="LIVE_FIRST")
    client = _exec_app(repo, strategy)
    body = client.post(f"/api/v1/evolution/proposals/{pid}/execute").json()
    assert body["counterfactual"]["mode"] == "shadow-variant"
    # a shadow variant was registered (scalper family), NOT a paper clone
    assert len(strategy.shadow_variants) == 1
    assert strategy.shadow_variants[0]["name"].startswith("evo-cf-manas-arora-")
    assert not any("evo-cf" in c["config"].get("id", "") for c in strategy.created)


def test_promote_execute_is_not_double_executable():
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    pid = _promote_seed(repo, executed=True)
    client = _exec_app(repo, strategy)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 409
    assert resp.json()["code"] == "PROPOSAL_ALREADY_EXECUTED"
    assert strategy.drafts == []


def test_promote_execute_blocks_stale_champion_hybrid():
    # audit PF-01 #6 (stale-champion CAS): a PROMOTE proposal assessed against champion V0 must NOT
    # publish once a sibling has moved the champion to V1 — overlaying its params onto V1's config
    # would be an UNTESTED HYBRID. 409 CHAMPION_CHANGED before any registry mutation.
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    pid = _promote_seed(repo, policy="SIM_FIRST")
    prop = next(p for p in repo.proposals if p["id"] == pid)
    prop["evidence"]["championVersionId"] = "champ-v0"   # captured at assessment time
    repo.campaigns[0]["championVersionId"] = "champ-v1"   # a sibling promoted since → moved
    client = _exec_app(repo, strategy)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 409
    assert resp.json()["code"] == "CHAMPION_CHANGED"
    assert strategy.drafts == []                          # nothing published — no untested hybrid


def test_promote_execute_revalidates_and_blocks_degraded_candidate():
    # audit PF-01 finding #6: readiness is re-checked on FRESH live evidence IMMEDIATELY before any
    # registry mutation. A PROMOTE proposal whose candidate's paper book no longer clears the
    # TAKE_ELIGIBLE bar (evidence degraded, or admitted before PF-01) is REFUSED (409) and nothing
    # is published.
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    pid = _promote_seed(repo, policy="SIM_FIRST")
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    live = FakeLiveRepo(versions={"ver-clone": {"config": _CLONE_CONFIG}},
                        trades={"ver-clone": _paper_book(n_win=3, n_loss=1)})  # 4 < 20 trades now
    app.state.proposals = ProposalService(
        repo_factory=lambda: repo, ntfy=FakeNtfy(), strategy_client=strategy,
        jobs_factory=lambda: FakeJobs(), live_factory=lambda: live,
        recon_factory=lambda: _aligned_recon(FakeReconRepo()),
    )
    app.include_router(proposals.router)
    client = TestClient(app)
    resp = client.post(f"/api/v1/evolution/proposals/{pid}/execute")
    assert resp.status_code == 409
    assert resp.json()["code"] == "PROMOTION_READINESS_NOT_MET"
    assert strategy.drafts == []                 # no registry mutation happened
    assert repo.candidates[_CAMPAIGN_ID][0]["state"] == "TAKE_ELIGIBLE"  # not demoted, just refused


# --- ROLLBACK check + execute -------------------------------------------------------------------

def _promoted_candidate(version_id="ver-champ"):
    return {"id": "c-prom", "generationId": "gen-1", "versionId": version_id,
            "parentCandidateId": None, "mutationKind": "PARAMS",
            "params": {"indicators[0].params.period": 10}, "structureDiff": None,
            "sweepJobId": "sweep-1", "holdoutRunId": None,
            "scorecard": {"robustScore": 1.5, "rankable": True, "gates": []},
            "state": "PROMOTED", "updatedAt": "2026-07-11T00:00:00+00:00"}


def _rollback_seed(repo, *, verdicts=("DIVERGENT", "DIVERGENT")):
    repo.campaigns.append(_campaign(champion="ver-champ"))
    repo.generations[_CAMPAIGN_ID] = [{
        "id": "gen-1", "campaignId": _CAMPAIGN_ID, "n": 1, "proposal": None,
        "searchSpaceHash": None, "engineSha": None, "dataEpoch": None, "stressTouches": 0,
        "status": "DONE", "startedAt": None, "finishedAt": None}]
    repo.candidates[_CAMPAIGN_ID] = [_promoted_candidate()]
    # the prior (executed) PROMOTE proposal carries the rollback lineage
    repo.proposals.append({
        "_seq": 1, "id": "promote-1", "campaignId": _CAMPAIGN_ID, "candidateId": "c-prom",
        "kind": "PROMOTE", "status": "APPROVED", "actor": "owner", "decidedAt": None,
        "expiresAt": None, "createdAt": "2026-07-11T00:00:00+00:00",
        "evidence": {"promotion": {"executed": True, "baseStrategyId": _STRATEGY_ID,
                                   "championVersionId": "ver-champ",
                                   "demotedChampionVersionId": "champ-old",
                                   "demotedChampionVersion": "1.0.0",
                                   "counterfactual": {"mode": "retained-paper-clone"}}}})
    recon = FakeReconRepo()
    for v in verdicts:
        recon.insert(version_id="ver-champ", strategy_id="s", window_from="a", window_to="b",
                     sim_job_id=None, sim_run_id=None, gap={"returnGap": -9.0}, gap_z=-2.0,
                     paired_trades=30, evidence_floor_met=True, verdict=v, diagnosis=None)
    return recon


def _rb_app(repo, recon, strategy):
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.evo = EvoReadService(repo_factory=lambda: repo)
    app.state.proposals = ProposalService(
        repo_factory=lambda: repo, ntfy=FakeNtfy(),
        strategy_client=strategy, jobs_factory=lambda: FakeJobs(),
        live_factory=lambda: FakeLiveRepo(), recon_factory=lambda: recon,
    )
    app.include_router(evolution.router)
    app.include_router(proposals.router)
    return TestClient(app)


def test_rollback_check_triggers_on_two_divergent_then_executes():
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    recon = _rollback_seed(repo, verdicts=("DIVERGENT", "DIVERGENT"))
    client = _rb_app(repo, recon, strategy)
    rc = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/rollback-check").json()
    assert rc["assessed"] == 1 and rc["eligible"] == 1 and rc["generated"] == 1
    rid = rc["items"][0]["id"]
    assert rc["items"][0]["kind"] == "ROLLBACK"
    client.post(f"/api/v1/evolution/proposals/{rid}/approve")
    rb = client.post(f"/api/v1/evolution/proposals/{rid}/execute").json()
    assert rb["candidateState"] == "ROLLED_BACK" and rb["restoredVersion"] == "1.0.0"
    assert strategy.rollbacks == [
        {"strategyId": _STRATEGY_ID, "version": "1.0.0", "andPublish": True}]
    # champion pointer restored to the copy-forward version the rollback published
    assert repo.campaigns[0]["championVersionId"] == f"rb-{_STRATEGY_ID}"
    # the promoted candidate keeps its own version_id — only the state flips to ROLLED_BACK
    assert repo.candidates[_CAMPAIGN_ID][0]["versionId"] == "ver-champ"


def test_rollback_check_no_trigger_generates_nothing():
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    recon = _rollback_seed(repo, verdicts=("DIVERGENT", "ALIGNED"))  # only 1 DIVERGENT
    client = _rb_app(repo, recon, strategy)
    rc = client.post(f"/api/v1/evolution/campaigns/{_CAMPAIGN_ID}/rollback-check").json()
    assert rc["assessed"] == 1 and rc["eligible"] == 0 and rc["generated"] == 0


def test_rollback_execute_without_target_is_422():
    # a ROLLBACK proposal whose evidence has no rollbackTarget (PROMOTE lineage missing)
    repo, strategy = FakeEvoRepo(), FakeStrategy(_CONFIG)
    repo.campaigns.append(_campaign(champion="ver-champ"))
    repo.generations[_CAMPAIGN_ID] = [{
        "id": "gen-1", "campaignId": _CAMPAIGN_ID, "n": 1, "proposal": None,
        "searchSpaceHash": None, "engineSha": None, "dataEpoch": None, "stressTouches": 0,
        "status": "DONE", "startedAt": None, "finishedAt": None}]
    repo.candidates[_CAMPAIGN_ID] = [_promoted_candidate()]
    repo.proposals.append({
        "_seq": 1, "id": "rb-1", "campaignId": _CAMPAIGN_ID, "candidateId": "c-prom",
        "kind": "ROLLBACK", "status": "APPROVED", "actor": "owner", "decidedAt": None,
        "expiresAt": None, "createdAt": "2026-07-11T00:00:00+00:00",
        "evidence": {"kind": "ROLLBACK"}})
    client = _rb_app(repo, FakeReconRepo(), strategy)
    resp = client.post("/api/v1/evolution/proposals/rb-1/execute")
    assert resp.status_code == 422
    assert resp.json()["code"] == "ROLLBACK_TARGET_UNRESOLVED"
    assert strategy.rollbacks == []
