"""§D.9 leaderboard: plateau-adjusted default sort (a broad ridge beats a lone spike) and the
service-level best() that excludes pruned/failed trials from the ranking."""

from app import leaderboard
from app.service import SweepService
from tests.fakes import FakeBacktest, FakeJobs, FakeStrategy, FakeTrials

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


def test_guard_metrics_aggregates_per_regime_oos_sharpe():
    summary = {
        "dataHash": "abc",
        "foldsExcluded": 1,
        "regimeOos": [
            {"UP_QUIET": {"sharpe": "1.0"}, "DOWN_QUIET": {"sharpe": "0.4"}},
            {"UP_QUIET": {"sharpe": "0.8"}, "UP_TURBULENT": {"sharpe": None}},  # untraded skipped
        ],
    }
    out = leaderboard.guard_metrics(summary)
    assert out["dataHash"] == "abc"
    assert out["foldsExcluded"] == 1
    # canonical RegimeLabel enum order; UP_TURBULENT absent (null Sharpe = untraded)
    assert out["regimesCovered"] == ["UP_QUIET", "DOWN_QUIET"]
    assert out["regimeOosMin"] == 0.4
    assert out["regimeOosMax"] == 1.0
    assert out["regimeOosMean"] == (1.0 + 0.4 + 0.8) / 3


def test_guard_metrics_no_traded_regime_yields_null_aggregates():
    out = leaderboard.guard_metrics({"dataHash": "h", "foldsExcluded": 0, "regimeOos": [{}]})
    assert out["regimesCovered"] == []
    assert out["regimeOosMin"] is None
    assert out["regimeOosMean"] is None
    assert out["regimeOosMax"] is None


def test_regimes_covered_populates_from_canonical_backtest_labels():
    """B10 regression: backtest-service serializes each fold's regimeOos keyed by the RegimeLabel
    enum name (UP_QUIET / UP_TURBULENT / DOWN_QUIET / DOWN_TURBULENT). The pre-fix leaderboard
    compared against a BULL/RANGE/BEAR/CRASH alias set, so the intersection was ALWAYS empty and
    ``regimesCovered`` never populated. It must now surface every traded canonical label, in enum
    order, regardless of the fold order they arrive in."""
    summary = {
        "regimeOos": [
            {"DOWN_TURBULENT": {"sharpe": "0.2"}, "UP_QUIET": {"sharpe": "1.1"}},
            {"DOWN_QUIET": {"sharpe": "0.5"}, "UP_TURBULENT": {"sharpe": "0.9"}},
        ],
    }
    out = leaderboard.guard_metrics(summary)
    assert out["regimesCovered"] == ["UP_QUIET", "UP_TURBULENT", "DOWN_QUIET", "DOWN_TURBULENT"]


def test_legacy_bull_range_aliases_do_not_populate_regimes_covered():
    """Guards the root cause from returning: the retired BULL/RANGE/BEAR/CRASH aliases are NOT
    canonical labels and never appear in a persisted fold_metrics row, so they must not resolve."""
    summary = {"regimeOos": [{"BULL": {"sharpe": "1.0"}, "BEAR": {"sharpe": "0.4"}}]}
    assert leaderboard.guard_metrics(summary)["regimesCovered"] == []


def _service(jobs, trials, backtest=None):
    return SweepService(
        strategy_client=FakeStrategy({}),
        backtest_client=backtest,
        jobs_factory=lambda: jobs,
        trials_factory=lambda: trials,
        dispatcher=None,
        runner=lambda **k: None,
    )


def _seed_one_complete(jobs, trials, run_id="r1"):
    sweep_id = jobs.insert_sweep(
        None,
        {
            "objective": {"metric": "sharpe", "direction": "maximize"},
            "parameters": [{"path": "indicators[0].params.period", "range": [5, 15], "step": 1}],
        },
    )
    good = trials.insert(sweep_id, 1, {"indicators[0].params.period": 10})
    trials.complete(good, {"sharpe": 1.5}, run_id)
    return sweep_id


def test_best_excludes_pruned_and_failed():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_one_complete(jobs, trials)
    trials.prune(trials.insert(sweep_id, 2, {"indicators[0].params.period": 11}))
    trials.fail(trials.insert(sweep_id, 3, {"indicators[0].params.period": 12}))
    out = _service(jobs, trials).best(sweep_id, top=10, sort="raw")
    assert out["metric"] == "sharpe"
    assert [r["trialNumber"] for r in out["items"]] == [1]
    assert "guardMetrics" not in out["items"][0]  # no backtest client wired → no enrichment


def test_best_attaches_guard_metrics_for_fold_runs():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_one_complete(jobs, trials, run_id="r1")
    backtest = FakeBacktest(
        folds=None,
        guard={
            "dataHash": "abc",
            "foldsExcluded": 2,
            "regimeOos": [{"UP_QUIET": {"sharpe": "1.0"}}, {"DOWN_QUIET": {"sharpe": "0.5"}}],
        },
    )
    out = _service(jobs, trials, backtest).best(sweep_id, top=10, sort="raw")
    assert backtest.guard_calls == ["r1"]
    guard = out["items"][0]["guardMetrics"]
    assert guard["regimesCovered"] == ["UP_QUIET", "DOWN_QUIET"]
    assert guard["foldsExcluded"] == 2
    assert guard["regimeOosMin"] == 0.5


def test_best_omits_guard_metrics_for_full_window_run():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed_one_complete(jobs, trials, run_id="r1")
    out = _service(jobs, trials, FakeBacktest(folds=None, guard=None)).best(
        sweep_id, top=10, sort="raw"
    )
    assert "guardMetrics" not in out["items"][0]


# --- AY-OPT-03: an nsga2 sweep's leaderboard is the Pareto front, never a scalar collapse ---------

_MO_OBJECTIVES = [
    {"metric": "cagr", "direction": "maximize"},
    {"metric": "maxDrawdown", "direction": "minimize"},
]


def test_pareto_front_drops_dominated_points_only():
    trials = [
        {"trialNumber": 1, "objectiveValues": {"cagr": 0.30, "maxDrawdown": 0.20}},
        {"trialNumber": 2, "objectiveValues": {"cagr": 0.20, "maxDrawdown": 0.10}},
        {"trialNumber": 3, "objectiveValues": {"cagr": 0.25, "maxDrawdown": 0.25}},  # dominated
        {"trialNumber": 4, "objectiveValues": {"cagr": 0.28}},  # missing maxDrawdown → excluded
    ]
    front = leaderboard.pareto_front(trials, _MO_OBJECTIVES)
    # 1 (best cagr) and 2 (best drawdown) trade off → both non-dominated; 3 is worse on BOTH than 1.
    assert [t["trialNumber"] for t in front] == [1, 2]


def test_best_multi_objective_returns_pareto_front_not_scalar():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = jobs.insert_sweep(
        None, {"method": "nsga2", "parameters": [], "objective": {"objectives": _MO_OBJECTIVES}}
    )
    trials.complete(trials.insert(sweep_id, 1, {"p": 1}), {"cagr": 0.30, "maxDrawdown": 0.20}, "r1")
    trials.complete(trials.insert(sweep_id, 2, {"p": 2}), {"cagr": 0.20, "maxDrawdown": 0.10}, "r2")
    trials.complete(trials.insert(sweep_id, 3, {"p": 3}), {"cagr": 0.25, "maxDrawdown": 0.25}, "r3")
    out = _service(jobs, trials).best(sweep_id, top=1, sort="plateau")
    # NOT collapsed to a single scalar winner even at top=1: the whole non-dominated front is kept.
    assert out["sort"] == "pareto"
    assert out["multiObjective"] is True
    assert out["metric"] is None
    assert out["objectives"] == _MO_OBJECTIVES
    assert sorted(r["trialNumber"] for r in out["items"]) == [1, 2]
    # every front row keeps BOTH objective values — no scalar collapse
    assert all(set(r["objectiveValues"]) == {"cagr", "maxDrawdown"} for r in out["items"])
