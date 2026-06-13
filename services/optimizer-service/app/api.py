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
