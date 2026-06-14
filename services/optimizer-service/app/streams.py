"""Redis Streams transport for the ask/tell loop (§D.7, D12 — transport only).

The optimizer XADDs trial jobs onto ``jobs.backtest.trials`` (backtest-service's
``cg-trials`` consumes) and reads trial metrics back off ``optimizations.results``
via its own ``cg-optuna`` group. Results are XACKed once delivered to the loop.
"""

from __future__ import annotations

from typing import Any

import redis

TRIALS_STREAM = "jobs.backtest.trials"
RESULTS_STREAM = "optimizations.results"
RESULTS_GROUP = "cg-optuna"
PROGRESS_CHANNEL = "jobs.progress"


class TrialDispatcher:
    """Dispatches trial jobs and drains their results (one instance per process)."""

    def __init__(self, client: redis.Redis, consumer: str = "optimizer-1") -> None:
        self._redis = client
        self._consumer = consumer

    def ensure_group(self) -> None:
        """Creates ``cg-optuna`` on ``optimizations.results`` (MKSTREAM); BUSYGROUP is fine."""
        try:
            self._redis.xgroup_create(RESULTS_STREAM, RESULTS_GROUP, id="0", mkstream=True)
        except redis.ResponseError as exc:  # pragma: no cover - depends on prior state
            if "BUSYGROUP" not in str(exc):
                raise

    def dispatch(self, trial_job_id: str) -> None:
        """XADDs a queued TRIAL job id onto the trials stream (its jobs row already exists)."""
        self._redis.xadd(TRIALS_STREAM, {"jobId": str(trial_job_id)})

    def read_results(self, max_count: int, block_ms: int = 2000) -> list[dict[str, Any]]:
        """Reads up to ``max_count`` trial results (decoded field maps), XACKing each."""
        response = self._redis.xreadgroup(
            RESULTS_GROUP,
            self._consumer,
            {RESULTS_STREAM: ">"},
            count=max_count,
            block=block_ms,
        )
        out: list[dict[str, Any]] = []
        if not response:
            return out
        for _stream, entries in response:
            for entry_id, fields in entries:
                out.append({_decode(k): _decode(v) for k, v in fields.items()})
                self._redis.xack(RESULTS_STREAM, RESULTS_GROUP, entry_id)
        return out

    def publish_progress(self, job_id: str, payload: str) -> None:
        """Publishes a sweep-progress delta on the single ``jobs.progress`` channel (D10); the
        payload's own ``jobId`` lets the gateway-relayed browser fan out per job. ``job_id`` is kept
        for caller symmetry with the backtest-service publisher."""
        self._redis.publish(PROGRESS_CHANNEL, payload)


def _decode(value: Any) -> Any:
    return value.decode() if isinstance(value, bytes) else value
