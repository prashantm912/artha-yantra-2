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


# Sentinel for update_campaign_champion: distinguishes "no CAS, move unconditionally" (ROLLBACK)
# from an explicit expected champion (PROMOTE — None is a valid value, the first champion).
_CHAMPION_CAS_ANY = object()

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


def _proposal_row(r: tuple) -> dict[str, Any]:
    return {
        "id": str(r[0]),
        "campaignId": str(r[1]),
        "candidateId": _uuid(r[2]),
        "kind": r[3],
        "evidence": r[4],
        "status": r[5],
        "actor": r[6],
        "decidedAt": _ts(r[7]),
        "expiresAt": _ts(r[8]),
        "createdAt": _ts(r[9]),
    }


_PROPOSAL_COLS = (
    "id, campaign_id, candidate_id, kind, evidence, status, actor, decided_at, expires_at, "
    "created_at"
)

# The V017 autonomy-scheduler columns, appended after _CAMPAIGN_COLS in the scheduler reads/writes.
_SCHED_COLS = "scheduler_state, pending_sweep_job_id, last_scheduled_at"


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

    # --- E4 proposals inbox (§12 item 11) -------------------------------------------------------

    def list_proposals(
        self,
        status: str | None,
        kind: str | None,
        campaign_id: str | None,
        limit: int,
        offset: int,
    ) -> list[dict[str, Any]]:
        """The inbox read (GET /proposals) — proposals newest-first, filterable by status / kind /
        campaign. Filters are ANDed and each optional (an absent filter widens the read)."""
        sql = f"SELECT {_PROPOSAL_COLS} FROM evo_proposals WHERE TRUE"
        args: list[Any] = []
        if status:
            sql += " AND status=%s"
            args.append(status)
        if kind:
            sql += " AND kind=%s"
            args.append(kind)
        if campaign_id:
            sql += " AND campaign_id=%s"
            args.append(campaign_id)
        sql += " ORDER BY created_at DESC LIMIT %s OFFSET %s"
        args.extend([limit, offset])
        with self._conn.cursor() as cur:
            cur.execute(sql, tuple(args))
            rows = cur.fetchall()
        return [_proposal_row(r) for r in rows]

    def get_proposal(self, proposal_id: str) -> dict[str, Any] | None:
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_PROPOSAL_COLS} FROM evo_proposals WHERE id=%s", (proposal_id,)
            )
            row = cur.fetchone()
        return _proposal_row(row) if row is not None else None

    def find_open_proposal(self, candidate_id: str, kind: str) -> dict[str, Any] | None:
        """The single OPEN (PENDING) proposal for a (candidate, kind), or None — the generation
        idempotency key ("one OPEN proposal per (candidate, kind); regeneration refreshes"). The
        optimizer is the sole evo writer + single-process, so this read-then-write has no competing
        writer; a terminal (APPROVED/REJECTED/EXPIRED) row never blocks a fresh proposal."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_PROPOSAL_COLS} FROM evo_proposals "
                "WHERE candidate_id=%s AND kind=%s AND status='PENDING' "
                "ORDER BY created_at DESC LIMIT 1",
                (candidate_id, kind),
            )
            row = cur.fetchone()
        return _proposal_row(row) if row is not None else None

    def insert_proposal(
        self,
        campaign_id: str,
        candidate_id: str | None,
        kind: str,
        evidence: dict[str, Any] | None,
        expiry_days: int = 7,
    ) -> dict[str, Any]:
        """Insert a PENDING proposal with a 7-day expiry (§8.2). ``expires_at`` is computed in SQL
        (``now() + interval``) so the expiry is DB-clock-anchored, never a Python wall-clock the
        tests would have to freeze; ``status`` defaults PENDING from the DDL."""
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO evo_proposals (campaign_id, candidate_id, kind, evidence, expires_at) "
                "VALUES (%s, %s, %s, %s::jsonb, now() + (%s * interval '1 day')) "
                f"RETURNING {_PROPOSAL_COLS}",
                (campaign_id, candidate_id, kind, _jsonb(evidence), expiry_days),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _proposal_row(row)

    def refresh_proposal_evidence(
        self, proposal_id: str, evidence: dict[str, Any] | None
    ) -> dict[str, Any]:
        """Refresh an OPEN proposal's evidence card in place (regeneration re-freezes the current
        scorecard/champion gap without minting a duplicate row). Guarded to PENDING so a decision
        that landed concurrently is never silently overwritten."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_proposals SET evidence=%s::jsonb "
                f"WHERE id=%s AND status='PENDING' RETURNING {_PROPOSAL_COLS}",
                (_jsonb(evidence), proposal_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _proposal_row(row) if row is not None else self.get_proposal(proposal_id)

    def decide_proposal(
        self, proposal_id: str, status: str, actor: str | None
    ) -> dict[str, Any] | None:
        """Mark a PENDING proposal APPROVED/REJECTED (audit: actor + decided_at=now()). Guarded to
        PENDING — a second decision (or a decision on an EXPIRED row) matches nothing and returns
        None, so the service maps it to a 409 rather than silently re-deciding (append-only table:
        the row is UPDATE-decided, never removed)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_proposals SET status=%s, actor=%s, decided_at=now() "
                f"WHERE id=%s AND status='PENDING' RETURNING {_PROPOSAL_COLS}",
                (status, actor, proposal_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _proposal_row(row) if row is not None else None

    # --- E4 slice 2: selection (SCORED→SURVIVOR) + PUBLISH_PAPER execute (§8.1 / §12 item 12) -----

    def get_generation_by_n(self, campaign_id: str, n: int) -> dict[str, Any] | None:
        """One generation resolved by (campaign_id, n) — the SELECT endpoint's path key
        (uq_evo_generations_campaign_n, V011). None → the service maps it to a 404."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_GENERATION_COLS} FROM evo_generations WHERE campaign_id=%s AND n=%s",
                (campaign_id, n),
            )
            row = cur.fetchone()
        return _generation_row(row) if row is not None else None

    def get_candidate(self, candidate_id: str) -> dict[str, Any] | None:
        """One candidate by id (the PUBLISH_PAPER execute path resolves a proposal's candidate)."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_CANDIDATE_COLS} FROM evo_candidates WHERE id=%s", (candidate_id,)
            )
            row = cur.fetchone()
        return _candidate_row(row) if row is not None else None

    def update_candidate_selection(
        self, candidate_id: str, state: str, scorecard: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        """The SCORED→SURVIVOR/RETIRED write: set the selected ``state`` and re-store the scorecard
        (now carrying the ``selection`` rationale sub-object). ``updated_at`` is set explicitly
        (the DDL default fires on INSERT only — a state change must maintain it, V011:74-76).
        RETURNs the row so the response can echo the affected candidates."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_candidates SET state=%s, scorecard=%s::jsonb, updated_at=now() "
                f"WHERE id=%s RETURNING {_CANDIDATE_COLS}",
                (state, json.dumps(scorecard), candidate_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _candidate_row(row) if row is not None else None

    def update_candidate_publish(
        self, candidate_id: str, version_id: str | None, state: str
    ) -> dict[str, Any] | None:
        """After the sibling clone is published (§8.2): link the candidate to the clone's published
        version (``version_id`` — the live-evidence FK signals key off, §2.1) and advance its state
        (SURVIVOR→PAPER). ``updated_at`` maintained explicitly. RETURNs the updated row."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_candidates SET version_id=%s, state=%s, updated_at=now() "
                f"WHERE id=%s RETURNING {_CANDIDATE_COLS}",
                (version_id, state, candidate_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _candidate_row(row) if row is not None else None

    def record_proposal_execution(
        self, proposal_id: str, evidence: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        """Stamp the executed clone's linkage back onto the APPROVED proposal's evidence card (the
        ``execution`` sub-object). Guarded to APPROVED — a PUBLISH_PAPER execute only runs on an
        already-approved proposal (the service validates that first; this is the durable backstop).
        An UPDATE of the evidence JSONB, never a row removal (append-only table)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_proposals SET evidence=%s::jsonb "
                f"WHERE id=%s AND status='APPROVED' RETURNING {_PROPOSAL_COLS}",
                (_jsonb(evidence), proposal_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _proposal_row(row) if row is not None else None

    # --- E4 slice 3: TAKE_ELIGIBLE / PROMOTE / ROLLBACK + RETIRE acks (§8.1-8.2 / §12 item 13) ----

    def find_latest_proposal(self, candidate_id: str, kind: str) -> dict[str, Any] | None:
        """The newest proposal for a (candidate, kind) in ANY status — the ROLLBACK generator reads
        the candidate's prior PROMOTE proposal (for the demoted-champion / rollback target), and the
        RETIRE-ack write uses it for idempotency (a re-select must not mint a second ack). Distinct
        from ``find_open_proposal`` (which is PENDING-only, the OPEN-proposal idempotency key)."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_PROPOSAL_COLS} FROM evo_proposals "
                "WHERE candidate_id=%s AND kind=%s ORDER BY created_at DESC LIMIT 1",
                (candidate_id, kind),
            )
            row = cur.fetchone()
        return _proposal_row(row) if row is not None else None

    def insert_acknowledged_proposal(
        self,
        campaign_id: str,
        candidate_id: str | None,
        kind: str,
        evidence: dict[str, Any] | None,
        actor: str,
    ) -> dict[str, Any]:
        """Insert an already-decided (status APPROVED) proposal — the §8.2 RETIRE acknowledge-row:
        RETIRE applies autonomously (no owner gate), so its evo_proposals row is auto-APPROVED with
        actor ``evo:{campaignId}`` and ``decided_at=now()`` at insert (the inbox shows it as a
        review/acknowledge item, never a pending gate). ``expires_at`` still set (7d) for a uniform
        row shape, though a terminal row never expires."""
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO evo_proposals "
                "(campaign_id, candidate_id, kind, evidence, status, actor, decided_at, "
                "expires_at) VALUES "
                "(%s, %s, %s, %s::jsonb, 'APPROVED', %s, now(), now() + interval '7 days') "
                f"RETURNING {_PROPOSAL_COLS}",
                (campaign_id, candidate_id, kind, _jsonb(evidence), actor),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _proposal_row(row)

    def update_campaign_champion(
        self, campaign_id: str, version_id: str | None,
        expected_version_id: Any = _CHAMPION_CAS_ANY,
    ) -> dict[str, Any] | None:
        """Move the campaign's champion pointer (``champion_version_id``) — PROMOTE sets it to the
        newly-published version, ROLLBACK restores the demoted champion's version. ``updated_at`` is
        maintained explicitly (the DDL default fires on INSERT only — an UPDATE must set it,
        V011:74-76). When ``expected_version_id`` is given, the move is a DURABLE ATOMIC
        compare-and-set (audit PF-01 #6): the UPDATE only fires while the champion is STILL the
        expected value (``IS NOT DISTINCT FROM`` so None==None first-champion works), so exactly one
        of two concurrent promotes wins the champion move and the loser gets 0 rows (→ None). It
        RETURNs the updated row, or None when the CAS did not match (champion moved under us)."""
        with self._conn.cursor() as cur:
            if expected_version_id is _CHAMPION_CAS_ANY:
                cur.execute(
                    "UPDATE evo_campaigns SET champion_version_id=%s, updated_at=now() "
                    f"WHERE id=%s RETURNING {_CAMPAIGN_COLS}",
                    (version_id, campaign_id),
                )
            else:
                cur.execute(
                    "UPDATE evo_campaigns SET champion_version_id=%s, updated_at=now() "
                    "WHERE id=%s AND champion_version_id IS NOT DISTINCT FROM %s "
                    f"RETURNING {_CAMPAIGN_COLS}",
                    (version_id, campaign_id, expected_version_id),
                )
            row = cur.fetchone()
        self._conn.commit()
        return _campaign_row(row) if row is not None else None

    def record_promotion_atomic(
        self, campaign_id: str, expected_champion: str | None, new_version_id: str | None,
        candidate_id: str, proposal_id: str, evidence: dict[str, Any] | None,
    ) -> bool:
        """Round-5 #2: the WINNING promotion's three writes — champion compare-and-set + candidate
        advance (→ PROMOTED, ``version_id`` = the new champion) + proposal execution stamp — in ONE
        transaction (all-or-nothing). Returns True iff the champion CAS matched (exactly one
        concurrent promoter wins); False (and rolls back) when the champion moved under us. No
        partial state can survive — a failure after any leg rolls the WHOLE promotion back, so there
        is never an advanced champion with a TAKE_ELIGIBLE candidate + unstamped proposal."""
        try:
            with self._conn.cursor() as cur:
                cur.execute(
                    "UPDATE evo_campaigns SET champion_version_id=%s, updated_at=now() "
                    "WHERE id=%s AND champion_version_id IS NOT DISTINCT FROM %s",
                    (new_version_id, campaign_id, expected_champion),
                )
                if cur.rowcount == 0:
                    self._conn.rollback()
                    return False
                cur.execute(
                    "UPDATE evo_candidates SET version_id=%s, state='PROMOTED', updated_at=now() "
                    "WHERE id=%s",
                    (new_version_id, candidate_id),
                )
                cur.execute(
                    "UPDATE evo_proposals SET evidence=%s::jsonb WHERE id=%s AND status='APPROVED'",
                    (_jsonb(evidence), proposal_id),
                )
            self._conn.commit()
            return True
        except Exception:
            self._conn.rollback()
            raise

    # --- E6 item 16: autonomy-scheduler durable state (V017) -------------------------------------
    # The scheduler's per-campaign state lives in three columns ISOLATED behind these methods (the
    # E1 _campaign_row envelope is intentionally untouched — the autonomy sub-state is a separate
    # axis, §V017). Everything here is written ONLY by the scheduler, which requires the default-OFF
    # ARTHA_EVO_SCHEDULER_ENABLED flag; nothing self-arms.

    def list_active_scheduler_targets(self) -> list[dict[str, Any]]:
        """Every ACTIVE campaign the scheduler may advance, with its autonomy sub-state. PAUSED and
        CLOSED campaigns are excluded (the owner lifecycle gates autonomy). One query — the table is
        a handful of rows, so no index/pagination is warranted."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_CAMPAIGN_COLS}, {_SCHED_COLS} "
                "FROM evo_campaigns WHERE status='ACTIVE' ORDER BY created_at"
            )
            rows = cur.fetchall()
        return [_scheduler_target_row(r) for r in rows]

    def get_scheduler_row(self, campaign_id: str) -> dict[str, Any] | None:
        """One campaign's scheduler sub-state (the report reads it). None → unknown campaign."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_CAMPAIGN_COLS}, {_SCHED_COLS} FROM evo_campaigns WHERE id=%s",
                (campaign_id,),
            )
            row = cur.fetchone()
        return _scheduler_target_row(row) if row is not None else None

    def claim_pending_sweep(
        self, campaign_id: str, sweep_job_id: str, scheduled_at: Any
    ) -> dict[str, Any] | None:
        """Record that the scheduler LAUNCHED a generation's sweep: scheduler_state→EVALUATING,
        pending_sweep_job_id set, last_scheduled_at stamped (the cadence anchor — the app-clock the
        scheduler passes, so cadence math is deterministic under an injected clock, never a surprise
        DB now()). CONDITIONAL on no sweep already pending (``pending_sweep_job_id IS NULL``) — the
        DB backstop behind the in-process tick lock: a competing claim matches 0 rows and returns
        None (the caller treats it as claim-lost and skips), so two writers can never double-launch
        a campaign (adversarial review finding 1). ``updated_at`` maintained explicitly."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_campaigns SET scheduler_state='EVALUATING', pending_sweep_job_id=%s, "
                "last_scheduled_at=%s, updated_at=now() "
                "WHERE id=%s AND pending_sweep_job_id IS NULL "
                f"RETURNING {_CAMPAIGN_COLS}, {_SCHED_COLS}",
                (sweep_job_id, scheduled_at, campaign_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _scheduler_target_row(row) if row is not None else None

    def resolve_pending_sweep(
        self, campaign_id: str, next_state: str | None
    ) -> dict[str, Any] | None:
        """Clear the in-flight sweep and set the next autonomy state — called after the generation
        finishes (IDLE → ready for the next one, or EXHAUSTED → budget spent), after a FAILED sweep
        (IDLE → relaunch next tick, §11 durable-resume), and by /scheduler/withdraw (``None`` →
        NOT-ENROLLED, pending abandoned — the sweep completes as an ordinary sweep, recordable
        manually). ``last_scheduled_at`` stays the cadence anchor. ``updated_at`` maintained."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_campaigns SET scheduler_state=%s, pending_sweep_job_id=NULL, "
                "updated_at=now() WHERE id=%s "
                f"RETURNING {_CAMPAIGN_COLS}, {_SCHED_COLS}",
                (next_state, campaign_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _scheduler_target_row(row) if row is not None else None

    def set_scheduler_state(self, campaign_id: str, state: str) -> dict[str, Any] | None:
        """Set the autonomy sub-state WITHOUT touching the pending sweep — the ENROLL write
        (NULL/EXHAUSTED → IDLE), the EXHAUSTED mark on an idle over-budget campaign, and the
        EVALUATING→PROBING→STRESSING mid-generation transitions (pending stays pinned)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_campaigns SET scheduler_state=%s, updated_at=now() WHERE id=%s "
                f"RETURNING {_CAMPAIGN_COLS}, {_SCHED_COLS}",
                (state, campaign_id),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _scheduler_target_row(row) if row is not None else None

    def expire_pending_proposals(self) -> list[dict[str, Any]]:
        """The §8.2 proposal-expiry sweep: flip every PENDING proposal past its ``expires_at``
        (DB-clock-anchored 7-day window, set at insert) to EXPIRED, stamping actor='evo:expiry' +
        decided_at=now(). DB-clock (``now()``) is the single source of truth — the clock that set
        ``expires_at``, so no Python wall-clock is trusted. Idempotent (an EXPIRED row no longer
        matches). Returns the rows expired THIS sweep (the count/log). Append-only table: an UPDATE
        of status, never a delete (V011:107)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "UPDATE evo_proposals SET status='EXPIRED', actor='evo:expiry', decided_at=now() "
                "WHERE status='PENDING' AND expires_at IS NOT NULL AND expires_at < now() "
                f"RETURNING {_PROPOSAL_COLS}"
            )
            rows = cur.fetchall()
        self._conn.commit()
        return [_proposal_row(r) for r in rows]


def _scheduler_target_row(r: tuple) -> dict[str, Any]:
    """The campaign read-envelope PLUS the three V017 scheduler columns (appended after
    _CAMPAIGN_COLS in the SELECT). Reuses _campaign_row for the base 11 columns so the campaign
    shape stays defined in exactly one place."""
    base = _campaign_row(r[:11])
    base["schedulerState"] = r[11]
    base["pendingSweepJobId"] = _uuid(r[12])
    base["lastScheduledAt"] = _ts(r[13])
    return base


def _reconciliation_row(r: tuple) -> dict[str, Any]:
    return {
        "id": str(r[0]),
        "versionId": _uuid(r[1]),
        "strategyId": _uuid(r[2]),
        "windowFrom": _ts(r[3]),
        "windowTo": _ts(r[4]),
        "simJobId": _uuid(r[5]),
        "simRunId": _uuid(r[6]),
        "gap": r[7],
        "gapZ": float(r[8]) if r[8] is not None else None,
        "pairedTrades": r[9],
        "evidenceFloorMet": r[10],
        "verdict": r[11],
        "diagnosis": r[12],
        "createdAt": _ts(r[13]),
    }


_RECONCILIATION_COLS = (
    "id, version_id, strategy_id, window_from, window_to, sim_job_id, sim_run_id, gap, gap_z, "
    "paired_trades, evidence_floor_met, verdict, diagnosis, created_at"
)


class ReconciliationRepo:
    """The E3 backtest-vs-live reconciliation store (``reconciliations`` table, backtest schema —
    V012). Writes the computed gap row and reads them back by version; also resolves the version's
    latest walk-forward run (for the §7.2 σ(fold returns)) off ``backtest_runs``. Thin psycopg
    wrapper, duck-typed like the other repos so tests substitute an in-memory fake."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    def close(self) -> None:
        """Closes the underlying connection (the factory opens one per use)."""
        self._conn.close()

    def get_by_version_window(
        self, version_id: str, window_from: str, window_to: str
    ) -> dict[str, Any] | None:
        """The reconciliation for a (version, window), or ``None`` — the POST's 409 idempotency
        check (the UNIQUE(version_id, window_from, window_to) index is the hard backstop)."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_RECONCILIATION_COLS} FROM reconciliations "
                "WHERE version_id=%s AND window_from=%s::timestamptz AND window_to=%s::timestamptz",
                (version_id, window_from, window_to),
            )
            row = cur.fetchone()
        return _reconciliation_row(row) if row is not None else None

    def list_by_version(
        self, version_id: str, limit: int, offset: int
    ) -> list[dict[str, Any]]:
        """A version's reconciliations, newest-first — the GET ?versionId= read."""
        with self._conn.cursor() as cur:
            cur.execute(
                f"SELECT {_RECONCILIATION_COLS} FROM reconciliations "
                "WHERE version_id=%s ORDER BY created_at DESC LIMIT %s OFFSET %s",
                (version_id, limit, offset),
            )
            rows = cur.fetchall()
        return [_reconciliation_row(r) for r in rows]

    def latest_walkforward_run_id(self, version_id: str) -> str | None:
        """The version's most recent WALK-FORWARD backtest run id (``fold_metrics`` present) — the
        §7.2 σ(fold returns) source (the version's existing walk-forward fold returns, its sweep/run
        history). ``None`` when the version has no fold history → gap_z NULL → verdict INSUFFICIENT.
        A direct read of ``backtest_runs`` (backtest-service's rows in the optimizer's own schema);
        the fold RETURNS are then read via BacktestClient.folds (the proven REST fold shape)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT id FROM backtest_runs "
                "WHERE strategy_version_id=%s AND fold_metrics IS NOT NULL "
                "ORDER BY completed_at DESC LIMIT 1",
                (version_id,),
            )
            row = cur.fetchone()
        return str(row[0]) if row is not None else None

    def insert(
        self,
        *,
        version_id: str,
        strategy_id: str | None,
        window_from: str,
        window_to: str,
        sim_job_id: str | None,
        sim_run_id: str | None,
        gap: dict[str, Any],
        gap_z: float | None,
        paired_trades: int,
        evidence_floor_met: bool,
        verdict: str,
        diagnosis: dict[str, Any] | None,
    ) -> dict[str, Any]:
        """Persist ONE reconciliation row (atomic single INSERT — never a half-row) and return it in
        the read-envelope shape. window_from/window_to are cast ::timestamptz (explicit IST bounds).
        A UNIQUE(version, window) collision surfaces as psycopg UniqueViolation — the drain logs it
        (the idempotency check + in-flight guard make it near-impossible; the constraint is the hard
        backstop against a concurrent double-submit)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO reconciliations "
                "(version_id, strategy_id, window_from, window_to, sim_job_id, sim_run_id, gap, "
                "gap_z, paired_trades, evidence_floor_met, verdict, diagnosis) "
                "VALUES (%s, %s, %s::timestamptz, %s::timestamptz, %s, %s, %s::jsonb, %s, %s, %s, "
                f"%s, %s::jsonb) RETURNING {_RECONCILIATION_COLS}",
                (
                    version_id, strategy_id, window_from, window_to, sim_job_id, sim_run_id,
                    json.dumps(gap), gap_z, paired_trades, evidence_floor_met, verdict,
                    _jsonb(diagnosis),
                ),
            )
            row = cur.fetchone()
        self._conn.commit()
        return _reconciliation_row(row)


class LiveEvidenceRepo:
    """Read-only access to the STRATEGY schema for the E3 reconciliation computer (§7.1 live plane).
    The optimizer connects as ``artha`` (D10 single-writer), so cross-schema READS are fine — writes
    stay confined to the backtest schema. Two reads: resolve a version UUID to its owning strategy +
    semver + config (there is NO version-by-UUID REST endpoint — RegistryController scopes every
    version route by strategyId), and the version's live paper trades over a window (the H5
    single-attribution join: paper_positions → opening signal → strategy_version, V026)."""

    def __init__(self, conn: psycopg.Connection) -> None:
        self._conn = conn

    def close(self) -> None:
        """Closes the underlying connection (the factory opens one per use)."""
        self._conn.close()

    def resolve_version(self, version_id: str) -> dict[str, Any] | None:
        """Resolve a ``strategy_versions`` UUID to ``{strategyId, version (semver), config}`` — the
        reconcile re-sim needs the strategyId + SEMVER string to submit through
        POST /api/v1/backtests/run (strategyVersion is the semver, StrategyVersionClient.java:55).
        The config detects the SWING session style. ``None`` for an unknown version (→ 404)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT strategy_id, version, config FROM strategy.strategy_versions WHERE id=%s",
                (version_id,),
            )
            row = cur.fetchone()
        if row is None:
            return None
        return {"strategyId": str(row[0]), "version": row[1], "config": row[2]}

    def paper_trades_for_version(
        self, version_id: str, window_from: str, window_to: str
    ) -> list[dict[str, Any]]:
        """The version's live paper positions ENTERED in the window (``opened_at`` ∈ [from, to)),
        joined to their opening signal (for the scalper_detail option leg). Window bounds are cast
        ::timestamptz and passed as explicit IST (+05:30) ISO strings — never a UTC ``::date``
        (off-by-one across IST midnight, the in-container-UTC trap). The entry price is
        ``avg_entry_price`` (the FillSimulator-stamped live fill, not the signal-time LTP); realized
        P&L + close_reason are authoritative on the position (there is no exit-price column)."""
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT p.tradingsymbol, p.side, p.avg_entry_price, p.opened_at, p.closed_at, "
                "p.realized_pnl, p.close_reason, p.status, s.scalper_detail "
                "FROM strategy.paper_positions p "
                "JOIN strategy.signals s ON s.id = p.opening_signal_id "
                "WHERE s.strategy_version_id=%s "
                "AND p.opened_at >= %s::timestamptz AND p.opened_at < %s::timestamptz "
                "ORDER BY p.opened_at",
                (version_id, window_from, window_to),
            )
            rows = cur.fetchall()
        return [
            {
                "tradingsymbol": r[0],
                "side": r[1],
                "avgEntryPrice": float(r[2]) if r[2] is not None else None,
                "openedAt": _ts(r[3]),
                "closedAt": _ts(r[4]),
                "realizedPnl": float(r[5]) if r[5] is not None else None,
                "closeReason": r[6],
                "status": r[7],
                "scalperDetail": r[8],
            }
            for r in rows
        ]
