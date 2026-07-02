"""Redis Streams transport for the ask/tell loop (§D.7, D12 — transport only).

The optimizer XADDs trial jobs onto ``jobs.backtest.trials`` (backtest-service's
``cg-trials`` consumes) and reads trial metrics back off ``optimizations.results``
via its own ``cg-optuna`` group. Results are XACKed once delivered to the loop.
"""

from __future__ import annotations

import threading
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
        # Per-sweep parking lot (audit P1-10b): concurrent sweeps share this ONE consumer and an
        # entry is XACKed on read — before this, a result whose trial belonged to ANOTHER sweep
        # was silently destroyed after the ack, hanging both sweeps at 'running' forever.
        self._parked: dict[str, list[dict[str, Any]]] = {}
        self._lock = threading.Lock()

    def ensure_group(self) -> None:
        """Creates ``cg-optuna`` on ``optimizations.results`` (MKSTREAM); BUSYGROUP is fine."""
        try:
            self._redis.xgroup_create(RESULTS_STREAM, RESULTS_GROUP, id="0", mkstream=True)
        except redis.ResponseError as exc:  # pragma: no cover - depends on prior state
            if "BUSYGROUP" not in str(exc):
                raise

    #: Approximate stream cap (audit P2): acked entries are never trimmed by Redis itself, and a
    #: 48 MB shared instance under volatile-lru evicts the owner's SESSION keys before touching
    #: TTL-less stream bytes. 10k dwarfs any real backlog; jobs are re-derivable from Postgres.
    STREAM_MAXLEN = 10_000

    def dispatch(self, trial_job_id: str) -> None:
        """XADDs a queued TRIAL job id onto the trials stream (its jobs row already exists)."""
        self._redis.xadd(
            TRIALS_STREAM,
            {"jobId": str(trial_job_id)},
            maxlen=self.STREAM_MAXLEN,
            approximate=True,
        )

    def read_results(
        self, max_count: int, block_ms: int = 2000, sweep_id: str | None = None
    ) -> list[dict[str, Any]]:
        """Reads up to ``max_count`` trial results (decoded field maps), XACKing each.

        With ``sweep_id``, an entry carrying a DIFFERENT ``sweepId`` is parked for its owning
        sweep (drained on that sweep's next call) instead of being lost after the ack; entries
        with no ``sweepId`` are delivered to the caller (legacy shape).
        """
        out: list[dict[str, Any]] = []
        if sweep_id is not None:
            with self._lock:
                out.extend(self._parked.pop(sweep_id, []))
        response = self._redis.xreadgroup(
            RESULTS_GROUP,
            self._consumer,
            {RESULTS_STREAM: ">"},
            count=max_count,
            block=block_ms,
        )
        for _stream, entries in response or []:
            for entry_id, fields in entries:
                decoded = {_decode(k): _decode(v) for k, v in fields.items()}
                owner = decoded.get("sweepId")
                if sweep_id is not None and owner and owner != sweep_id:
                    with self._lock:
                        self._parked.setdefault(owner, []).append(decoded)
                else:
                    out.append(decoded)
                self._redis.xack(RESULTS_STREAM, RESULTS_GROUP, entry_id)
        return out

    def publish_progress(self, job_id: str, payload: str) -> None:
        """Publishes a sweep-progress delta on the single ``jobs.progress`` channel (D10); the
        payload's own ``jobId`` lets the gateway-relayed browser fan out per job. ``job_id`` is kept
        for caller symmetry with the backtest-service publisher."""
        self._redis.publish(PROGRESS_CHANNEL, payload)


def _decode(value: Any) -> Any:
    return value.decode() if isinstance(value, bytes) else value
