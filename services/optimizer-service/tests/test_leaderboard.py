"""§D.9 leaderboard: plateau-adjusted default sort (a broad ridge beats a lone spike) and the
service-level best() that excludes pruned/failed trials from the ranking."""

from app import leaderboard
from app.service import SweepService
from tests.fakes import FakeJobs, FakeStrategy, FakeTrials

PARAMS = [{"path": "p", "range": [1, 10], "step": 1}]


def _t(number, value):
    return {"trialNumber": number, "params": {"p": number}, "objective": value}


def test_spike_sinks_and_ridge_rises():
    trials = [
        _t(1, 0.1), _t(2, 1.0), _t(3, 0.1),  # a lone spike at p=2
        _t(6, 0.80), _t(7, 0.85), _t(8, 0.82),  # a broad ridge around p=7
    ]
    scored = {r["trialNumber"]: r for r in leaderboard.plateau_scores(trials, PARAMS)}
    assert scored[2]["plateauObjective"] == 0.1  # median(1.0, 0.1, 0.1) — spike sinks
    assert scored[7]["plateauObjective"] == 0.82  # median(0.85, 0.80, 0.82) — ridge holds
    # plateau (default) ranks a ridge trial first (never the spike); raw still surfaces the spike
    assert leaderboard.best(trials, PARAMS, 1, "maximize", "plateau")[0]["trialNumber"] in {6, 7, 8}
    assert leaderboard.best(trials, PARAMS, 1, "maximize", "raw")[0]["trialNumber"] == 2


def test_choice_neighbors_are_adjacent_only():
    params = [{"path": "m", "choices": ["a", "b", "c", "d"]}]
    trials = [
        {"trialNumber": i, "params": {"m": c}, "objective": o}
        for i, (c, o) in enumerate([("a", 0.2), ("b", 0.3), ("c", 0.9), ("d", 0.4)])
    ]
    scored = {r["trialNumber"]: r for r in leaderboard.plateau_scores(trials, params)}
    assert scored[2]["plateauObjective"] == 0.4  # 'c' neighbors b,d → median(0.9,0.3,0.4)


def _service(jobs, trials):
    return SweepService(
        strategy_client=FakeStrategy({}),
        jobs_factory=lambda: jobs,
        trials_factory=lambda: trials,
        dispatcher=None,
        runner=lambda **k: None,
    )


def test_best_excludes_pruned_and_failed():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = jobs.insert_sweep(
        None,
        {
            "objective": {"metric": "sharpe", "direction": "maximize"},
            "parameters": [{"path": "indicators[0].params.period", "range": [5, 15], "step": 1}],
        },
    )
    good = trials.insert(sweep_id, 1, {"indicators[0].params.period": 10})
    trials.complete(good, {"sharpe": 1.5}, "r1")
    trials.prune(trials.insert(sweep_id, 2, {"indicators[0].params.period": 11}))
    trials.fail(trials.insert(sweep_id, 3, {"indicators[0].params.period": 12}))
    out = _service(jobs, trials).best(sweep_id, top=10, sort="raw")
    assert out["metric"] == "sharpe"
    assert [r["trialNumber"] for r in out["items"]] == [1]
