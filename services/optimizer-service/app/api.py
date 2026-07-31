"""The §D.5 optimizer REST surface under ``/api/v1/optimizations``."""

from __future__ import annotations

from typing import Any
from uuid import UUID

from fastapi import APIRouter, Request
from pydantic import BaseModel

router = APIRouter(prefix="/api/v1/optimizations")


class PromoteRequest(BaseModel):
    """Which COMPLETE trial to materialize as a new draft, plus the optional human note."""

    trialId: int
    notes: str | None = None


class ProbeRequest(BaseModel):
    """Neighbor-probe options: how many plateau leaders to fill around, and the batch ceiling."""

    topK: int = 5
    maxProbes: int = 40


class ProbeTrial(BaseModel):
    trialNumber: int
    params: dict[str, Any]
    trialJobId: str


class ProbeReceipt(BaseModel):
    """Receipt for a neighbor-probe submission (§3.2.3): probe trials dispatched as ordinary trials
    of the sweep, plus how many candidate cells were skipped (already evaluated or over the cap)."""

    submitted: int
    skipped: int
    trials: list[ProbeTrial]


def _service(request: Request) -> Any:
    return request.app.state.sweeps


@router.post(
    "/run",
    status_code=202,
    # §D STAGE_D line 179 documents 400 for this route and test_missing_field_is_400 pins it, but
    # the hand-rolled body validation lives in the service so springdoc-style inference can't see
    # it — declare it so the published surface matches the shipped behaviour.
    responses={400: {"description": "VALIDATION_FAILED / INVALID_PARAMETER_PATH"}},
)
def run(body: dict[str, Any], request: Request) -> dict[str, Any]:
    """Submit a sweep → 202 {jobId, status:"queued"}; 400 on a bad/missing field or a
    closed-grammar parameter path."""
    job_id = _service(request).submit(body)
    return {"jobId": job_id, "status": "queued"}


@router.get("/jobs")
def jobs(request: Request, limit: int = 50, offset: int = 0) -> dict[str, Any]:
    """List optimizer sweeps (OPTIMIZATION jobs), newest first — the sweep-native listing surface
    (previously sweeps were only discoverable via the shared backtest jobs table)."""
    bounded_limit = min(max(limit, 1), 200)
    return _service(request).list_sweeps(bounded_limit, max(offset, 0))


@router.get("/jobs/{job_id}")
def job(job_id: UUID, request: Request) -> dict[str, Any]:
    """Sweep status / progress / trials completed. UUID-typed so a malformed id is refused at the
    boundary (422) instead of reaching the UUID-typed ``jobs.id`` column as an uncaught Postgres
    parse error; a well-formed but unknown id still 404s."""
    return _service(request).job_status(str(job_id))


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
def promote(sweep_id: str, body: PromoteRequest, request: Request) -> dict[str, Any]:
    """Promote a COMPLETE trial's params to a new draft version (§D.9); 409 if not promotable.
    Modelled like ``/probes`` below, so a body missing ``trialId`` is a 422 rather than a KeyError
    escaping as a bare 500."""
    return _service(request).promote(sweep_id, body.trialId, body.notes)


@router.post("/{sweep_id}/probes", response_model=ProbeReceipt)
def probes(sweep_id: str, request: Request, body: ProbeRequest | None = None) -> dict[str, Any]:
    """Submit the plateau top-K's missing ±1-step neighbours as ordinary trials of this COMPLETED
    sweep, so a later ``/best`` read MEASURES ``neighborCount`` (§3.2.3 / §11). Body is optional
    (``topK`` default 5, ``maxProbes`` default 40). 404 unknown or non-sweep job; 409 unless the
    sweep is completed."""
    opts = body or ProbeRequest()
    return _service(request).probe(sweep_id, opts.topK, opts.maxProbes)
