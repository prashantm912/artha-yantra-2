"""The §12 E2 insights endpoint — GET /api/v1/evolution/insights/{sweepJobId} — driven through a
TestClient over InsightsService + the in-memory fakes (no Postgres, no backtest svc).

Fixtures carry a KNOWN param↔objective structure so the importance ordering, the brittle-param
classification, and the slice grid are all hand-computed, not just smoke-checked. Also covers the
404 idiom (unknown + non-OPTIMIZATION job) and the two honest-degradation paths (< 2 trials; a
non-varying param excluded from importance)."""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import insights as insights_mod
from app.errors import ApiError, api_error_handler
from app.insights import InsightsService
from tests.fakes import FakeJobs, FakeTrials


def _client(jobs: FakeJobs, trials: FakeTrials) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.state.insights = InsightsService(lambda: jobs, lambda: trials)
    app.include_router(insights_mod.router)
    return TestClient(app)


def _seed(
    jobs: FakeJobs,
    trials: FakeTrials,
    parameters: list[dict],
    rows: list[tuple[dict, float]],
    metric: str = "sharpe",
) -> str:
    """Insert an OPTIMIZATION job + one COMPLETE trial per (params, objective) row."""
    request = {"parameters": parameters, "objective": {"metric": metric, "direction": "maximize"}}
    sweep_id = jobs.insert_sweep(None, request)
    for i, (params, obj) in enumerate(rows):
        row_id = trials.insert(sweep_id, i, params)
        trials.complete(row_id, {metric: obj}, f"run-{i}")
    return sweep_id


def _get(jobs: FakeJobs, trials: FakeTrials, sweep_id: str):
    return _client(jobs, trials).get(f"/api/v1/evolution/insights/{sweep_id}")


# --- Importance ----------------------------------------------------------------------------------

# obj == [1,2,3,4,5]; a ≡ obj (rho +1), c = reverse (rho -1), b a permutation (rho +0.3).
_IMPORTANCE_PARAMS = [
    {"path": "a", "range": [1, 5], "step": 1},
    {"path": "b", "range": [1, 5], "step": 1},
    {"path": "c", "range": [1, 5], "step": 1},
]
_A = [1, 2, 3, 4, 5]
_B = [3, 1, 5, 2, 4]
_C = [5, 4, 3, 2, 1]
_OBJ = [1.0, 2.0, 3.0, 4.0, 5.0]
_IMPORTANCE_ROWS = [
    ({"a": _A[i], "b": _B[i], "c": _C[i]}, _OBJ[i]) for i in range(5)
]


def test_importance_ranks_by_effect_strength_and_signs_direction():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, _IMPORTANCE_PARAMS, _IMPORTANCE_ROWS)
    body = _get(jobs, trials, sweep_id).json()

    assert body["sweepJobId"] == sweep_id
    assert body["metric"] == "sharpe"
    assert body["direction"] == "maximize"  # the sweep's optimize direction

    imp = body["importance"]
    # a (|rho|=1, +) and c (|rho|=1, -) tie on score → broken by param name; then b (|rho|=0.3).
    assert [it["param"] for it in imp] == ["a", "c", "b"]
    assert imp[0]["score"] == pytest.approx(1.0)
    assert imp[0]["direction"] == "positive"
    assert imp[1]["score"] == pytest.approx(1.0)
    assert imp[1]["direction"] == "negative"
    assert imp[2]["score"] == pytest.approx(0.3)
    assert imp[2]["direction"] == "positive"
    # every param varied → none excluded from importance
    assert not any("excluded from importance" in n for n in body["notes"])


def test_categorical_param_ranked_via_choice_order():
    # mode increases lo→mid→hi with the objective → strong positive importance via choice rank.
    params = [{"path": "mode", "choices": ["lo", "mid", "hi"]}]
    rows = [({"mode": "lo"}, 1.0), ({"mode": "mid"}, 2.0), ({"mode": "hi"}, 3.0)]
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, params, rows)
    imp = _get(jobs, trials, sweep_id).json()["importance"]
    assert imp[0]["param"] == "mode"
    assert imp[0]["score"] == pytest.approx(1.0)
    assert imp[0]["direction"] == "positive"


def test_non_varying_param_excluded_from_importance_with_note():
    params = [
        {"path": "a", "range": [1, 3], "step": 1},
        {"path": "k", "range": [7, 7], "step": 1},  # constant across every trial
    ]
    rows = [({"a": 1, "k": 7}, 1.0), ({"a": 2, "k": 7}, 2.0), ({"a": 3, "k": 7}, 3.0)]
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, params, rows)
    body = _get(jobs, trials, sweep_id).json()
    assert [it["param"] for it in body["importance"]] == ["a"]
    assert any("k" in n and "excluded from importance" in n for n in body["notes"])


# --- Brittleness ---------------------------------------------------------------------------------

# A full 2x2 grid so EVERY pair is a neighbor. obj = 100*a + b makes 'a' both the more important
# knob AND the higher-local-variance one → 'a' brittle, 'b' not.
_GRID_PARAMS = [
    {"path": "a", "range": [1, 2], "step": 1},
    {"path": "b", "range": [1, 2], "step": 1},
]
_GRID_ROWS = [
    ({"a": 1, "b": 1}, 101.0),
    ({"a": 1, "b": 2}, 102.0),
    ({"a": 2, "b": 1}, 201.0),
    ({"a": 2, "b": 2}, 202.0),
]


def test_brittleness_flags_high_importance_high_variance_param():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, _GRID_PARAMS, _GRID_ROWS)
    body = _get(jobs, trials, sweep_id).json()

    britt = {r["param"]: r for r in body["brittleness"]}
    # localVariance = mean over neighbor pairs differing in the param of (Δobjective)^2 / 2.
    assert britt["a"]["localVariance"] == pytest.approx(5000.25)
    assert britt["b"]["localVariance"] == pytest.approx(2500.5)
    assert britt["a"]["importance"] > britt["b"]["importance"]
    assert britt["a"]["brittle"] is True
    assert britt["b"]["brittle"] is False


# --- Interaction slices --------------------------------------------------------------------------

def test_slices_build_existing_trial_grid_for_top_pair():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, _GRID_PARAMS, _GRID_ROWS)
    slices = _get(jobs, trials, sweep_id).json()["slices"]
    # only two varying params → exactly one pair, ordered by importance (a before b).
    assert len(slices) == 1
    assert (slices[0]["paramX"], slices[0]["paramY"]) == ("a", "b")
    cells = slices[0]["cells"]
    assert len(cells) == 4  # one per existing (a, b) combination
    assert all(c["count"] == 1 for c in cells)
    # cells are the raw trial objectives, sorted deterministically by (str(x), str(y)).
    assert cells[0] == {"x": 1, "y": 1, "objective": 101.0, "count": 1}
    assert cells[-1] == {"x": 2, "y": 2, "objective": 202.0, "count": 1}


def test_slice_cell_aggregates_repeated_combination_as_median():
    # Two trials share (a=1, b=1) with a third param varying → the cell medians their objectives.
    params = [
        {"path": "a", "range": [1, 2], "step": 1},
        {"path": "b", "range": [1, 2], "step": 1},
        {"path": "d", "range": [1, 9], "step": 1},
    ]
    rows = [
        ({"a": 1, "b": 1, "d": 1}, 10.0),
        ({"a": 1, "b": 1, "d": 2}, 20.0),  # same (a,b) as above
        ({"a": 2, "b": 2, "d": 1}, 99.0),
    ]
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, params, rows)
    slices = _get(jobs, trials, sweep_id).json()["slices"]
    ab = next(s for s in slices if {s["paramX"], s["paramY"]} == {"a", "b"})
    cell = next(c for c in ab["cells"] if c["x"] == 1 and c["y"] == 1)
    assert cell["count"] == 2
    assert cell["objective"] == pytest.approx(15.0)  # median(10, 20)


# --- Degradation + 404 ---------------------------------------------------------------------------

def test_too_few_trials_returns_200_empty_with_note():
    params = [{"path": "a", "range": [1, 3], "step": 1}]
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, params, [({"a": 1}, 1.0)])  # a single completed trial
    resp = _get(jobs, trials, sweep_id)
    assert resp.status_code == 200
    body = resp.json()
    assert body["importance"] == []
    assert body["brittleness"] == []
    assert body["slices"] == []
    assert any("insufficient data" in n for n in body["notes"])


def test_unknown_job_404():
    jobs, trials = FakeJobs(), FakeTrials()
    assert _get(jobs, trials, "does-not-exist").status_code == 404


def test_non_optimization_job_404():
    jobs, trials = FakeJobs(), FakeTrials()
    trial_job = jobs.insert_trial("sweep-x", None, {})  # kind == TRIAL, not OPTIMIZATION
    assert _get(jobs, trials, trial_job).status_code == 404
