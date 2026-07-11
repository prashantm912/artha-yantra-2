"""Sweep durability + native listing (audit P2-1 / F4). Optimizer sweeps run on in-process
daemon threads, so a service restart abandons them. Two behaviours are asserted here:

  1. The boot orphan-reaper (JobsRepo.fail_orphaned_sweeps, invoked from build_app at startup)
     marks stranded OPTIMIZATION rows failed AND populates ``error`` with a real reason — a
     restart-killed sweep is no longer indistinguishable from a genuine failure (error was NULL).
  2. Sweeps are natively listable via ``GET /optimizations/jobs`` (previously 404).

The reaper/listing SQL lives in the coverage-omitted thin repo adapter; FakeJobs mirrors its
semantics (as it already mirrors the terminal-status guard), so the observable behaviour is
tested DB-free — the pattern the rest of this suite uses.
"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import api
from app.errors import ApiError, api_error_handler, invalid_path_handler
from app.path_grammar import InvalidParameterPath
from app.repos import ORPHAN_SWEEP_ERROR
from app.service import SweepService
from tests.fakes import FakeDispatcher, FakeJobs, FakeStrategy, FakeTrials

_REQUEST = {"strategyId": "s1", "method": "grid", "from": "2026-01-01", "to": "2026-02-01",
            "objective": {"metric": "sharpe", "direction": "maximize"}}


def _service(jobs: FakeJobs) -> SweepService:
    return SweepService(
        strategy_client=FakeStrategy({}),
        jobs_factory=lambda: jobs,
        trials_factory=lambda: FakeTrials(),
        dispatcher=FakeDispatcher(jobs),
        runner=lambda **kwargs: None,
    )


def _client(jobs: FakeJobs) -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(InvalidParameterPath, invalid_path_handler)
    app.state.sweeps = _service(jobs)
    app.include_router(api.router)
    return TestClient(app)


# --- orphan reaper -----------------------------------------------------------------------------


def test_reaper_marks_running_sweep_failed_with_error_text() -> None:
    jobs = FakeJobs()
    sweep_id = jobs.insert_sweep(None, _REQUEST)
    jobs.set_status(sweep_id, "running", 40)

    count = jobs.fail_orphaned_sweeps()

    assert count == 1
    row = jobs.get(sweep_id)
    assert row["status"] == "failed"
    assert row["error"] == ORPHAN_SWEEP_ERROR
    assert "restart" in row["error"]  # loud, not NULL — distinguishable from a real failure


def test_reaper_also_reaps_a_never_started_queued_sweep() -> None:
    jobs = FakeJobs()
    sweep_id = jobs.insert_sweep(None, _REQUEST)  # left 'queued' (thread never ran)

    assert jobs.fail_orphaned_sweeps() == 1
    assert jobs.get(sweep_id)["status"] == "failed"


def test_reaper_leaves_terminal_and_trial_rows_untouched() -> None:
    """Normal lifecycle unaffected: a completed sweep keeps its status/NULL error, and an
    in-flight TRIAL row (kind != OPTIMIZATION) is never touched by the sweep reaper."""
    jobs = FakeJobs()
    done = jobs.insert_sweep(None, _REQUEST)
    jobs.set_status(done, "completed", 100)
    trial = jobs.insert_trial(done, None, {"paramsOverride": {}})  # queued TRIAL

    count = jobs.fail_orphaned_sweeps()

    assert count == 0
    assert jobs.get(done)["status"] == "completed"
    assert jobs.get(done)["error"] is None
    assert jobs.get(trial)["status"] == "queued"  # TRIAL untouched


# --- native listing ----------------------------------------------------------------------------


def test_list_sweeps_newest_first_projects_summary_and_surfaces_error() -> None:
    jobs = FakeJobs()
    first = jobs.insert_sweep(None, _REQUEST)
    second = jobs.insert_sweep(None, {**_REQUEST, "method": "tpe"})
    jobs.set_status(first, "running", 10)
    jobs.fail_orphaned_sweeps()  # first + second both interrupted

    result = _service(jobs).list_sweeps(50, 0)

    items = result["items"]
    assert [i["jobId"] for i in items] == [second, first]  # newest first
    assert result["limit"] == 50 and result["offset"] == 0
    top = items[0]
    assert top["strategyId"] == "s1"
    assert top["method"] == "tpe"
    assert top["objective"] == {"metric": "sharpe", "direction": "maximize"}
    assert top["status"] == "failed"
    assert top["error"] == ORPHAN_SWEEP_ERROR  # the interruption reason is visible in the list


def test_list_sweeps_paginates() -> None:
    jobs = FakeJobs()
    for _ in range(3):
        jobs.insert_sweep(None, _REQUEST)

    page = _service(jobs).list_sweeps(2, 0)
    assert len(page["items"]) == 2

    tail = _service(jobs).list_sweeps(2, 2)
    assert len(tail["items"]) == 1


def test_get_jobs_endpoint_lists_sweeps() -> None:
    jobs = FakeJobs()
    sweep_id = jobs.insert_sweep(None, _REQUEST)
    jobs.set_status(sweep_id, "running", 25)

    resp = _client(jobs).get("/api/v1/optimizations/jobs")

    assert resp.status_code == 200
    body = resp.json()
    assert body["limit"] == 50 and body["offset"] == 0
    assert len(body["items"]) == 1
    assert body["items"][0]["jobId"] == sweep_id
    assert body["items"][0]["status"] == "running"


def test_get_jobs_endpoint_bounds_limit() -> None:
    resp = _client(FakeJobs()).get("/api/v1/optimizations/jobs?limit=9999&offset=-5")
    assert resp.status_code == 200
    assert resp.json()["limit"] == 200  # clamped to the max
    assert resp.json()["offset"] == 0  # negative offset floored
