"""In-memory doubles for the sweep collaborators — no Postgres, no Redis."""

from __future__ import annotations

import json
from typing import Any

from app.repos import ORPHAN_SWEEP_ERROR


class FakeJobs:
    """In-memory ``jobs`` table."""

    def __init__(self) -> None:
        self.rows: dict[str, dict[str, Any]] = {}
        self._seq = 0

    def insert_sweep(
        self, version_id: str | None, request: dict[str, Any], created_by: str = "optimizer"
    ) -> str:
        self._seq += 1
        job_id = f"sweep-{self._seq}"
        self.rows[job_id] = {"kind": "OPTIMIZATION", "status": "queued", "progress": 0,
                             "request": request, "error": None, "created": self._seq,
                             "created_by": created_by}
        return job_id

    def insert_trial(
        self,
        sweep_id: str,
        version_id: str | None,
        request: dict[str, Any],
        created_by: str | None = None,
    ) -> str:
        self._seq += 1
        job_id = f"trial-{self._seq}"
        self.rows[job_id] = {"kind": "TRIAL", "status": "queued", "progress": 0,
                             "request": request, "parent": sweep_id,
                             "created_by": created_by or f"optimizer:{sweep_id}"}
        return job_id

    def set_status(self, job_id: str, status: str, progress: int | None = None) -> None:
        # Mirrors the real repo's terminal guard (P1-10b): terminal rows are never overwritten.
        row = self.rows.setdefault(job_id, {})
        if row.get("status") in ("completed", "failed", "cancelled"):
            return
        row["status"] = status
        if progress is not None:
            row["progress"] = progress

    def fail_orphaned_sweeps(self) -> int:
        """Mirrors JobsRepo.fail_orphaned_sweeps (repos.py): OPTIMIZATION rows stranded at
        queued/running on a restart are marked failed with a populated ``error`` (a real reason,
        not NULL); terminal rows and TRIAL rows are untouched."""
        count = 0
        for row in self.rows.values():
            if row.get("kind") == "OPTIMIZATION" and row.get("status") in ("queued", "running"):
                row["status"] = "failed"
                row["error"] = row.get("error") or ORPHAN_SWEEP_ERROR
                count += 1
        return count

    def list_sweeps(self, limit: int, offset: int) -> list[dict[str, Any]]:
        """Mirrors JobsRepo.list_sweeps: OPTIMIZATION rows newest-first (by insertion order)."""
        sweeps = [(jid, r) for jid, r in self.rows.items() if r.get("kind") == "OPTIMIZATION"]
        sweeps.sort(key=lambda item: item[1]["created"], reverse=True)
        projected = [
            {"id": jid, "status": r["status"], "progress": r["progress"],
             "request": r["request"], "error": r.get("error"), "createdAt": None}
            for jid, r in sweeps
        ]
        return projected[offset:offset + limit]

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

    def read_results(
        self, max_count: int, block_ms: int = 2000, sweep_id: str | None = None
    ) -> list[dict[str, Any]]:
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

    def create_draft(
        self, strategy_id: str, config: dict[str, Any], notes: str, created_by: str | None = None
    ) -> dict[str, Any]:
        self.drafts.append(
            {"strategyId": strategy_id, "config": config, "notes": notes, "createdBy": created_by}
        )
        return {"version": "1.1.0", "status": "draft"}


class FakeBacktest:
    """A fixed fold array (+ optional guard summary / results) per run id. ``folds`` / ``results``
    may be a dict keyed by run id (per-run retro-scoring fixtures) or a single value returned for
    every run id (the simpler leaderboard-test shape). An Exception fixture (per-run or single) is
    RAISED instead of returned — simulates a purged/404/timed-out run."""

    def __init__(self, folds: Any, guard: Any = None, results: Any = None) -> None:
        self._folds = folds
        self._guard = guard
        self._results = results
        self.calls: list[str] = []
        self.guard_calls: list[str] = []
        self.results_calls: list[str] = []

    def folds(self, run_id: str) -> Any:
        self.calls.append(run_id)
        return self._per_run(self._folds, run_id, default=[])

    def guard_summary(self, run_id: str) -> Any:
        self.guard_calls.append(run_id)
        return self._guard

    def results(self, run_id: str) -> Any:
        self.results_calls.append(run_id)
        return self._per_run(self._results, run_id, default={})

    @staticmethod
    def _per_run(value: Any, run_id: str, default: Any) -> Any:
        """A per-run dict returns its run_id entry; anything else is returned verbatim per run.
        An Exception fixture is raised (a dead/purged run)."""
        if isinstance(value, dict) and run_id in value:
            value = value[run_id]
        elif value is None:
            value = default
        if isinstance(value, Exception):
            raise value
        return value


class FakeEvoRepo:
    """In-memory ``evo_*`` read model — returns the same camelCase envelope shape EvoRepo
    emits, so EvoReadService maps it to the response models identically to production."""

    def __init__(
        self,
        campaigns: list[dict[str, Any]] | None = None,
        generations: dict[str, list[dict[str, Any]]] | None = None,
        candidates: dict[str, list[dict[str, Any]]] | None = None,
    ) -> None:
        self.campaigns = campaigns or []
        self.generations = generations or {}
        self.candidates = candidates or {}
        self._seq = 0

    def list_campaigns(self, limit: int, offset: int) -> list[dict[str, Any]]:
        return self.campaigns[offset:offset + limit]

    def get_campaign(self, campaign_id: str) -> dict[str, Any] | None:
        return next((c for c in self.campaigns if c["id"] == campaign_id), None)

    def list_generations(self, campaign_id: str) -> list[dict[str, Any]]:
        return self.generations.get(campaign_id, [])

    def list_candidates_for_campaign(self, campaign_id: str) -> list[dict[str, Any]]:
        return self.candidates.get(campaign_id, [])

    def create_campaign(
        self,
        strategy_id: str,
        family: str,
        evidence_policy: str,
        objective_spec: dict[str, Any] | None,
        search_space: dict[str, Any] | None,
        budget: dict[str, Any] | None,
    ) -> dict[str, Any]:
        """Mirrors EvoRepo.create_campaign: appends an ACTIVE campaign and returns the read
        envelope (status + timestamps synthesized, as the DDL defaults would)."""
        self._seq += 1
        row = {
            "id": f"camp-{self._seq}", "strategyId": strategy_id, "family": family,
            "evidencePolicy": evidence_policy, "objectiveSpec": objective_spec,
            "searchSpace": search_space, "budget": budget, "status": "ACTIVE",
            "championVersionId": None, "createdAt": "2026-07-11T00:00:00+00:00",
            "updatedAt": "2026-07-11T00:00:00+00:00",
        }
        self.campaigns.append(row)
        return row

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
        """Mirrors EvoRepo.record_generation: assigns n=MAX(n)+1 for the campaign, appends the
        generation, and appends one candidate row per scored trial (SCORED, version_id NULL)."""
        self._seq += 1
        existing = self.generations.get(campaign_id, [])
        n = max((g["n"] for g in existing), default=0) + 1
        gen_id = f"gen-{self._seq}"
        gen_row = {
            "id": gen_id, "campaignId": campaign_id, "n": n, "proposal": proposal,
            "searchSpaceHash": None, "engineSha": engine_sha, "dataEpoch": data_epoch,
            "stressTouches": 0, "status": status, "startedAt": started_at,
            "finishedAt": finished_at,
        }
        self.generations.setdefault(campaign_id, []).append(gen_row)
        cand_rows = [
            {
                "id": f"{gen_id}-cand-{i}", "generationId": gen_id, "versionId": None,
                "parentCandidateId": None, "mutationKind": cand["mutationKind"],
                "params": cand["params"], "structureDiff": None,
                "sweepJobId": cand["sweepJobId"], "holdoutRunId": None,
                "scorecard": cand["scorecard"], "state": cand["state"],
                "updatedAt": "2026-07-11T00:00:00+00:00",
            }
            for i, cand in enumerate(candidates)
        ]
        self.candidates.setdefault(campaign_id, []).extend(cand_rows)
        return gen_row

    def close(self) -> None:
        pass
