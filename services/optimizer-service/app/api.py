"""The §D.5 optimizer REST surface under ``/api/v1/optimizations``."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request

router = APIRouter(prefix="/api/v1/optimizations")


def _service(request: Request) -> Any:
    return request.app.state.sweeps


@router.post("/run", status_code=202)
def run(body: dict[str, Any], request: Request) -> dict[str, Any]:
    """Submit a sweep → 202 {jobId, status:"queued"}."""
    job_id = _service(request).submit(body)
    return {"jobId": job_id, "status": "queued"}


@router.get("/jobs/{job_id}")
def job(job_id: str, request: Request) -> dict[str, Any]:
    """Sweep status / progress / trials completed."""
    return _service(request).job_status(job_id)


@router.delete("/jobs/{job_id}", status_code=202)
def cancel(job_id: str, request: Request) -> dict[str, Any]:
    """Cancel a sweep (queued trials dropped; running finish at a checkpoint)."""
    _service(request).cancel(job_id)
    return {"status": "cancelling"}


@router.get("/{sweep_id}/trials")
def trials(
    sweep_id: str,
    request: Request,
    state: str | None = None,
    limit: int = 100,
    offset: int = 0,
) -> dict[str, Any]:
    """Paged trial leaderboard rows for a sweep."""
    bounded_limit = min(max(limit, 1), 1000)
    return _service(request).trials(sweep_id, state, bounded_limit, max(offset, 0))


@router.get("/{sweep_id}/best")
def best(
    sweep_id: str,
    request: Request,
    top: int = 10,
    sort: str = "plateau",
) -> dict[str, Any]:
    """Top-N leaderboard; default sort is plateau-adjusted, ``sort=raw`` for raw objective."""
    return _service(request).best(sweep_id, min(max(top, 1), 100), sort)


@router.get("/{sweep_id}/trials/{trial_id}/folds")
def trial_folds(sweep_id: str, trial_id: int, request: Request) -> Any:
    """The per-fold metric array for one trial (resolved via its ``backtest_run_id``)."""
    return _service(request).trial_folds(sweep_id, trial_id)


@router.post("/{sweep_id}/promote", status_code=201)
def promote(sweep_id: str, body: dict[str, Any], request: Request) -> dict[str, Any]:
    """Promote a COMPLETE trial's params to a new draft version (§D.9); 409 if not promotable."""
    return _service(request).promote(sweep_id, int(body["trialId"]), body.get("notes"))
