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

# Boot-recovery marker (audit P2-1 / F4): an OPTIMIZATION sweep runs on an in-process daemon
# thread holding an in-memory Optuna study — a service restart abandons it with no thread to
# resume. fail_orphaned_sweeps() stamps THIS text into jobs.error so a restart-killed sweep is
# loudly distinguishable from a genuine failure (previously error was left NULL — the two were
# indistinguishable in the UI/API).
ORPHAN_SWEEP_ERROR = (
    "sweep interrupted by an optimizer restart — in-process sweep threads do not survive a "
    "restart and cannot resume; resubmit the sweep to re-run"
)


class JobsRepo:
    """The shared ``jobs`` table (backtest-service is the BACKTEST/TRIAL writer; the optimizer
    writes OPTIMIZATION parent rows + the queued TRIAL child rows it dispatches)."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    def close(self) -> None:
        """Closes the underlying connection (the factory opens one per use)."""
        self._conn.close()

    def insert_sweep(
        self,
        strategy_version_id: str | None,
        request: dict[str, Any],
        created_by: str = "optimizer",
    ) -> str:
        # Audit T3 / EVO §13 row 4: the sweep parent is engine-created — its own id IS the sweep
        # id, so the bare 'optimizer' actor marks it (trials carry 'optimizer:{sweepId}').
        return self._insert("OPTIMIZATION", None, strategy_version_id, request, created_by)

    def insert_trial(
        self,
        sweep_id: str,
        strategy_version_id: str | None,
        request: dict[str, Any],
        created_by: str | None = None,
    ) -> str:
        # A trial belongs to its sweep — 'optimizer:{sweepId}' ties every child (and its run) back
        # to the parent OPTIMIZATION job for EVO's prefix-filterable provenance.
        actor = created_by if created_by is not None else f"optimizer:{sweep_id}"
        return self._insert("TRIAL", sweep_id, strategy_version_id, request, actor)

    def _insert(
        self,
        kind: str,
        parent: str | None,
        version_id: str | None,
        request: dict[str, Any],
        created_by: str,
    ) -> str:
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO jobs (kind, parent_job_id, strategy_version_id, request, created_by) "
                "VALUES (%s, %s, %s, %s::jsonb, %s) RETURNING id",
                (kind, parent, version_id, json.dumps(request), created_by),
            )
            row_id = cur.fetchone()[0]
        self._conn.commit()
        return str(row_id)

    def set_status(self, job_id: str, status: str, progress: int | None = None) -> None:
        """Status write with a terminal guard (audit P1-10b): a row already in
        completed/failed/cancelled is never overwritten — the sweep thread's final
        'completed' (or the blanket failure handler) used to clobber a just-written
        'cancelled', misrepresenting the research record."""
        guard = " AND status NOT IN ('completed','failed','cancelled')"
        with self._conn.cursor() as cur:
            if progress is None:
                cur.execute("UPDATE jobs SET status=%s WHERE id=%s" + guard, (status, job_id))
            else:
                cur.execute(
                    "UPDATE jobs SET status=%s, progress=%s WHERE id=%s" + guard,
                    (status, progress, job_id),
                )
        self._conn.commit()

    def fail_orphaned_sweeps(self) -> int:
        """Boot recovery (audit P2-1 / F4): sweeps run as in-memory daemon threads, so a restart
        strands their OPTIMIZATION rows at queued/running forever (no thread can resume an
        in-memory Optuna study). Marks them failed AND populates ``error`` with a real reason so a
        restart-killed sweep is loudly distinguishable from a genuine failure — the error was
        previously left NULL, making the two indistinguishable in the UI/API. COALESCE guards a
        pre-existing error (a queued/running row never has one, but the write stays idempotent)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE jobs SET status='failed', error=COALESCE(error, %s)"
                " WHERE kind='OPTIMIZATION' AND status IN ('queued','running')",
                (ORPHAN_SWEEP_ERROR,),
            )
            count = cur.rowcount
        self._conn.commit()
        return count

    def list_sweeps(self, limit: int, offset: int) -> list[dict[str, Any]]:
        """Native sweep listing (audit P2-1): OPTIMIZATION rows newest-first for the sweep-scoped
        list surface (``GET /optimizations/jobs``). Sweeps were only discoverable via the shared
        backtest jobs table before. Returns the shared fields plus ``error`` + ``created_at`` so a
        restart-interrupted sweep shows its reason in the list."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT id, status, progress, request, error, created_at FROM jobs "
                "WHERE kind='OPTIMIZATION' ORDER BY created_at DESC LIMIT %s OFFSET %s",
                (limit, offset),
            )
            rows = cur.fetchall()
        return [
            {
                "id": str(r[0]),
                "status": r[1],
                "progress": r[2],
                "request": r[3],
                "error": r[4],
                "createdAt": r[5].isoformat() if r[5] else None,
            }
            for r in rows
        ]

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
