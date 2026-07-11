"""EVO E2 item 4 — neighbor-probe submission (design §3.2.3 / §11).

Four layers, each deterministic (the drain thread is exercised through in-memory doubles whose
``dispatch`` pre-loads a result, so it always terminates — never a spinning daemon):

* geometry  — ``leaderboard.axis_neighbors`` reuses the canonical ``_is_neighbor`` relation;
* planning  — ``service._plan_probes`` ranks the plateau top-K, dedups, and caps;
* drain     — ``sweep.run_probes`` completes/fails probe rows and reconciles dead jobs;
* end-to-end— ``SweepService.probe`` lifts a trial's MEASURED ``neighborCount`` (the whole point),
              plus the 404 / 409 / actor-stamping / numbering contract and the REST surface;
* durability— the boot reaper for RUNNING rows stranded under a terminal sweep, the
              partial-dispatch rescue drain, and 23505 numbering-race degradation.
"""

from __future__ import annotations

import json
import time
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import api, leaderboard
from app.errors import ApiError, api_error_handler, invalid_path_handler
from app.leaderboard import _is_neighbor
from app.path_grammar import InvalidParameterPath
from app.repos import TrialNumberConflict
from app.service import SweepService, _param_key, _plan_probes
from app.sweep import run_probes
from tests.fakes import FakeJobs, FakeTrials

GRID = [
    {"path": "a", "range": [0, 2], "step": 1},
    {"path": "b", "range": [0, 2], "step": 1},
]
OBJ = {"metric": "sharpe", "direction": "maximize"}


# --------------------------------------------------------------------------------------------------
# geometry: axis_neighbors reuses the plateau relation (never a second definition)
# --------------------------------------------------------------------------------------------------


def _specs(parameters):
    return {p["path"]: p for p in parameters}


def test_axis_neighbors_every_cell_is_a_canonical_neighbor():
    params = {"a": 1, "b": 1}
    cells = leaderboard.axis_neighbors(params, GRID)
    specs = _specs(GRID)
    for cell in cells:
        assert _is_neighbor(params, cell, specs)  # the exact gate plateau_scores uses
        differing = [k for k in params if cell[k] != params[k]]
        assert len(differing) == 1  # a probe moves exactly ONE param one step


def test_axis_neighbors_interior_point_has_all_four_axis_cells():
    cells = leaderboard.axis_neighbors({"a": 1, "b": 1}, GRID)
    got = {(c["a"], c["b"]) for c in cells}
    assert got == {(0, 1), (2, 1), (1, 0), (1, 2)}


def test_axis_neighbors_drops_out_of_range_steps_at_the_edge():
    cells = leaderboard.axis_neighbors({"a": 0, "b": 0}, GRID)
    got = {(c["a"], c["b"]) for c in cells}
    assert got == {(1, 0), (0, 1)}  # a=-1 and b=-1 dropped, not clamped


def test_axis_neighbors_adjacent_choices():
    spec = [{"path": "m", "choices": ["ema", "sma", "wma"]}]
    assert {c["m"] for c in leaderboard.axis_neighbors({"m": "sma"}, spec)} == {"ema", "wma"}
    assert {c["m"] for c in leaderboard.axis_neighbors({"m": "ema"}, spec)} == {"sma"}


def test_axis_neighbors_stepless_range_uses_epsilon_ball():
    spec = [{"path": "x", "range": [0.0, 10.0]}]  # no step -> ε-ball geometry
    cells = leaderboard.axis_neighbors({"x": 5.0}, spec)
    specs = _specs(spec)
    assert len(cells) == 2
    for cell in cells:
        assert _is_neighbor({"x": 5.0}, cell, specs)  # lands strictly inside the ε-ball


# --------------------------------------------------------------------------------------------------
# planning: plateau top-K -> missing neighbors, deduped + capped
# --------------------------------------------------------------------------------------------------


def _row(a, b, *, state="COMPLETE", sharpe=None, number=0):
    return {
        "trialNumber": number,
        "params": {"a": a, "b": b},
        "state": state,
        "objectiveValues": ({"sharpe": sharpe} if sharpe is not None else None),
    }


def test_plan_probes_fills_the_top_trial_missing_neighbors():
    rows = [_row(1, 1, sharpe=1.0, number=0), _row(0, 1, sharpe=0.5, number=1)]
    to_submit, skipped = _plan_probes(rows, GRID, "sharpe", "maximize", top_k=1, max_probes=40)
    got = {(c["a"], c["b"]) for c in to_submit}
    assert got == {(2, 1), (1, 0), (1, 2)}  # (0,1) already exists -> deduped
    assert skipped == 1  # the one already-present neighbor


def test_plan_probes_dedups_pruned_but_retries_failed():
    rows = [
        _row(1, 1, sharpe=1.0, number=0),
        _row(2, 1, state="PRUNED", number=1),
        _row(1, 0, state="FAILED", number=2),
    ]
    to_submit, _ = _plan_probes(rows, GRID, "sharpe", "maximize", top_k=1, max_probes=40)
    got = {(c["a"], c["b"]) for c in to_submit}
    # PRUNED was JUDGED (the pruner saw its folds) -> stays deduped. FAILED was LOST (transient
    # worker error / NaN metric) -> retryable, else one flaky failure blackholes the cell forever.
    assert got == {(0, 1), (1, 2), (1, 0)}


def test_plan_probes_caps_at_max_probes():
    rows = [_row(1, 1, sharpe=1.0, number=0)]
    to_submit, skipped = _plan_probes(rows, GRID, "sharpe", "maximize", top_k=1, max_probes=2)
    assert len(to_submit) == 2
    assert skipped == 2  # 4 axis cells, 2 submitted -> 2 over the cap


def test_plan_probes_empty_when_no_complete_trials():
    rows = [_row(1, 1, state="FAILED", number=0)]
    to_submit, skipped = _plan_probes(rows, GRID, "sharpe", "maximize", top_k=5, max_probes=40)
    assert to_submit == []
    assert skipped == 0


def test_param_key_collapses_int_and_float():
    assert _param_key({"a": 1, "b": 2}) == _param_key({"b": 2.0, "a": 1.0})


# --------------------------------------------------------------------------------------------------
# drain: run_probes resolves probe rows (complete / fail / reconcile dead)
# --------------------------------------------------------------------------------------------------


class QueueDispatcher:
    """``read_results`` drains a pre-loaded queue once; ``dispatch`` records the job id."""

    def __init__(self, results: list[dict[str, Any]] | None = None) -> None:
        self.queue = list(results or [])
        self.dispatched: list[str] = []

    def dispatch(self, trial_job_id: str) -> None:
        self.dispatched.append(str(trial_job_id))

    def read_results(self, max_count, block_ms=2000, sweep_id=None):
        out = self.queue[:max_count]
        self.queue = self.queue[max_count:]
        return out

    def publish_progress(self, job_id: str, payload: str) -> None:
        pass


def _result(job_id: str, metrics: dict[str, Any]) -> dict[str, Any]:
    return {"trialId": job_id, "runId": f"run-{job_id}", "metrics": json.dumps(metrics)}


def test_run_probes_completes_and_fails_rows():
    trials = FakeTrials()
    r1 = trials.insert("s1", 10, {"a": 2, "b": 1})
    r2 = trials.insert("s1", 11, {"a": 1, "b": 0})
    dispatcher = QueueDispatcher(
        [_result("j1", {"sharpe": 0.7}), _result("j2", {})]  # j2 has no sharpe -> NaN -> FAILED
    )
    run_probes(
        sweep_id="s1", pending={"j1": r1, "j2": r2}, metric="sharpe",
        jobs=FakeJobs(), trials=trials, dispatcher=dispatcher,
    )
    assert trials.rows[r1]["state"] == "COMPLETE"
    assert trials.rows[r1]["objectiveValues"] == {"sharpe": 0.7}
    assert trials.rows[r1]["backtestRunId"] == "run-j1"
    assert trials.rows[r2]["state"] == "FAILED"


def test_run_probes_reconciles_a_dead_job():
    trials = FakeTrials()
    r = trials.insert("s1", 10, {"a": 2, "b": 1})
    jobs = FakeJobs()
    jobs.rows["dead"] = {"kind": "TRIAL", "status": "cancelled", "request": {}}
    run_probes(
        sweep_id="s1", pending={"dead": r}, metric="sharpe",
        jobs=jobs, trials=trials, dispatcher=QueueDispatcher([]),
    )
    assert trials.rows[r]["state"] == "FAILED"  # never hangs on a job that will never report


# --------------------------------------------------------------------------------------------------
# end-to-end: SweepService.probe — the MEASURED neighborCount + the 404/409/actor/number contract
# --------------------------------------------------------------------------------------------------


class EchoDispatcher:
    """Every dispatched probe immediately enqueues a COMPLETE result, so the drain thread finishes
    (never spins). A fixed sharpe is enough — the geometry, not the value, lifts neighborCount."""

    def __init__(self, metric: str = "sharpe", value: float = 0.5) -> None:
        self.dispatched: list[str] = []
        self._q: list[dict[str, Any]] = []
        self._metric = metric
        self._value = value

    def dispatch(self, trial_job_id: str) -> None:
        j = str(trial_job_id)
        self.dispatched.append(j)
        self._q.append(_result(j, {self._metric: self._value}))

    def read_results(self, max_count, block_ms=2000, sweep_id=None):
        out = self._q[:max_count]
        self._q = self._q[max_count:]
        return out

    def publish_progress(self, job_id: str, payload: str) -> None:
        pass


def _seed(jobs, trials, complete, *, others=(), status="completed", walk_forward=None):
    """A completed sweep: its OPTIMIZATION row, one child TRIAL job (the probe template), and the
    given COMPLETE (+ other-state) trials. ``complete`` = list of ((a,b), sharpe)."""
    request = {
        "strategyId": "s1", "strategyVersion": "1.0.0", "from": "2026-01-01", "to": "2026-01-10",
        "method": "grid", "parameters": GRID, "objective": OBJ,
    }
    if walk_forward:
        request["walkForward"] = walk_forward
    sweep_id = jobs.insert_sweep(None, request)
    jobs.rows[sweep_id]["status"] = status
    template = {
        "strategyId": "s1", "strategyVersion": "1.0.0", "from": "2026-01-01", "to": "2026-01-10",
        "paramsOverride": {"a": 0, "b": 0}, "foldContext": bool(walk_forward),
    }
    if walk_forward:
        template["walkForward"] = walk_forward
    jobs.insert_trial(sweep_id, None, template)
    n = 0
    for (a, b), sharpe in complete:
        trials.complete(trials.insert(sweep_id, n, {"a": a, "b": b}), {"sharpe": sharpe}, f"r{n}")
        n += 1
    for (a, b), state in others:
        trials.rows[trials.insert(sweep_id, n, {"a": a, "b": b})]["state"] = state
        n += 1
    return sweep_id


def _service(jobs, trials, dispatcher):
    return SweepService(
        strategy_client=None,
        jobs_factory=lambda: jobs,
        trials_factory=lambda: trials,
        dispatcher=dispatcher,
    )


def _wait(cond, timeout=3.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if cond():
            return
        time.sleep(0.01)
    raise AssertionError("probe drain did not resolve in time")


def test_probe_raises_measured_neighbor_count():
    jobs, trials = FakeJobs(), FakeTrials()
    dispatcher = EchoDispatcher()
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0), ((0, 1), 0.5)])
    svc = _service(jobs, trials, dispatcher)

    def _center_nc(sort="plateau"):
        best = svc.best(sweep_id, 10, sort)
        center = next(i for i in best["items"] if i["params"] == {"a": 1, "b": 1})
        return center["neighborCount"]

    assert _center_nc() == 1  # only (0,1) before probing

    receipt = svc.probe(sweep_id, top_k=1, max_probes=40)
    assert receipt["submitted"] == 3  # (2,1),(1,0),(1,2); (0,1) deduped
    _wait(lambda: len(trials.list_for_sweep(sweep_id, "COMPLETE", 1000, 0)) == 5)

    assert _center_nc() >= 4  # neighborCount is now MEASURED, not accidental


def test_probe_receipt_actor_and_numbering():
    jobs, trials = FakeJobs(), FakeTrials()
    dispatcher = EchoDispatcher()
    # existing trial numbers 0 (complete) + others -> max is 1, so probes start at 2.
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0)], others=[((0, 1), "PRUNED")])
    receipt = _service(jobs, trials, dispatcher).probe(sweep_id, top_k=1, max_probes=40)

    numbers = sorted(t["trialNumber"] for t in receipt["trials"])
    assert numbers == [2, 3, 4]  # continues past the highest existing (1), no collision
    for job_id in dispatcher.dispatched:
        assert jobs.rows[job_id]["created_by"] == f"optimizer:{sweep_id}:probe"
        # the probe request reuses the template, swapping only paramsOverride
        assert jobs.rows[job_id]["request"]["strategyVersion"] == "1.0.0"
        assert "paramsOverride" in jobs.rows[job_id]["request"]


def test_probe_skips_when_all_neighbors_present():
    jobs, trials = FakeJobs(), FakeTrials()
    # every axis neighbor of (1,1) already exists -> nothing new to submit
    complete = [((1, 1), 1.0), ((0, 1), 0.5), ((2, 1), 0.5), ((1, 0), 0.5), ((1, 2), 0.5)]
    sweep_id = _seed(jobs, trials, complete)
    receipt = _service(jobs, trials, EchoDispatcher()).probe(sweep_id, top_k=1, max_probes=40)
    assert receipt["submitted"] == 0
    assert receipt["skipped"] == 4


def test_probe_unknown_job_is_404():
    svc = _service(FakeJobs(), FakeTrials(), EchoDispatcher())
    with pytest.raises(ApiError) as exc:
        svc.probe("nope", 5, 40)
    assert exc.value.status == 404


def test_probe_non_optimization_job_is_404():
    jobs, trials = FakeJobs(), FakeTrials()
    trial_job = jobs.insert_trial("sweep-x", None, {"paramsOverride": {}})
    with pytest.raises(ApiError) as exc:
        _service(jobs, trials, EchoDispatcher()).probe(trial_job, 5, 40)
    assert exc.value.status == 404


def test_probe_running_sweep_is_409():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0)], status="running")
    with pytest.raises(ApiError) as exc:
        _service(jobs, trials, EchoDispatcher()).probe(sweep_id, 5, 40)
    assert exc.value.status == 409


# --------------------------------------------------------------------------------------------------
# REST surface
# --------------------------------------------------------------------------------------------------


def _client(jobs, trials, dispatcher):
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(InvalidParameterPath, invalid_path_handler)
    app.state.sweeps = _service(jobs, trials, dispatcher)
    app.include_router(api.router)
    return TestClient(app)


def test_probes_endpoint_returns_receipt():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0), ((0, 1), 0.5)])
    resp = _client(jobs, trials, EchoDispatcher()).post(
        f"/api/v1/optimizations/{sweep_id}/probes", json={"topK": 1, "maxProbes": 40}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["submitted"] == 3
    cells = {(t["params"]["a"], t["params"]["b"]) for t in body["trials"]}
    assert cells == {(2, 1), (1, 0), (1, 2)}
    assert all(t["trialJobId"] for t in body["trials"])


def test_probes_endpoint_body_optional():
    jobs, trials = FakeJobs(), FakeTrials()
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0)])
    resp = _client(jobs, trials, EchoDispatcher()).post(f"/api/v1/optimizations/{sweep_id}/probes")
    assert resp.status_code == 200  # defaults topK=5, maxProbes=40


def test_probes_endpoint_unknown_is_404():
    resp = _client(FakeJobs(), FakeTrials(), EchoDispatcher()).post(
        "/api/v1/optimizations/nope/probes", json={}
    )
    assert resp.status_code == 404
    assert resp.json()["code"] == "NOT_FOUND_JOB"


# --------------------------------------------------------------------------------------------------
# durability: boot reaper for stranded RUNNING rows, partial-dispatch drain, numbering races
# --------------------------------------------------------------------------------------------------


class ExplodingDispatcher(EchoDispatcher):
    """Raises on the Nth dispatch (1-based) — the partial-dispatch failure fixture; earlier
    dispatches still echo results so the rescue drain can finish."""

    def __init__(self, explode_at: int) -> None:
        super().__init__()
        self._explode_at = explode_at

    def dispatch(self, trial_job_id: str) -> None:
        if len(self.dispatched) + 1 == self._explode_at:
            raise RuntimeError("dispatch exploded")
        super().dispatch(trial_job_id)


class RacingTrials(FakeTrials):
    """Simulates the 23505 race ONCE (after ``armed``): the first probe insert finds its number
    just taken by a concurrent POST — a foreign row is recorded at that number, then the conflict
    raised, so the retry's max_trial_number re-read sees the racer's row."""

    def __init__(self) -> None:
        super().__init__()
        self.armed = False
        self._raced = False

    def insert(self, sweep_id: str, trial_number: int, params: dict[str, Any]) -> int:
        if self.armed and not self._raced:
            self._raced = True
            super().insert(sweep_id, trial_number, {"a": -1, "b": -1})  # the racing POST's row
            raise TrialNumberConflict("simulated 23505")
        return super().insert(sweep_id, trial_number, params)


class AlwaysConflictTrials(FakeTrials):
    """Every insert after ``armed`` conflicts — the persistent-collision fixture."""

    def __init__(self) -> None:
        super().__init__()
        self.armed = False

    def insert(self, sweep_id: str, trial_number: int, params: dict[str, Any]) -> int:
        if self.armed:
            raise TrialNumberConflict("simulated persistent 23505")
        return super().insert(sweep_id, trial_number, params)


def test_delete_stranded_running_reaps_only_terminal_sweep_rows():
    jobs = FakeJobs()
    trials = FakeTrials(jobs)
    done = jobs.insert_sweep(None, {})
    jobs.rows[done]["status"] = "completed"
    live = jobs.insert_sweep(None, {})
    jobs.rows[live]["status"] = "running"
    stranded = trials.insert(done, 0, {"a": 1, "b": 1})  # RUNNING under terminal -> reaped
    in_flight = trials.insert(live, 0, {"a": 1, "b": 1})  # RUNNING under live -> kept
    finished = trials.insert(done, 1, {"a": 2, "b": 1})
    trials.complete(finished, {"sharpe": 1.0}, None)  # COMPLETE -> kept
    assert trials.delete_stranded_running() == 1
    assert stranded not in trials.rows
    assert in_flight in trials.rows
    assert finished in trials.rows


def test_boot_reap_frees_a_zombie_cell_for_reprobe():
    jobs = FakeJobs()
    trials = FakeTrials(jobs)
    # every axis neighbor of the (deterministic, stable-sort) top trial (1,1) is COMPLETE except
    # (2,1), which a mid-drain restart left stranded at RUNNING.
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0), ((0, 1), 0.5), ((1, 0), 0.5), ((1, 2), 0.5)])
    trials.insert(sweep_id, 50, {"a": 2, "b": 1})
    svc = _service(jobs, trials, EchoDispatcher())

    receipt = svc.probe(sweep_id, top_k=1, max_probes=40)
    assert receipt["submitted"] == 0  # the zombie RUNNING row blackholes its cell...
    assert receipt["skipped"] == 4

    assert trials.delete_stranded_running() == 1  # ...until the boot reaper deletes it

    receipt2 = svc.probe(sweep_id, top_k=1, max_probes=40)
    assert [t["params"] for t in receipt2["trials"]] == [{"a": 2, "b": 1}]
    _wait(lambda: len(trials.list_for_sweep(sweep_id, "COMPLETE", 1000, 0)) == 5)


def test_probe_mid_loop_exception_still_drains_dispatched():
    jobs, trials = FakeJobs(), FakeTrials()
    dispatcher = ExplodingDispatcher(explode_at=2)
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0), ((0, 1), 0.5)])
    with pytest.raises(RuntimeError):
        _service(jobs, trials, dispatcher).probe(sweep_id, top_k=1, max_probes=40)
    # the probe dispatched BEFORE the explosion is still drained to COMPLETE (2 seed + 1 probe) —
    # without the rescue drain its row would sit RUNNING forever with the result destroyed unread.
    _wait(lambda: len(trials.list_for_sweep(sweep_id, "COMPLETE", 1000, 0)) == 3)
    # the exploding cell's ledger row is stranded RUNNING — exactly what the boot reaper heals.
    running = [r for r in trials.rows.values() if r["state"] == "RUNNING"]
    assert len(running) == 1


def test_probe_numbering_race_degrades_to_retry_not_500():
    jobs = FakeJobs()
    trials = RacingTrials()
    dispatcher = EchoDispatcher()
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0), ((0, 1), 0.5)])
    trials.armed = True
    receipt = _service(jobs, trials, dispatcher).probe(sweep_id, top_k=1, max_probes=40)
    assert receipt["submitted"] == 3  # the race cost one number slot, never a cell or a 500
    assert sorted(t["trialNumber"] for t in receipt["trials"]) == [3, 4, 5]  # racer took slot 2
    _wait(lambda: len(trials.list_for_sweep(sweep_id, "COMPLETE", 1000, 0)) == 5)


def test_probe_persistent_collision_skips_cells_not_500():
    jobs = FakeJobs()
    trials = AlwaysConflictTrials()
    sweep_id = _seed(jobs, trials, [((1, 1), 1.0), ((0, 1), 0.5)])
    trials.armed = True
    receipt = _service(jobs, trials, EchoDispatcher()).probe(sweep_id, top_k=1, max_probes=40)
    assert receipt["submitted"] == 0
    assert receipt["skipped"] == 4  # 1 deduped + 3 collision-skipped
    assert receipt["trials"] == []
