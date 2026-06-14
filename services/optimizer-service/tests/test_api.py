"""§D.5 optimizer REST surface — validation envelope + 202/404 via TestClient with
the real SweepService over in-memory fakes (a no-op runner, so no sweep thread)."""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import api
from app.errors import ApiError, api_error_handler, invalid_path_handler
from app.path_grammar import InvalidParameterPath
from app.service import SweepService
from tests.fakes import FakeDispatcher, FakeJobs, FakeStrategy, FakeTrials

GOOD_CONFIG = {
    "backtest": {
        "optimize": {
            "parameters": [{"path": "indicators[0].params.period", "range": [5, 9], "step": 1}]
        }
    }
}
RUN_BODY = {
    "strategyId": "s1",
    "strategyVersion": "1.0.0",
    "from": "2026-01-05",
    "to": "2026-01-10",
    "method": "grid",
    "maxTrials": 5,
}


def _client(config):
    jobs = FakeJobs()
    trials = FakeTrials()
    service = SweepService(
        strategy_client=FakeStrategy(config),
        jobs_factory=lambda: jobs,
        trials_factory=lambda: trials,
        dispatcher=FakeDispatcher(jobs),
        runner=lambda **kwargs: None,  # don't actually sweep in the API test
    )
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(InvalidParameterPath, invalid_path_handler)
    app.state.sweeps = service
    app.include_router(api.router)
    return TestClient(app)


def test_run_returns_202_with_job_id():
    resp = _client(GOOD_CONFIG).post("/api/v1/optimizations/run", json=RUN_BODY)
    assert resp.status_code == 202
    assert resp.json()["jobId"].startswith("sweep-")
    assert resp.json()["status"] == "queued"


def test_unsupported_method_is_400():
    resp = _client(GOOD_CONFIG).post(
        "/api/v1/optimizations/run", json={**RUN_BODY, "method": "annealing"}
    )
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_missing_field_is_400():
    body = {k: v for k, v in RUN_BODY.items() if k != "from"}
    resp = _client(GOOD_CONFIG).post("/api/v1/optimizations/run", json=body)
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_strategy_version_optional_resolves_current():
    # strategyVersion omitted -> resolved to the strategy's current version (§D.5), not a 400.
    body = {k: v for k, v in RUN_BODY.items() if k != "strategyVersion"}
    resp = _client(GOOD_CONFIG).post("/api/v1/optimizations/run", json=body)
    assert resp.status_code == 202
    assert resp.json()["jobId"].startswith("sweep-")


def test_no_tunable_parameters_is_422():
    resp = _client({"backtest": {"optimize": {"parameters": []}}}).post(
        "/api/v1/optimizations/run", json=RUN_BODY
    )
    assert resp.status_code == 422
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_invalid_parameter_path_is_400():
    bad = {
        "backtest": {"optimize": {"parameters": [{"path": "universe.symbols[0]", "range": [1, 2]}]}}
    }
    resp = _client(bad).post("/api/v1/optimizations/run", json=RUN_BODY)
    assert resp.status_code == 400
    assert resp.json()["code"] == "INVALID_PARAMETER_PATH"


def test_unknown_job_is_404():
    resp = _client(GOOD_CONFIG).get("/api/v1/optimizations/jobs/nope")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_JOB"
