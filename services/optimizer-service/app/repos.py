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


class TrialNumberConflict(Exception):
    """A concurrent writer took this (sweep_job_id, trial_number) slot (unique-index 23505 —
    e.g. two probe POSTs on one sweep reading the same max_trial_number). Probe submission
    absorbs it: re-read max_trial_number and retry, else skip the cell — never a 500."""


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

    def insert_backtest(
        self, sweep_id: str, request: dict[str, Any], created_by: str
    ) -> str:
        """Insert a queued BACKTEST job for the E2 cost-stress orchestrator (design §3.2.5). A
        stress re-run is an ordinary BACKTEST job (kind=BACKTEST) — NOT a TRIAL — so it never enters
        the ``optimization_trials`` cohort/plateau/z-cohorts and never emits onto
        ``optimizations.results`` (only a TRIAL does, BacktestRunner §D.7). ``parent_job_id`` = the
        source sweep and the ``optimizer:{sweepId}:stress`` actor tie it back to its generation for
        EVO provenance; BacktestRunner reads ``paramsOverride`` + ``stressOverrides`` off the
        request JSONB regardless of kind, so the candidate's params replay at wider slippage."""
        return self._insert("BACKTEST", sweep_id, None, request, created_by)

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
                "SELECT id, kind, status, progress, request, started_at, finished_at "
                "FROM jobs WHERE id=%s",
                (job_id,),
            )
            row = cur.fetchone()
        if row is None:
            return None
        # started_at/finished_at added for the evo recorder (§12 E1 item 3): a recorded generation
        # lifts its started/finished timestamps off the source sweep job. Additive — every existing
        # caller reads by key and is unaffected.
        return {"id": str(row[0]), "kind": row[1], "status": row[2], "progress": row[3],
                "request": row[4], "startedAt": _ts(row[5]), "finishedAt": _ts(row[6])}

    def child_trial_request(self, sweep_id: str) -> dict[str, Any] | None:
        """One of a sweep's child TRIAL job requests (the earliest), reused VERBATIM as the
        neighbor-probe template (§3.2.3) so a probe runs the EXACT resolved version + window + fold
        context the sweep's own trials ran — only ``paramsOverride`` is swapped. ``None`` when the
        sweep has no trial job (a re-resolve here could drift to a newer published version)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT request FROM jobs WHERE parent_job_id=%s AND kind='TRIAL' "
                "ORDER BY created_at LIMIT 1",
                (sweep_id,),
            )
            row = cur.fetchone()
        return row[0] if row is not None else None


class TrialsRepo:
    """The optimizer-owned ``optimization_trials`` ledger (resumable via study.add_trial replay)."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    def close(self) -> None:
        """Closes the underlying connection (the factory opens one per use)."""
        self._conn.close()

    def insert(self, sweep_id: str, trial_number: int, params: dict[str, Any]) -> int:
        try:
            with self._conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO optimization_trials (sweep_job_id, trial_number, params) "
                    "VALUES (%s, %s, %s::jsonb) RETURNING id",
                    (sweep_id, trial_number, json.dumps(params)),
                )
                row_id = cur.fetchone()[0]
        except psycopg.errors.UniqueViolation as exc:
            # A concurrent writer took the (sweep, trial_number) slot. Roll the aborted
            # transaction back so THIS connection stays usable for the caller's re-read + retry.
            self._conn.rollback()
            raise TrialNumberConflict(str(exc)) from exc
        self._conn.commit()
        return int(row_id)

    def max_trial_number(self, sweep_id: str) -> int:
        """The highest ``trial_number`` recorded for a sweep, or -1 when it has none — so a
        neighbor-probe batch continues the numbering (``max + 1``) instead of colliding."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT COALESCE(MAX(trial_number), -1) FROM optimization_trials "
                "WHERE sweep_job_id=%s",
                (sweep_id,),
            )
            return int(cur.fetchone()[0])

    def delete_stranded_running(self) -> int:
        """Boot reaper (EVO E2 probes): DELETEs rows stranded at RUNNING whose parent sweep job is
        already terminal — an optimizer restart mid-probe-drain leaves them unresolvable (the drain
        thread died; nothing will ever complete them), and a stranded RUNNING row dedupes its cell
        away from every future probe POST while never lifting ``neighborCount`` — the probe feature
        would silently defeat itself. DELETE (not flip-to-FAILED) deliberately frees the cell + its
        ``trial_number`` so a re-POST re-probes it. A RUNNING row under a LIVE (queued/running)
        sweep is genuinely in flight and untouched."""
        with self._conn.cursor() as cur:
            cur.execute(
                "DELETE FROM optimization_trials t USING jobs j "
                "WHERE t.sweep_job_id=j.id AND t.state='RUNNING' AND j.kind='OPTIMIZATION' "
                "AND j.status IN ('completed','failed','cancelled')"
            )
            count = cur.rowcount
        self._conn.commit()
        return count

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


def _uuid(value: Any) -> str | None:
    """Stringify a psycopg uuid.UUID (or None) for the JSON envelope."""
    return str(value) if value is not None else None


def _ts(value: Any) -> str | None:
    """ISO-8601 a timestamptz (or None) — mirrors JobsRepo.list_sweeps' createdAt."""
    return value.isoformat() if value is not None else None


def _jsonb(value: Any) -> str | None:
    """json.dumps a JSONB payload for an INSERT, or None → SQL NULL (never the JSON literal
    ``'null'``, which a ``None`` passed through json.dumps would produce)."""
    return json.dumps(value) if value is not None else None


def _campaign_row(r: tuple) -> dict[str, Any]:
    return {
        "id": str(r[0]),
        "strategyId": _uuid(r[1]),
        "family": r[2],
        "evidencePolicy": r[3],
        "objectiveSpec": r[4],
        "searchSpace": r[5],
        "budget": r[6],
        "status": r[7],
        "championVersionId": _uuid(r[8]),
        "createdAt": _ts(r[9]),
        "updatedAt": _ts(r[10]),
    }


def _generation_row(r: tuple) -> dict[str, Any]:
    return {
        "id": str(r[0]),
        "campaignId": str(r[1]),
        "n": r[2],
        "proposal": r[3],
        "searchSpaceHash": r[4],
        "engineSha": r[5],
        "dataEpoch": r[6],
        "stressTouches": r[7],
        "status": r[8],
        "startedAt": _ts(r[9]),
        "finishedAt": _ts(r[10]),
    }


def _candidate_row(r: tuple) -> dict[str, Any]:
    return {
        "id": str(r[0]),
        "generationId": str(r[1]),
        "versionId": _uuid(r[2]),
        "parentCandidateId": _uuid(r[3]),
        "mutationKind": r[4],
        "params": r[5],
        "structureDiff": r[6],
        "sweepJobId": _uuid(r[7]),
        "holdoutRunId": _uuid(r[8]),
        "scorecard": r[9],
        "state": r[10],
        "updatedAt": _ts(r[11]),
    }


_CAMPAIGN_COLS = (
    "id, strategy_id, family, evidence_policy, objective_spec, search_space, budget, "
    "status, champion_version_id, created_at, updated_at"
)
_GENERATION_COLS = (
    "id, campaign_id, n, proposal, search_space_hash, engine_sha, data_epoch, "
    "stress_touches, status, started_at, finished_at"
)
_CANDIDATE_COLS = (
    "id, generation_id, version_id, parent_candidate_id, mutation_kind, params, "
    "structure_diff, sweep_job_id, holdout_run_id, scorecard, state, updated_at"
)


class EvoRepo:
    """Read-only access to the evolution experiment model (``evo_*`` tables, backtest
    schema). Only the E1 read surface is implemented — campaigns, their generations, and
    their candidates; the write/scoring/recorder path lands in later PRs. Thin psycopg
    wrapper, duck-typed like JobsRepo/TrialsRepo so tests substitute an in-memory fake."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    def close(self) -> None:
        """Closes the underlying connection (the factory opens one per use)."""
        self._conn.close()

    def list_campaigns(self, limit: int, offset: int) -> list[dict[str, Any]]:
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_CAMPAIGN_COLS} FROM evo_campaigns "
                "ORDER BY created_at DESC LIMIT %s OFFSET %s",
                (limit, offset),
            )
            rows = cur.fetchall()
        return [_campaign_row(r) for r in rows]

    def get_campaign(self, campaign_id: str) -> dict[str, Any] | None:
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_CAMPAIGN_COLS} FROM evo_campaigns WHERE id=%s", (campaign_id,)
            )
            row = cur.fetchone()
        return _campaign_row(row) if row is not None else None

    def list_generations(self, campaign_id: str) -> list[dict[str, Any]]:
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_GENERATION_COLS} FROM evo_generations "
                "WHERE campaign_id=%s ORDER BY n",
                (campaign_id,),
            )
            rows = cur.fetchall()
        return [_generation_row(r) for r in rows]

    def list_candidates_for_campaign(self, campaign_id: str) -> list[dict[str, Any]]:
        cols = ", ".join(f"c.{c.strip()}" for c in _CANDIDATE_COLS.split(","))
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {cols} FROM evo_candidates c "
                "JOIN evo_generations g ON g.id = c.generation_id "
                "WHERE g.campaign_id=%s ORDER BY g.n, c.updated_at",
                (campaign_id,),
            )
            rows = cur.fetchall()
        return [_candidate_row(r) for r in rows]

    def get_generation(self, generation_id: str) -> dict[str, Any] | None:
        """One generation by id (E2 cost-stress orchestrator) — its campaign, ``n``, and frozen
        ``proposal`` (carrying the source ``sweepJobId`` the stress round re-runs)."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_GENERATION_COLS} FROM evo_generations WHERE id=%s", (generation_id,)
            )
            row = cur.fetchone()
        return _generation_row(row) if row is not None else None

    def list_candidates_for_generation(self, generation_id: str) -> list[dict[str, Any]]:
        """The candidate rows of ONE generation (E2 cost-stress) — the stress round selects its
        top-K from here and re-scores every row of this set as the cohort."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_CANDIDATE_COLS} FROM evo_candidates "
                "WHERE generation_id=%s ORDER BY updated_at",
                (generation_id,),
            )
            rows = cur.fetchall()
        return [_candidate_row(r) for r in rows]

    # --- E1 recorder writes (§12 item 3) --------------------------------------------------------

    def create_campaign(
        self,
        strategy_id: str,
        family: str,
        evidence_policy: str,
        objective_spec: dict[str, Any] | None,
        search_space: dict[str, Any] | None,
        budget: dict[str, Any] | None,
    ) -> dict[str, Any]:
        """Insert a new campaign and return it in the read-envelope shape. ``status`` (ACTIVE) and
        ``created_at``/``updated_at`` (now()) come from the DDL defaults — a fresh row needs no
        explicit updated_at (that rule binds UPDATEs, of which this writer performs none)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO evo_campaigns "
                "(strategy_id, family, evidence_policy, objective_spec, search_space, budget) "
                "VALUES (%s, %s, %s, %s::jsonb, %s::jsonb, %s::jsonb) "
                f"RETURNING {_CAMPAIGN_COLS}",
                (strategy_id, family, evidence_policy,
                 _jsonb(objective_spec), _jsonb(search_space), _jsonb(budget)),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _campaign_row(row)

    def record_generation(
        self,
        *,
        campaign_id: str,
        proposal: dict[str, Any],
        engine_sha: str | None,
        data_epoch: dict[str, Any] | None,
        status: str,
        started_at: str | None,
        finished_at: str | None,
        candidates: list[dict[str, Any]],
    ) -> dict[str, Any]:
        """Record a generation + one candidate row per scored trial ATOMICALLY — the generation and
        all its candidates share one connection/transaction and one commit, so a crash mid-loop
        rolls back the whole generation (never a half-recorded one).

        ``n`` = MAX(n)+1 for the campaign, read INSIDE the transaction. optimizer-service is the
        SOLE evo writer, so this read-modify-write on ``n`` has no competing writer; the theoretical
        race (two concurrent recorders on one campaign) is out of scope — the UNIQUE(campaign_id, n)
        index (V011) is the hard backstop if that ever breaks (a racing writer 23505s, it cannot
        silently duplicate a number)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT COALESCE(MAX(n), 0) + 1 FROM evo_generations WHERE campaign_id=%s",
                (campaign_id,),
            )
            n = cur.fetchone()[0]
            cur.execute(
                "INSERT INTO evo_generations "
                "(campaign_id, n, proposal, engine_sha, data_epoch, status, "
                "started_at, finished_at) "
                "VALUES (%s, %s, %s::jsonb, %s, %s::jsonb, %s, %s::timestamptz, %s::timestamptz) "
                f"RETURNING {_GENERATION_COLS}",
                (campaign_id, n, json.dumps(proposal), engine_sha, _jsonb(data_epoch),
                 status, started_at, finished_at),
            )
            gen_row = cur.fetchone()
            generation_id = gen_row[0]
            for cand in candidates:
                cur.execute(
                    "INSERT INTO evo_candidates "
                    "(generation_id, mutation_kind, params, sweep_job_id, scorecard, state) "
                    "VALUES (%s, %s, %s::jsonb, %s, %s::jsonb, %s)",
                    (generation_id, cand["mutationKind"], json.dumps(cand["params"]),
                     cand["sweepJobId"], json.dumps(cand["scorecard"]), cand["state"]),
                )
        self._conn.commit()
        return _generation_row(gen_row)

    def apply_stress_round(
        self, generation_id: str, updates: list[dict[str, Any]]
    ) -> None:
        """Persist ONE cost-stress round ATOMICALLY (design §3.2.5 / §12 E2 item 5): overwrite each
        candidate's re-scored ``scorecard`` (with the cost_resilience component now filled),
        increment the generation's ``stress_touches`` ONCE, and restore the lifecycle marker
        (``status`` STRESSING → DONE) — all in one transaction/commit, so a scoring crash mid-round
        can never half-write a cohort or leave a committed round marked in-flight. ``updated_at``
        is set to ``now()`` explicitly (the DDL default fires on INSERT only — an UPDATE must
        maintain it, V011:74-76). A no-op ``updates`` still bumps the touch counter (an honest
        'a round ran' record)."""
        with self._conn.cursor() as cur:
            for update in updates:
                cur.execute(
                    "UPDATE evo_candidates SET scorecard=%s::jsonb, updated_at=now() WHERE id=%s",
                    (json.dumps(update["scorecard"]), update["candidateId"]),
                )
            cur.execute(
                "UPDATE evo_generations SET stress_touches = stress_touches + 1, status = 'DONE' "
                "WHERE id=%s",
                (generation_id,),
            )
        self._conn.commit()

    def set_generation_status(self, generation_id: str, status: str | None) -> None:
        """Write a generation's lifecycle ``status`` (free TEXT, no CHECK enum — V011:52). The E2
        stress orchestrator commits ``STRESSING`` at round dispatch as the DURABLE in-flight
        marker; ``apply_stress_round`` restores ``DONE`` atomically with the round's writes."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_generations SET status=%s WHERE id=%s", (status, generation_id)
            )
        self._conn.commit()

    def reap_stressing_generations(self) -> int:
        """Boot reaper (E2 cost-stress): a restart mid-stress-round kills the in-memory drain, so
        its generation is stranded at ``STRESSING`` forever — nothing can complete the round, and
        the durable 409 guard would refuse every future POST. Flip orphans back to ``DONE`` (their
        recorded state — the round's dispatched BACKTEST jobs may still complete but nothing reads
        them; a re-POST simply reruns the round). Returns the count for the boot warn-log."""
        with self._conn.cursor() as cur:
            cur.execute("UPDATE evo_generations SET status='DONE' WHERE status='STRESSING'")
            count = cur.rowcount
        self._conn.commit()
        return count
