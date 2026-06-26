"""The fold-sweep objective guard: a walk-forward sweep on an in-sample metric is steered to
oos_fold_mean (+ warning), so the silent in-sample overfit trap can't recur. Foldless sweeps and
sweeps already on oos_fold_mean are untouched."""

from app.service import _fold_objective_guard

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
