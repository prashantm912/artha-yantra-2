"""Sweep submission + query orchestration — the seam the API depends on (and tests
replace with fakes). Validates the optimize block + path grammar, INSERTs the
parent OPTIMIZATION job, and runs the ask/tell loop in a background thread."""

from __future__ import annotations

import json
import logging
import threading
from collections.abc import Callable
from typing import Any

from app import config_patch, leaderboard, path_grammar, sweep
from app.errors import ApiError

_METHODS = {"grid", "random", "tpe", "nsga2"}
_PROMOTABLE = "COMPLETE"
_OOS_METRIC = "oos_fold_mean"
_LOG = logging.getLogger(__name__)

# An uncapped maxTrials is a runaway sweep (register §9-8): grid self-caps at GRID_CAP, but
# random/tpe/nsga2 run EXACTLY maxTrials, so bound the request here.
_MAX_TRIALS_CAP = 1000

# The rankable metric keys the backtest TRIAL worker emits into each trial's `metrics` JSON
# (source of truth: backtest-service MetricsCalculator.compute + the BacktestRunner augmentation
# sites — keep in sync when a metric is added there). An objective naming anything outside this
# set scores every trial NaN, so the sweep "completes" with an EMPTY leaderboard (register §9-9);
# reject it at submit instead. `oos_fold_mean` is the walk-forward-only aggregate (a separate
# stream field), allowed here too.
_ALLOWED_OBJECTIVE_METRICS = frozenset(
    {
        "totalReturn",
        "cagr",
        "sharpe",
        "sortino",
        "maxDrawdown",
        "maxDrawdownDurationBars",
        "winRate",
        "profitFactor",
        "expectancy",
        "averageTrade",
        "exposure",
        "tradeCount",
        "foldsExcluded",
        "alpha",
        "beta",
        "informationRatio",
        "excessCagr",
        "upCapture",
        "downCapture",
        _OOS_METRIC,
    }
)


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
        """The plateau-adjusted leaderboard (§D.9); COMPLETE trials only (pruned/failed dropped)."""
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        try:
            job = jobs.get(sweep_id)
            if job is None:
                raise ApiError(404, "NOT_FOUND_JOB", f"no such job: {sweep_id}")
            request = job.get("request") or {}
            parameters = request.get("parameters", [])
            metric, direction = _primary_objective(request.get("objective", {}))
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
        self._attach_guard_metrics(ranked)
        return {"metric": metric, "sort": "raw" if sort == "raw" else "plateau", "items": ranked}

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
        (§D.9) — never published, provenance recorded in the notes. 409 for an invalid/failed
        trial."""
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
        provenance = f"created_by=optimizer:{sweep_id}"
        note = f"{provenance}; {notes}" if notes else provenance
        result = self._strategy.create_draft(strategy_id, patched, note)
        return {
            "strategyId": strategy_id,
            "newVersion": result.get("version") or result.get("currentVersion"),
            "status": result.get("status", "draft"),
        }

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


def _primary_objective(objective: dict[str, Any]) -> tuple[str, str]:
    """The scalar metric + direction the leaderboard ranks on (the first of an nsga2 ``objectives``
    list, else the single-objective ``metric``)."""
    objectives = objective.get("objectives")
    if objectives:
        first = objectives[0]
        return first["metric"], first.get("direction", "maximize")
    return objective.get("metric", "sharpe"), objective.get("direction", "maximize")


def _early_stopping(request: dict[str, Any]) -> int | None:
    """Trials-without-improvement window for early stopping, or None when disabled."""
    window = request.get("earlyStopping")
    if isinstance(window, bool):  # `earlyStopping: true` → a sensible default window
        return 20 if window else None
    if not window:  # None / 0 / "" → disabled
        return None
    return _coerce_int(window, "earlyStopping")
