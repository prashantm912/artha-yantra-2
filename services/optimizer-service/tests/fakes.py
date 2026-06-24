"""In-memory doubles for the sweep collaborators — no Postgres, no Redis."""

from __future__ import annotations

import json
from typing import Any


class FakeJobs:
    """In-memory ``jobs`` table."""

    def __init__(self) -> None:
        self.rows: dict[str, dict[str, Any]] = {}
        self._seq = 0

    def insert_sweep(self, version_id: str | None, request: dict[str, Any]) -> str:
        self._seq += 1
        job_id = f"sweep-{self._seq}"
        self.rows[job_id] = {"kind": "OPTIMIZATION", "status": "queued", "progress": 0,
                             "request": request}
        return job_id

    def insert_trial(self, sweep_id: str, version_id: str | None, request: dict[str, Any]) -> str:
        self._seq += 1
        job_id = f"trial-{self._seq}"
        self.rows[job_id] = {"kind": "TRIAL", "status": "queued", "progress": 0,
                             "request": request, "parent": sweep_id}
        return job_id

    def set_status(self, job_id: str, status: str, progress: int | None = None) -> None:
        row = self.rows.setdefault(job_id, {})
        row["status"] = status
        if progress is not None:
            row["progress"] = progress

    def get(self, job_id: str) -> dict[str, Any] | None:
        row = self.rows.get(job_id)
        return {"id": job_id, **row} if row else None

    def close(self) -> None:
        pass


class FakeTrials:
    """In-memory ``optimization_trials`` ledger."""

    def __init__(self) -> None:
        self.rows: dict[int, dict[str, Any]] = {}
        self._seq = 0

    def insert(self, sweep_id: str, trial_number: int, params: dict[str, Any]) -> int:
        self._seq += 1
        self.rows[self._seq] = {"sweep": sweep_id, "trialNumber": trial_number, "params": params,
                                "state": "RUNNING", "objectiveValues": None, "backtestRunId": None}
        return self._seq

    def complete(self, trial_id: int, objective_values: dict[str, Any], run_id: str | None) -> None:
        self.rows[trial_id].update(state="COMPLETE", objectiveValues=objective_values,
                                   backtestRunId=run_id)

    def fail(self, trial_id: int) -> None:
        self.rows[trial_id]["state"] = "FAILED"

    def prune(self, trial_id: int) -> None:
        self.rows[trial_id]["state"] = "PRUNED"

    def list_for_sweep(self, sweep_id: str, state: str | None, limit: int, offset: int) -> list:
        items = [r for r in self.rows.values()
                 if r["sweep"] == sweep_id and (state is None or r["state"] == state)]
        items.sort(key=lambda r: r["trialNumber"])
        return items[offset:offset + limit]

    def get_trial(self, sweep_id: str, trial_number: int) -> dict[str, Any] | None:
        for r in self.rows.values():
            if r["sweep"] == sweep_id and r["trialNumber"] == trial_number:
                return r
        return None

    def close(self) -> None:
        pass


class FakeDispatcher:
    """Synthesizes a deterministic 'backtest' result per dispatched trial: a Sharpe peaking at
    period=15 (so grid/TPE should converge there), read straight back off an internal queue."""

    def __init__(self, jobs: FakeJobs) -> None:
        self._jobs = jobs
        self._queue: list[dict[str, Any]] = []
        self.progress_events: list[str] = []

    def dispatch(self, trial_job_id: str) -> None:
        request = self._jobs.get(str(trial_job_id))["request"]
        period = request["paramsOverride"]["indicators[0].params.period"]
        sharpe = 1.0 - abs(period - 15) / 10.0
        self._queue.append({
            "trialId": str(trial_job_id),
            "runId": f"run-{trial_job_id}",
            "metrics": json.dumps({"sharpe": sharpe}),
        })

    def read_results(self, max_count: int, block_ms: int = 2000) -> list[dict[str, Any]]:
        out = self._queue[:max_count]
        self._queue = self._queue[max_count:]
        return out

    def publish_progress(self, job_id: str, payload: str) -> None:
        self.progress_events.append(payload)


class FakeStrategy:
    """Returns a fixed version config (with an optimize block) and records promote drafts."""

    def __init__(self, config: dict[str, Any]) -> None:
        self._config = config
        self.drafts: list[dict[str, Any]] = []

    def version_config(self, strategy_id: str, version: str) -> dict[str, Any]:
        return self._config

    def resolve(self, strategy_id: str, version: str | None) -> tuple[str, dict[str, Any]]:
        return (version or "1.0.0"), self._config

    def create_draft(self, strategy_id: str, config: dict[str, Any], notes: str) -> dict[str, Any]:
        self.drafts.append({"strategyId": strategy_id, "config": config, "notes": notes})
        return {"version": "1.1.0", "status": "draft"}


class FakeBacktest:
    """Returns a fixed fold array (and optional guard summary) for any run id."""

    def __init__(self, folds: Any, guard: Any = None) -> None:
        self._folds = folds
        self._guard = guard
        self.calls: list[str] = []
        self.guard_calls: list[str] = []

    def folds(self, run_id: str) -> Any:
        self.calls.append(run_id)
        return self._folds

    def guard_summary(self, run_id: str) -> Any:
        self.guard_calls.append(run_id)
        return self._guard
