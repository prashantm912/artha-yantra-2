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
