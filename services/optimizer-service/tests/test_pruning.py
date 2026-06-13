"""CD-12 fold-fed pruning: the MedianPruner uses the S3-calibrated defaults and prunes a trial
whose OOS fold objectives sit below the median of prior trials — fed by OOS folds only, never
train/OOS divergence (the rejected S1B design)."""

import json

import optuna

from app import optuna_runner, sweep


def test_make_pruner_uses_s3_defaults():
    pruner = optuna_runner.make_pruner()
    assert isinstance(pruner, optuna.pruners.MedianPruner)
    assert (
        optuna_runner.PRUNER_STARTUP_TRIALS,
        optuna_runner.PRUNER_WARMUP_FOLDS,
        optuna_runner.PRUNER_MIN_TRIALS,
    ) == (5, 3, 2)


def _seeded_study() -> optuna.Study:
    """A study with three strong completed trials reporting per-fold OOS, and an eager pruner so
    the decision table is deterministic."""
    study = optuna.create_study(
        direction="maximize",
        pruner=optuna.pruners.MedianPruner(n_startup_trials=2, n_warmup_steps=0, n_min_trials=1),
    )
    for value in (1.0, 1.1, 0.9):
        trial = study.ask()
        for step in range(3):
            trial.report(value, step)
        study.tell(trial, value)
    return study


def test_below_median_oos_folds_are_pruned():
    study = _seeded_study()
    result = {"oosFoldObjectives": json.dumps(["0.0", "0.0", "0.0"])}
    assert sweep.should_prune_trial(study.ask(), result) is True


def test_strong_oos_folds_survive():
    study = _seeded_study()
    result = {"oosFoldObjectives": json.dumps(["1.2", "1.2", "1.2"])}
    assert sweep.should_prune_trial(study.ask(), result) is False


def test_full_window_trial_is_never_pruned():
    study = _seeded_study()
    assert sweep.should_prune_trial(study.ask(), {}) is False
    assert sweep.should_prune_trial(study.ask(), {"oosFoldObjectives": "[]"}) is False
