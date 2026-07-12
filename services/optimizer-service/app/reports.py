"""Per-campaign evolution report (design §11 endpoint list / §12 E6 item 16 / §8.3 graveyard).

A single read-only GET /api/v1/evolution/campaigns/{id}/report that summarizes a campaign's
research so far: its generations (with per-generation candidate/survivor counts + best RobustScore),
the candidate-state census, the survivor set, the proposal tallies, the budget spend + scheduler
sub-state, and — a first-class output per §8.3 ("rejection != silence") — the GRAVEYARD of RETIRED
candidates with their recorded retire reason. Pure aggregation over the existing EvoRepo reads
(campaign + generations + candidates + proposals); it adds no new repo write and no scoring.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app.errors import ApiError

router = APIRouter(prefix="/api/v1/evolution")

# The candidate states that count as "survived to (or past) live evidence" — SURVIVOR and everything
# downstream of an owner PUBLISH_PAPER. SCORED is pre-selection; RETIRED/ROLLED_BACK are terminal.
_SURVIVOR_STATES = frozenset({"SURVIVOR", "PAPER", "TAKE_ELIGIBLE", "PROMOTED"})


class GenerationSummary(BaseModel):
    """One generation's roll-up: how many candidates it scored, how many survived selection, its
    cost-stress touch count, and its best RobustScore (the plateau leader)."""

    n: int
    status: str | None = None
    candidateCount: int
    survivorCount: int
    stressTouches: int
    bestRobustScore: float | None = None
    startedAt: str | None = None
    finishedAt: str | None = None


class BudgetSpend(BaseModel):
    """The campaign's budget caps vs. what has been spent, plus the scheduler sub-state (V018).
    ``holdoutTouches`` is the configured cap; ``stressTouchesUsed`` is the realized cost-stress
    touch count summed across generations (the enforced holdout/stress budget, §1.4.2)."""

    maxGenerations: int | None = None
    generationsUsed: int
    maxTrialsPerGen: int | None = None
    cadenceSeconds: int | None = None
    holdoutTouches: int | None = None
    stressTouchesUsed: int
    candidatesScored: int
    schedulerState: str | None = None
    pendingSweepJobId: str | None = None
    lastScheduledAt: str | None = None


class ProposalTally(BaseModel):
    """One (kind, status) proposal bucket and its count (the owner-inbox census)."""

    kind: str
    status: str
    count: int


class GraveyardEntry(BaseModel):
    """One RETIRED candidate and WHY it was retired (§8.3) — the recorded selection reason (a
    hard-gate fail / dominated), so the report proves the search is not cherry-picking."""

    candidateId: str
    generationN: int | None = None
    robustScore: float | None = None
    reason: str | None = None


class CampaignReport(BaseModel):
    """The full per-campaign report shape (§12 E6 item 16)."""

    campaignId: str
    strategyId: str | None = None
    family: str | None = None
    evidencePolicy: str
    status: str
    championVersionId: str | None = None
    budget: BudgetSpend
    generations: list[GenerationSummary]
    candidateStates: dict[str, int]
    survivors: int
    proposals: list[ProposalTally]
    graveyard: list[GraveyardEntry]


# The proposal read is bounded (a campaign's proposal count is small — one-ish per candidate); the
# cap is a generous ceiling that also flags a runaway (never a silent truncation of the tally).
_PROPOSAL_READ_CAP = 1000


class CampaignReportService:
    """Assembles the report from one EvoRepo (opened per call via the factory)."""

    def __init__(self, repo_factory: Callable[[], Any]) -> None:
        self._repo_factory = repo_factory

    def report(self, campaign_id: str) -> CampaignReport:
        repo = self._repo_factory()
        try:
            campaign = repo.get_scheduler_row(campaign_id)
            if campaign is None:
                raise ApiError(404, "NOT_FOUND_CAMPAIGN", f"no campaign {campaign_id}")
            generations = repo.list_generations(campaign_id)
            candidates = repo.list_candidates_for_campaign(campaign_id)
            proposals = repo.list_proposals(None, None, campaign_id, _PROPOSAL_READ_CAP, 0)
        finally:
            repo.close()

        gen_n_by_id = {g["id"]: g["n"] for g in generations}
        cands_by_gen = _group_by_generation(candidates)

        budget_cfg = campaign.get("budget") or {}
        generation_summaries = [
            _generation_summary(gen, cands_by_gen.get(gen["id"], [])) for gen in generations
        ]
        state_census = Counter(c.get("state") for c in candidates if c.get("state"))
        stress_used = sum(int(g.get("stressTouches") or 0) for g in generations)

        return CampaignReport(
            campaignId=campaign_id,
            strategyId=campaign.get("strategyId"),
            family=campaign.get("family"),
            evidencePolicy=campaign["evidencePolicy"],
            status=campaign["status"],
            championVersionId=campaign.get("championVersionId"),
            budget=BudgetSpend(
                maxGenerations=_int(budget_cfg.get("maxGenerations")),
                generationsUsed=len(generations),
                maxTrialsPerGen=_int(budget_cfg.get("maxTrialsPerGen")),
                cadenceSeconds=_int(budget_cfg.get("cadenceSeconds")),
                holdoutTouches=_int(budget_cfg.get("holdoutTouches")),
                stressTouchesUsed=stress_used,
                candidatesScored=len(candidates),
                schedulerState=campaign.get("schedulerState"),
                pendingSweepJobId=campaign.get("pendingSweepJobId"),
                lastScheduledAt=campaign.get("lastScheduledAt"),
            ),
            generations=generation_summaries,
            candidateStates=dict(state_census),
            survivors=sum(1 for c in candidates if c.get("state") in _SURVIVOR_STATES),
            proposals=_proposal_tallies(proposals),
            graveyard=_graveyard(candidates, gen_n_by_id),
        )


def _group_by_generation(candidates: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for cand in candidates:
        grouped.setdefault(cand.get("generationId"), []).append(cand)
    return grouped


def _generation_summary(
    generation: dict[str, Any], candidates: list[dict[str, Any]]
) -> GenerationSummary:
    scores = [
        _num((c.get("scorecard") or {}).get("robustScore"))
        for c in candidates
    ]
    present = [s for s in scores if s is not None]
    return GenerationSummary(
        n=generation["n"],
        status=generation.get("status"),
        candidateCount=len(candidates),
        survivorCount=sum(1 for c in candidates if c.get("state") in _SURVIVOR_STATES),
        stressTouches=int(generation.get("stressTouches") or 0),
        bestRobustScore=max(present) if present else None,
        startedAt=generation.get("startedAt"),
        finishedAt=generation.get("finishedAt"),
    )


def _proposal_tallies(proposals: list[dict[str, Any]]) -> list[ProposalTally]:
    counts = Counter((p.get("kind"), p.get("status")) for p in proposals)
    tallies = [
        ProposalTally(kind=kind, status=status, count=count)
        for (kind, status), count in counts.items()
    ]
    # Deterministic order (kind, then status) — a report must be byte-stable across reads.
    tallies.sort(key=lambda t: (t.kind, t.status))
    return tallies


def _graveyard(
    candidates: list[dict[str, Any]], gen_n_by_id: dict[str, int]
) -> list[GraveyardEntry]:
    entries = [
        GraveyardEntry(
            candidateId=cand["id"],
            generationN=gen_n_by_id.get(cand.get("generationId")),
            robustScore=_num((cand.get("scorecard") or {}).get("robustScore")),
            reason=((cand.get("scorecard") or {}).get("selection") or {}).get("reason"),
        )
        for cand in candidates
        if cand.get("state") == "RETIRED"
    ]
    # Newest generation first, then candidate id — deterministic ordering.
    entries.sort(key=lambda e: (-(e.generationN or 0), e.candidateId))
    return entries


def _num(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _service(request: Request) -> CampaignReportService:
    return request.app.state.reports


@router.get("/campaigns/{campaign_id}/report", response_model=CampaignReport)
def campaign_report(campaign_id: str, request: Request) -> CampaignReport:
    """The per-campaign evolution report: generations, scores, survivors, proposals, budget spend,
    and the §8.3 graveyard. 404 if the campaign is unknown."""
    return _service(request).report(campaign_id)
