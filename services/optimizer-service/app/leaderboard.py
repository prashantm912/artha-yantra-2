"""The sweep leaderboard's plateau-adjusted ranking (§D.9, Phase 34).

The default sort re-scores each trial as the **median objective of its
parameter-space neighbors** (within ±1 grid step, an adjacent choice, or a
normalized ε-ball for continuous params) using only already-computed trials — no
extra backtests. A sharp spike surrounded by mediocrity sinks; a broad ridge
rises. Raw-objective sort stays one query away (``sort=raw``). Pruned/failed
trials are excluded upstream (only COMPLETE rows reach here), so a partial-coverage
trial never lands in a ``min`` aggregation.
"""

from __future__ import annotations

import statistics
from typing import Any

EPS = 0.1  # normalized ε-ball radius for continuous (stepless) parameters


def plateau_scores(
    trials: list[dict[str, Any]], parameters: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Each trial gets a ``plateauObjective`` (median of itself + its neighbors) and a
    ``neighborCount``. ``trials`` carry ``params`` (path→value) and a scalar ``objective``."""
    specs = {p["path"]: p for p in parameters}
    scored: list[dict[str, Any]] = []
    for trial in trials:
        values = [trial["objective"]]
        for other in trials:
            if other is trial:
                continue
            if _is_neighbor(trial["params"], other["params"], specs):
                values.append(other["objective"])
        scored.append({
            **trial,
            "plateauObjective": statistics.median(values),
            "neighborCount": len(values) - 1,
        })
    return scored


def best(
    trials: list[dict[str, Any]],
    parameters: list[dict[str, Any]],
    top: int,
    direction: str,
    sort: str,
) -> list[dict[str, Any]]:
    """The top-``top`` trials, sorted by plateau-adjusted objective (default) or raw objective."""
    scored = plateau_scores(trials, parameters)
    key = "objective" if sort == "raw" else "plateauObjective"
    scored.sort(key=lambda row: row[key], reverse=direction != "minimize")
    return scored[:top]


def pareto_front(
    trials: list[dict[str, Any]], objectives: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """The non-dominated (Pareto-optimal) subset of ``trials`` over a MULTI-objective ``objectives``
    list — used INSTEAD OF a scalar ranking for an ``nsga2`` sweep (AY-OPT-03: never collapse a
    multi-objective front to one number). Each objective is ``{metric, direction}``; a trial carries
    ``objectiveValues`` (``{metric: value}``). Trial ``a`` DOMINATES ``b`` when it is at least as
    good on every objective and strictly better on at least one (per each objective's direction);
    the front is every trial no other trial dominates. Trials missing any objective metric are
    excluded (cannot be compared). Returned in ``trialNumber`` order (deterministic response)."""
    metrics = [o["metric"] for o in objectives]
    candidates = [
        t for t in trials
        if all(m in (t.get("objectiveValues") or {}) for m in metrics)
    ]
    front = [
        t for t in candidates
        if not any(_dominates(other, t, objectives) for other in candidates if other is not t)
    ]
    front.sort(key=lambda t: t.get("trialNumber") if t.get("trialNumber") is not None else 0)
    return front


def _dominates(a: dict[str, Any], b: dict[str, Any], objectives: list[dict[str, Any]]) -> bool:
    """True when trial ``a`` Pareto-dominates ``b`` across ``objectives`` (≥ on all, > on ≥1),
    respecting each objective's direction (``minimize`` → smaller is better)."""
    a_values = a.get("objectiveValues") or {}
    b_values = b.get("objectiveValues") or {}
    strictly_better = False
    for objective in objectives:
        maximize = objective.get("direction", "maximize") != "minimize"
        av = float(a_values[objective["metric"]])
        bv = float(b_values[objective["metric"]])
        if av == bv:
            continue
        if (av > bv) != maximize:
            return False  # a is worse on this objective → cannot dominate b
        strictly_better = True
    return strictly_better


def axis_neighbors(
    params: dict[str, Any], parameters: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Every axis-adjacent cell of ``params``: for each tuned parameter, ``params`` with THAT one
    parameter moved a single step (±1 grid step, an adjacent choice, or ±ε·span for a stepless
    continuous range) and all others held — the "adjacent cell" a neighbor probe (§3.2.3) fills.

    Each cell differs from ``params`` in exactly one parameter, and only cells the CANONICAL
    ``_is_neighbor`` relation actually admits are returned (the same gate ``plateau_scores`` reads),
    so a probe submitted at one of these cells is guaranteed to be counted as a neighbor — one
    geometry, never a second definition. Range candidates outside ``[lo, hi]`` are dropped, not
    clamped (a clamped point sits less than a step away and would misrepresent the grid)."""
    specs = {p["path"]: p for p in parameters}
    cells: list[dict[str, Any]] = []
    for path, spec in specs.items():
        if path not in params:
            continue
        for value in _adjacent_values(spec, params[path]):
            cell = {**params, path: value}
            if _is_neighbor(params, cell, specs):
                cells.append(cell)
    return cells


# The four §D.4 regime labels, in the canonical RegimeLabel enum order (backtest-service
# serializes each fold's regimeOos keyed by RegimeLabel.name(), never a BULL/RANGE alias).
_REGIME_LABELS = ("UP_QUIET", "UP_TURBULENT", "DOWN_QUIET", "DOWN_TURBULENT")


def guard_metrics(summary: dict[str, Any]) -> dict[str, Any]:
    """Condenses a run's persisted guard summary into the compact ``/best`` row object: the
    ``dataHash`` + ``foldsExcluded`` pass through; ``regimesCovered`` is the union (canonical order)
    of regimes that traded across folds; ``regimeOosMin/Mean/Max`` aggregate every per-(fold,regime)
    OOS Sharpe. Sharpes arrive as decimal strings (or null for an untraded regime) — null/absent
    Sharpes are skipped, so when no regime traded the three aggregates are ``None`` (the badge then
    shows coverage only)."""
    sharpes: list[float] = []
    covered: set[str] = set()
    for fold in summary.get("regimeOos") or []:
        for label, stat in (fold or {}).items():
            sharpe = (stat or {}).get("sharpe")
            if sharpe is None:
                continue
            covered.add(label)
            sharpes.append(float(sharpe))
    return {
        "dataHash": summary.get("dataHash"),
        "foldsExcluded": summary.get("foldsExcluded"),
        "regimesCovered": [label for label in _REGIME_LABELS if label in covered],
        "regimeOosMin": min(sharpes) if sharpes else None,
        "regimeOosMean": statistics.fmean(sharpes) if sharpes else None,
        "regimeOosMax": max(sharpes) if sharpes else None,
    }


def _is_neighbor(
    a: dict[str, Any], b: dict[str, Any], specs: dict[str, dict[str, Any]]
) -> bool:
    """Two trials are neighbors when every tuned parameter is within one step / ε of the other."""
    for path, spec in specs.items():
        if path not in a or path not in b:
            return False
        if not _within(spec, a[path], b[path]):
            return False
    return True


def _within(spec: dict[str, Any], a: Any, b: Any) -> bool:
    if "choices" in spec:
        choices = list(spec["choices"])
        try:
            return abs(choices.index(a) - choices.index(b)) <= 1
        except ValueError:
            return a == b
    lo, hi = spec["range"]
    step = spec.get("step")
    if step:
        return abs(a - b) <= step + 1e-9
    span = (hi - lo) or 1
    return abs(a - b) / span <= EPS


def _adjacent_values(spec: dict[str, Any], value: Any) -> list[Any]:
    """The one-step neighbours of a SINGLE parameter value, mirroring ``_within``'s geometry: the
    adjacent ``choices`` entries, ``value ± step``, or ``value ± ε·span`` for a stepless range.
    Range candidates outside ``[lo, hi]`` are dropped. Stepless cells land just inside the ε-ball
    (the ``1 - 1e-9`` factor keeps the ratio strictly ≤ EPS under float rounding, so the caller's
    ``_is_neighbor`` gate admits them)."""
    if "choices" in spec:
        choices = list(spec["choices"])
        try:
            i = choices.index(value)
        except ValueError:
            return []
        return [choices[j] for j in (i - 1, i + 1) if 0 <= j < len(choices)]
    lo, hi = spec["range"]
    step = spec.get("step")
    delta = step if step else EPS * ((hi - lo) or 1) * (1 - 1e-9)
    return [_snap(c) for c in (value - delta, value + delta) if lo <= c <= hi]


def _snap(value: Any) -> Any:
    """Integer-snap an on-grid value (mirrors optuna_runner.grid_values), else round float drift."""
    return int(value) if float(value).is_integer() else round(value, 10)
