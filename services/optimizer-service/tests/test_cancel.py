"""Sweep-cancellation lifecycle (audit P1-10b): the loop polls ``cancelled`` every iteration —
even when NO results arrive — and an observed cancel exits without writing a terminal status,
so the 'cancelled' the cancel API wrote is never clobbered by 'completed'/'failed'."""

from __future__ import annotations

from typing import Any

from app import sweep
from tests.fakes import FakeJobs, FakeTrials

PARAMETERS = [
    {"path": "indicators[0].params.period", "type": "int", "range": [10, 20], "step": 1}
]


class NeverRespondingDispatcher:
    """A worker outage: trials dispatch but no result ever comes back."""

    def __init__(self) -> None:
        self.dispatched: list[str] = []

    def dispatch(self, trial_job_id: str) -> None:
        self.dispatched.append(str(trial_job_id))

    def read_results(
        self, max_count: int, block_ms: int = 2000, sweep_id: str | None = None
    ) -> list[dict[str, Any]]:
        return []

    def publish_progress(self, job_id: str, payload: str) -> None:
        pass


def test_cancel_exits_the_no_result_loop_and_keeps_the_cancelled_status() -> None:
    jobs = FakeJobs()
    trials = FakeTrials()
    sweep_id = jobs.insert_sweep(None, {})
    jobs.set_status(sweep_id, "running", 0)

    polls = {"n": 0}

    def cancelled() -> bool:
        # First iteration runs; the owner cancels before the second.
        polls["n"] += 1
        if polls["n"] >= 2:
            jobs.set_status(sweep_id, "cancelled")
            return True
        return False

    sweep.run_sweep(
        sweep_id=sweep_id,
        strategy_version_id=None,
        parameters=PARAMETERS,
        method="grid",
        max_trials=50,
        objective={"metric": "sharpe", "direction": "maximize"},
        seed=1,
        walk_forward=None,
        request_base={"strategyId": "s", "from": "2026-01-01", "to": "2026-02-01"},
        jobs=jobs,
        trials=trials,
        dispatcher=NeverRespondingDispatcher(),
        cancelled=cancelled,
    )

    # The loop exited (no hang) and 'cancelled' survived — run_sweep wrote no terminal status.
    assert jobs.get(sweep_id)["status"] == "cancelled"


def test_terminal_status_is_never_overwritten() -> None:
    jobs = FakeJobs()
    sweep_id = jobs.insert_sweep(None, {})
    jobs.set_status(sweep_id, "cancelled")
    jobs.set_status(sweep_id, "failed")  # the blanket handler's old clobber path
    jobs.set_status(sweep_id, "completed", 100)  # run_sweep's old final write
    assert jobs.get(sweep_id)["status"] == "cancelled"


def test_uncancelled_sweep_still_completes(monkeypatch) -> None:
    from tests.fakes import FakeDispatcher

    jobs = FakeJobs()
    trials = FakeTrials()
    sweep_id = jobs.insert_sweep(None, {})
    dispatcher = FakeDispatcher(jobs)

    sweep.run_sweep(
        sweep_id=sweep_id,
        strategy_version_id=None,
        parameters=PARAMETERS,
        method="grid",
        max_trials=50,
        objective={"metric": "sharpe", "direction": "maximize"},
        seed=1,
        walk_forward=None,
        request_base={"strategyId": "s", "from": "2026-01-01", "to": "2026-02-01"},
        jobs=jobs,
        trials=trials,
        dispatcher=dispatcher,
        cancelled=lambda: False,
    )

    assert jobs.get(sweep_id)["status"] == "completed"
    states = {r["state"] for r in trials.rows.values()}
    assert states == {"COMPLETE"}
