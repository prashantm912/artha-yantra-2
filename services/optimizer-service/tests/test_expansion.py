"""§D.7 sweep expansion: grid value sets, grid size cap, planned-trial counts."""

from app import optuna_runner


def test_grid_values_from_range_and_step():
    assert optuna_runner.grid_values({"path": "p", "range": [5, 13], "step": 1}) == [
        5, 6, 7, 8, 9, 10, 11, 12, 13
    ]
    assert optuna_runner.grid_values({"path": "p", "range": [0, 1], "step": 0.5}) == [0, 0.5, 1]


def test_grid_values_from_choices():
    assert optuna_runner.grid_values({"path": "p", "choices": ["a", "b"]}) == ["a", "b"]


def test_grid_size_is_the_product():
    params = [
        {"path": "a", "range": [1, 3], "step": 1},  # 3
        {"path": "b", "choices": ["x", "y"]},  # 2
    ]
    assert optuna_runner.grid_size(params) == 6


def test_planned_trials_caps_grid_but_runs_full_random():
    params = [{"path": "a", "range": [1, 100], "step": 1}]  # 100 grid points
    assert optuna_runner.planned_trials(params, "grid", max_trials=20) == 20
    assert optuna_runner.planned_trials(params, "grid", max_trials=500) == 100
    assert optuna_runner.planned_trials(params, "random", max_trials=50) == 50


def test_unknown_method_rejected():
    import pytest

    with pytest.raises(ValueError):
        optuna_runner.make_sampler("annealing", [{"path": "a", "range": [1, 2], "step": 1}], seed=1)
