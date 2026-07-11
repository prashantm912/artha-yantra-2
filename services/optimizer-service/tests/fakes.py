"""In-memory doubles for the sweep collaborators — no Postgres, no Redis."""

from __future__ import annotations

import json
from typing import Any

import httpx

from app.repos import ORPHAN_SWEEP_ERROR, TrialNumberConflict


def _http_conflict(message: str) -> httpx.HTTPStatusError:
    """A 409 httpx.HTTPStatusError shaped like ``resp.raise_for_status()`` would raise — the
    registry duplicate-slug/name conflict the execute path must absorb."""
    request = httpx.Request("POST", "http://strategy-signal/api/v1/strategies")
    response = httpx.Response(409, json={"message": message}, request=request)
    return httpx.HTTPStatusError(message, request=request, response=response)


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

    def insert_backtest(
        self, sweep_id: str, request: dict[str, Any], created_by: str
    ) -> str:
        """Mirrors JobsRepo.insert_backtest: a queued BACKTEST job (NOT a trial) for the E2
        cost-stress orchestrator — kind=BACKTEST, parented on the sweep, stress actor."""
        self._seq += 1
        job_id = f"bt-{self._seq}"
        self.rows[job_id] = {"kind": "BACKTEST", "status": "queued", "progress": 0,
                             "request": request, "parent": sweep_id, "created_by": created_by}
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

    def child_trial_request(self, sweep_id: str) -> dict[str, Any] | None:
        """Mirrors JobsRepo.child_trial_request: the earliest child TRIAL job's request."""
        for _jid, row in self.rows.items():
            if row.get("kind") == "TRIAL" and row.get("parent") == sweep_id:
                return row["request"]
        return None

    def close(self) -> None:
        pass


class FakeTrials:
    """In-memory ``optimization_trials`` ledger. ``jobs`` (optional) backs the boot-reaper
    mirror, whose real SQL joins the parent sweep's status."""

    def __init__(self, jobs: FakeJobs | None = None) -> None:
        self.rows: dict[int, dict[str, Any]] = {}
        self._seq = 0
        self._jobs = jobs

    def insert(self, sweep_id: str, trial_number: int, params: dict[str, Any]) -> int:
        # Mirrors the DB's (sweep_job_id, trial_number) unique index — the 23505 the probe
        # path must absorb (TrialNumberConflict), never a silent duplicate.
        if any(r["sweep"] == sweep_id and r["trialNumber"] == trial_number
               for r in self.rows.values()):
            raise TrialNumberConflict(f"duplicate trial number {trial_number} for {sweep_id}")
        self._seq += 1
        self.rows[self._seq] = {"sweep": sweep_id, "trialNumber": trial_number, "params": params,
                                "state": "RUNNING", "objectiveValues": None, "backtestRunId": None}
        return self._seq

    def max_trial_number(self, sweep_id: str) -> int:
        """Mirrors TrialsRepo.max_trial_number: highest trial_number for a sweep, or -1 if none."""
        numbers = [r["trialNumber"] for r in self.rows.values() if r["sweep"] == sweep_id]
        return max(numbers, default=-1)

    def delete_stranded_running(self) -> int:
        """Mirrors TrialsRepo.delete_stranded_running: RUNNING rows whose parent OPTIMIZATION job
        is terminal are DELETED (freeing the cell + number for a re-probe)."""
        if self._jobs is None:
            return 0
        terminal = ("completed", "failed", "cancelled")
        doomed = [
            tid for tid, r in self.rows.items()
            if r["state"] == "RUNNING"
            and (self._jobs.rows.get(r["sweep"]) or {}).get("kind") == "OPTIMIZATION"
            and (self._jobs.rows.get(r["sweep"]) or {}).get("status") in terminal
        ]
        for tid in doomed:
            del self.rows[tid]
        return len(doomed)

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
    """Returns a fixed version config (with an optimize block) and records promote drafts. The E4
    slice-2 registry surface (create / publish / detail / list) is modelled with an in-memory
    registry so the PUBLISH_PAPER execute round-trips end-to-end: ``existing`` pre-seeds published
    strategies (the per-family cap fixture); ``create_conflict`` forces the duplicate-slug 409."""

    def __init__(
        self,
        config: dict[str, Any],
        existing: list[dict[str, Any]] | None = None,
        create_conflict: bool = False,
    ) -> None:
        self._config = config
        self.drafts: list[dict[str, Any]] = []
        # in-memory registry keyed by strategy id; pre-seeded rows model already-live strategies.
        self._registry: dict[str, dict[str, Any]] = {}
        for row in existing or []:
            self._registry[row["id"]] = dict(row)
        self._create_conflict = create_conflict
        self.created: list[dict[str, Any]] = []
        self.published: list[str] = []
        self._seq = 0

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

    def create(
        self,
        name: str,
        description: str | None,
        tags: list[str],
        config: dict[str, Any],
        created_by: str | None = None,
    ) -> dict[str, Any]:
        slug = config.get("id")
        # mirror RegistryService.create: 409 on a duplicate slug OR name (CONFLICT_SLUG_EXISTS).
        collides = self._create_conflict or any(
            r.get("slug") == slug or r.get("name") == name for r in self._registry.values()
        )
        if collides:
            raise _http_conflict("a strategy with this id or name already exists")
        self._seq += 1
        strategy_id = f"clone-{self._seq}"
        self.created.append(
            {"id": strategy_id, "name": name, "description": description, "tags": tags,
             "config": config, "createdBy": created_by}
        )
        self._registry[strategy_id] = {
            "id": strategy_id, "slug": slug, "name": name, "tags": tags,
            "status": "draft", "versionId": None, "version": "1.0.0",
        }
        return {"id": strategy_id, "version": "1.0.0", "status": "draft", "checksum": "chk"}

    def publish(
        self, strategy_id: str, target_version: str | None = None, notes: str | None = None
    ) -> dict[str, Any]:
        row = self._registry[strategy_id]
        row["status"] = "published"
        row["versionId"] = f"ver-{strategy_id}"
        self.published.append(strategy_id)
        return {"id": strategy_id, "version": row["version"], "status": "published"}

    def detail(self, strategy_id: str) -> dict[str, Any]:
        row = self._registry[strategy_id]
        return {
            "id": strategy_id, "versionId": row["versionId"], "version": row["version"],
            "status": row["status"], "slug": row["slug"], "name": row["name"],
            "tags": row["tags"],
        }

    def list_strategies(
        self, tag: str | None = None, status: str | None = None, limit: int = 500
    ) -> list[dict[str, Any]]:
        return [
            dict(r) for r in self._registry.values()
            if (tag is None or tag in (r.get("tags") or []))
            and (status is None or r.get("status") == status)
        ]


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
        self.proposals: list[dict[str, Any]] = []
        self._seq = 0

    def list_campaigns(self, limit: int, offset: int) -> list[dict[str, Any]]:
        return self.campaigns[offset:offset + limit]

    def get_campaign(self, campaign_id: str) -> dict[str, Any] | None:
        return next((c for c in self.campaigns if c["id"] == campaign_id), None)

    def list_generations(self, campaign_id: str) -> list[dict[str, Any]]:
        return self.generations.get(campaign_id, [])

    def list_candidates_for_campaign(self, campaign_id: str) -> list[dict[str, Any]]:
        return self.candidates.get(campaign_id, [])

    def get_generation(self, generation_id: str) -> dict[str, Any] | None:
        """Mirrors EvoRepo.get_generation: one generation by id across all campaigns."""
        for gens in self.generations.values():
            for gen in gens:
                if gen["id"] == generation_id:
                    return gen
        return None

    def list_candidates_for_generation(self, generation_id: str) -> list[dict[str, Any]]:
        """Mirrors EvoRepo.list_candidates_for_generation: the candidate rows of ONE generation."""
        out: list[dict[str, Any]] = []
        for cands in self.candidates.values():
            out.extend(c for c in cands if c.get("generationId") == generation_id)
        return out

    def apply_stress_round(
        self, generation_id: str, updates: list[dict[str, Any]]
    ) -> None:
        """Mirrors EvoRepo.apply_stress_round: overwrite each candidate's scorecard + touch
        updated_at, increment the generation's stress_touches ONCE, and restore the lifecycle
        marker (status STRESSING → DONE) — atomic in prod."""
        by_id = {u["candidateId"]: u["scorecard"] for u in updates}
        for cands in self.candidates.values():
            for cand in cands:
                if cand["id"] in by_id:
                    cand["scorecard"] = by_id[cand["id"]]
                    cand["updatedAt"] = "2026-07-11T01:00:00+00:00"
        for gens in self.generations.values():
            for gen in gens:
                if gen["id"] == generation_id:
                    gen["stressTouches"] = (gen.get("stressTouches") or 0) + 1
                    gen["status"] = "DONE"

    def set_generation_status(self, generation_id: str, status: str | None) -> None:
        """Mirrors EvoRepo.set_generation_status: the durable STRESSING/DONE round marker."""
        gen = self.get_generation(generation_id)
        if gen is not None:
            gen["status"] = status

    def reap_stressing_generations(self) -> int:
        """Mirrors EvoRepo.reap_stressing_generations: flip STRESSING orphans back to DONE."""
        count = 0
        for gens in self.generations.values():
            for gen in gens:
                if gen.get("status") == "STRESSING":
                    gen["status"] = "DONE"
                    count += 1
        return count

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

    # --- E4 proposals inbox (mirrors EvoRepo's proposal methods) --------------------------------

    def list_proposals(
        self,
        status: str | None,
        kind: str | None,
        campaign_id: str | None,
        limit: int,
        offset: int,
    ) -> list[dict[str, Any]]:
        rows = [
            r for r in self.proposals
            if (status is None or r["status"] == status)
            and (kind is None or r["kind"] == kind)
            and (campaign_id is None or r["campaignId"] == campaign_id)
        ]
        rows.sort(key=lambda r: r["_seq"], reverse=True)  # created_at DESC
        return [_strip_seq(r) for r in rows[offset:offset + limit]]

    def get_proposal(self, proposal_id: str) -> dict[str, Any] | None:
        return next((_strip_seq(r) for r in self.proposals if r["id"] == proposal_id), None)

    def find_open_proposal(self, candidate_id: str, kind: str) -> dict[str, Any] | None:
        matches = [
            r for r in self.proposals
            if r["candidateId"] == candidate_id and r["kind"] == kind and r["status"] == "PENDING"
        ]
        matches.sort(key=lambda r: r["_seq"], reverse=True)
        return _strip_seq(matches[0]) if matches else None

    def insert_proposal(
        self,
        campaign_id: str,
        candidate_id: str | None,
        kind: str,
        evidence: dict[str, Any] | None,
        expiry_days: int = 7,
    ) -> dict[str, Any]:
        self._seq += 1
        row = {
            "_seq": self._seq, "id": f"prop-{self._seq}", "campaignId": campaign_id,
            "candidateId": candidate_id, "kind": kind, "evidence": evidence, "status": "PENDING",
            "actor": None, "decidedAt": None,
            "expiresAt": "2026-07-18T00:00:00+00:00",  # DB computes now()+7d; fixed here
            "createdAt": "2026-07-11T00:00:00+00:00",
        }
        self.proposals.append(row)
        return _strip_seq(row)

    def refresh_proposal_evidence(
        self, proposal_id: str, evidence: dict[str, Any] | None
    ) -> dict[str, Any]:
        for r in self.proposals:
            if r["id"] == proposal_id and r["status"] == "PENDING":
                r["evidence"] = evidence
                return _strip_seq(r)
        return self.get_proposal(proposal_id)

    def decide_proposal(
        self, proposal_id: str, status: str, actor: str | None
    ) -> dict[str, Any] | None:
        for r in self.proposals:
            if r["id"] == proposal_id and r["status"] == "PENDING":
                r.update(status=status, actor=actor, decidedAt="2026-07-11T02:00:00+00:00")
                return _strip_seq(r)
        return None  # unknown or already-decided → the service maps to 404/409

    # --- E4 slice 2: selection + PUBLISH_PAPER execute (mirrors EvoRepo's slice-2 methods) -------

    def get_generation_by_n(self, campaign_id: str, n: int) -> dict[str, Any] | None:
        """Mirrors EvoRepo.get_generation_by_n: the (campaign_id, n) generation."""
        for gen in self.generations.get(campaign_id, []):
            if gen["n"] == n:
                return gen
        return None

    def _find_candidate(self, candidate_id: str) -> dict[str, Any] | None:
        for cands in self.candidates.values():
            for cand in cands:
                if cand["id"] == candidate_id:
                    return cand
        return None

    def get_candidate(self, candidate_id: str) -> dict[str, Any] | None:
        """Mirrors EvoRepo.get_candidate: one candidate by id across all campaigns."""
        found = self._find_candidate(candidate_id)
        return dict(found) if found is not None else None

    def update_candidate_selection(
        self, candidate_id: str, state: str, scorecard: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        """Mirrors EvoRepo.update_candidate_selection: set state + scorecard, touch updated_at."""
        cand = self._find_candidate(candidate_id)
        if cand is None:
            return None
        cand.update(state=state, scorecard=scorecard, updatedAt="2026-07-12T00:00:00+00:00")
        return dict(cand)

    def update_candidate_publish(
        self, candidate_id: str, version_id: str | None, state: str
    ) -> dict[str, Any] | None:
        """Mirrors EvoRepo.update_candidate_publish: link the clone version + advance state."""
        cand = self._find_candidate(candidate_id)
        if cand is None:
            return None
        cand.update(versionId=version_id, state=state, updatedAt="2026-07-12T00:00:00+00:00")
        return dict(cand)

    def record_proposal_execution(
        self, proposal_id: str, evidence: dict[str, Any] | None
    ) -> dict[str, Any] | None:
        """Mirrors EvoRepo.record_proposal_execution: stamp the execution evidence on an APPROVED
        proposal."""
        for r in self.proposals:
            if r["id"] == proposal_id and r["status"] == "APPROVED":
                r["evidence"] = evidence
                return _strip_seq(r)
        return None

    def close(self) -> None:
        pass


def _strip_seq(row: dict[str, Any]) -> dict[str, Any]:
    """Return a copy without the internal ``_seq`` ordering key (the real repo has no such key)."""
    return {k: v for k, v in row.items() if k != "_seq"}


class FakeNtfy:
    """Captures every ntfy push so a test can assert one-per-new-proposal without a network call."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, str, str]] = []

    def send(self, title: str, message: str, priority: str = "default") -> None:
        self.sent.append((title, message, priority))


class FakeReconRepo:
    """In-memory ``reconciliations`` store (V012) + the version→walk-forward-run lookup — the same
    camelCase envelope shape ReconciliationRepo emits, so the service maps it identically."""

    def __init__(self, walkforward_run_ids: dict[str, str] | None = None) -> None:
        self.rows: list[dict[str, Any]] = []
        self._walkforward = walkforward_run_ids or {}
        self._seq = 0

    def get_by_version_window(
        self, version_id: str, window_from: str, window_to: str
    ) -> dict[str, Any] | None:
        return next(
            (
                r for r in self.rows
                if r["versionId"] == version_id
                and r["windowFrom"] == window_from
                and r["windowTo"] == window_to
            ),
            None,
        )

    def list_by_version(self, version_id: str, limit: int, offset: int) -> list[dict[str, Any]]:
        matches = [r for r in self.rows if r["versionId"] == version_id]
        matches.sort(key=lambda r: r["_seq"], reverse=True)  # newest-first (created_at DESC)
        return matches[offset:offset + limit]

    def latest_walkforward_run_id(self, version_id: str) -> str | None:
        return self._walkforward.get(version_id)

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
        self._seq += 1
        row = {
            "_seq": self._seq,
            "id": f"recon-{self._seq}", "versionId": version_id, "strategyId": strategy_id,
            "windowFrom": window_from, "windowTo": window_to, "simJobId": sim_job_id,
            "simRunId": sim_run_id, "gap": gap, "gapZ": gap_z, "pairedTrades": paired_trades,
            "evidenceFloorMet": evidence_floor_met, "verdict": verdict, "diagnosis": diagnosis,
            "createdAt": "2026-07-11T00:00:00+00:00",
        }
        self.rows.append(row)
        return row

    def close(self) -> None:
        pass


class FakeLiveRepo:
    """In-memory STRATEGY-schema read model — mirrors LiveEvidenceRepo: resolve a version UUID to
    ``{strategyId, version, config}`` and return its live paper trades (the window is ignored — the
    fixture pre-scopes the trades to the window under test)."""

    def __init__(
        self,
        versions: dict[str, dict[str, Any]] | None = None,
        trades: dict[str, list[dict[str, Any]]] | None = None,
    ) -> None:
        self.versions = versions or {}
        self.trades = trades or {}

    def resolve_version(self, version_id: str) -> dict[str, Any] | None:
        return self.versions.get(version_id)

    def paper_trades_for_version(
        self, version_id: str, window_from: str, window_to: str
    ) -> list[dict[str, Any]]:
        return self.trades.get(version_id, [])

    def close(self) -> None:
        pass
