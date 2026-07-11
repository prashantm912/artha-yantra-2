"""The evolution-engine read surface under ``/api/v1/evolution`` (design §11 / §12 E1).

E1 ships the READ-ONLY view of the experiment model: list campaigns, a campaign detail
(with its generations), and a campaign's candidates. Every endpoint returns a typed
Pydantic model (house rule: never a bare ``dict``/Map shape), and lists use the
``{items: [...]}`` envelope. The write/scoring/recorder path is a later PR — nothing here
mutates. Empty tables (the state at deploy) return clean empty envelopes.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.errors import ApiError

router = APIRouter(prefix="/api/v1/evolution")


class GenerationModel(BaseModel):
    """One propose→evaluate→select iteration within a campaign (``evo_generations``)."""

    id: str
    campaignId: str
    n: int
    proposal: dict[str, Any] | None = None
    searchSpaceHash: str | None = None
    engineSha: str | None = None
    dataEpoch: dict[str, Any] | None = None
    stressTouches: int
    status: str | None = None
    startedAt: str | None = None
    finishedAt: str | None = None


class CampaignModel(BaseModel):
    """A long-lived evolution program for one base strategy (``evo_campaigns``)."""

    id: str
    strategyId: str | None = None
    family: str | None = None
    evidencePolicy: str
    objectiveSpec: dict[str, Any] | None = None
    searchSpace: dict[str, Any] | None = None
    budget: dict[str, Any] | None = None
    status: str
    championVersionId: str | None = None
    createdAt: str | None = None
    updatedAt: str | None = None


class CampaignDetail(CampaignModel):
    """A campaign plus its generations (the ``/campaigns/{id}`` detail shape)."""

    generations: list[GenerationModel] = []


class CampaignListResponse(BaseModel):
    items: list[CampaignModel]


class CandidateModel(BaseModel):
    """One concrete version under evaluation (``evo_candidates``); ``scorecard`` is the
    §6 gates/RobustScore JSONB passed through verbatim."""

    id: str
    generationId: str
    versionId: str | None = None
    parentCandidateId: str | None = None
    mutationKind: str | None = None
    params: dict[str, Any] | None = None
    structureDiff: dict[str, Any] | None = None
    sweepJobId: str | None = None
    holdoutRunId: str | None = None
    scorecard: dict[str, Any] | None = None
    state: str | None = None
    updatedAt: str | None = None


class CandidateListResponse(BaseModel):
    items: list[CandidateModel]


class EvoReadService:
    """Read-only orchestration over EvoRepo (opened per call via a factory, mirroring how
    SweepService uses its jobs/trials factories). Maps rows to the typed models and raises
    the shared 404 for an unknown campaign id."""

    def __init__(self, repo_factory: Callable[[], Any]) -> None:
        self._repo_factory = repo_factory

    def list_campaigns(self, limit: int, offset: int) -> CampaignListResponse:
        repo = self._repo_factory()
        try:
            rows = repo.list_campaigns(limit, offset)
        finally:
            repo.close()
        return CampaignListResponse(items=[CampaignModel(**r) for r in rows])

    def campaign_detail(self, campaign_id: str) -> CampaignDetail:
        repo = self._repo_factory()
        try:
            campaign = repo.get_campaign(campaign_id)
            if campaign is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            generations = repo.list_generations(campaign_id)
        finally:
            repo.close()
        return CampaignDetail(
            **campaign, generations=[GenerationModel(**g) for g in generations]
        )

    def campaign_candidates(self, campaign_id: str) -> CandidateListResponse:
        repo = self._repo_factory()
        try:
            if repo.get_campaign(campaign_id) is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            rows = repo.list_candidates_for_campaign(campaign_id)
        finally:
            repo.close()
        return CandidateListResponse(items=[CandidateModel(**r) for r in rows])


def _service(request: Request) -> EvoReadService:
    return request.app.state.evo


@router.get("/campaigns", response_model=CampaignListResponse)
def campaigns(request: Request, limit: int = 50, offset: int = 0) -> CampaignListResponse:
    """List evolution campaigns, newest first."""
    bounded_limit = min(max(limit, 1), 200)
    return _service(request).list_campaigns(bounded_limit, max(offset, 0))


@router.get("/campaigns/{campaign_id}", response_model=CampaignDetail)
def campaign(campaign_id: str, request: Request) -> CampaignDetail:
    """One campaign with its generations; 404 if unknown."""
    return _service(request).campaign_detail(campaign_id)


@router.get("/campaigns/{campaign_id}/candidates", response_model=CandidateListResponse)
def candidates(campaign_id: str, request: Request) -> CandidateListResponse:
    """The campaign's candidates (incl. the scorecard JSONB); 404 if the campaign is unknown."""
    return _service(request).campaign_candidates(campaign_id)
