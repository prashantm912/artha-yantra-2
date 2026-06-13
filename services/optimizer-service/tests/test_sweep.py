"""§D.7 ask/tell loop: a grid sweep dispatches trials, collects results, tells the
study, persists trial rows, and converges on the synthetic optimum (period=15)."""

from app import sweep
from tests.fakes import FakeDispatcher, FakeJobs, FakeTrials

PARAMS = [{"path": "indicators[0].params.period", "range": [5, 25], "step": 1}]
OBJECTIVE = {"metric": "sharpe", "direction": "maximize"}
REQUEST_BASE = {
    "strategyId": "s1",
    "strategyVersion": "1.0.0",
    "from": "2026-01-05",
    "to": "2026-01-10",
}


def _run(method, seed=7, max_trials=21):
    jobs = FakeJobs()
    trials = FakeTrials()
    dispatcher = FakeDispatcher(jobs)
    sweep_id = jobs.insert_sweep(None, {"echo": True})
    study = sweep.run_sweep(
        sweep_id=sweep_id,
        strategy_version_id=None,
        parameters=PARAMS,
        method=method,
        max_trials=max_trials,
        objective=OBJECTIVE,
        seed=seed,
        walk_forward=None,
        request_base=REQUEST_BASE,
        jobs=jobs,
        trials=trials,
        dispatcher=dispatcher,
        parallelism=4,
    )
    return study, jobs, trials, sweep_id


def test_grid_sweep_completes_and_converges():
    study, jobs, trials, sweep_id = _run("grid")
    # every grid point became a COMPLETE trial row
    completed = trials.list_for_sweep(sweep_id, "COMPLETE", 1000, 0)
    assert len(completed) == 21
    # the parent sweep is marked completed
    assert jobs.get(sweep_id)["status"] == "completed"
    # the optimum is the synthetic peak
    assert study.best_params["indicators[0].params.period"] == 15
    assert study.best_value == 1.0


def test_each_trial_carried_its_override_and_a_run_link():
    _study, _jobs, trials, sweep_id = _run("grid")
    rows = trials.list_for_sweep(sweep_id, None, 1000, 0)
    assert all(r["backtestRunId"] is not None for r in rows)
    assert all("indicators[0].params.period" in r["params"] for r in rows)


def test_seeded_random_sweep_is_reproducible():
    study_a, *_ = _run("random", seed=42, max_trials=15)
    study_b, *_ = _run("random", seed=42, max_trials=15)
    seq_a = [t.params["indicators[0].params.period"] for t in study_a.trials]
    seq_b = [t.params["indicators[0].params.period"] for t in study_b.trials]
    assert seq_a == seq_b


def test_foldless_trial_request_marks_full_window():
    request = sweep.build_trial_request(REQUEST_BASE, {"x": 1}, None)
    assert request["foldContext"] is False
    assert request["paramsOverride"] == {"x": 1}
    request_wf = sweep.build_trial_request(REQUEST_BASE, {"x": 1}, {"train_days": 60})
    assert request_wf["foldContext"] is True
    assert request_wf["walkForward"] == {"train_days": 60}
