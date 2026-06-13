"""Optuna sampler construction + parameter expansion (§D.7).

The optimizer NEVER evaluates a strategy itself — it only proposes parameter
vectors (D6). This module turns a strategy's ``optimize`` block into an Optuna
study + per-trial suggestions. Phase 33 ships ``grid``/``random``; the
``tpe``/``nsga2`` samplers + fold-fed pruning land in Phase 34 (defaults from the
S3 spike: ``n_startup_trials=5``).
"""

from __future__ import annotations

from typing import Any

import optuna

optuna.logging.set_verbosity(optuna.logging.WARNING)

GRID_CAP = 500  # §D.7: grid product is capped; max_trials truncates further

# S3 spike defaults (docs/design/DECISIONS_LOG.md, 2026-06-13).
TPE_STARTUP_TRIALS = 5


def grid_values(parameter: dict[str, Any]) -> list[Any]:
    """The discrete value set for one parameter — explicit ``choices`` or an
    inclusive ``range: [lo, hi]`` walked by ``step`` (default 1)."""
    if "choices" in parameter:
        return list(parameter["choices"])
    lo, hi = parameter["range"]
    step = parameter.get("step", 1)
    # integer-friendly accumulation; tolerate float steps without drift blow-up
    count = int(round((hi - lo) / step)) + 1
    values: list[Any] = []
    for i in range(count):
        v = lo + i * step
        values.append(int(v) if float(v).is_integer() else round(v, 10))
    return values


def grid_size(parameters: list[dict[str, Any]]) -> int:
    """Product of every parameter's discrete-value count (the full grid)."""
    size = 1
    for parameter in parameters:
        size *= len(grid_values(parameter))
    return size


def planned_trials(parameters: list[dict[str, Any]], method: str, max_trials: int) -> int:
    """How many trials the sweep will run: grid is min(product, cap, max_trials);
    random/tpe/nsga2 run exactly ``max_trials``."""
    if method == "grid":
        return min(grid_size(parameters), GRID_CAP, max_trials)
    return max_trials


def make_sampler(
    method: str, parameters: list[dict[str, Any]], seed: int
) -> optuna.samplers.BaseSampler:
    """The Optuna sampler for a method (seeded → reproducible trial sequence)."""
    if method == "grid":
        space = {p["path"]: grid_values(p) for p in parameters}
        return optuna.samplers.GridSampler(space, seed=seed)
    if method == "random":
        return optuna.samplers.RandomSampler(seed=seed)
    if method == "tpe":
        return optuna.samplers.TPESampler(seed=seed, n_startup_trials=TPE_STARTUP_TRIALS)
    if method == "nsga2":
        return optuna.samplers.NSGAIISampler(seed=seed)
    raise ValueError(f"unsupported optimize method: {method!r}")


def suggest_params(trial: optuna.Trial, parameters: list[dict[str, Any]]) -> dict[str, Any]:
    """Samples one value per parameter, keyed by its ``path`` (the override map)."""
    out: dict[str, Any] = {}
    for parameter in parameters:
        path = parameter["path"]
        if "choices" in parameter:
            out[path] = trial.suggest_categorical(path, list(parameter["choices"]))
        else:
            lo, hi = parameter["range"]
            step = parameter.get("step", 1)
            if isinstance(lo, int) and isinstance(hi, int) and float(step).is_integer():
                out[path] = trial.suggest_int(path, lo, hi, step=int(step))
            else:
                out[path] = trial.suggest_float(path, lo, hi, step=step or None)
    return out
