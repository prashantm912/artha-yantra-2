"""The ask/tell sweep loop (§D.7, Flow 5). The optimizer proposes parameter
vectors and dispatches each as a TRIAL job; backtest-service evaluates them
through the SAME engine JAR (D6) and streams metrics back. The loop never
evaluates a strategy itself.

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


def objective_value(result: dict[str, Any], metric: str) -> float:
    """Pulls the sweep's objective metric out of a trial result entry."""
    if metric == "oos_fold_mean" and result.get("oosFoldMean") is not None:
        return float(result["oosFoldMean"])
    metrics = json.loads(result.get("metrics") or "{}")
    raw = metrics.get(metric)
    return float(raw) if raw is not None else math.nan


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
) -> optuna.Study:
    """Runs the sweep to completion and returns the in-memory study."""
    direction = objective.get("direction", "maximize")
    metric = objective.get("metric", "sharpe")
    sampler = optuna_runner.make_sampler(method, parameters, seed)
    study = optuna.create_study(direction=direction, sampler=sampler)
    planned = optuna_runner.planned_trials(parameters, method, max_trials)

    pending: dict[str, tuple[optuna.Trial, int]] = {}
    completed = 0
    best: float | None = None

    while completed < planned:
        while len(pending) < parallelism and completed + len(pending) < planned:
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

        results = dispatcher.read_results(max_count=max(len(pending), 1))
        for result in results:
            key = result.get("trialId")
            if key not in pending:
                continue
            trial, row_id = pending.pop(key)
            value = objective_value(result, metric)
            if math.isnan(value):
                _tell(study, trial, state=optuna.trial.TrialState.FAIL)
                trials.fail(row_id)
            else:
                _tell(study, trial, value=value)
                trials.complete(row_id, {metric: value}, result.get("runId"))
                best = _better(best, value, direction)
            completed += 1
            if on_progress:
                on_progress(completed, planned, best)

        if not results:
            completed += _reconcile_dead(pending, jobs, trials, study)

    jobs.set_status(sweep_id, "completed", 100)
    return study


def _tell(
    study: optuna.Study, trial: optuna.Trial, *, value: float | None = None, state=None
) -> None:
    """Records a trial result, swallowing the ``Study.stop`` RuntimeError the GridSampler raises
    when the grid is exhausted (the value is already recorded; stop() only fires after)."""
    try:
        if state is not None:
            study.tell(trial, state=state)
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
