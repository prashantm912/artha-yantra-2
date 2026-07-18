"""The fold-sweep objective guard: a walk-forward sweep on an in-sample metric is steered to
oos_fold_mean (+ warning), so the silent in-sample overfit trap can't recur. Foldless sweeps and
sweeps already on oos_fold_mean are untouched."""

from app import metrics_catalog
from app.service import (
    _effective_fold_direction,
    _effective_fold_metric,
    _fold_objective_guard,
)

_WF = {"train_days": 30, "test_days": 30, "step_days": 20}
_SHARPE = {"metric": "sharpe", "direction": "maximize"}
_OOS = {"metric": "oos_fold_mean", "direction": "maximize"}


def test_fold_sweep_on_insample_metric_is_overridden_to_oos():
    objective, warning = _fold_objective_guard(_SHARPE, _WF)
    assert objective == _OOS
    assert warning is not None and "oos_fold_mean" in warning


def test_fold_sweep_already_on_oos_is_untouched():
    objective, warning = _fold_objective_guard(_OOS, _WF)
    assert objective == _OOS
    assert warning is None


def test_foldless_sweep_keeps_its_objective():
    objective, warning = _fold_objective_guard(_SHARPE, None)
    assert objective == _SHARPE
    assert warning is None


def test_fold_sweep_with_empty_objective_defaults_to_oos():
    objective, warning = _fold_objective_guard({}, _WF)
    assert objective == _OOS
    assert warning is not None


# --- AY-OPT-02: the fold objective metric is REQUEST-owned, YAML is the fallback ------------------

_YAML_SHARPE = {"objective": {"metric": "sharpe"}}


def test_effective_fold_metric_prefers_request_over_yaml():
    # The documented request/YAML split: the request's concrete objective metric wins over the YAML.
    assert _effective_fold_metric({"metric": "expectancy"}, _YAML_SHARPE) == "expectancy"


def test_effective_fold_metric_falls_back_to_yaml_when_request_omits_it():
    # Request names no objective → the YAML objective.metric is the fallback.
    assert _effective_fold_metric({}, {"objective": {"metric": "expectancy"}}) == "expectancy"


def test_effective_fold_metric_oos_fold_mean_is_the_aggregate_not_a_fold_metric():
    # oos_fold_mean is the AGGREGATE (mean of the fold metric), not a per-fold metric → YAML wins.
    yaml_cagr = {"objective": {"metric": "cagr"}}
    assert _effective_fold_metric({"metric": "oos_fold_mean"}, yaml_cagr) == "cagr"


def test_effective_fold_metric_none_when_neither_names_one():
    # Neither the request nor the YAML names a metric → None (the worker defaults to sharpe).
    assert _effective_fold_metric({}, {}) is None


# --- AY-OPT-02: the fold objective DIRECTION follows the metric (minimize metrics pick best) ---

def test_canonical_direction_reuses_the_metric_catalog():
    # Reuse the catalog's per-metric direction (no hand-maintained list): lower-is-better=minimize.
    assert metrics_catalog.canonical_direction("maxDrawdown") == "minimize"
    assert metrics_catalog.canonical_direction("expectancy") == "maximize"
    assert metrics_catalog.canonical_direction("tradeCount") == "maximize"  # neutral → maximize
    assert metrics_catalog.canonical_direction(None) == "maximize"  # unknown → maximize


def test_effective_fold_direction_request_wins():
    req = {"metric": "maxDrawdown", "direction": "minimize"}
    yaml = {"objective": {"direction": "maximize"}}
    assert _effective_fold_direction(req, yaml, "maxDrawdown") == "minimize"


def test_effective_fold_direction_falls_back_to_yaml_then_catalog():
    # Request omits direction → the YAML objective's direction wins over the catalog.
    assert _effective_fold_direction({}, {"objective": {"direction": "minimize"}}, "cagr") == (
        "minimize"
    )
    # Neither request nor YAML gives a direction → the catalog's canonical direction for the metric.
    assert _effective_fold_direction({}, {}, "maxDrawdown") == "minimize"
    assert _effective_fold_direction({}, {}, "expectancy") == "maximize"
