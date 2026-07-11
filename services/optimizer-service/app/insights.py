"""Parameter effect & brittleness analytics over an EXISTING sweep (design §3.4 / §12 E2 item 6).

Read-only. One endpoint — ``GET /api/v1/evolution/insights/{sweepJobId}`` — that reads a completed
sweep's COMPLETE trials (params + the scalar objective) and derives three views, no new backtests:

1. **Importance** (the tornado): per swept parameter, the strength + sign of its monotonic effect on
   the objective, ranked strongest-first. Mechanism = a hand-rolled **|Spearman| rank correlation**
   (``|rho|`` of the param's rank vs the objective's rank), NOT Optuna's importance evaluator. The
   installed optuna (4.9.0, no scikit-learn) offers only ``PedAnovaImportanceEvaluator`` without
   sklearn (fANOVA + MeanDecreaseImpurity both hard-import sklearn), and PedAnova (a) yields
   UNSIGNED quantile-membership magnitudes — it cannot answer the design's literal ask, "strongest
   **positive / negative** effect per parameter" (§3.4) — and (b) needs a full in-memory
   ``optuna.Study`` reconstructed from our trial rows (each param spec mapped back to an optuna
   distribution). Spearman gives the signed effect the tornado wants, is deterministic by
   construction (pure arithmetic, no seed), stays robust at the small trial counts these sweeps
   produce, and shares one trial-load with the brittleness + slice views. The by-hand route is
   explicitly blessed by the design ("implement … by hand — n is small"). See the build receipt /
   open-doubts for the full trade-off.

2. **Brittleness map**: per parameter, the objective's local variance across its NEIGHBOR PAIRS
   (design §3.4). Neighbors are defined ONCE, in ``leaderboard`` — this module reuses
   ``leaderboard._is_neighbor`` verbatim (never a second neighbor definition). High importance AND
   high local variance ⇒ brittle (a median split across the varying params).

3. **Interaction sniff**: 2-D plateau slices for the top-3 importance pairs — a grid of the EXISTING
   trials only (median objective + trial count per (x, y) cell), no extra runs.

Degrades honestly: < 2 completed trials ⇒ 200 with empty arrays + a ``notes`` entry; a parameter
that never varied is excluded from importance with a note. 404 for an unknown/non-OPTIMIZATION job.
"""

from __future__ import annotations

import math
import statistics
from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app import leaderboard
from app.errors import ApiError
from app.service import _PROMOTABLE, _primary_objective

# Own router on the shared /api/v1/evolution prefix (already gateway-allowlisted this week — no
# edge-gateway change needed). A sibling router to evolution.router, wired independently in main.py.
router = APIRouter(prefix="/api/v1/evolution")

# Cohort read bound — mirrors the /best leaderboard's 1000-row cap (service.best). A sweep rarely
# reaches this many COMPLETE trials; the analytics are O(n^2) over pairs, same as plateau_scores.
_TRIAL_CAP = 1000


class ImportanceItem(BaseModel):
    """One parameter's tornado entry. ``score`` = |Spearman rho| (0..1); ``direction`` is the SIGN
    of the param↔objective relationship on the RAW objective metric — ``positive`` (higher param →
    higher metric), ``negative``, or ``flat`` — independent of the maximize/minimize goal."""

    param: str
    score: float
    direction: str | None = None


class BrittlenessItem(BaseModel):
    """A parameter × stability cell. ``importance`` is the same |rho| reused from the tornado;
    ``localVariance`` is the mean per-neighbor-pair objective variance (None if no neighbor pair
    varies this param); ``brittle`` flags high-importance + high-local-variance."""

    param: str
    importance: float
    localVariance: float | None = None
    brittle: bool


class SliceCell(BaseModel):
    """One existing-trials cell of a 2-D interaction slice: the (x, y) param values, the median
    objective of the trials at that combination, and how many there are."""

    x: Any = None
    y: Any = None
    objective: float | None = None
    count: int


class InteractionSlice(BaseModel):
    """A 2-D plateau slice over two parameters — a grid of existing trials, no new runs."""

    paramX: str
    paramY: str
    cells: list[SliceCell]


class InsightsResponse(BaseModel):
    """The parameter-effect analytics for one sweep. ``direction`` here is the sweep's OPTIMIZE
    direction (maximize/minimize) — distinct from each importance item's effect ``direction``.
    ``notes`` carries every honest-degradation caveat (too few trials, a non-varying param, a param
    with no local neighbor variation, skipped slices)."""

    sweepJobId: str
    metric: str
    direction: str
    importance: list[ImportanceItem]
    brittleness: list[BrittlenessItem]
    slices: list[InteractionSlice]
    notes: list[str] = []


class InsightsService:
    """Read-only analytics over an existing sweep's trials. Takes the same jobs/trials factories the
    sweep + retro services use (opened per call, closed in ``finally``), so tests drive it with the
    in-memory fakes. Needs no backtest client — every view is computed from the trial params + the
    scalar objective already in ``optimization_trials``."""

    def __init__(
        self, jobs_factory: Callable[[], Any], trials_factory: Callable[[], Any]
    ) -> None:
        self._jobs_factory = jobs_factory
        self._trials_factory = trials_factory

    def insights(self, sweep_id: str) -> InsightsResponse:
        jobs = self._jobs_factory()
        trials = self._trials_factory()
        try:
            job = jobs.get(sweep_id)
            if job is None or job.get("kind") != "OPTIMIZATION":
                raise ApiError(404, "NOT_FOUND_JOB", f"no such sweep: {sweep_id}")
            request = job.get("request") or {}
            parameters = request.get("parameters", [])
            metric, direction = _primary_objective(request.get("objective", {}))
            rows = trials.list_for_sweep(sweep_id, _PROMOTABLE, _TRIAL_CAP, 0)
        finally:
            jobs.close()
            trials.close()

        points = [
            {
                "trialNumber": row["trialNumber"],
                "params": row.get("params") or {},
                "objective": float((row.get("objectiveValues") or {})[metric]),
            }
            for row in rows
            if metric in (row.get("objectiveValues") or {})
        ]

        notes: list[str] = []
        if len(points) < 2:
            notes.append(
                f"insufficient data: {len(points)} completed trial(s) carrying objective "
                f"{metric!r}; parameter analytics need >= 2"
            )
            return InsightsResponse(
                sweepJobId=sweep_id, metric=metric, direction=direction,
                importance=[], brittleness=[], slices=[], notes=notes,
            )

        specs = {p["path"]: p for p in parameters}
        imp_items, imp_notes = _importance(points, specs)
        notes.extend(imp_notes)
        britt_rows, britt_notes = _brittleness(points, specs, imp_items)
        notes.extend(britt_notes)
        slice_rows = _slices(points, imp_items)
        if len(imp_items) < 2:
            notes.append("interaction slices skipped: < 2 parameters with variation")

        return InsightsResponse(
            sweepJobId=sweep_id,
            metric=metric,
            direction=direction,
            importance=[ImportanceItem(**it) for it in imp_items],
            brittleness=[BrittlenessItem(**r) for r in britt_rows],
            slices=[InteractionSlice(**s) for s in slice_rows],
            notes=notes,
        )


# --- Importance: |Spearman| rank correlation of each param vs the objective ----------------------

def _importance(
    points: list[dict[str, Any]], specs: dict[str, dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[str]]:
    """One tornado row per swept parameter that varied. A parameter constant across every trial (or
    present in < 2 trials) is excluded with a note — a rank correlation is undefined without spread.
    Rows sorted by |rho| desc, then param name (stable, deterministic)."""
    notes: list[str] = []
    items: list[dict[str, Any]] = []
    for path, spec in specs.items():
        paired = [
            (_ordinal(spec, p["params"][path]), p["objective"])
            for p in points
            if path in p["params"]
        ]
        if len(paired) < 2 or len({x for x, _ in paired}) < 2:
            notes.append(f"param {path!r} excluded from importance: no variation across trials")
            continue
        rho = _spearman([x for x, _ in paired], [y for _, y in paired])
        items.append({
            "param": path,
            "score": abs(rho),
            "direction": "positive" if rho > 0 else "negative" if rho < 0 else "flat",
        })
    items.sort(key=lambda it: (-it["score"], it["param"]))
    return items, notes


def _ordinal(spec: dict[str, Any], value: Any) -> float:
    """A sortable ordinal for ranking a param value. Categorical (``choices``) params rank by their
    position in the DECLARED choice order (the same order ``leaderboard._within`` uses for
    neighbors) — meaningful only when the choices are ordered; numeric params rank by value."""
    if "choices" in spec:
        choices = list(spec["choices"])
        try:
            return float(choices.index(value))
        except ValueError:
            return float(value)
    return float(value)


def _spearman(xs: list[float], ys: list[float]) -> float:
    """Spearman rho = Pearson correlation of the (tie-averaged) rank vectors."""
    return _pearson(_average_ranks(xs), _average_ranks(ys))


def _average_ranks(values: list[float]) -> list[float]:
    """1-based ranks with ties sharing their average rank (proper Spearman tie handling)."""
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        avg = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[order[k]] = avg
        i = j + 1
    return ranks


def _pearson(xs: list[float], ys: list[float]) -> float:
    """Pearson correlation; 0.0 when either series has no spread (a flat rank vector)."""
    mx = statistics.fmean(xs)
    my = statistics.fmean(ys)
    cov = sum((a - mx) * (b - my) for a, b in zip(xs, ys, strict=True))
    vx = sum((a - mx) ** 2 for a in xs)
    vy = sum((b - my) ** 2 for b in ys)
    if vx == 0 or vy == 0:
        return 0.0
    return cov / math.sqrt(vx * vy)


# --- Brittleness: per-param local objective variance across neighbor pairs × importance ----------

def _brittleness(
    points: list[dict[str, Any]],
    specs: dict[str, dict[str, Any]],
    importance_items: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[str]]:
    """For each VARYING param, the objective's local variance over the neighbor pairs that move it.
    A neighbor pair (from ``leaderboard._is_neighbor`` — the one neighbor definition) differing in
    param p contributes the pair's 2-point sample variance ``(y_a - y_b)^2 / 2``; the param's
    ``localVariance`` is the mean of those. ``brittle`` = high importance AND high local variance,
    via a median split across the params that have a defined local variance."""
    notes: list[str] = []
    imp_by_param = {it["param"]: it["score"] for it in importance_items}
    if not imp_by_param:
        return [], notes

    pairs = [
        (points[i], points[j])
        for i in range(len(points))
        for j in range(i + 1, len(points))
        if leaderboard._is_neighbor(points[i]["params"], points[j]["params"], specs)
    ]

    local_var: dict[str, float | None] = {}
    for param in imp_by_param:
        deltas = [
            (pa["objective"] - pb["objective"]) ** 2 / 2.0
            for pa, pb in pairs
            if param in pa["params"] and param in pb["params"]
            and pa["params"][param] != pb["params"][param]
        ]
        local_var[param] = statistics.fmean(deltas) if deltas else None

    defined = {p: v for p, v in local_var.items() if v is not None}
    brittle: set[str] = set()
    if len(defined) >= 2:
        imp_median = statistics.median([imp_by_param[p] for p in defined])
        lv_median = statistics.median(list(defined.values()))
        brittle = {
            p for p, v in defined.items()
            if imp_by_param[p] >= imp_median and v >= lv_median
        }
    else:
        notes.append(
            "brittleness classification skipped: < 2 params have local neighbor variation"
        )

    missing = [p for p in imp_by_param if local_var[p] is None]
    if missing:
        notes.append(
            "no neighbor pair varies these params — localVariance unavailable: "
            + ", ".join(sorted(missing))
        )

    rows = [
        {
            "param": it["param"],
            "importance": it["score"],
            "localVariance": local_var[it["param"]],
            "brittle": it["param"] in brittle,
        }
        for it in importance_items
    ]
    return rows, notes


# --- Interaction sniff: 2-D plateau slices for the top-3 importance pairs -------------------------

def _slices(
    points: list[dict[str, Any]], importance_items: list[dict[str, Any]], max_pairs: int = 3
) -> list[dict[str, Any]]:
    """The top-``max_pairs`` param pairs by summed importance, each a grid of the existing trials:
    one cell per (x, y) param combination with the median objective + trial count. Existing trials
    only — no new runs. No pairs when < 2 params varied."""
    imp_score = {it["param"]: it["score"] for it in importance_items}
    params = [it["param"] for it in importance_items]
    scored_pairs = sorted(
        (
            (imp_score[params[i]] + imp_score[params[j]], params[i], params[j])
            for i in range(len(params))
            for j in range(i + 1, len(params))
        ),
        key=lambda t: (-t[0], t[1], t[2]),
    )

    slices: list[dict[str, Any]] = []
    for _, px, py in scored_pairs[:max_pairs]:
        cells_map: dict[tuple[Any, Any], list[float]] = {}
        for p in points:
            if px in p["params"] and py in p["params"]:
                cells_map.setdefault(
                    (p["params"][px], p["params"][py]), []
                ).append(p["objective"])
        cells = [
            {"x": kx, "y": ky, "objective": statistics.median(vs), "count": len(vs)}
            for (kx, ky), vs in cells_map.items()
        ]
        cells.sort(key=lambda c: (str(c["x"]), str(c["y"])))
        slices.append({"paramX": px, "paramY": py, "cells": cells})
    return slices


def _service(request: Request) -> InsightsService:
    return request.app.state.insights


@router.get("/insights/{sweep_job_id}", response_model=InsightsResponse)
def insights(sweep_job_id: str, request: Request) -> InsightsResponse:
    """Parameter-effect analytics over an existing sweep — importance tornado, brittleness map, and
    2-D interaction slices (existing trials only). 404 for an unknown / non-OPTIMIZATION job; 200
    with empty arrays + a ``notes`` entry when there is too little data to analyze."""
    return _service(request).insights(sweep_job_id)
