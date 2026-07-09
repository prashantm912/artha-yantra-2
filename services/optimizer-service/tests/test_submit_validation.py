"""Submit-time request-dict hardening (register §9-8/§9-9): a bad numeric knob is a clean 400
(not an opaque int()/KeyError 500), maxTrials can't run away, and an objective naming a metric the
backtest never emits is rejected up front instead of "completing" as an empty leaderboard."""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import api
from app.errors import ApiError, api_error_handler, invalid_path_handler
from app.path_grammar import InvalidParameterPath
from app.service import SweepService, _coerce_int, _validate_objective_metrics
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


def _client(config=GOOD_CONFIG):
    jobs = FakeJobs()
    trials = FakeTrials()
    service = SweepService(
        strategy_client=FakeStrategy(config),
        jobs_factory=lambda: jobs,
        trials_factory=lambda: trials,
        dispatcher=FakeDispatcher(jobs),
        runner=lambda **kwargs: None,  # don't actually sweep in the validation test
    )
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(InvalidParameterPath, invalid_path_handler)
    app.state.sweeps = service
    app.include_router(api.router)
    return TestClient(app)


def _post(body_overrides):
    return _client().post("/api/v1/optimizations/run", json={**RUN_BODY, **body_overrides})


# --- pure helpers -----------------------------------------------------------------------------


def test_coerce_int_accepts_numeric_string():
    assert _coerce_int("7", "maxTrials") == 7


def test_coerce_int_rejects_non_numeric():
    with pytest.raises(ApiError) as exc:
        _coerce_int("abc", "maxTrials")
    assert exc.value.status == 400 and "maxTrials" in exc.value.message


def test_validate_objective_metric_known_passes():
    _validate_objective_metrics("tpe", {"metric": "cagr"})  # no raise


def test_validate_objective_metric_unknown_raises():
    with pytest.raises(ApiError) as exc:
        _validate_objective_metrics("tpe", {"metric": "sharp"})  # typo of sharpe
    assert exc.value.status == 400 and exc.value.code == "VALIDATION_FAILED"


def test_validate_nsga2_default_objectives_pass():
    # nsga2 with no explicit objectives ranks the cagr/maxDrawdown default — both are known.
    _validate_objective_metrics("nsga2", {})  # no raise


def test_validate_nsga2_explicit_unknown_raises():
    obj = {"objectives": [{"metric": "cagr"}, {"metric": "bogus"}]}
    with pytest.raises(ApiError):
        _validate_objective_metrics("nsga2", obj)


# --- submit path (400s that used to be 500s / silent empty sweeps) ----------------------------


def test_unknown_objective_metric_is_400():
    resp = _post({"objective": {"metric": "shrape", "direction": "maximize"}})
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_known_objective_metric_is_202():
    resp = _post({"objective": {"metric": "cagr", "direction": "maximize"}})
    assert resp.status_code == 202


def test_non_numeric_max_trials_is_400():
    resp = _post({"maxTrials": "lots"})
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_max_trials_over_cap_is_400():
    resp = _post({"maxTrials": 100000})
    assert resp.status_code == 400


def test_zero_max_trials_is_400():
    resp = _post({"maxTrials": 0})
    assert resp.status_code == 400


def test_non_numeric_seed_is_400():
    resp = _post({"seed": "x"})
    assert resp.status_code == 400


def test_non_numeric_early_stopping_is_400():
    resp = _post({"earlyStopping": "soon"})
    assert resp.status_code == 400


def test_parameter_without_path_is_400_not_500():
    # An override parameter missing 'path' used to KeyError → 500; now a clean 400.
    resp = _post({"parameters": [{"range": [1, 2]}]})
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_FAILED"


def test_walk_forward_non_object_is_400():
    resp = _post({"walkForward": "30d"})
    assert resp.status_code == 400


def test_early_stopping_true_still_accepted():
    # Regression: the bool short-circuit must survive the coercion change (earlyStopping: true).
    resp = _post({"earlyStopping": True})
    assert resp.status_code == 202
