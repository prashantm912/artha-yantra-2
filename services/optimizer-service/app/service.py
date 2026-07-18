"""Sweep submission + query orchestration — the seam the API depends on (and tests
replace with fakes). Validates the optimize block + path grammar, INSERTs the
parent OPTIMIZATION job, and runs the ask/tell loop in a background thread."""

from __future__ import annotations

import json
import logging
import threading
from collections.abc import Callable
from typing import Any

from app import config_patch, leaderboard, metrics_catalog, path_grammar, sweep
from app.errors import ApiError
from app.repos import TrialNumberConflict

_METHODS = {"grid", "random", "tpe", "nsga2"}
_PROMOTABLE = "COMPLETE"
_OOS_METRIC = "oos_fold_mean"
_LOG = logging.getLogger(__name__)

# An uncapped maxTrials is a runaway sweep (register §9-8): grid self-caps at GRID_CAP, but
# random/tpe/nsga2 run EXACTLY maxTrials, so bound the request here.
_MAX_TRIALS_CAP = 1000

# Neighbor-probe batch ceiling (§3.2.3): probes ride the SAME trial queue as a sweep, so an
# uncapped batch is a runaway ask — bound it, as maxTrials is bounded. The default (40) is the
# request default; this is the hard ceiling.
_MAX_PROBES_CEILING = 200
# A completed sweep holds ≤ maxTrials (1000) trials; this LIMIT fetches every one for dedup.
_TRIAL_SCAN_LIMIT = 100_000

# The rankable metric keys the backtest TRIAL worker emits into each trial's `metrics` JSON —
# DERIVED from the shared catalog (contracts/metrics/trial-metrics-catalog.json), the ONE source of
# truth both languages consume so the optimizer's allow-list and the Java emitter never drift (a
# metric added on either side without the other used to slip through). An objective naming anything
# outside this set scores every trial NaN, so the sweep "completes" with an EMPTY leaderboard
# (register §9-9); reject it at submit instead. `oos_fold_mean` (the walk-forward-only aggregate) is
# a catalog entry too.
_ALLOWED_OBJECTIVE_METRICS = metrics_catalog.objective_metric_names()


def resolve_parameters(config: dict[str, Any], override: list[dict] | None) -> list[dict]:
    """The sweep's tunable parameters: an explicit override, else the config's optimize block."""
    if override:
        return override
    optimize = config.get("backtest", {}).get("optimize") or config.get("optimize") or {}
    return optimize.get("parameters", [])


def _yaml_precedence_warnings(optimize: dict[str, Any], request: dict[str, Any]) -> list[str]:
    """Sweep-control fields come from the /optimizations/run REQUEST, never the strategy YAML.

    Only ``optimize.parameters`` is read from the YAML (see resolve_parameters); method / maxTrials
    / objective / walkForward come from the request body. A user who sets, say, ``walk_forward`` in
    the YAML optimize block and omits it from the request would silently get a plain in-sample sweep
    with empty OOS folds (register §9-7). Return a warning for each control field the YAML sets but
    the request omits, so the ignored value is surfaced instead of swallowed."""
    fields = (
        ("method", "method"),
        ("max_trials", "maxTrials"),
        ("objective", "objective"),
        ("walk_forward", "walkForward"),
    )
    return [
        f"optimize.{yaml_key} in the strategy YAML is IGNORED — {req_key} is read from the "
        f"/optimizations/run request only; add it to the request body to take effect"
        for yaml_key, req_key in fields
        if optimize.get(yaml_key) is not None and request.get(req_key) is None
    ]


def _fold_objective_guard(
    objective: dict[str, Any], walk_forward: Any
) -> tuple[dict[str, Any], str | None]:
    """Steer a WALK-FORWARD sweep to the out-of-sample objective (the silent in-sample trap).

    A fold sweep that optimizes an IN-SAMPLE metric (e.g. ``sharpe``) across folds curve-fits each
    fold's train window and ignores OOS — the exact footgun behind the overfit scalper sweeps. When
    ``walk_forward`` is present and the objective is not already ``oos_fold_mean``, override it to
    ``{metric: oos_fold_mean, direction: maximize}`` and return a warning to log. No-op for plain
    (foldless) sweeps and for sweeps already on ``oos_fold_mean``.
    """
    if not walk_forward or (objective or {}).get("metric") == _OOS_METRIC:
        return objective, None
    overridden = {"metric": _OOS_METRIC, "direction": "maximize"}
    warning = (
        f"walk-forward sweep submitted with objective {objective!r}; overriding to "
        f"{overridden!r} (an in-sample fold objective overfits — pass objective.metric="
        f"{_OOS_METRIC!r} to silence)."
    )
    return overridden, warning


def _effective_fold_metric(
    request_objective: dict[str, Any], optimize_block: dict[str, Any]
) -> str | None:
    """The metric each OOS fold is measured in — what ``oos_fold_mean`` aggregates and what the
    fold-fed pruner keys off (AY-OPT-02). The objective is REQUEST-owned (the documented split:
    objective/walkForward/maxTrials come from the request, only parameters from the YAML), so the
    request's concrete objective metric wins; the YAML ``optimize.objective.metric`` is the fallback
    ONLY when the request names none. ``oos_fold_mean`` is the AGGREGATE (the mean of the
    fold metric), not a per-fold metric, so it too falls back to the YAML. ``None`` when neither
    names one (the backtest worker then defaults to ``sharpe``)."""
    request_metric = (request_objective or {}).get("metric")
    if request_metric and request_metric != _OOS_METRIC:
        return request_metric
    return ((optimize_block or {}).get("objective") or {}).get("metric")


def _effective_fold_direction(
    request_objective: dict[str, Any], optimize_block: dict[str, Any], fold_metric: str | None
) -> str:
    """The direction the fold objective — and thus the ``oos_fold_mean`` sweep objective — is
    optimized in (AY-OPT-02). A mean-of-a-minimize-metric (e.g. maxDrawdown) must be MINIMIZED, so
    the optimizer selects the BEST (smallest) trials, not the worst. Resolution: the request's
    ``direction`` > the YAML objective's ``direction`` > the catalog's canonical direction for the
    fold metric. Only the LAST leans on the catalog, so an explicit direction always wins."""
    request_direction = (request_objective or {}).get("direction")
    if request_direction:
        return request_direction
    yaml_direction = ((optimize_block or {}).get("objective") or {}).get("direction")
    if yaml_direction:
        return yaml_direction
    return metrics_catalog.canonical_direction(fold_metric)


def _coerce_int(value: Any, field: str) -> int:
    """Coerce a request field to int, raising a 400 (not an opaque int()/KeyError 500) on a
    non-numeric value (register §9-8)."""
    try:
        return int(value)
    except (TypeError, ValueError):
        raise ApiError(
            400, "VALIDATION_FAILED", f"{field} must be an integer, got {value!r}"
        ) from None


def _validate_objective_metrics(method: str, objective: dict[str, Any]) -> None:
    """Reject an objective naming a metric the backtest never emits — else every trial scores NaN
    and the sweep 'completes' with an empty leaderboard (register §9-9). Mirrors the run_sweep
    read: nsga2 ranks the multi-objective list, everything else the single scalar metric."""
    if method == "nsga2":
        names = [o.get("metric") for o in sweep.multi_objectives(objective)]
    else:
        names = [objective.get("metric", "sharpe")]
    for name in names:
        if name not in _ALLOWED_OBJECTIVE_METRICS:
            raise ApiError(
                400,
                "VALIDATION_FAILED",
                f"unknown objective metric {name!r}; expected one of "
                f"{sorted(_ALLOWED_OBJECTIVE_METRICS)}",
            )


class SweepService:
    """Validates + launches sweeps and answers status/trial queries."""

    def __init__(
        self,
        *,
        strategy_client: Any,
        jobs_factory: Callable[[], Any],
        trials_factory: Callable[[], Any],
        dispatcher: Any,
        backtest_client: Any = None,
        runner: Callable[..., Any] = sweep.run_sweep,
    ) -> None:
        self._strategy = strategy_client
        self._backtest = backtest_client
        self._jobs_factory = jobs_factory  # fresh repo (+ conn) per thread — psycopg isn't shared
        self._trials_factory = trials_factory
        self._dispatcher = dispatcher
        self._runner = runner
        self._cancelled: set[str] = set()

    def submit(self, request: dict[str, Any]) -> str:
        method = request.get("method", "tpe")
        if method not in _METHODS:
            raise ApiError(400, "VALIDATION_FAILED", f"unsupported method: {method!r}")
        for field in ("strategyId", "from", "to"):
            if not request.get(field):
                raise ApiError(400, "VALIDATION_FAILED", f"missing required field: {field}")

        # Validate the request-only knobs BEFORE the strategy-resolve round-trip (register §9-8):
        # objective / walkForward / maxTrials / seed / earlyStopping all come from the request, not
        # the config, so an obviously-bad value is a fast, clean 400 — never a resolve-dependent 500
        # (resolve() raise_for_status()es a bad strategyId), and no wasted call to strategy-signal.
        walk_forward = request.get("walkForward")
        if walk_forward is not None and not isinstance(walk_forward, dict):
            raise ApiError(
                400, "VALIDATION_FAILED", f"walkForward must be an object, got {walk_forward!r}"
            )
        # Fold-sweep objective guard: a walk-forward sweep on an in-sample metric overfits — steer
        # it to oos_fold_mean (+ warn). Run before the echo so the job records the EFFECTIVE
        # objective, not the as-submitted one.
        raw_objective = request.get("objective") or {}  # pre-guard: the request's own metric
        objective = request.get("objective", {"metric": "sharpe", "direction": "maximize"})
        objective, fold_warning = _fold_objective_guard(objective, walk_forward)
        if fold_warning:
            _LOG.warning(fold_warning)
        _validate_objective_metrics(method, objective)
        max_trials = _coerce_int(request.get("maxTrials", 100), "maxTrials")
        if not 1 <= max_trials <= _MAX_TRIALS_CAP:
            raise ApiError(
                400,
                "VALIDATION_FAILED",
                f"maxTrials must be between 1 and {_MAX_TRIALS_CAP}, got {max_trials}",
            )
        seed = _coerce_int(request.get("seed", 0), "seed")
        early_stopping = _early_stopping(request)

        # strategyVersion is OPTIONAL — when omitted we pin the strategy's current resolution
        # (latest published, else latest draft), matching /backtests/run (§D.5).
        version, config = self._strategy.resolve(
            request["strategyId"], request.get("strategyVersion")
        )
        parameters = resolve_parameters(config, request.get("parameters"))
        if not parameters:
            raise ApiError(422, "VALIDATION_FAILED", "no tunable parameters in the optimize block")
        for parameter in parameters:
            if not isinstance(parameter, dict) or "path" not in parameter:
                raise ApiError(
                    400,
                    "VALIDATION_FAILED",
                    f"each optimize parameter needs a 'path'; got {parameter!r}",
                )
            path_grammar.validate(parameter["path"])  # raises InvalidParameterPath -> 400 handler

        # Surface any YAML optimize-block control field the request omits (else silently ignored).
        optimize_block = config.get("backtest", {}).get("optimize") or config.get("optimize") or {}
        for warning in _yaml_precedence_warnings(optimize_block, request):
            _LOG.warning(warning)

        # The REQUEST-owned fold objective metric (AY-OPT-02) — threaded into each trial's backtest
        # request so the fold aggregation (oos_fold_mean) + per-fold pruner telemetry key off the
        # metric the REQUEST declared, not whatever the YAML names.
        fold_objective_metric = _effective_fold_metric(raw_objective, optimize_block)
        # ...and the oos_fold_mean sweep objective the fold guard emits must be optimized in that
        # metric's DIRECTION (AY-OPT-02): a mean-of-a-minimize-metric (maxDrawdown) is MINIMIZED, so
        # the optimizer picks the BEST trials — the guard's hardcoded 'maximize' picks the WORST.
        if objective.get("metric") == _OOS_METRIC:
            objective = {
                **objective,
                "direction": _effective_fold_direction(
                    raw_objective, optimize_block, fold_objective_metric
                ),
            }

        jobs = self._jobs_factory()
        try:
            sweep_id = jobs.insert_sweep(None, _sweep_echo(request, parameters, objective))
        finally:
            jobs.close()

        request_base = {
            "strategyId": request["strategyId"],
            "strategyVersion": version,
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
                "max_trials": max_trials,
                "objective": objective,
                "seed": seed,
                "walk_forward": walk_forward,
                "request_base": request_base,
                "early_stopping": early_stopping,
                "fold_objective_metric": fold_objective_metric,
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
                cancelled=lambda: sweep_id in self._cancelled,
                **kwargs,
            )
        except Exception:  # noqa: BLE001 - mark the sweep failed, never crash the thread
            # Log the traceback BEFORE marking failed: without it a config typo, a strategy-resolve
            # 500, and an infra outage all present identically as a bare "failed" status with no
            # diagnosable cause (register §9-6).
            _LOG.exception("sweep %s failed", sweep_id)
            jobs.set_status(sweep_id, "failed")
        finally:
            jobs.close()
            trials.close()

    def _progress(
        self, sweep_id: str, jobs: Any, done: int, total: int, best: float | None
    ) -> None:
        # Cancellation is handled by run_sweep's per-iteration ``cancelled`` poll (P1-10b) — the
        # old raise-from-progress path made the blanket _run handler overwrite 'cancelled' with
        # 'failed'; the repos.set_status terminal guard now also blocks that class of overwrite.
        pct = int(done * 100 / total) if total else 100
        jobs.set_status(sweep_id, "running", min(pct, 99))
        self._dispatcher.publish_progress(
            sweep_id,
            json.dumps({"jobId": sweep_id, "status": "running", "progress": pct,
                        "trialsCompleted": done, "trialsTotal": total, "bestSoFar": best}),
        )

    def list_sweeps(self, limit: int, offset: int) -> dict[str, Any]:
        """Native sweep list (audit P2-1): OPTIMIZATION jobs newest-first, each projected to a
        compact summary. A restart-interrupted sweep surfaces its ``error`` here (marked failed
        with a real reason on boot by fail_orphaned_sweeps), so the list distinguishes it from a
        genuine failure. Envelope mirrors ``trials`` — {items, limit, offset}."""
        jobs = self._jobs_factory()
        try:
            rows = jobs.list_sweeps(limit, offset)
        finally:
            jobs.close()
        return {"items": [_sweep_summary(r) for r in rows], "limit": limit, "offset": offset}

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

    def best(self, sweep_id: str, top: int, sort: str) -> dict[str, Any]:
        """The plateau-adjusted leaderboard (§D.9); COMPLETE trials only (pruned/failed dropped).

        A MULTI-objective (``nsga2``) sweep keeps the SAME scalar leaderboard shape the FE contract
        requires (``metric`` + per-row ``objective``, ranked on the first objective as a
        representative view, capped at ``top``) and ADDITIVELY exposes its full Pareto front under
        ``paretoFront`` — so the non-dominated set is surfaced, never SILENTLY collapsed to the
        scalar ranking (AY-OPT-03). The front carries core objective values only, with NO per-row
        guard enrichment (that stays bounded to the scalar top-N) — a large front never fans out."""
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        try:
            job = jobs.get(sweep_id)
            if job is None:
                raise ApiError(404, "NOT_FOUND_JOB", f"no such job: {sweep_id}")
            request = job.get("request") or {}
            parameters = request.get("parameters", [])
            objective = request.get("objective", {})
            multi = request.get("method") == "nsga2"
            metric, direction = _primary_objective(objective)
            rows = trials.list_for_sweep(sweep_id, _PROMOTABLE, 1000, 0)
        finally:
            jobs.close()
            trials.close()
        items = []
        for row in rows:
            values = row.get("objectiveValues") or {}
            if metric not in values:
                continue
            items.append(
                {
                    "trialNumber": row["trialNumber"],
                    "params": row["params"],
                    "objective": float(values[metric]),
                    "objectiveValues": values,
                    "backtestRunId": row.get("backtestRunId"),
                }
            )
        ranked = leaderboard.best(items, parameters, top, direction, sort)
        self._attach_guard_metrics(ranked)  # bounded to the top-N view — never the whole front
        response = {
            "metric": metric,
            "sort": "raw" if sort == "raw" else "plateau",
            "items": ranked,
        }
        if multi:
            self._expose_pareto_front(response, objective, rows)
        return response

    def _expose_pareto_front(
        self, response: dict[str, Any], objective: dict[str, Any], rows: list[dict[str, Any]]
    ) -> None:
        """AY-OPT-03: additively expose a multi-objective (``nsga2``) sweep's Pareto front on the
        leaderboard response, WITHOUT touching the scalar ``metric``/``items``/per-row ``objective``
        fields the FE contract requires. The non-dominated set is surfaced under ``paretoFront``
        (no longer silently collapsed to the scalar ranking), each row carrying its identity + full
        ``objectiveValues`` only — NO per-row guard enrichment (that stays bounded to the scalar
        top-N ``items``), so a large front never fans out on every poll. Picking ONE point off the
        front is an explicit owner choice downstream (``promote``)."""
        objectives = sweep.multi_objectives(objective)
        front = leaderboard.pareto_front(rows, objectives)
        response["multiObjective"] = True
        response["objectives"] = objectives
        response["paretoFront"] = [
            {
                "trialNumber": row["trialNumber"],
                "params": row["params"],
                "objectiveValues": row.get("objectiveValues") or {},
                "backtestRunId": row.get("backtestRunId"),
            }
            for row in front
        ]

    def _attach_guard_metrics(self, ranked: list[dict[str, Any]]) -> None:
        """Surfaces the §D.4 guard outputs (already persisted — never recomputed) as a compact
        optional ``guardMetrics`` object on each ranked row, only after ranking so the per-run reads
        stay bounded by ``top``. A full-window/legacy trial (no fold structure, or no backtest
        client wired) is left without the key — the React leaderboard then shows a 'no fold guards'
        badge."""
        if self._backtest is None:
            return
        for row in ranked:
            run_id = row.get("backtestRunId")
            if run_id is None:
                continue
            summary = self._backtest.guard_summary(run_id)
            if summary is not None:
                row["guardMetrics"] = leaderboard.guard_metrics(summary)

    def trial_folds(self, sweep_id: str, trial_number: int) -> Any:
        """The per-fold metric array for one sweep trial, resolved via its ``backtest_run_id``."""
        trials = self._trials_factory()
        try:
            row = trials.get_trial(sweep_id, trial_number)
        finally:
            trials.close()
        if row is None:
            raise ApiError(404, "NOT_FOUND_RESOURCE", f"no such trial: {trial_number}")
        run_id = row.get("backtestRunId")
        if run_id is None:
            return []
        return self._backtest.folds(run_id)

    def promote(self, sweep_id: str, trial_number: int, notes: str | None) -> dict[str, Any]:
        """Materializes a COMPLETE trial's params onto the source version and POSTs a new draft
        (§D.9) — never published. Provenance is the version's `created_by` column, stamped
        `optimizer:{sweepId}` (V002:36's stated contract / audit T3), NOT a free-text note. 409 for
        an invalid/failed trial."""
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        try:
            job = jobs.get(sweep_id)
            if job is None:
                raise ApiError(404, "NOT_FOUND_JOB", f"no such job: {sweep_id}")
            request = job.get("request") or {}
            row = trials.get_trial(sweep_id, trial_number)
        finally:
            jobs.close()
            trials.close()
        if row is None:
            raise ApiError(404, "NOT_FOUND_RESOURCE", f"no such trial: {trial_number}")
        if row.get("state") != _PROMOTABLE:
            raise ApiError(
                409,
                "CONFLICT_JOB_TERMINAL",
                f"trial {trial_number} is {row.get('state')}, not promotable",
            )
        strategy_id = request["strategyId"]
        config = self._strategy.version_config(strategy_id, request["strategyVersion"])
        patched = config_patch.apply_overrides(config, row["params"])
        # Audit T3: the machine-readable provenance is now the `created_by` COLUMN (a first-class,
        # EVO-filterable actor), not embedded in the free-text `notes`. The note keeps the
        # human-readable lineage.
        created_by = f"optimizer:{sweep_id}"
        lineage = f"promoted trial {trial_number} of sweep {sweep_id}"
        note = f"{lineage}; {notes}" if notes else lineage
        result = self._strategy.create_draft(strategy_id, patched, note, created_by=created_by)
        return {
            "strategyId": strategy_id,
            "newVersion": result.get("version") or result.get("currentVersion"),
            "status": result.get("status", "draft"),
        }

    def probe(self, sweep_id: str, top_k: int, max_probes: int) -> dict[str, Any]:
        """Submit the plateau top-K's MISSING ±1-step neighbors as ordinary trials of THIS completed
        sweep (design §3.2.3 / §11), so a later ``/best`` read MEASURES ``neighborCount`` instead of
        inheriting it accidentally. Manual, no autonomy. 404 for an unknown or non-OPTIMIZATION job;
        409 unless the sweep is completed. Candidate cells are deduped against existing trials
        (all states except FAILED, which is retryable — see ``_plan_probes``), then ceiling-capped.
        Each probe is dispatched through the SAME queue the sweep used (never a direct backtest
        submission), so it rides the B16 worker-pool cap; a background thread drains their results
        (the sweep's own thread is long gone). Returns ``{submitted, skipped, trials}``."""
        top_k = min(max(top_k, 1), 100)
        max_probes = min(max(max_probes, 0), _MAX_PROBES_CEILING)
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        try:
            job = jobs.get(sweep_id)
            if job is None or job.get("kind") != "OPTIMIZATION":
                raise ApiError(404, "NOT_FOUND_JOB", f"no such sweep: {sweep_id}")
            if job.get("status") != "completed":
                raise ApiError(
                    409,
                    "CONFLICT_JOB_TERMINAL",
                    f"sweep is {job.get('status')!r}, not completed; probes run only on a "
                    "completed sweep",
                )
            request = job.get("request") or {}
            parameters = request.get("parameters", [])
            metric, direction = _primary_objective(request.get("objective", {}))
            rows = trials.list_for_sweep(sweep_id, None, _TRIAL_SCAN_LIMIT, 0)
            to_submit, skipped = _plan_probes(
                rows, parameters, metric, direction, top_k, max_probes
            )
            if not to_submit:
                return {"submitted": 0, "skipped": skipped, "trials": []}
            template = jobs.child_trial_request(sweep_id)
            if template is None:  # a completed sweep with COMPLETE trials always has a trial job
                raise ApiError(
                    409, "CONFLICT_JOB_TERMINAL", "sweep has no trial job to template a probe from"
                )
            number = trials.max_trial_number(sweep_id) + 1
            pending: dict[str, int] = {}
            descriptors: list[dict[str, Any]] = []
            try:
                for cell in to_submit:
                    # Ledger row FIRST (a numbering race then skips the cell before any jobs row
                    # exists; a later failure strands only a RUNNING ledger row, which the boot
                    # reaper deletes — a queued jobs row would have no reaper).
                    row_id, number = _insert_with_retry(trials, sweep_id, number, cell)
                    if row_id is None:  # persistent numbering collision — degrade to a skip
                        skipped += 1
                        continue
                    trial_job_id = jobs.insert_trial(
                        sweep_id, None, _probe_request(template, cell),
                        created_by=f"optimizer:{sweep_id}:probe",
                    )
                    self._dispatcher.dispatch(trial_job_id)
                    pending[str(trial_job_id)] = row_id
                    descriptors.append(
                        {"trialNumber": number, "params": cell, "trialJobId": str(trial_job_id)}
                    )
                    number += 1
            except Exception:
                # Partial-dispatch safety: anything ALREADY dispatched must still be drained (else
                # its ledger row sits RUNNING forever while the worker's result is destroyed
                # unread); start the drain, then surface the error. The failing cell's own
                # dispatched-nothing remnants (a RUNNING row without a dispatch) are boot-reaped.
                if pending:
                    self._start_drain(sweep_id, pending, metric)
                raise
        finally:
            jobs.close()
            trials.close()
        if pending:
            self._start_drain(sweep_id, pending, metric)
        return {"submitted": len(descriptors), "skipped": skipped, "trials": descriptors}

    def _start_drain(self, sweep_id: str, pending: dict[str, int], metric: str) -> None:
        threading.Thread(
            target=self._drain_probes,
            kwargs={"sweep_id": sweep_id, "pending": pending, "metric": metric},
            daemon=True,
            name=f"probe-{sweep_id[:8]}",
        ).start()

    def _drain_probes(self, *, sweep_id: str, pending: dict[str, int], metric: str) -> None:
        """The probe result-drain thread (fresh per-thread repos, like ``_run``): resolves each
        dispatched probe row to COMPLETE/FAILED. The sweep's own loop has exited, so without this
        the probe rows would sit unresolved and never lift ``neighborCount``."""
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        try:
            sweep.run_probes(
                sweep_id=sweep_id, pending=pending, metric=metric,
                jobs=jobs, trials=trials, dispatcher=self._dispatcher,
            )
        except Exception:  # noqa: BLE001 - never crash the daemon thread
            _LOG.exception("probe drain for sweep %s failed", sweep_id)
        finally:
            jobs.close()
            trials.close()

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


def _sweep_echo(
    request: dict[str, Any], parameters: list[dict], objective: dict[str, Any] | None = None
) -> dict[str, Any]:
    echo = {k: request[k] for k in request if k != "parameters"}
    echo["parameters"] = parameters
    # Record the EFFECTIVE objective (post fold-guard) so the job reflects what actually ran.
    if objective is not None:
        echo["objective"] = objective
    return echo


def _sweep_summary(row: dict[str, Any]) -> dict[str, Any]:
    """Projects a JobsRepo.list_sweeps row to the stable listing shape — the raw ``request`` echo
    is projected down to its identifying fields (strategyId/method/objective/window), never dumped
    whole. ``error`` carries the restart-interruption reason for a boot-reaped sweep."""
    request = row.get("request") or {}
    return {
        "jobId": row["id"],
        "status": row["status"],
        "progress": row["progress"],
        "error": row.get("error"),
        "createdAt": row.get("createdAt"),
        "strategyId": request.get("strategyId"),
        "method": request.get("method"),
        "objective": request.get("objective"),
        "from": request.get("from"),
        "to": request.get("to"),
    }


def _primary_objective(objective: dict[str, Any]) -> tuple[str, str]:
    """The single scalar metric + direction for the surfaces that need one: the ``/best`` scalar
    leaderboard view (a representative ranking), the retro scorecard's ``rawObjective``, and the
    neighbour-probe geometry. For a multi-objective (``nsga2``) sweep this is the FIRST objective —
    a DOCUMENTED, deliberate representative pick, NOT a silent collapse: ``/best`` ALSO exposes the
    full non-dominated set under ``paretoFront`` (AY-OPT-03) — surfaced, never hidden."""
    objectives = objective.get("objectives")
    if objectives:
        first = objectives[0]
        return first["metric"], first.get("direction", "maximize")
    return objective.get("metric", "sharpe"), objective.get("direction", "maximize")


def _plan_probes(
    rows: list[dict[str, Any]],
    parameters: list[dict[str, Any]],
    metric: str,
    direction: str,
    top_k: int,
    max_probes: int,
) -> tuple[list[dict[str, Any]], int]:
    """Plan the neighbor-probe batch: rank the COMPLETE trials by plateau objective, take the
    top-K, expand each into its axis neighbours (``leaderboard.axis_neighbors`` — the ONE geometry),
    drop cells that already exist as a trial or repeat across the top-K, and cap at ``max_probes``.
    The best trial's neighbours are expanded first, so the cap favours filling the top plateau.
    Returns ``(cells to submit, skipped)``, skipped = already-present + over-cap.

    Dedup excludes FAILED rows: a FAILED cell was LOST (transient worker error / NaN metric), not
    judged — deduping it would blackhole that neighbour forever after one flaky failure; a re-POST
    retries it. COMPLETE, PRUNED, and RUNNING (in flight) stay deduped; a RUNNING row STRANDED by a
    restart is DELETED at boot (TrialsRepo.delete_stranded_running), so it cannot dedupe forever."""
    existing = {_param_key(r["params"]) for r in rows if r.get("state") != "FAILED"}
    complete = [
        {"params": r["params"], "objective": float((r.get("objectiveValues") or {})[metric])}
        for r in rows
        if r.get("state") == _PROMOTABLE and metric in (r.get("objectiveValues") or {})
    ]
    top = leaderboard.best(complete, parameters, top_k, direction, "plateau")
    seen: set[tuple] = set()
    new_cells: list[dict[str, Any]] = []
    already = 0
    for trial in top:
        for cell in leaderboard.axis_neighbors(trial["params"], parameters):
            key = _param_key(cell)
            if key in seen:
                continue
            seen.add(key)
            if key in existing:
                already += 1
            else:
                new_cells.append(cell)
    to_submit = new_cells[:max_probes]
    return to_submit, already + (len(new_cells) - len(to_submit))


def _param_key(params: dict[str, Any]) -> tuple:
    """An order-independent, hashable identity for a parameter vector (``1`` and ``1.0`` collapse —
    Python hashes them equal), for deduping probe cells against existing trials."""
    return tuple(sorted(params.items()))


def _insert_with_retry(
    trials: Any, sweep_id: str, number: int, cell: dict[str, Any]
) -> tuple[int | None, int]:
    """Insert a probe's ledger row, absorbing a numbering collision (23505 on the sweep's
    (sweep_job_id, trial_number) unique index — a concurrent second POST read the same
    ``max_trial_number``): re-read max+1 and retry once; a second collision skips the cell, so a
    race degrades to a skip, never a 500. Returns ``(row_id, number_used)``; ``row_id`` is ``None``
    when the cell was skipped."""
    for _ in range(2):
        try:
            return trials.insert(sweep_id, number, cell), number
        except TrialNumberConflict:
            number = trials.max_trial_number(sweep_id) + 1
    return None, number


def _probe_request(template: dict[str, Any], cell: dict[str, Any]) -> dict[str, Any]:
    """A probe's TRIAL request = the sweep's own trial template with only ``paramsOverride`` swapped
    to the neighbour cell — same resolved version, window, and fold context, so the probe is an
    honest neighbour, not a differently-configured run. No extra field is added to the request the
    backtest worker parses; probe provenance rides the trial job's ``created_by`` actor."""
    request = dict(template)
    request["paramsOverride"] = cell
    return request


def _early_stopping(request: dict[str, Any]) -> int | None:
    """Trials-without-improvement window for early stopping, or None when disabled."""
    window = request.get("earlyStopping")
    if isinstance(window, bool):  # `earlyStopping: true` → a sensible default window
        return 20 if window else None
    if not window:  # None / 0 / "" → disabled
        return None
    return _coerce_int(window, "earlyStopping")
