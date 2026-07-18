"""AY-OPT-02 fold-fed pruning honesty. A walk-forward trial reports its per-fold OOS objectives
(the DECLARED objective, no longer a fixed Sharpe) into the study's MedianPruner so its running
median informs FUTURE trials — but a trial whose backtest ran to completion is recorded COMPLETE and
NEVER relabelled PRUNED post-hoc (a completed trial's terminal state is completed)."""

import json

import optuna

from app import optuna_runner, sweep
from tests.fakes import FakeTrials


def test_make_pruner_uses_s3_defaults():
    pruner = optuna_runner.make_pruner()
    assert isinstance(pruner, optuna.pruners.MedianPruner)
    assert (
        optuna_runner.PRUNER_STARTUP_TRIALS,
        optuna_runner.PRUNER_WARMUP_FOLDS,
        optuna_runner.PRUNER_MIN_TRIALS,
    ) == (5, 3, 2)


def _seeded_study() -> optuna.Study:
    """A study with three strong completed trials reporting per-fold OOS, and an eager pruner so a
    below-median trial WOULD prune under the old post-hoc path — the regression fixture."""
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


def test_report_fold_objectives_feeds_the_pruner_median():
    # The per-fold OOS values are reported into the study so a FUTURE trial's should_prune() sees
    # them — the mechanism is fed the DECLARED objective (AY-OPT-02), no longer a fixed Sharpe.
    study = _seeded_study()
    trial = study.ask()
    sweep.report_fold_objectives(trial, {"oosFoldObjectives": json.dumps(["0.0", "0.0", "0.0"])})
    study.tell(trial, 0.0)
    assert study.trials[-1].intermediate_values == {0: 0.0, 1: 0.0, 2: 0.0}


def test_full_window_trial_reports_nothing():
    study = _seeded_study()
    trial = study.ask()
    sweep.report_fold_objectives(trial, {})
    sweep.report_fold_objectives(trial, {"oosFoldObjectives": "[]"})
    sweep.report_fold_objectives(trial, {"oosFoldObjectives": "not-json"})
    study.tell(trial, 0.0)
    assert study.trials[-1].intermediate_values == {}


def test_completed_trial_is_never_relabelled_pruned():
    # A trial whose per-fold OOS sits FAR below the seeded median (0.0 vs a 1.0 median) WOULD have
    # been relabelled PRUNED under the old post-hoc path. Its backtest still ran to completion, so
    # it is now recorded COMPLETE — a completed trial's terminal state is completed (AY-OPT-02).
    study = _seeded_study()
    trials = FakeTrials()
    row_id = trials.insert("s1", 99, {"p": 1})
    trial = study.ask()
    result = {
        "runId": "run-99",
        "metrics": json.dumps({"sharpe": 0.0}),
        "oosFoldObjectives": json.dumps(["0.0", "0.0", "0.0"]),  # below median → pruned pre-fix
    }
    value = sweep._resolve(  # noqa: SLF001 - exercising the single-objective resolve directly
        study, trial, row_id, result, multi=False, objs=None, metric="sharpe", trials=trials,
    )
    assert value == 0.0
    assert trials.rows[row_id]["state"] == "COMPLETE"  # completed, never relabelled PRUNED
    states = {t.state for t in study.get_trials(deepcopy=False)}
    assert optuna.trial.TrialState.PRUNED not in states
