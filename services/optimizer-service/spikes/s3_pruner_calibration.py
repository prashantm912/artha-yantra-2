"""S3 spike — optimizer pruner calibration on noisy objectives (§D.13).

The fold-fed ``MedianPruner`` design (guard 2) is fixed; the open question is
*calibration*: what ``n_startup_trials`` + warm-up-fold counts avoid (a) pruning
on noise — Sharpe estimated from few trades is high-variance — and (b)
early-regime selection bias, where oldest-first walk-forward folds make a trial
look good (or bad) before later regimes are seen. And do TPE / NSGA-II still
converge under those settings?

This is a PURE-PYTHON study (no backtest-service dependency): a synthetic
strategy with a known optimum, walk-forward folds whose OOS objective carries a
per-fold regime drift plus trade-count-dependent noise. We sweep the pruner knobs
and measure false-prune rate of near-optimal trials vs true-prune rate of poor
trials, then check TPE/NSGA-II convergence under the chosen settings.

Run: ``python services/optimizer-service/spikes/s3_pruner_calibration.py``
Deterministic (seeded); prints a summary + the recommended defaults that the
Phase-34 pruner is configured with (recorded in docs/design/DECISIONS_LOG.md).
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass

import numpy as np
import optuna

optuna.logging.set_verbosity(optuna.logging.WARNING)

# ---- synthetic strategy -----------------------------------------------------

N_FOLDS = 6  # walk-forward OOS folds per trial
OPT_X, OPT_Y = 0.70, 0.30  # known optimum in the [0,1]^2 parameter space

# per-fold regime drift: fold 0 (oldest) is a benign trending regime that
# flatters every trial; the middle folds are turbulent (depress Sharpe). This is
# the early-regime-bias trap the warm-up must survive.
REGIME_DRIFT = np.array([0.45, 0.10, -0.35, -0.20, 0.05, 0.15])


def true_score(x: float, y: float) -> float:
    """Noise-free skill of a parameter vector (max 0 at the optimum)."""
    return -((x - OPT_X) ** 2 + (y - OPT_Y) ** 2) * 6.0


def fold_oos(x: float, y: float, fold: int, trades: int, rng: np.random.Generator) -> float:
    """One fold's noisy OOS Sharpe: skill + regime drift + trade-count noise.

    Fewer trades ⇒ noisier Sharpe (the prune-on-noise hazard). ~1/sqrt(trades).
    """
    noise_sd = 0.9 / math.sqrt(max(trades, 1))
    return true_score(x, y) + REGIME_DRIFT[fold] + rng.normal(0.0, noise_sd)


# ---- pruner calibration -----------------------------------------------------


@dataclass
class CalibrationResult:
    n_startup: int
    n_warmup: int
    false_prune_rate: float  # near-optimal trials wrongly pruned
    true_prune_rate: float  # poor trials correctly pruned
    score: float  # true_prune_rate - false_prune_rate (higher is better)


def calibrate(n_startup: int, n_warmup: int, seed: int = 7) -> CalibrationResult:
    """Replays a fixed cohort of trials through a MedianPruner at the given knobs
    and measures how it treats near-optimal vs poor trials."""
    rng = np.random.default_rng(seed)
    pruner = optuna.pruners.MedianPruner(
        n_startup_trials=n_startup, n_warmup_steps=n_warmup, n_min_trials=2
    )
    study = optuna.create_study(direction="maximize", pruner=pruner)

    # a cohort: a third are near-optimal, the rest spread across the space.
    near_opt, poor = [], []
    n_trials = 60
    for i in range(n_trials):
        if i % 3 == 0:
            x = OPT_X + rng.normal(0, 0.04)
            y = OPT_Y + rng.normal(0, 0.04)
            is_near = True
        else:
            x, y = rng.uniform(0, 1), rng.uniform(0, 1)
            is_near = true_score(x, y) > -0.5
        trades = int(rng.integers(8, 45))  # under-traded folds are noisier

        trial = study.ask()
        trial.suggest_float("x", 0.0, 1.0)  # registered for the sampler's bookkeeping
        trial.suggest_float("y", 0.0, 1.0)
        pruned = False
        running = []
        for fold in range(N_FOLDS):
            running.append(fold_oos(x, y, fold, trades, rng))
            trial.report(float(np.mean(running)), step=fold)
            if trial.should_prune():
                pruned = True
                study.tell(trial, state=optuna.trial.TrialState.PRUNED)
                break
        if not pruned:
            study.tell(trial, float(np.mean(running)))

        (near_opt if is_near else poor).append(pruned)

    false_prune = float(np.mean(near_opt)) if near_opt else 0.0
    true_prune = float(np.mean(poor)) if poor else 0.0
    return CalibrationResult(
        n_startup, n_warmup, false_prune, true_prune, true_prune - false_prune
    )


# ---- sampler convergence ----------------------------------------------------


def converge_single(sampler_name: str, max_trials: int, seed: int = 11) -> float:
    """Best |found - optimum| distance for a single-objective sampler."""
    rng = np.random.default_rng(seed)
    sampler = {
        "grid": optuna.samplers.GridSampler(
            {"x": list(np.linspace(0, 1, 12)), "y": list(np.linspace(0, 1, 12))}, seed=seed
        ),
        "random": optuna.samplers.RandomSampler(seed=seed),
        "tpe": optuna.samplers.TPESampler(seed=seed, n_startup_trials=10),
    }[sampler_name]
    study = optuna.create_study(direction="maximize", sampler=sampler)

    def objective(trial: optuna.Trial) -> float:
        x = trial.suggest_float("x", 0.0, 1.0)
        y = trial.suggest_float("y", 0.0, 1.0)
        trades = int(rng.integers(20, 45))
        return float(np.mean([fold_oos(x, y, f, trades, rng) for f in range(N_FOLDS)]))

    study.optimize(objective, n_trials=max_trials)
    bx, by = study.best_params["x"], study.best_params["y"]
    return math.hypot(bx - OPT_X, by - OPT_Y)


def converge_nsga2(max_trials: int, seed: int = 13) -> int:
    """NSGA-II returns a Pareto front (return ↑ vs drawdown ↓); report its size."""
    rng = np.random.default_rng(seed)
    study = optuna.create_study(
        directions=["maximize", "minimize"],
        sampler=optuna.samplers.NSGAIISampler(seed=seed),
    )

    def objective(trial: optuna.Trial):
        x = trial.suggest_float("x", 0.0, 1.0)
        y = trial.suggest_float("y", 0.0, 1.0)
        ret = float(np.mean([fold_oos(x, y, f, 30, rng) for f in range(N_FOLDS)]))
        drawdown = (x - OPT_X) ** 2 + (y - OPT_Y) ** 2  # proxy: worse params draw down more
        return ret, drawdown

    study.optimize(objective, n_trials=max_trials)
    return len(study.best_trials)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    log = logging.getLogger("s3")

    log.info("== MedianPruner calibration (false-prune of near-optimal vs true-prune of poor) ==")
    grid = []
    for n_startup in (5, 10, 15, 20):
        for n_warmup in (1, 2, 3):
            grid.append(calibrate(n_startup, n_warmup))
    grid.sort(key=lambda r: r.score, reverse=True)
    for r in grid:
        log.info(
            "  n_startup=%2d n_warmup=%d  false_prune=%.2f  true_prune=%.2f  score=%.2f",
            r.n_startup, r.n_warmup, r.false_prune_rate, r.true_prune_rate, r.score,
        )
    best = grid[0]
    # prefer the smallest knobs whose false-prune stays low and whose score is
    # within 0.05 of the best — cheaper warm-up that still protects the optimum.
    safe = [
        r
        for r in grid
        if r.false_prune_rate <= 0.10 and r.score >= best.score - 0.05
    ]
    chosen = min(safe, key=lambda r: (r.n_startup, r.n_warmup)) if safe else best

    log.info("\n== sampler convergence (|best - known optimum|; lower is better) ==")
    for name in ("grid", "random", "tpe"):
        log.info("  %-7s @150 trials: dist=%.4f", name, converge_single(name, 150))
    front = converge_nsga2(200)
    log.info("  nsga2   @200 trials: pareto_front_size=%d", front)

    log.info("\n== RECOMMENDED PRUNER DEFAULTS (Phase 34) ==")
    log.info("  n_startup_trials = %d", chosen.n_startup)
    log.info("  n_warmup_folds   = %d", chosen.n_warmup)
    log.info("  n_min_trials     = 2")
    log.info("  default max_trials = 150 (tpe) / 200 (nsga2)")
    log.info(
        "  rationale: false_prune=%.2f true_prune=%.2f at the chosen knobs",
        chosen.false_prune_rate, chosen.true_prune_rate,
    )


if __name__ == "__main__":
    main()
