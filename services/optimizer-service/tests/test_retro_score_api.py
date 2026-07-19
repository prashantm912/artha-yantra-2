"""The §12 E1 retro-scoring endpoint — GET /api/v1/evolution/retro-score/{sweepJobId} — driven
through a TestClient over RetroScoreService + the in-memory fakes (no Postgres, no backtest svc).
Covers the {items} envelope, the OOS fold-metric assembly (returns/trades/regime/drawdown), the 404
idiom, an empty sweep, the dead-run degradation, and the cohort-cap truncation caveat."""

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import evolution
from app.errors import ApiError, api_error_handler
from app.evolution import EvoRecorderService, RetroScoreService
from tests.fakes import FakeBacktest, FakeEvoRepo, FakeJobs, FakeTrials

# Three OOS folds spanning three regimes so the regime-floor gate can pass (≥ 3 of 4 covered).
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


def _client(jobs: FakeJobs, trials: FakeTrials, backtest: FakeBacktest) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.retro = RetroScoreService(lambda: jobs, lambda: trials, backtest)
    app.include_router(evolution.router)
    return TestClient(app)


def _seed_sweep(jobs: FakeJobs, trials: FakeTrials, n: int = 2) -> str:
    # A walk-forward sweep (objective oos_fold_mean); constant objective value ⇒ plateauRatio ≡ 1.
    request = {"parameters": [], "objective": {"metric": "oos_fold_mean", "direction": "maximize"}}
    sweep_id = jobs.insert_sweep(None, request)
    for i in range(n):
        row_id = trials.insert(sweep_id, i, {"period": 10 + i})
        trials.complete(row_id, {"oos_fold_mean": 1.0}, f"run-{i}")
    return sweep_id


def test_retro_score_returns_items_envelope_and_context():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, n=2)
    resp = _client(jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS)).get(
        f"/api/v1/evolution/retro-score/{sweep_id}"
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["sweepJobId"] == sweep_id
    assert body["metric"] == "oos_fold_mean"
    assert body["direction"] == "maximize"
    assert body["policy"] == "SIM_FIRST"
    assert body["caveats"] == []  # 2 trials — nowhere near the cohort cap
    assert len(body["items"]) == 2
    # every retro scorecard carries the standing descriptive-only caveat + the per-sweep-N caveat
    # (the standalone read has no campaign context — cumulative N applies only when recorded)
    assert any("retro-score is descriptive" in c for c in body["items"][0]["caveats"])
    assert any("multiplicity N = this sweep only" in c for c in body["items"][0]["caveats"])


def test_retro_score_round_trips_stage_readiness():
    # audit PF-01 finding #7: the typed Scorecard carries the ``stageReadiness`` field, so the read
    # never silently strips it (a loose extra dict would be dropped by Pydantic's extra-ignore).
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, n=2)
    body = _client(jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS)).get(
        f"/api/v1/evolution/retro-score/{sweep_id}"
    ).json()
    sr = body["items"][0]["stageReadiness"]
    assert sr is not None
    assert sr["evidencePolicy"] == "SIM_FIRST"
    assert sr["stage"] == "SCORED_TO_SURVIVOR"
    assert set(sr) >= {"ready", "requiredGates", "blockedBy"}


def test_retro_score_assembles_oos_fold_metrics_into_gates():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, n=2)
    body = _client(jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS)).get(
        f"/api/v1/evolution/retro-score/{sweep_id}"
    ).json()
    card = body["items"][0]
    gates = {g["id"]: g for g in card["gates"]}
    # evidence_floor = Σ per-fold OOS tradeCount = 40+45+30 = 115 ≥ 60
    assert gates["evidence_floor"]["value"] == 115
    assert gates["evidence_floor"]["status"] == "PASS"
    # fold_consistency = fraction of OOS fold returns > 0 = 3/3 = 1.0
    assert gates["fold_consistency"]["value"] == 1.0
    # drawdown_cap value = worst OOS fold drawdown = 12.0 ≤ 40
    assert gates["drawdown_cap"]["value"] == 12.0
    assert gates["drawdown_cap"]["status"] == "PASS"
    # regime_floor passes: 3 of 4 regimes covered, min 0.5 ≥ −0.5 · mean 0.7 = −0.35
    assert gates["regime_floor"]["status"] == "PASS"
    # comparability PASS because the run carries an engine SHA (#703)
    assert gates["comparability"]["status"] == "PASS"
    assert card["evidence"]["simRuns"] == ["run-0"]


def test_retro_score_recovery_and_regime_components_from_run_evidence():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, n=1)
    body = _client(jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS)).get(
        f"/api/v1/evolution/retro-score/{sweep_id}"
    ).json()
    comps = {c["id"]: c for c in body["items"][0]["components"]}
    # recoveryFactor numerator is the OOS-fold mean return (5+7+3)/3 = 5.0 — NEVER the run-level
    # totalReturn (12.0), which on a walk-forward run is full-window train+test and would inflate.
    # Denominator = worst OOS fold DD 12.0 ⇒ 5/12 = 0.4167.
    assert comps["drawdown_quality"]["raw"]["recoveryFactor"] == 0.4167
    # regime aggregates read via leaderboard.guard_metrics over the per-fold regimeOos
    assert comps["regime_consistency"]["raw"]["regimeOosMin"] == 0.5


def test_retro_score_unknown_sweep_is_404():
    resp = _client(FakeJobs(), FakeTrials(), FakeBacktest(folds=[])).get(
        "/api/v1/evolution/retro-score/nope"
    )
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_JOB"


def test_retro_score_sweep_with_no_complete_trials_is_empty():
    jobs, trials = FakeJobs(), FakeTrials()
    request = {"parameters": [], "objective": {"metric": "sharpe"}}
    sweep_id = jobs.insert_sweep(None, request)  # a sweep with zero trials
    resp = _client(jobs, trials, FakeBacktest(folds=[])).get(
        f"/api/v1/evolution/retro-score/{sweep_id}"
    )
    assert resp.status_code == 200
    assert resp.json()["items"] == []


def test_retro_score_survives_a_dead_run():
    # ONE purged/404 run must never 500 the whole retro-score: the dead trial degrades to a
    # low-evidence card with a "run fetch failed" caveat; the other trials score normally.
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, n=3)
    backtest = FakeBacktest(
        folds={"run-0": _FOLDS, "run-1": httpx.HTTPError("404 Not Found"), "run-2": _FOLDS},
        results={"run-0": _RESULTS, "run-2": _RESULTS},
    )
    resp = _client(jobs, trials, backtest).get(f"/api/v1/evolution/retro-score/{sweep_id}")
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert len(items) == 3
    dead = next(i for i in items if i["runId"] == "run-1")
    assert any("run fetch failed: 404 Not Found" in c for c in dead["caveats"])
    live = next(i for i in items if i["runId"] == "run-0")
    assert not any("run fetch failed" in c for c in live["caveats"])
    # the degraded card has no evidence to FAIL a gate on — it degrades, it is not disqualified
    assert {g["status"] for g in dead["gates"]} <= {"UNKNOWN", "SKIPPED", "NOT_IMPLEMENTED", "PASS"}


def test_retro_score_flags_a_capped_cohort(monkeypatch):
    # At the cohort read cap the trial list MAY be truncated — the response must say so (z-scores
    # normalized over a partial cohort are never silent). Cap shrunk to 2 to exercise the branch.
    monkeypatch.setattr(evolution, "_COHORT_CAP", 2)
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_sweep(jobs, trials, n=3)
    body = _client(jobs, trials, FakeBacktest(folds=_FOLDS, results=_RESULTS)).get(
        f"/api/v1/evolution/retro-score/{sweep_id}"
    ).json()
    assert len(body["items"]) == 2
    assert any("capped" in c for c in body["caveats"])


def test_retro_score_deflated_sharpe_uses_full_trial_count():
    # The §4 multiplicity N is the sweep's FULL trial count (every state), NOT just the scored
    # COMPLETE cohort: 2 completed + 3 failed trials ⇒ N = 5, even though only 2 are ranked. The
    # gate note echoes the N the retro caller threaded through, proving the wiring.
    jobs, trials = FakeJobs(), FakeTrials()
    request = {"parameters": [], "objective": {"metric": "oos_fold_mean", "direction": "maximize"}}
    sweep_id = jobs.insert_sweep(None, request)
    for i in range(2):
        trials.complete(trials.insert(sweep_id, i, {"period": 10 + i}), {"oos_fold_mean": 1.0},
                        f"run-{i}")
    for i in range(2, 5):
        trials.fail(trials.insert(sweep_id, i, {"period": 10 + i}))
    # Give the OOS folds a Sharpe so the gate is assessable (the base _FOLDS carry none → SKIPPED).
    folds = [dict(f, oosMetrics=dict(f["oosMetrics"], sharpe="2.0")) for f in _FOLDS]
    body = _client(jobs, trials, FakeBacktest(folds=folds, results=_RESULTS)).get(
        f"/api/v1/evolution/retro-score/{sweep_id}"
    ).json()
    assert len(body["items"]) == 2  # only the COMPLETE trials are scored
    gate = next(g for g in body["items"][0]["gates"] if g["id"] == "multiplicity_tstat")
    assert gate["status"] == "PASS"        # Sharpe 2.0 over 115 trades clears the N=5 bar
    assert "N=5 trials" in gate["note"]    # full count (2 complete + 3 failed), not the 2 scored


def test_recorder_charges_campaign_cumulative_multiplicity():
    # Design §4: "the campaign records total trials-to-date N". The RECORDER charges the campaign-
    # CUMULATIVE look count — prior generations' sweep trials + the current sweep's own — while the
    # standalone retro read (no campaign context) stays per-sweep. Same marginal candidate (mean
    # OOS-fold Sharpe 0.4, pooled T=70): S/se = 0.4/√(1.08/69) = 3.1972. Per-sweep N=100 → bar
    # √(2·ln 100) = 3.0349 ⇒ deflated +0.1624 PASS; campaign N=300 → bar √(2·ln 300) = 3.3775 ⇒
    # deflated −0.1803 FAIL — the snooping haircut visibly flips the verdict.
    jobs, trials = FakeJobs(), FakeTrials()
    request = {"parameters": [], "objective": {"metric": "oos_fold_mean", "direction": "maximize"}}
    sweep_id = jobs.insert_sweep(None, request)
    for i in range(100):
        trials.complete(trials.insert(sweep_id, i, {"period": i}), {"oos_fold_mean": 1.0}, "run-x")
    jobs.set_status(sweep_id, "completed")  # only a completed sweep is recordable
    # Two PRIOR generations' sweeps, 100 trials each — every state counts as a "look" (failed here).
    for prior in ("prior-A", "prior-B"):
        for i in range(100):
            trials.fail(trials.insert(prior, i, {"period": i}))
    repo = FakeEvoRepo(
        campaigns=[{"id": "camp-1", "evidencePolicy": "SIM_FIRST"}],
        generations={"camp-1": [
            {"id": "gen-a", "campaignId": "camp-1", "n": 1, "stressTouches": 0,
             "proposal": {"sweepJobId": "prior-A"}},
            {"id": "gen-b", "campaignId": "camp-1", "n": 2, "stressTouches": 0,
             "proposal": {"sweepJobId": "prior-B"}},
        ]},
    )
    folds = [
        {"fold": 0, "regimeOos": {"UP_QUIET": {"sharpe": "1.0"}},
         "oosMetrics": {"totalReturn": "5.0", "sharpe": "0.4", "tradeCount": 35}},
        {"fold": 1, "regimeOos": {"DOWN_QUIET": {"sharpe": "0.6"}},
         "oosMetrics": {"totalReturn": "7.0", "sharpe": "0.4", "tradeCount": 35}},
    ]
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    scorer = RetroScoreService(lambda: jobs, lambda: trials,
                               FakeBacktest(folds=folds, results=_RESULTS))
    app.state.retro = scorer
    app.state.evo_writer = EvoRecorderService(repo_factory=lambda: repo, scorer=scorer)
    app.include_router(evolution.router)
    client = TestClient(app)

    # Standalone retro read: per-sweep N=100 → PASS (+0.1624), flagged as per-sweep-only.
    retro = client.get(f"/api/v1/evolution/retro-score/{sweep_id}").json()
    gate = next(g for g in retro["items"][0]["gates"] if g["id"] == "multiplicity_tstat")
    assert gate["status"] == "PASS"
    assert gate["value"] == 0.1624
    assert "N=100 trials" in gate["note"]
    assert any("multiplicity N = this sweep only" in c for c in retro["items"][0]["caveats"])

    # Recorded into the campaign: N = 100 + 100 + 100 = 300 → the SAME candidate FAILS (−0.1803).
    resp = client.post(
        "/api/v1/evolution/campaigns/camp-1/generations", json={"sweepJobId": sweep_id}
    )
    assert resp.status_code == 201
    card = repo.candidates["camp-1"][0]["scorecard"]
    rec_gate = next(g for g in card["gates"] if g["id"] == "multiplicity_tstat")
    assert rec_gate["status"] == "FAIL"
    assert rec_gate["value"] == -0.1803
    assert "N=300 trials" in rec_gate["note"]
    assert card["rankable"] is False
    # the per-sweep-N caveat is a STANDALONE-read stamp — recorder cards never carry it
    assert not any("multiplicity N = this sweep only" in c for c in card["caveats"])
