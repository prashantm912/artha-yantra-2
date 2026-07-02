"""The ask/tell sweep loop (§D.7, Flow 5). The optimizer proposes parameter
vectors and dispatches each as a TRIAL job; backtest-service evaluates them
through the SAME engine JAR (D6) and streams metrics back. The loop never
evaluates a strategy itself.

``grid``/``random``/``tpe`` are single-objective and support fold-fed
``MedianPruner`` early stopping; ``nsga2`` is multi-objective and returns a
Pareto front (never collapsed to one number, never pruned — Optuna does not
prune multi-objective studies).

Collaborators (jobs/trials repos, dispatcher) are injected so the loop is unit
tested with in-memory fakes — no Postgres, no Redis."""

from __future__ import annotations

import json
import math
from collections.abc import Callable
from typing import Any

import optuna

from app import optuna_runner

optuna.logging.set_verbosity(optuna.logging.WARNING)

_TERMINAL_NO_RESULT = {"failed", "cancelled"}

# nsga2 default objectives when the request names none: maximize return, minimize drawdown
# (§D.7 — "the owner picks from the Pareto front with drawdown explicitly on the axis").
_DEFAULT_MULTI_OBJECTIVES = (
    {"metric": "cagr", "direction": "maximize"},
    {"metric": "maxDrawdown", "direction": "minimize"},
)


def objective_value(result: dict[str, Any], metric: str) -> float:
    """Pulls one objective metric out of a trial result entry."""
    if metric == "oos_fold_mean" and result.get("oosFoldMean") is not None:
        return float(result["oosFoldMean"])
    metrics = json.loads(result.get("metrics") or "{}")
    raw = metrics.get(metric)
    return float(raw) if raw is not None else math.nan


def multi_objectives(objective: dict[str, Any]) -> list[dict[str, Any]]:
    """The objective list for an nsga2 sweep (explicit ``objectives``, else the return/drawdown
    default). Each entry is ``{metric, direction}``."""
    objs = objective.get("objectives")
    return list(objs) if objs else [dict(o) for o in _DEFAULT_MULTI_OBJECTIVES]


def should_prune_trial(trial: optuna.Trial, result: dict[str, Any]) -> bool:
    """Reports a trial's per-fold OOS objectives (CD-12 — the flat ``oosFoldObjectives`` array the
    TRIAL worker emits) into the study's pruner and returns whether the trial should be pruned.
    A full-window trial (no folds) is never pruned. Fed by OOS fold values only."""
    raw = result.get("oosFoldObjectives")
    if not raw:
        return False
    try:
        folds = json.loads(raw)
    except (TypeError, ValueError):
        return False
    for step, fold_oos in enumerate(folds):
        try:
            value = float(fold_oos)
        except (TypeError, ValueError):
            continue
        trial.report(value, step)
        if trial.should_prune():
            return True
    return False


def build_trial_request(
    request_base: dict[str, Any], params: dict[str, Any], walk_forward: dict[str, Any] | None
) -> dict[str, Any]:
    """A TRIAL job request = the base run spec + the sampled override + fold context."""
    request = dict(request_base)
    request["paramsOverride"] = params
    request["foldContext"] = bool(walk_forward)
    if walk_forward:
        request["walkForward"] = walk_forward
    return request


def run_sweep(
    *,
    sweep_id: str,
    strategy_version_id: str | None,
    parameters: list[dict[str, Any]],
    method: str,
    max_trials: int,
    objective: dict[str, Any],
    seed: int,
    walk_forward: dict[str, Any] | None,
    request_base: dict[str, Any],
    jobs: Any,
    trials: Any,
    dispatcher: Any,
    on_progress: Callable[[int, int, float | None], None] | None = None,
    parallelism: int = 4,
    early_stopping: int | None = None,
    cancelled: Callable[[], bool] | None = None,
) -> optuna.Study:
    """Runs the sweep to completion and returns the in-memory study.

    ``cancelled`` is polled each loop iteration (audit P1-10b): before, a cancel was observed
    only when a RESULT arrived — a sweep whose worker was down span forever, uncancellable.
    On observed cancel the loop stops dispatching and returns WITHOUT writing a terminal
    status (``cancel()`` already wrote 'cancelled'; the repo guard keeps it)."""
    sampler = optuna_runner.make_sampler(method, parameters, seed)
    multi = method == "nsga2"
    direction = objective.get("direction", "maximize")
    metric = objective.get("metric", "sharpe")
    if multi:
        objs = multi_objectives(objective)
        study = optuna.create_study(directions=[o["direction"] for o in objs], sampler=sampler)
    else:
        objs = None
        study = optuna.create_study(
            direction=direction, sampler=sampler, pruner=optuna_runner.make_pruner()
        )
    planned = optuna_runner.planned_trials(parameters, method, max_trials)

    pending: dict[str, tuple[optuna.Trial, int]] = {}
    completed = 0
    best: float | None = None
    since_improved = 0
    stopped_early = False

    while completed < planned and not stopped_early:
        if cancelled and cancelled():
            return study
        while (
            len(pending) < parallelism
            and completed + len(pending) < planned
            and not stopped_early
        ):
            trial = study.ask()
            params = optuna_runner.suggest_params(trial, parameters)
            trial_job_id = jobs.insert_trial(
                sweep_id,
                strategy_version_id,
                build_trial_request(request_base, params, walk_forward),
            )
            row_id = trials.insert(sweep_id, trial.number, params)
            pending[str(trial_job_id)] = (trial, row_id)
            dispatcher.dispatch(trial_job_id)

        results = dispatcher.read_results(max_count=max(len(pending), 1), sweep_id=sweep_id)
        for result in results:
            key = result.get("trialId")
            if key not in pending:
                continue
            trial, row_id = pending.pop(key)
            value = _resolve(study, trial, row_id, result, multi, objs, metric, trials)
            if not multi:
                if value is not None:
                    new_best = _better(best, value, direction)
                    since_improved = 0 if new_best != best else since_improved + 1
                    best = new_best
                else:
                    since_improved += 1
            completed += 1
            if on_progress:
                on_progress(completed, planned, best)
            if early_stopping and not multi and since_improved >= early_stopping:
                stopped_early = True

        if not results and pending:
            completed += _reconcile_dead(pending, jobs, trials, study)

    jobs.set_status(sweep_id, "completed", 100)
    return study


def _resolve(
    study: optuna.Study,
    trial: optuna.Trial,
    row_id: int,
    result: dict[str, Any],
    multi: bool,
    objs: list[dict[str, Any]] | None,
    metric: str,
    trials: Any,
) -> float | None:
    """Tells the study one trial result and updates its ledger row. Returns the single-objective
    value on success (for best/early-stop tracking), else ``None`` (multi-objective, failed,
    or pruned)."""
    if multi:
        values = [objective_value(result, o["metric"]) for o in objs]
        if any(math.isnan(v) for v in values):
            _tell(study, trial, state=optuna.trial.TrialState.FAIL)
            trials.fail(row_id)
        else:
            _tell(study, trial, values=values)
            trials.complete(row_id, _named(objs, values), result.get("runId"))
        return None

    if should_prune_trial(trial, result):
        _tell(study, trial, state=optuna.trial.TrialState.PRUNED)
        trials.prune(row_id)
        return None

    value = objective_value(result, metric)
    if math.isnan(value):
        _tell(study, trial, state=optuna.trial.TrialState.FAIL)
        trials.fail(row_id)
        return None
    _tell(study, trial, value=value)
    trials.complete(row_id, {metric: value}, result.get("runId"))
    return value


def _named(objs: list[dict[str, Any]], values: list[float]) -> dict[str, float]:
    return {o["metric"]: v for o, v in zip(objs, values, strict=True)}


def _tell(
    study: optuna.Study,
    trial: optuna.Trial,
    *,
    value: float | None = None,
    values: list[float] | None = None,
    state=None,
) -> None:
    """Records a trial result, swallowing the ``Study.stop`` RuntimeError the GridSampler raises
    when the grid is exhausted (the value is already recorded; stop() only fires after)."""
    try:
        if state is not None:
            study.tell(trial, state=state)
        elif values is not None:
            study.tell(trial, values)
        else:
            study.tell(trial, value)
    except RuntimeError as exc:
        if "Study.stop" not in str(exc):
            raise


def _reconcile_dead(
    pending: dict[str, tuple[optuna.Trial, int]], jobs: Any, trials: Any, study: optuna.Study
) -> int:
    """Resolves pending trials whose job died without a result, so the loop never hangs."""
    resolved = 0
    for key in list(pending):
        job = jobs.get(key)
        if job and job.get("status") in _TERMINAL_NO_RESULT:
            trial, row_id = pending.pop(key)
            study.tell(trial, state=optuna.trial.TrialState.FAIL)
            trials.fail(row_id)
            resolved += 1
    return resolved


def _better(best: float | None, value: float, direction: str) -> float:
    if best is None:
        return value
    if direction == "minimize":
        return min(best, value)
    return max(best, value)
