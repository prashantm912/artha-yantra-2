"""Loads the shared trial-metrics catalog — the single source of truth for the rankable metric
names/directions the backtest TRIAL worker emits into each trial's ``metrics`` JSON.

The catalog lives at ``contracts/metrics/trial-metrics-catalog.json`` (repo root in dev/CI; copied
under the image WORKDIR by the Dockerfile). Both languages consume THIS file so the optimizer's
objective allow-list and the Java emitter can never drift silently (remaining-items §9 candidate
03): the Java side pins it with a consistency test, this side derives ``_ALLOWED_OBJECTIVE_METRICS``
from ``objective_metric_names()``. It is resolved by walking up from this module to the first
ancestor holding the file, so the same code works from the repo checkout and the container image."""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

_CATALOG_RELPATH = ("contracts", "metrics", "trial-metrics-catalog.json")


def _locate() -> Path:
    """The catalog file on the nearest ancestor of this module (repo root, or image WORKDIR)."""
    for parent in Path(__file__).resolve().parents:
        candidate = parent.joinpath(*_CATALOG_RELPATH)
        if candidate.is_file():
            return candidate
    rel = "/".join(_CATALOG_RELPATH)
    raise FileNotFoundError(f"trial-metrics catalog {rel} not found on any ancestor of {__file__}")


@lru_cache(maxsize=1)
def _catalog() -> dict[str, Any]:
    return json.loads(_locate().read_text(encoding="utf-8"))


def metrics() -> list[dict[str, Any]]:
    """Every catalog metric row (``key``/``label``/``unit``/``direction``/``source``)."""
    return list(_catalog()["metrics"])


@lru_cache(maxsize=1)
def objective_metric_names() -> frozenset[str]:
    """The rankable metric keys an optimization objective may name — the derived allow-list."""
    return frozenset(row["key"] for row in _catalog()["metrics"])


def direction(key: str) -> str | None:
    """The natural ranking direction for a metric (``higher_is_better`` / ``lower_is_better`` /
    ``neutral``), or ``None`` for an unknown key. Metadata for consumers; the sweep still takes the
    objective direction from the request."""
    for row in _catalog()["metrics"]:
        if row["key"] == key:
            return row.get("direction")
    return None


def canonical_direction(key: str | None) -> str:
    """The optimizer ranking direction (``maximize`` / ``minimize``) for a metric, derived from the
    catalog's ``lower_is_better`` / ``higher_is_better`` metadata — one source of truth, not a
    hand-maintained list. An unknown / ``neutral`` metric → ``maximize`` (run_sweep's default). The
    LAST fallback when neither the request nor the YAML gives the fold objective a direction
    (AY-OPT-02: a mean-of-a-minimize-metric like maxDrawdown must be MINIMIZED, not maximized)."""
    return "minimize" if direction(key) == "lower_is_better" else "maximize"
