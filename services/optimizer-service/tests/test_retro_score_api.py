"""The §12 E1 retro-scoring endpoint — GET /api/v1/evolution/retro-score/{sweepJobId} — driven
through a TestClient over RetroScoreService + the in-memory fakes (no Postgres, no backtest svc).
Covers the {items} envelope, the OOS fold-metric assembly (returns/trades/regime/drawdown), the 404
idiom, and an empty sweep."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import evolution
from app.errors import ApiError, api_error_handler
from app.evolution import RetroScoreService
from tests.fakes import FakeBacktest, FakeJobs, FakeTrials

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
    assert len(body["items"]) == 2


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
    # recoveryFactor = totalReturn 12.0 ÷ worst OOS fold DD 12.0 = 1.0 (derived in Python)
    assert comps["drawdown_quality"]["raw"]["recoveryFactor"] == 1.0
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
