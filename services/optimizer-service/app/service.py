"""Sweep submission + query orchestration — the seam the API depends on (and tests
replace with fakes). Validates the optimize block + path grammar, INSERTs the
parent OPTIMIZATION job, and runs the ask/tell loop in a background thread."""

from __future__ import annotations

import json
import threading
from collections.abc import Callable
from typing import Any

from app import path_grammar, sweep
from app.errors import ApiError

_METHODS = {"grid", "random", "tpe", "nsga2"}


def resolve_parameters(config: dict[str, Any], override: list[dict] | None) -> list[dict]:
    """The sweep's tunable parameters: an explicit override, else the config's optimize block."""
    if override:
        return override
    optimize = config.get("backtest", {}).get("optimize") or config.get("optimize") or {}
    return optimize.get("parameters", [])


class SweepService:
    """Validates + launches sweeps and answers status/trial queries."""

    def __init__(
        self,
        *,
        strategy_client: Any,
        jobs_factory: Callable[[], Any],
        trials_factory: Callable[[], Any],
        dispatcher: Any,
        runner: Callable[..., Any] = sweep.run_sweep,
    ) -> None:
        self._strategy = strategy_client
        self._jobs_factory = jobs_factory  # fresh repo (+ conn) per thread — psycopg isn't shared
        self._trials_factory = trials_factory
        self._dispatcher = dispatcher
        self._runner = runner
        self._cancelled: set[str] = set()

    def submit(self, request: dict[str, Any]) -> str:
        method = request.get("method", "tpe")
        if method not in _METHODS:
            raise ApiError(400, "VALIDATION_FAILED", f"unsupported method: {method!r}")
        for field in ("strategyId", "strategyVersion", "from", "to"):
            if not request.get(field):
                raise ApiError(400, "VALIDATION_FAILED", f"missing required field: {field}")

        config = self._strategy.version_config(request["strategyId"], request["strategyVersion"])
        parameters = resolve_parameters(config, request.get("parameters"))
        if not parameters:
            raise ApiError(422, "VALIDATION_FAILED", "no tunable parameters in the optimize block")
        for parameter in parameters:
            path_grammar.validate(parameter["path"])  # raises InvalidParameterPath -> 400 handler

        jobs = self._jobs_factory()
        try:
            sweep_id = jobs.insert_sweep(None, _sweep_echo(request, parameters))
        finally:
            jobs.close()

        request_base = {
            "strategyId": request["strategyId"],
            "strategyVersion": request["strategyVersion"],
            "from": request["from"],
            "to": request["to"],
        }
        for optional in ("interval", "initialCapital"):
            if request.get(optional) is not None:
                request_base[optional] = request[optional]

        thread = threading.Thread(
            target=self._run,
            kwargs={
                "sweep_id": sweep_id,
                "parameters": parameters,
                "method": method,
                "max_trials": int(request.get("maxTrials", 100)),
                "objective": request.get(
                    "objective", {"metric": "sharpe", "direction": "maximize"}
                ),
                "seed": int(request.get("seed", 0)),
                "walk_forward": request.get("walkForward"),
                "request_base": request_base,
            },
            daemon=True,
            name=f"sweep-{sweep_id[:8]}",
        )
        thread.start()
        return sweep_id

    def _run(self, **kwargs: Any) -> None:
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        sweep_id = kwargs["sweep_id"]
        jobs.set_status(sweep_id, "running", 0)
        try:
            self._runner(
                strategy_version_id=None,
                jobs=jobs,
                trials=trials,
                dispatcher=self._dispatcher,
                on_progress=lambda done, total, best: self._progress(
                    sweep_id, jobs, done, total, best
                ),
                **kwargs,
            )
        except Exception:  # noqa: BLE001 - mark the sweep failed, never crash the thread
            jobs.set_status(sweep_id, "failed")
        finally:
            jobs.close()
            trials.close()

    def _progress(
        self, sweep_id: str, jobs: Any, done: int, total: int, best: float | None
    ) -> None:
        if sweep_id in self._cancelled:
            raise ApiError(409, "CONFLICT_JOB_TERMINAL", "sweep cancelled")
        pct = int(done * 100 / total) if total else 100
        jobs.set_status(sweep_id, "running", min(pct, 99))
        self._dispatcher.publish_progress(
            sweep_id,
            json.dumps({"jobId": sweep_id, "status": "running", "progress": pct,
                        "trialsCompleted": done, "trialsTotal": total, "bestSoFar": best}),
        )

    def job_status(self, job_id: str) -> dict[str, Any]:
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        try:
            job = jobs.get(job_id)
            if job is None:
                raise ApiError(404, "NOT_FOUND_JOB", f"no such job: {job_id}")
            completed = trials.list_for_sweep(job_id, "COMPLETE", 1000, 0)
            return {
                "jobId": job_id,
                "status": job["status"],
                "progress": job["progress"],
                "trialsCompleted": len(completed),
            }
        finally:
            jobs.close()
            trials.close()

    def trials(self, sweep_id: str, state: str | None, limit: int, offset: int) -> dict[str, Any]:
        trials = self._trials_factory()
        try:
            items = trials.list_for_sweep(sweep_id, state, limit, offset)
        finally:
            trials.close()
        return {"items": items, "limit": limit, "offset": offset}

    def cancel(self, job_id: str) -> None:
        jobs = self._jobs_factory()
        try:
            job = jobs.get(job_id)
            if job is None:
                raise ApiError(404, "NOT_FOUND_JOB", f"no such job: {job_id}")
            if job["status"] in ("completed", "failed", "cancelled"):
                raise ApiError(409, "CONFLICT_JOB_TERMINAL", "sweep already terminal")
            self._cancelled.add(job_id)
            jobs.set_status(job_id, "cancelled")
        finally:
            jobs.close()


def _sweep_echo(request: dict[str, Any], parameters: list[dict]) -> dict[str, Any]:
    echo = {k: request[k] for k in request if k != "parameters"}
    echo["parameters"] = parameters
    return echo
