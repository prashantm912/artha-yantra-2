"""Postgres access for the shared ``jobs`` table and the optimizer-owned
``optimization_trials`` table (§D.3). Thin psycopg wrappers — the optimizer
INSERTs the authoritative TRIAL ``jobs`` row BEFORE XADDing (D12), so the
worker's conditional claim always has a row to claim.

The sweep loop depends only on these public methods, so tests substitute
in-memory fakes (duck-typed) and need no database.
"""

from __future__ import annotations

import json
from typing import Any

import psycopg


class JobsRepo:
    """The shared ``jobs`` table (backtest-service is the BACKTEST/TRIAL writer; the optimizer
    writes OPTIMIZATION parent rows + the queued TRIAL child rows it dispatches)."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    def close(self) -> None:
        """Closes the underlying connection (the factory opens one per use)."""
        self._conn.close()

    def insert_sweep(self, strategy_version_id: str | None, request: dict[str, Any]) -> str:
        return self._insert("OPTIMIZATION", None, strategy_version_id, request)

    def insert_trial(
        self, sweep_id: str, strategy_version_id: str | None, request: dict[str, Any]
    ) -> str:
        return self._insert("TRIAL", sweep_id, strategy_version_id, request)

    def _insert(
        self, kind: str, parent: str | None, version_id: str | None, request: dict[str, Any]
    ) -> str:
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO jobs (kind, parent_job_id, strategy_version_id, request) "
                "VALUES (%s, %s, %s, %s::jsonb) RETURNING id",
                (kind, parent, version_id, json.dumps(request)),
            )
            row_id = cur.fetchone()[0]
        self._conn.commit()
        return str(row_id)

    def set_status(self, job_id: str, status: str, progress: int | None = None) -> None:
        with self._conn.cursor() as cur:
            if progress is None:
                cur.execute("UPDATE jobs SET status=%s WHERE id=%s", (status, job_id))
            else:
                cur.execute(
                    "UPDATE jobs SET status=%s, progress=%s WHERE id=%s",
                    (status, progress, job_id),
                )
        self._conn.commit()

    def get(self, job_id: str) -> dict[str, Any] | None:
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT id, kind, status, progress, request FROM jobs WHERE id=%s", (job_id,)
            )
            row = cur.fetchone()
        if row is None:
            return None
        return {"id": str(row[0]), "kind": row[1], "status": row[2], "progress": row[3],
                "request": row[4]}


class TrialsRepo:
    """The optimizer-owned ``optimization_trials`` ledger (resumable via study.add_trial replay)."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    def close(self) -> None:
        """Closes the underlying connection (the factory opens one per use)."""
        self._conn.close()

    def insert(self, sweep_id: str, trial_number: int, params: dict[str, Any]) -> int:
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO optimization_trials (sweep_job_id, trial_number, params) "
                "VALUES (%s, %s, %s::jsonb) RETURNING id",
                (sweep_id, trial_number, json.dumps(params)),
            )
            row_id = cur.fetchone()[0]
        self._conn.commit()
        return int(row_id)

    def complete(
        self, trial_id: int, objective_values: dict[str, Any], backtest_run_id: str | None
    ) -> None:
        self._finish(trial_id, "COMPLETE", objective_values, backtest_run_id)

    def fail(self, trial_id: int) -> None:
        self._finish(trial_id, "FAILED", None, None)

    def prune(self, trial_id: int) -> None:
        self._finish(trial_id, "PRUNED", None, None)

    def _finish(
        self,
        trial_id: int,
        state: str,
        objective_values: dict[str, Any] | None,
        backtest_run_id: str | None,
    ) -> None:
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE optimization_trials SET state=%s, objective_values=%s::jsonb, "
                "backtest_run_id=%s, completed_at=now() WHERE id=%s",
                (
                    state,
                    json.dumps(objective_values) if objective_values is not None else None,
                    backtest_run_id,
                    trial_id,
                ),
            )
        self._conn.commit()

    def get_trial(self, sweep_id: str, trial_number: int) -> dict[str, Any] | None:
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT trial_number, params, objective_values, state, backtest_run_id "
                "FROM optimization_trials WHERE sweep_job_id=%s AND trial_number=%s",
                (sweep_id, trial_number),
            )
            row = cur.fetchone()
        if row is None:
            return None
        return {
            "trialNumber": row[0],
            "params": row[1],
            "objectiveValues": row[2],
            "state": row[3],
            "backtestRunId": str(row[4]) if row[4] else None,
        }

    def list_for_sweep(
        self, sweep_id: str, state: str | None, limit: int, offset: int
    ) -> list[dict[str, Any]]:
        sql = (
            "SELECT trial_number, params, objective_values, state, backtest_run_id "
            "FROM optimization_trials WHERE sweep_job_id=%s"
        )
        args: list[Any] = [sweep_id]
        if state:
            sql += " AND state=%s"
            args.append(state)
        sql += " ORDER BY trial_number LIMIT %s OFFSET %s"
        args.extend([limit, offset])
        with self._conn.cursor() as cur:
            cur.execute(sql, tuple(args))
            rows = cur.fetchall()
        return [
            {
                "trialNumber": r[0],
                "params": r[1],
                "objectiveValues": r[2],
                "state": r[3],
                "backtestRunId": str(r[4]) if r[4] else None,
            }
            for r in rows
        ]
