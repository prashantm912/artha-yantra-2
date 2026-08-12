"""§D.5 optimizer REST surface — validation envelope + 202/404 via TestClient with
the real SweepService over in-memory fakes (a no-op runner, so no sweep thread)."""

import uuid

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import api
from app.errors import ApiError, api_error_handler, invalid_path_handler
from app.main import app as service_app
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
    # A WELL-FORMED but unknown id still 404s (the UUID typing below must not swallow this path).
    resp = _client(GOOD_CONFIG).get(f"/api/v1/optimizations/jobs/{uuid.uuid4()}")
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_JOB"


def test_malformed_job_id_is_422():
    """T1b-F2(b): a non-UUID id used to reach the UUID-typed ``jobs.id`` column and escape as an
    uncaught Postgres parse error (bare 500). It is now refused at the HTTP boundary, in the SHARED
    envelope — FastAPI's stock ``{"detail": [...]}`` is not the house shape. Driven against the
    REAL app: transport validation fires before the handler, so no fake service is needed.
    NOTE: this pins the BOUNDARY contract only — no test here reaches the real UUID-typed column."""
    resp = TestClient(service_app).get("/api/v1/optimizations/jobs/not-a-uuid")
    assert resp.status_code == 422
    body = resp.json()
    assert set(body) == {"code", "message", "details"}  # exactly the envelope, no stray `detail`
    assert body["code"] == "VALIDATION_FAILED"
    assert body["message"].startswith("path.job_id: ")
    assert body["details"]["errors"][0]["loc"] == ["path", "job_id"]


def test_promote_without_trial_id_is_422():
    """T1b-F2(a): the handler read a RAW dict body, so ``{}`` crashed on ``body["trialId"]``
    (KeyError → bare 500). Driven at the HTTP layer against the REAL app — the pre-existing promote
    tests call the service function directly and never reach this."""
    resp = TestClient(service_app).post(f"/api/v1/optimizations/{uuid.uuid4()}/promote", json={})
    assert resp.status_code == 422
    body = resp.json()
    assert set(body) == {"code", "message", "details"}
    assert body["code"] == "VALIDATION_FAILED"
    assert body["message"] == "body.trialId: Field required"
    assert body["details"]["errors"][0]["loc"] == ["body", "trialId"]


def test_unexpected_error_returns_the_shared_envelope_with_a_correlation_id():
    """T1b-F2(c): only ApiError + InvalidParameterPath were mapped, so anything else escaped as a
    bodyless 500. Driven against the REAL app object so it exercises main.py's own handler wiring,
    not a hand-built test app. ``raise_server_exceptions=False``: Starlette re-raises after sending
    the response so the ASGI server can log it, and TestClient would otherwise surface that raise
    instead of the response. The correlation id is what ties this 500 to its server log line: the
    gateway's forwarded X-Request-Id when there is one, a locally-minted UUID when there is not."""
    class Boom:
        def job_status(self, job_id):
            raise RuntimeError("kaboom")

    previous = service_app.state.sweeps
    service_app.state.sweeps = Boom()
    try:
        client = TestClient(service_app, raise_server_exceptions=False)
        path = f"/api/v1/optimizations/jobs/{uuid.uuid4()}"
        forwarded = client.get(path, headers={"X-Request-Id": "gateway-req-42"})
        generated = client.get(path)
    finally:
        service_app.state.sweeps = previous
    # The gateway forwards one X-Request-Id per inbound request — it must ride through verbatim.
    assert forwarded.status_code == 500
    assert forwarded.json() == {
        "code": "INTERNAL_ERROR",
        "message": "Internal error",
        "details": {"correlationId": "gateway-req-42"},
    }
    # A direct call carries no header: the id is generated, never absent/blank (it is the ONLY
    # handle back to the log line).
    assert generated.status_code == 500
    body = generated.json()
    assert set(body) == {"code", "message", "details"}
    assert body["code"] == "INTERNAL_ERROR"
    assert body["message"] == "Internal error"
    assert uuid.UUID(body["details"]["correlationId"])  # a real UUID, so parsing must not raise
