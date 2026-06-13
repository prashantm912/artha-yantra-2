"""§D.7 Phase 34 samplers: TPE runs with constant-liar for the parallel pool; NSGA-II is
multi-objective and returns a Pareto front (never collapsed to one winner)."""

import json

import optuna

from app import optuna_runner, sweep
from tests.fakes import FakeJobs, FakeTrials

PARAMS = [{"path": "indicators[0].params.period", "range": [5, 12], "step": 1}]
REQUEST_BASE = {"strategyId": "s1", "strategyVersion": "1.0.0", "from": "a", "to": "b"}


class FrontDispatcher:
    """Emits cagr AND maxDrawdown both rising with period → every point is non-dominated, so the
    Pareto front spans the sampled trials."""

    def __init__(self, jobs: FakeJobs) -> None:
        self._jobs = jobs
        self._queue: list[dict] = []

    def dispatch(self, trial_job_id: str) -> None:
        request = self._jobs.get(str(trial_job_id))["request"]
        period = request["paramsOverride"]["indicators[0].params.period"]
        self._queue.append({
            "trialId": str(trial_job_id),
            "runId": f"run-{trial_job_id}",
            "metrics": json.dumps({"cagr": period / 10.0, "maxDrawdown": period / 20.0}),
        })

    def read_results(self, max_count: int, block_ms: int = 2000) -> list[dict]:
        out = self._queue[:max_count]
        self._queue = self._queue[max_count:]
        return out

    def publish_progress(self, *_a) -> None:
        pass


def test_make_sampler_tpe_is_constant_liar():
    sampler = optuna_runner.make_sampler("tpe", PARAMS, seed=1)
    assert isinstance(sampler, optuna.samplers.TPESampler)
    assert sampler._constant_liar is True  # noqa: SLF001 - sampler config assertion


def test_make_sampler_nsga2():
    assert isinstance(
        optuna_runner.make_sampler("nsga2", PARAMS, 1), optuna.samplers.NSGAIISampler
    )


def test_nsga2_returns_a_pareto_front():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = jobs.insert_sweep(None, {})
    study = sweep.run_sweep(
        sweep_id=sweep_id,
        strategy_version_id=None,
        parameters=PARAMS,
        method="nsga2",
        max_trials=8,
        objective={},  # default return↑/drawdown↓ objectives
        seed=3,
        walk_forward=None,
        request_base=REQUEST_BASE,
        jobs=jobs,
        trials=trials,
        dispatcher=FrontDispatcher(jobs),
        parallelism=4,
    )
    # multi-objective: a front, not a single collapsed winner
    assert len(study.best_trials) >= 2
    rows = trials.list_for_sweep(sweep_id, "COMPLETE", 100, 0)
    assert rows
    assert all(set(r["objectiveValues"]) == {"cagr", "maxDrawdown"} for r in rows)
