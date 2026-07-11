"""§D.9 promote + folds route: a COMPLETE trial's params materialize onto the source version as a
new DRAFT (provenance in the `created_by` column — audit T3; exactly the trial's deltas);
invalid/failed → 409; folds resolve via the trial's backtest_run_id."""

import pytest

from app.errors import ApiError
from app.service import SweepService
from tests.fakes import FakeBacktest, FakeJobs, FakeStrategy, FakeTrials

CONFIG = {
    "indicators": [{"alias": "ema", "type": "ema", "params": {"period": 10}}],
    "entry_rules": {"scoring": {"threshold": 5}},
    "risk": {"position_sizing": {"risk_pct": 1.0}},
}
SWEEP_REQUEST = {
    "strategyId": "s1",
    "strategyVersion": "1.0.0",
    "objective": {"metric": "sharpe", "direction": "maximize"},
    "parameters": [{"path": "indicators[alias=ema].params.period", "range": [5, 15], "step": 1}],
}
PARAMS = {"indicators[alias=ema].params.period": 12}


def _service(strategy, jobs, trials, backtest=None):
    return SweepService(
        strategy_client=strategy,
        backtest_client=backtest,
        jobs_factory=lambda: jobs,
        trials_factory=lambda: trials,
        dispatcher=None,
        runner=lambda **k: None,
    )


def _seed(jobs, trials, state="COMPLETE", run_id="run-3"):
    sweep_id = jobs.insert_sweep(None, SWEEP_REQUEST)
    row = trials.insert(sweep_id, 3, PARAMS)
    if state == "COMPLETE":
        trials.complete(row, {"sharpe": 1.4}, run_id)
    else:
        trials.fail(row)
    return sweep_id


def test_promote_creates_draft_with_trial_params():
    jobs, trials, strat = FakeJobs(), FakeTrials(), FakeStrategy(CONFIG)
    sweep_id = _seed(jobs, trials)
    out = _service(strat, jobs, trials).promote(sweep_id, 3, "great ridge")
    assert out == {"strategyId": "s1", "newVersion": "1.1.0", "status": "draft"}
    draft = strat.drafts[0]
    assert draft["config"]["indicators"][0]["params"]["period"] == 12  # the trial delta
    # Audit T3: provenance is the machine-readable `created_by` column, not embedded in the note.
    assert draft["createdBy"] == f"optimizer:{sweep_id}"
    assert "great ridge" in draft["notes"] and "created_by=" not in draft["notes"]
    assert CONFIG["indicators"][0]["params"]["period"] == 10  # source untouched


def test_promote_failed_trial_is_409():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, state="FAILED")
    with pytest.raises(ApiError) as exc:
        _service(FakeStrategy(CONFIG), jobs, trials).promote(sweep_id, 3, None)
    assert exc.value.status == 409


def test_promote_unknown_trial_is_404():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = jobs.insert_sweep(None, SWEEP_REQUEST)
    with pytest.raises(ApiError) as exc:
        _service(FakeStrategy(CONFIG), jobs, trials).promote(sweep_id, 99, None)
    assert exc.value.status == 404


def test_trial_folds_resolves_via_run_id():
    jobs, trials = FakeJobs(), FakeTrials()
    backtest = FakeBacktest([{"fold": 0, "oosMetrics": {"sharpe": "0.7"}}])
    sweep_id = _seed(jobs, trials)
    out = _service(FakeStrategy(CONFIG), jobs, trials, backtest).trial_folds(sweep_id, 3)
    assert out[0]["fold"] == 0
    assert backtest.calls == ["run-3"]


def test_trial_folds_full_window_is_empty():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, run_id=None)
    out = _service(FakeStrategy(CONFIG), jobs, trials, FakeBacktest([])).trial_folds(sweep_id, 3)
    assert out == []
